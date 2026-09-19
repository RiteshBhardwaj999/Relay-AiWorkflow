package com.relay.run.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.run.Run;
import com.relay.run.Step;

import java.time.Instant;
import java.util.List;

/** Full run view including the complete step trace. */
public record RunDetail(
        String runId,
        String workflowId,
        String status,
        String triggerType,
        String currentNodeId,
        int stepsExecuted,
        int aiTokensUsed,
        JsonNode error,
        Instant startedAt,
        Instant finishedAt,
        List<StepView> steps) {

    public static RunDetail from(Run run, List<Step> steps) {
        return new RunDetail(
                run.getRunId(),
                run.getWorkflowId(),
                run.getStatus().name(),
                run.getTriggerType(),
                run.getCurrentNodeId(),
                run.getStepsExecuted(),
                run.getAiTokensUsed(),
                run.getError(),
                run.getStartedAt(),
                run.getFinishedAt(),
                steps.stream().map(StepView::from).toList());
    }
}
