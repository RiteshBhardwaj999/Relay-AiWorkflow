package com.relay.nl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.ai.AiProvider;
import com.relay.config.RelayProperties;
import com.relay.workflow.WorkflowValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Compiles a natural-language description into a Relay workflow definition. The model is given the
 * full node catalog and must use only those types (or refuse). The candidate is validated with the
 * same {@link WorkflowValidator} used at publish; a validation failure triggers one repair retry.
 *
 * <p>Needs a real model ({@code relay.ai.provider=openrouter}); the deterministic mock cannot author
 * workflows.
 */
@Service
public class NlCompilerService {

    private static final Logger log = LoggerFactory.getLogger(NlCompilerService.class);
    private static final int MAX_ATTEMPTS = 2;

    private final AiProvider provider;
    private final ObjectMapper mapper;
    private final WorkflowValidator validator;
    private final RelayProperties properties;

    private volatile String catalogJson;
    private volatile String exampleJson;

    public NlCompilerService(AiProvider provider, ObjectMapper mapper, WorkflowValidator validator,
                             RelayProperties properties) {
        this.provider = provider;
        this.mapper = mapper;
        this.validator = validator;
        this.properties = properties;
    }

    public CompileResult compile(String description) {
        String basePrompt = buildPrompt(description);
        String prompt = basePrompt;
        int pt = 0;
        int ct = 0;
        List<String> lastErrors = List.of("no output");

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            AiProvider.Completion completion;
            try {
                completion = provider.complete(prompt, null);
            } catch (RuntimeException e) {
                log.warn("NL compile provider error: {}", e.getMessage());
                return CompileResult.invalid(List.of("provider error: " + e.getMessage()), attempt, pt, ct);
            }
            pt += completion.promptTokens();
            ct += completion.completionTokens();

            JsonNode envelope = parseJson(completion.content());
            if (envelope == null) {
                lastErrors = List.of("response was not valid JSON");
                prompt = repairPrompt(basePrompt, lastErrors);
                continue;
            }

            // Explicit refusal
            if (envelope.has("refusal")) {
                JsonNode r = envelope.get("refusal");
                return CompileResult.refused(
                        r.path("reason").asText("capability not available"),
                        stringList(r.path("missing_capabilities")), attempt, pt, ct);
            }

            JsonNode workflow = envelope.has("workflow") ? envelope.get("workflow")
                    : (envelope.has("nodes") ? envelope : null);
            if (workflow == null) {
                lastErrors = List.of("expected a 'workflow' or 'refusal' object");
                prompt = repairPrompt(basePrompt, lastErrors);
                continue;
            }

            List<String> errors = validator.validate(workflow);
            if (errors.isEmpty()) {
                return CompileResult.compiled(workflow, attempt, pt, ct);
            }
            lastErrors = errors;
            prompt = repairPrompt(basePrompt, errors);
        }
        return CompileResult.invalid(lastErrors, MAX_ATTEMPTS, pt, ct);
    }

    private String repairPrompt(String basePrompt, List<String> errors) {
        return basePrompt + "\n\nYour previous response was invalid:\n- " + String.join("\n- ", errors)
                + "\nReturn a corrected response using the SAME JSON envelope.";
    }

    private String buildPrompt(String description) {
        return """
                You are Relay's workflow compiler. Convert the user's request into a workflow
                definition using ONLY the node and trigger types in the CATALOG below. If the request
                needs any capability the catalog cannot express (e.g. a database, CRM/Salesforce, SMS,
                telephony, transcription, inbox-watching, blog/CMS), you MUST refuse — never invent a
                node or trigger type, and never pretend http_request covers an unavailable system.

                CATALOG (authoritative):
                %s

                DEFINITION SHAPE:
                {
                  "id": "wf_snake_case", "name": "Human name",
                  "trigger": {"type":"webhook","secret":"whsec_x"}  // or {"type":"manual"} or {"type":"schedule","cron":"0 8 * * *"}
                  "entry": "<first node id>",
                  "limits": {"max_steps":50,"timeout_seconds":300,"max_ai_tokens":10000},
                  "nodes": [ {"id":"...","type":"...","params":{...},"next":"<id|null>"}, ... ]
                }
                RULES:
                - Non-branching nodes use "next" (null ends the run). A "condition" node uses "on_true"
                  and "on_false" instead of "next".
                - Reference data with templates: {{trigger.body.field}} and {{nodes.<id>.output.<field>}}.
                - order_action is SENSITIVE: an approval node MUST execute before any order_action on
                  every path. Never place an order_action before its approval.
                - notify supports only channel "email" or "chat" (no SMS).
                - Every node id is unique; entry and every next/on_true/on_false references a real node id or null.
                - Include each node type's required params exactly as the catalog specifies.

                EXAMPLE
                Request: "When a support ticket arrives by webhook, classify it with AI; refund requests need a manager approval before the refund, everything else notifies the support channel."
                Output: {"workflow": %s}

                Respond with ONLY ONE JSON object, either:
                {"workflow": { ...definition... }}
                or
                {"refusal": {"reason":"<why>","missing_capabilities":["<capability>", ...]}}

                Request: "%s"
                """.formatted(catalog(), example(), description.replace("\"", "'"));
    }

    private String catalog() {
        if (catalogJson == null) {
            catalogJson = readData("node_catalog.json", "{}");
        }
        return catalogJson;
    }

    private String example() {
        if (exampleJson == null) {
            try {
                JsonNode seeds = mapper.readTree(readData("seed_workflows.json", "{\"workflows\":[]}"));
                JsonNode wf = null;
                for (JsonNode w : seeds.path("workflows")) {
                    if ("wf_support_triage".equals(w.path("id").asText())) {
                        wf = w;
                        break;
                    }
                }
                exampleJson = wf != null ? wf.toString() : "{}";
            } catch (Exception e) {
                exampleJson = "{}";
            }
        }
        return exampleJson;
    }

    private String readData(String file, String fallback) {
        try {
            Path p = Path.of(properties.getSeed().getDataDir(), file);
            return Files.exists(p) ? Files.readString(p) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private JsonNode parseJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(content);
        } catch (Exception ignored) {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return mapper.readTree(content.substring(start, end + 1));
                } catch (Exception ignored2) {
                    return null;
                }
            }
            return null;
        }
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        node.forEach(v -> out.add(v.asText()));
        return out;
    }
}
