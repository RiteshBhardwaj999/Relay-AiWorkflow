package com.relay.engine.nodes;

/**
 * Pluggable executor for one node type. Implementations are Spring beans; the registry wires them
 * by {@link #type()}. Executors are pure with respect to the engine — they receive resolved params
 * and return an {@link ExecResult}; the engine owns persistence, sequencing, and guardrails.
 */
public interface NodeExecutor {

    String type();

    ExecResult execute(NodeContext ctx);
}
