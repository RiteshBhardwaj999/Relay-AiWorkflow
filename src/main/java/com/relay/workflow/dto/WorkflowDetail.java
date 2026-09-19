package com.relay.workflow.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.workflow.Workflow;

import java.time.Instant;

/** Full workflow view including the definition. */
public record WorkflowDetail(
        String id,
        String name,
        String description,
        String status,
        JsonNode definition,
        Instant createdAt,
        Instant updatedAt) {

    public static WorkflowDetail from(Workflow w) {
        return new WorkflowDetail(w.getId(), w.getName(), w.getDescription(), w.getStatus().name(),
                w.getDefinition(), w.getCreatedAt(), w.getUpdatedAt());
    }
}
