package com.relay.engine.nodes;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Outcome of executing a node.
 *
 * <ul>
 *   <li>{@link Outcome#CONTINUE} — step succeeded; proceed to {@link #nextNodeId} (null ⇒ run done)</li>
 *   <li>{@link Outcome#SLEEP} — durable delay: advance past this node but only become runnable
 *       again at {@link #resumeAt}; the run is rescheduled, not blocked on a thread</li>
 *   <li>{@link Outcome#WAIT} — run must pause for a human (approval); the worker stops advancing it</li>
 *   <li>{@link Outcome#FAIL} — step failed; the run fails with {@link #errorMessage}</li>
 * </ul>
 */
public record ExecResult(
        Outcome outcome,
        JsonNode output,
        String nextNodeId,
        Instant resumeAt,
        String errorMessage,
        boolean retryable,
        String idempotencyKey,
        Integer tokensPrompt,
        Integer tokensCompletion) {

    public enum Outcome {
        CONTINUE,
        SLEEP,
        WAIT,
        FAIL
    }

    public static ExecResult continueTo(JsonNode output, String nextNodeId) {
        return new ExecResult(Outcome.CONTINUE, output, nextNodeId, null, null, false, null, null, null);
    }

    public static ExecResult sleepUntil(JsonNode output, String nextNodeId, Instant resumeAt) {
        return new ExecResult(Outcome.SLEEP, output, nextNodeId, resumeAt, null, false, null, null, null);
    }

    /** Deterministic failure — do not retry. */
    public static ExecResult fail(String message) {
        return new ExecResult(Outcome.FAIL, null, null, null, message, false, null, null, null);
    }

    /** Transient failure (timeout, 5xx, connection error) — the engine may retry with backoff. */
    public static ExecResult failRetryable(String message) {
        return new ExecResult(Outcome.FAIL, null, null, null, message, true, null, null, null);
    }

    public static ExecResult wait(JsonNode output) {
        return new ExecResult(Outcome.WAIT, output, null, null, null, false, null, null, null);
    }

    public ExecResult withIdempotencyKey(String key) {
        return new ExecResult(outcome, output, nextNodeId, resumeAt, errorMessage, retryable, key, tokensPrompt, tokensCompletion);
    }

    public ExecResult withTokens(Integer prompt, Integer completion) {
        return new ExecResult(outcome, output, nextNodeId, resumeAt, errorMessage, retryable, idempotencyKey, prompt, completion);
    }
}
