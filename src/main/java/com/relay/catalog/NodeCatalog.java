package com.relay.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The parsed node/trigger catalog. Published workflows may only use node and trigger types
 * listed here, with their required params present. This is the authority the workflow validator
 * (Phase 2) and the executors consult.
 */
public class NodeCatalog {

    private final Map<String, NodeTypeSpec> nodes;
    private final Map<String, TriggerSpec> triggers;

    private NodeCatalog(Map<String, NodeTypeSpec> nodes, Map<String, TriggerSpec> triggers) {
        this.nodes = nodes;
        this.triggers = triggers;
    }

    public Optional<NodeTypeSpec> node(String type) {
        return Optional.ofNullable(nodes.get(type));
    }

    public boolean hasNode(String type) {
        return nodes.containsKey(type);
    }

    public boolean hasTrigger(String type) {
        return triggers.containsKey(type);
    }

    public Optional<TriggerSpec> trigger(String type) {
        return Optional.ofNullable(triggers.get(type));
    }

    public Set<String> nodeTypes() {
        return nodes.keySet();
    }

    public Set<String> triggerTypes() {
        return triggers.keySet();
    }

    /** Parse a {@code node_catalog.json} document into a {@link NodeCatalog}. */
    public static NodeCatalog fromJson(JsonNode root) {
        Map<String, NodeTypeSpec> nodes = new LinkedHashMap<>();
        for (JsonNode n : root.path("nodes")) {
            String type = n.path("type").asText();
            NodeTypeSpec spec = new NodeTypeSpec(
                    type,
                    n.path("side_effect").asBoolean(false),
                    n.path("requires_approval").asBoolean(false),
                    parseParams(n.path("params")),
                    parseStringList(n.path("branches")));
            nodes.put(type, spec);
        }
        Map<String, TriggerSpec> triggers = new LinkedHashMap<>();
        for (JsonNode t : root.path("triggers")) {
            String type = t.path("type").asText();
            triggers.put(type, new TriggerSpec(type, parseParams(t.path("config"))));
        }
        return new NodeCatalog(nodes, triggers);
    }

    private static Map<String, ParamSpec> parseParams(JsonNode paramsNode) {
        Map<String, ParamSpec> params = new LinkedHashMap<>();
        if (paramsNode == null || !paramsNode.isObject()) {
            return params;
        }
        paramsNode.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            JsonNode p = entry.getValue();
            params.put(name, new ParamSpec(
                    name,
                    p.path("type").asText(null),
                    p.path("required").asBoolean(false),
                    p.path("templatable").asBoolean(false),
                    parseStringList(p.path("enum"))));
        });
        return params;
    }

    private static List<String> parseStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(v -> out.add(v.asText()));
        }
        return out;
    }
}
