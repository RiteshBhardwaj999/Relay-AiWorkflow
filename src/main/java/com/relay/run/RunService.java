package com.relay.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.common.Ids;
import com.relay.engine.queue.RunQueue;
import com.relay.run.dto.RunDetail;
import com.relay.web.error.ApiException;
import com.relay.workflow.Workflow;
import com.relay.workflow.WorkflowService;
import com.relay.workflow.WorkflowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates runs from triggers. Each run captures an immutable snapshot of the workflow definition
 * so later edits or re-publishes never change an in-flight run. Execution is always asynchronous:
 * the trigger only enqueues; the worker (Phase 4) runs the workflow.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final RunRepository runs;
    private final StepRepository steps;
    private final RunQueue queue;
    private final WorkflowService workflows;
    private final ObjectMapper objectMapper;

    public RunService(RunRepository runs, StepRepository steps, RunQueue queue,
                      WorkflowService workflows, ObjectMapper objectMapper) {
        this.runs = runs;
        this.steps = steps;
        this.queue = queue;
        this.workflows = workflows;
        this.objectMapper = objectMapper;
    }

    /** Manual trigger: {@code {"input": {...}}} becomes {@code {{trigger.body}}}. */
    @Transactional
    public Run triggerManual(String workflowId, JsonNode body) {
        Workflow workflow = requirePublished(workflowId);
        JsonNode input = (body != null && body.hasNonNull("input")) ? body.get("input") : emptyObject();
        return createRun(workflow, "manual", input);
    }

    /** Webhook trigger: validates the workflow secret; the raw body becomes {@code {{trigger.body}}}. */
    @Transactional
    public Run triggerWebhook(String workflowId, String presentedSecret, JsonNode body) {
        Workflow workflow = workflows.require(workflowId);
        verifySecret(workflow, presentedSecret);
        ensurePublished(workflow);
        JsonNode input = (body != null && !body.isMissingNode() && !body.isNull()) ? body : emptyObject();
        return createRun(workflow, "webhook", input);
    }

    private Run createRun(Workflow workflow, String triggerType, JsonNode input) {
        Run run = new Run(Ids.runId(), workflow.getId(), workflow.getDefinition(), triggerType, input);
        runs.save(run);
        queue.enqueue(run.getRunId());
        log.info("Enqueued {} run {} for workflow {}", triggerType, run.getRunId(), workflow.getId());
        return run;
    }

    private Workflow requirePublished(String workflowId) {
        Workflow workflow = workflows.require(workflowId);
        ensurePublished(workflow);
        return workflow;
    }

    private void ensurePublished(Workflow workflow) {
        if (workflow.getStatus() != WorkflowStatus.published) {
            throw ApiException.conflict("Workflow '" + workflow.getId() + "' is not published");
        }
    }

    private void verifySecret(Workflow workflow, String presentedSecret) {
        String expected = workflow.getDefinition().path("trigger").path("secret").asText(null);
        if (expected == null) {
            throw ApiException.conflict("Workflow '" + workflow.getId() + "' has no webhook secret configured");
        }
        if (presentedSecret == null || presentedSecret.isBlank()) {
            throw ApiException.unauthorized("Missing X-Relay-Secret header");
        }
        if (!expected.equals(presentedSecret)) {
            throw ApiException.forbidden("Invalid webhook secret");
        }
    }

    private JsonNode emptyObject() {
        return objectMapper.createObjectNode();
    }

    @Transactional(readOnly = true)
    public Run require(String runId) {
        return runs.findById(runId)
                .orElseThrow(() -> ApiException.notFound("No run with id '" + runId + "'"));
    }

    @Transactional(readOnly = true)
    public RunDetail getDetail(String runId) {
        Run run = require(runId);
        return RunDetail.from(run, steps.findByRunIdOrderBySequenceAsc(runId));
    }
}
