package com.relay.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Adapter over an LLM. Kept behind this interface so engine tests use a deterministic fake and a
 * real provider (e.g. Anthropic) can drop in without touching the engine. The {@code outputSchema}
 * is passed so a provider (or the mock) can shape its response; validation still happens in the
 * {@code ai} executor regardless of what the provider returns.
 */
public interface AiProvider {

    /** A model completion plus token accounting. */
    record Completion(String content, int promptTokens, int completionTokens) {
    }

    Completion complete(String prompt, JsonNode outputSchema);
}
