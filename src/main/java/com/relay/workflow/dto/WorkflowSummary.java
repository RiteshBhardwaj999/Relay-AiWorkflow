package com.relay.workflow.dto;

import com.relay.workflow.Workflow;

import java.time.Instant;

/** Compact workflow view for list responses. */
public record WorkflowSummary(
        String id,
        String name,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static WorkflowSummary from(Workflow w) {
        return new WorkflowSummary(w.getId(), w.getName(), w.getStatus().name(),
                w.getCreatedAt(), w.getUpdatedAt());
    }
}
