package com.relay.run.dto;

import com.relay.run.Run;

import java.time.Instant;

/** Compact run view for history lists. */
public record RunSummary(
        String runId,
        String workflowId,
        String status,
        String triggerType,
        int stepsExecuted,
        Instant startedAt,
        Instant finishedAt) {

    public static RunSummary from(Run run) {
        return new RunSummary(run.getRunId(), run.getWorkflowId(), run.getStatus().name(),
                run.getTriggerType(), run.getStepsExecuted(), run.getStartedAt(), run.getFinishedAt());
    }
}
