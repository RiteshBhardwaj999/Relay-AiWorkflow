package com.relay.run;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Type;

import java.time.Instant;

/**
 * A single execution of a workflow. Carries an immutable {@link #definitionSnapshot} taken at
 * trigger time so later edits/publishes never change how an in-flight run behaves.
 */
@Entity
@Table(name = "run")
public class Run {

    @Id
    @Column(name = "run_id")
    private String runId;

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Type(JsonType.class)
    @Column(name = "definition_snapshot", columnDefinition = "jsonb", nullable = false)
    private JsonNode definitionSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status = RunStatus.queued;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode input;

    @Column(name = "current_node_id")
    private String currentNodeId;

    @Column(name = "steps_executed", nullable = false)
    private int stepsExecuted;

    @Column(name = "ai_tokens_used", nullable = false)
    private int aiTokensUsed;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode error;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected Run() {
    }

    public Run(String runId, String workflowId, JsonNode definitionSnapshot, String triggerType, JsonNode input) {
        this.runId = runId;
        this.workflowId = workflowId;
        this.definitionSnapshot = definitionSnapshot;
        this.triggerType = triggerType;
        this.input = input;
        this.status = RunStatus.queued;
        this.startedAt = Instant.now();
    }

    public String getRunId() {
        return runId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public JsonNode getDefinitionSnapshot() {
        return definitionSnapshot;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public JsonNode getInput() {
        return input;
    }

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public void setCurrentNodeId(String currentNodeId) {
        this.currentNodeId = currentNodeId;
    }

    public int getStepsExecuted() {
        return stepsExecuted;
    }

    public void setStepsExecuted(int stepsExecuted) {
        this.stepsExecuted = stepsExecuted;
    }

    public int getAiTokensUsed() {
        return aiTokensUsed;
    }

    public void setAiTokensUsed(int aiTokensUsed) {
        this.aiTokensUsed = aiTokensUsed;
    }

    public JsonNode getError() {
        return error;
    }

    public void setError(JsonNode error) {
        this.error = error;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
