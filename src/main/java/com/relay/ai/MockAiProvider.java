package com.relay.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

/**
 * Deterministic, schema-aware fake model. It emits a JSON object that conforms to the node's
 * {@code output_schema}, choosing enum values by simple keyword heuristics over the prompt. This
 * lets the {@code ai} node run — and the support-triage demo branch — with no API key.
 *
 * <p>Crucially, it only ever fills the fields the schema declares: it cannot invent a field that
 * would, say, redirect a notification or grant an approval. Prompt-injection text in the input is
 * just more words to classify; the approval gate and step cap are enforced by the engine anyway.
 *
 * <p>This is the default provider (see {@code AiConfig}); a real provider replaces it when
 * {@code relay.ai.provider} is set.
 */
public class MockAiProvider implements AiProvider {

    private final ObjectMapper mapper;

    public MockAiProvider(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Completion complete(String prompt, JsonNode outputSchema) {
        String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        ObjectNode out = mapper.createObjectNode();
        JsonNode properties = outputSchema == null ? null : outputSchema.path("properties");
        if (properties != null && properties.isObject()) {
            properties.fields().forEachRemaining(entry ->
                    out.set(entry.getKey(), valueFor(entry.getKey(), entry.getValue(), text)));
        }
        String content = out.toString();
        return new Completion(content, approxTokens(prompt), approxTokens(content));
    }

    private JsonNode valueFor(String name, JsonNode spec, String text) {
        JsonNode enumValues = spec.path("enum");
        if (enumValues.isArray() && !enumValues.isEmpty()) {
            return mapper.getNodeFactory().textNode(pickEnum(name, enumValues, text));
        }
        String type = spec.path("type").asText("string");
        return switch (type) {
            case "string" -> mapper.getNodeFactory().textNode(stringFor(name, text));
            case "number", "integer" -> mapper.getNodeFactory().numberNode(0);
            case "boolean" -> mapper.getNodeFactory().booleanNode(false);
            case "array" -> mapper.createArrayNode();
            case "object" -> mapper.createObjectNode();
            default -> mapper.getNodeFactory().textNode("");
        };
    }

    private String pickEnum(String name, JsonNode enumValues, String text) {
        if (name.contains("category")) {
            String category = classifyCategory(text);
            if (enumContains(enumValues, category)) {
                return category;
            }
        }
        if (name.contains("priority")) {
            String priority = classifyPriority(text);
            if (enumContains(enumValues, priority)) {
                return priority;
            }
        }
        return enumValues.get(0).asText();
    }

    private String classifyCategory(String text) {
        if (containsAny(text, "refund", "money back", "my money", "want a refund", "return it")) {
            return "refund_request";
        }
        if (containsAny(text, "broken", "cracked", "damaged", "stopped working", "not working",
                "disappointed", "defective")) {
            return "complaint";
        }
        return "question";
    }

    private String classifyPriority(String text) {
        if (containsAny(text, "refund", "urgent", "asap", "immediately", "disappointed", "broken")) {
            return "high";
        }
        if (containsAny(text, "soon", "please", "issue")) {
            return "medium";
        }
        return "low";
    }

    private String stringFor(String name, String text) {
        if (name.contains("summary")) {
            // Neutral, bounded summary — deliberately does not echo the (possibly hostile) input.
            return "Customer message classified as " + classifyCategory(text) + ".";
        }
        return "ok";
    }

    private boolean enumContains(JsonNode enumValues, String value) {
        for (JsonNode v : enumValues) {
            if (v.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, String... needles) {
        for (String n : needles) {
            if (text.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private int approxTokens(String s) {
        if (s == null || s.isBlank()) {
            return 1;
        }
        return s.trim().split("\\s+").length;
    }
}
