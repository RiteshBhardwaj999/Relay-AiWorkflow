package com.relay.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code {{trigger.body.x.y}}} and {@code {{nodes.id.output.z}}} templates by simple path
 * lookup — no expressions or arithmetic. An unresolvable path throws {@link TemplateException}.
 */
@Component
public class TemplateResolver {

    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}");

    private final ObjectMapper mapper;

    public TemplateResolver(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Build the resolution context: {@code {trigger:{body:<input>}, nodes:{<id>:{output:<out>}}}}.
     */
    public JsonNode buildRoot(JsonNode triggerBody, Map<String, JsonNode> nodeOutputs) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode trigger = root.putObject("trigger");
        trigger.set("body", triggerBody == null ? mapper.nullNode() : triggerBody);
        ObjectNode nodes = root.putObject("nodes");
        nodeOutputs.forEach((id, output) -> {
            ObjectNode n = nodes.putObject(id);
            n.set("output", output == null ? mapper.nullNode() : output);
        });
        return root;
    }

    /** Resolve every template inside a string, throwing if any path is missing. */
    public String resolveString(String template, JsonNode root) {
        Matcher m = TEMPLATE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String path = m.group(1).trim();
            JsonNode value = resolvePath(root, path)
                    .orElseThrow(() -> new TemplateException("unresolvable template path: " + path));
            if (value.isObject() || value.isArray()) {
                throw new TemplateException("template path '" + path + "' resolved to a non-scalar value");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value.asText()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Deep-resolve every string leaf of a JSON value (used for node params). */
    public JsonNode resolveJson(JsonNode node, JsonNode root) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        if (node.isTextual()) {
            return mapper.getNodeFactory().textNode(resolveString(node.asText(), root));
        }
        if (node.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            node.fields().forEachRemaining(e -> copy.set(e.getKey(), resolveJson(e.getValue(), root)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            node.forEach(child -> copy.add(resolveJson(child, root)));
            return copy;
        }
        return node;
    }

    /** Navigate a dotted path through objects (and numeric array indices). */
    public Optional<JsonNode> resolvePath(JsonNode root, String path) {
        JsonNode cur = root;
        for (String seg : path.split("\\.")) {
            if (cur == null) {
                return Optional.empty();
            }
            if (cur.isObject()) {
                cur = cur.get(seg);
            } else if (cur.isArray() && seg.matches("\\d+")) {
                cur = cur.get(Integer.parseInt(seg));
            } else {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(cur);
    }
}
