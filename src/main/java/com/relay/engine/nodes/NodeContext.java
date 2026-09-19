package com.relay.engine.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.run.Run;

/**
 * Everything an executor needs for one node execution. {@link #resolvedParams} already has all
 * {@code {{...}}} templates resolved; {@link #node} is the raw node definition (for edges/config).
 */
public record NodeContext(
        Run run,
        JsonNode node,
        JsonNode resolvedParams,
        JsonNode templateRoot) {

    public String nodeId() {
        return node.path("id").asText();
    }

    public String nodeType() {
        return node.path("type").asText();
    }

    /** The linear successor for non-branching nodes ({@code next}); null terminates the run. */
    public String next() {
        JsonNode next = node.get("next");
        return (next == null || next.isNull()) ? null : next.asText();
    }

    public String param(String name) {
        return resolvedParams.path(name).asText(null);
    }

    /** Stable idempotency key for a side-effect from this node: {@code {run_id}:{node_id}}. */
    public String idempotencyKey() {
        return run.getRunId() + ":" + nodeId();
    }
}
