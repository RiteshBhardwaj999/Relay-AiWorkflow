package com.relay.nl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.config.RelayProperties;
import com.relay.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the labelled NL cases in {@code nl_eval.jsonl} through the compiler and scores each per the
 * evaluation guide: a workflow case must compile, pass validation, contain the required node types,
 * branch when required, and gate order_action behind an approval; a refusal case must refuse. The
 * compiler only ever sees {@code description} — never the assertions (the answer key).
 */
@Service
public class NlEvalService {

    private static final Logger log = LoggerFactory.getLogger(NlEvalService.class);

    private final NlCompilerService compiler;
    private final ObjectMapper mapper;
    private final RelayProperties properties;

    public NlEvalService(NlCompilerService compiler, ObjectMapper mapper, RelayProperties properties) {
        this.compiler = compiler;
        this.mapper = mapper;
        this.properties = properties;
    }

    public ObjectNode runEval() {
        Path file = Path.of(properties.getSeed().getDataDir(), "nl_eval.jsonl");
        List<JsonNode> cases = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    cases.add(mapper.readTree(line));
                }
            }
        } catch (Exception e) {
            throw ApiException.badRequest("Could not read nl_eval.jsonl: " + e.getMessage());
        }

        ArrayNode caseResults = mapper.createArrayNode();
        int correct = 0;
        for (JsonNode c : cases) {
            ObjectNode scored = scoreCase(c);
            if ("pass".equals(scored.path("result").asText())) {
                correct++;
            }
            caseResults.add(scored);
        }

        ObjectNode report = mapper.createObjectNode();
        report.put("total", cases.size());
        report.put("correct", correct);
        report.put("accuracy", cases.isEmpty() ? 0.0 : Math.round((double) correct / cases.size() * 100.0) / 100.0);
        report.set("cases", caseResults);
        log.info("NL eval: {}/{} correct", correct, cases.size());
        return report;
    }

    private ObjectNode scoreCase(JsonNode c) {
        String id = c.path("id").asText();
        String expect = c.path("expect").asText();
        JsonNode assertions = c.path("assertions");
        ObjectNode out = mapper.createObjectNode();
        out.put("id", id);
        out.put("expected", expect);

        CompileResult result;
        try {
            result = compiler.compile(c.path("description").asText());
        } catch (RuntimeException e) {
            out.put("result", "fail");
            out.put("error", e.getMessage());
            return out;
        }

        if ("refusal".equals(expect)) {
            boolean pass = result.refused();
            out.put("result", pass ? "pass" : "fail");
            if (result.refused()) {
                out.put("refusal_reason", result.refusalReason());
            } else if (result.compiled()) {
                out.put("note", "emitted a workflow instead of refusing");
            } else {
                out.put("note", "did not cleanly refuse: " + String.join("; ", result.validationErrors()));
            }
            return out;
        }

        // expect == workflow
        if (!result.compiled()) {
            // nl_011 is deliberately dual-acceptable: a refusal citing the requires_approval rule
            // is a valid answer (see EVALUATION_GUIDE). Everything else must produce a workflow.
            if ("nl_011".equals(id) && result.refused()) {
                out.put("result", "pass");
                out.put("note", "acceptable refusal (requires_approval rule)");
                return out;
            }
            out.put("result", "fail");
            out.put("note", result.refused() ? "refused a valid request"
                    : "invalid output: " + String.join("; ", result.validationErrors()));
            return out;
        }

        JsonNode wf = result.workflow();
        ObjectNode checks = mapper.createObjectNode();
        checks.put("publishes", true); // compiled ⇒ already passed validation
        boolean ok = true;

        boolean typesOk = requiredTypesPresent(wf, assertions.path("required_node_types"));
        checks.put("required_node_types", typesOk);
        ok &= typesOk;

        if (assertions.path("requires_branching").asBoolean(false)) {
            boolean branch = hasLiveBranching(wf);
            checks.put("branching", branch);
            ok &= branch;
        }
        if (assertions.path("approval_must_precede_order_action").asBoolean(false)) {
            boolean gated = approvalPrecedesOrderAction(wf);
            checks.put("approval_precedes_order_action", gated);
            ok &= gated;
        }

        out.put("result", ok ? "pass" : "fail");
        out.set("checks", checks);
        return out;
    }

    private boolean requiredTypesPresent(JsonNode wf, JsonNode requiredTypes) {
        Set<String> present = new HashSet<>();
        wf.path("nodes").forEach(n -> present.add(n.path("type").asText()));
        for (JsonNode t : requiredTypes) {
            if (!present.contains(t.asText())) {
                return false;
            }
        }
        return true;
    }

    /**
     * A condition that actually routes: its two edges diverge (a branch to {@code null} is a valid
     * "this path ends" per the eval notes, e.g. nl_008), and at least one edge points at a real node.
     */
    private boolean hasLiveBranching(JsonNode wf) {
        Set<String> ids = new HashSet<>();
        wf.path("nodes").forEach(n -> ids.add(n.path("id").asText()));
        for (JsonNode n : wf.path("nodes")) {
            if ("condition".equals(n.path("type").asText())) {
                String t = n.path("on_true").asText(null);
                String f = n.path("on_false").asText(null);
                boolean diverges = !java.util.Objects.equals(t, f);
                boolean oneReal = (t != null && ids.contains(t)) || (f != null && ids.contains(f));
                if (diverges && oneReal) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True if no path from entry reaches an order_action without first passing an approval. */
    private boolean approvalPrecedesOrderAction(JsonNode wf) {
        Map<String, JsonNode> nodes = new HashMap<>();
        wf.path("nodes").forEach(n -> nodes.put(n.path("id").asText(), n));
        String entry = wf.path("entry").asText(null);
        if (entry == null) {
            return true;
        }
        Deque<String[]> stack = new ArrayDeque<>();  // (nodeId, approvalSeen:"0"|"1")
        Set<String> visited = new HashSet<>();
        stack.push(new String[]{entry, "0"});
        while (!stack.isEmpty()) {
            String[] cur = stack.pop();
            JsonNode node = nodes.get(cur[0]);
            if (node == null) {
                continue;
            }
            boolean seen = "1".equals(cur[1]);
            String type = node.path("type").asText();
            if ("order_action".equals(type) && !seen) {
                return false;
            }
            boolean nowSeen = seen || "approval".equals(type);
            for (String succ : successors(node)) {
                String key = succ + "|" + (nowSeen ? "1" : "0");
                if (visited.add(key)) {
                    stack.push(new String[]{succ, nowSeen ? "1" : "0"});
                }
            }
        }
        return true;
    }

    private List<String> successors(JsonNode node) {
        List<String> out = new ArrayList<>();
        if ("condition".equals(node.path("type").asText())) {
            addIfPresent(out, node.path("on_true"));
            addIfPresent(out, node.path("on_false"));
        } else {
            addIfPresent(out, node.path("next"));
        }
        return out;
    }

    private void addIfPresent(List<String> out, JsonNode ref) {
        if (ref != null && !ref.isNull() && !ref.isMissingNode() && !ref.asText().isBlank()) {
            out.add(ref.asText());
        }
    }
}
