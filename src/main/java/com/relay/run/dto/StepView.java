package com.relay.run.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.run.Step;

import java.time.Instant;

/** One step in a run trace, as exposed by the API/console. */
public record StepView(
        int sequence,
        String nodeId,
        String type,
        String status,
        int attempt,
        JsonNode input,
        JsonNode output,
        String idempotencyKey,
        Integer tokensPrompt,
        Integer tokensCompletion,
        Instant startedAt,
        Integer durationMs) {

    public static StepView from(Step s) {
        return new StepView(
                s.getSequence(),
                s.getNodeId(),
                s.getNodeType(),
                s.getStatus().name(),
                s.getAttempt(),
                s.getResolvedInput(),
                s.getOutput(),
                s.getIdempotencyKey(),
                s.getTokensPrompt(),
                s.getTokensCompletion(),
                s.getStartedAt(),
                s.getDurationMs());
    }
}
