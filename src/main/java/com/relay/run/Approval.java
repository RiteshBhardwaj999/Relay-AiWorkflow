package com.relay.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A human decision gate. The engine consults approval rows before executing sensitive nodes;
 * nothing an AI node outputs can create or mutate one.
 */
@Entity
@Table(name = "approval")
public class Approval {

    @Id
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(columnDefinition = "text", nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalStatus status = ApprovalStatus.pending;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Approval() {
    }

    public Approval(String id, String runId, String nodeId, String message) {
        this.id = id;
        this.runId = runId;
        this.nodeId = nodeId;
        this.message = message;
        this.status = ApprovalStatus.pending;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getMessage() {
        return message;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public void setStatus(ApprovalStatus status) {
        this.status = status;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
