package com.relay.engine.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves a node type to its executor. Types present in the catalog but not yet implemented fall
 * back to a no-op that records an empty output and follows {@code next} — this lets the engine run
 * end-to-end while node types are filled in phase by phase.
 */
@Component
public class NodeExecutorRegistry {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutorRegistry.class);

    private final Map<String, NodeExecutor> executors;
    private final ObjectMapper mapper;

    public NodeExecutorRegistry(List<NodeExecutor> executors, ObjectMapper mapper) {
        this.executors = executors.stream()
                .collect(Collectors.toMap(NodeExecutor::type, Function.identity()));
        this.mapper = mapper;
        log.info("Registered node executors: {}", this.executors.keySet());
    }

    public NodeExecutor forType(String type) {
        return executors.getOrDefault(type, noop(type));
    }

    private NodeExecutor noop(String type) {
        return new NodeExecutor() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public ExecResult execute(NodeContext ctx) {
                log.warn("No executor for node type '{}' (node {}) — treating as no-op", type, ctx.nodeId());
                JsonNode output = mapper.createObjectNode().put("_stub", true);
                return ExecResult.continueTo(output, ctx.next());
            }
        };
    }
}
