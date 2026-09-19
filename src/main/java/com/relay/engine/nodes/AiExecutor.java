package com.relay.engine.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.ai.AiProvider;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Calls the model, then validates its output against the node's {@code output_schema} before any
 * downstream step can use it. On a parse/validation failure it retries exactly once, appending the
 * error to the prompt; a second failure fails the step. Token usage is recorded on the step either
 * way. The model's output is data only — it cannot bypass the engine's approval gate or step cap.
 */
@Component
public class AiExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(AiExecutor.class);
    private static final int MAX_ATTEMPTS = 2; // initial + one repair

    private final AiProvider provider;
    private final ObjectMapper mapper;
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    public AiExecutor(AiProvider provider, ObjectMapper mapper) {
        this.provider = provider;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "ai";
    }

    @Override
    public ExecResult execute(NodeContext ctx) {
        String basePrompt = ctx.param("prompt");
        if (basePrompt == null) {
            return ExecResult.fail("ai node requires a 'prompt'");
        }
        JsonNode schemaNode = ctx.resolvedParams().path("output_schema");
        JsonSchema schema = schemaNode.isObject() ? schemaFactory.getSchema(schemaNode) : null;

        String prompt = basePrompt;
        int totalPrompt = 0;
        int totalCompletion = 0;
        String lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            AiProvider.Completion completion = provider.complete(prompt, schemaNode);
            totalPrompt += completion.promptTokens();
            totalCompletion += completion.completionTokens();

            JsonNode parsed = parseJson(completion.content());
            if (parsed == null) {
                lastError = "response was not valid JSON";
            } else if (schema != null) {
                Set<ValidationMessage> errors = schema.validate(parsed);
                lastError = errors.isEmpty() ? null
                        : errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "));
            } else {
                lastError = null;
            }

            if (lastError == null) {
                return ExecResult.continueTo(parsed, ctx.next()).withTokens(totalPrompt, totalCompletion);
            }
            log.warn("ai node {} attempt {} invalid: {}", ctx.nodeId(), attempt, lastError);
            prompt = basePrompt + "\n\nYour previous response was invalid: " + lastError
                    + "\nReturn ONLY a JSON object that matches the schema.";
        }

        return ExecResult.fail("ai output failed schema validation after repair: " + lastError)
                .withTokens(totalPrompt, totalCompletion);
    }

    /** Parse JSON, tolerating prose around a single JSON object. */
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
}
