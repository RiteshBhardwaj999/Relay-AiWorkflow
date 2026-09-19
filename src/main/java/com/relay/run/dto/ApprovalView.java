package com.relay.run.dto;

import com.relay.run.Approval;

import java.time.Instant;

/** Approval as exposed by the API/console. */
public record ApprovalView(
        String id,
        String runId,
        String nodeId,
        String message,
        String status,
        String decidedBy,
        Instant createdAt,
        Instant decidedAt) {

    public static ApprovalView from(Approval a) {
        return new ApprovalView(a.getId(), a.getRunId(), a.getNodeId(), a.getMessage(),
                a.getStatus().name(), a.getDecidedBy(), a.getCreatedAt(), a.getDecidedAt());
    }
}
