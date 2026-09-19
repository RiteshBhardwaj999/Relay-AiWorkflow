package com.relay.run;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Type;

import java.time.Instant;

/**
 * One node execution within a run — the trace record. Steps are append-mostly and ordered by
 * {@link #sequence}; {@code (run_id, sequence)} is unique.
 */
@Entity
@Table(name = "step")
public class Step {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "node_type", nullable = false)
    private String nodeType;

    @Column(nullable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepStatus status;

    @Column(nullable = false)
    private int attempt = 1;

    @Type(JsonType.class)
    @Column(name = "resolved_input", columnDefinition = "jsonb")
    private JsonNode resolvedInput;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode output;

    @Column(name = "tokens_prompt")
    private Integer tokensPrompt;

    @Column(name = "tokens_completion")
    private Integer tokensCompletion;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "duration_ms")
    private Integer durationMs;

    protected Step() {
    }

    public Step(String runId, String nodeId, String nodeType, int sequence, StepStatus status) {
        this.runId = runId;
        this.nodeId = nodeId;
        this.nodeType = nodeType;
        this.sequence = sequence;
        this.status = status;
        this.startedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public int getSequence() {
        return sequence;
    }

    public StepStatus getStatus() {
        return status;
    }

    public void setStatus(StepStatus status) {
        this.status = status;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public JsonNode getResolvedInput() {
        return resolvedInput;
    }

    public void setResolvedInput(JsonNode resolvedInput) {
        this.resolvedInput = resolvedInput;
    }

    public JsonNode getOutput() {
        return output;
    }

    public void setOutput(JsonNode output) {
        this.output = output;
    }

    public Integer getTokensPrompt() {
        return tokensPrompt;
    }

    public void setTokensPrompt(Integer tokensPrompt) {
        this.tokensPrompt = tokensPrompt;
    }

    public Integer getTokensCompletion() {
        return tokensCompletion;
    }

    public void setTokensCompletion(Integer tokensCompletion) {
        this.tokensCompletion = tokensCompletion;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }
}
