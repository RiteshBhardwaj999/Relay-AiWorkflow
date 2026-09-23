package com.relay.nl;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Result of compiling a natural-language description.
 *
 * <ul>
 *   <li>{@code compiled=true} — {@link #workflow} is a definition that passes catalog validation</li>
 *   <li>{@code refused=true} — the model declined (missing capability); see {@link #refusalReason}</li>
 *   <li>neither — the model produced something that could not be turned into a valid workflow</li>
 * </ul>
 */
public record CompileResult(
        boolean compiled,
        boolean refused,
        JsonNode workflow,
        String refusalReason,
        List<String> missingCapabilities,
        List<String> validationErrors,
        int attempts,
        int promptTokens,
        int completionTokens) {

    public static CompileResult compiled(JsonNode workflow, int attempts, int pt, int ct) {
        return new CompileResult(true, false, workflow, null, List.of(), List.of(), attempts, pt, ct);
    }

    public static CompileResult refused(String reason, List<String> missing, int attempts, int pt, int ct) {
        return new CompileResult(false, true, null, reason, missing == null ? List.of() : missing,
                List.of(), attempts, pt, ct);
    }

    public static CompileResult invalid(List<String> errors, int attempts, int pt, int ct) {
        return new CompileResult(false, false, null, null, List.of(), errors, attempts, pt, ct);
    }
}
