package com.relay.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.catalog.NodeCatalog;
import com.relay.catalog.NodeTypeSpec;
import com.relay.engine.nodes.ExecResult;
import com.relay.engine.nodes.NodeContext;
import com.relay.engine.nodes.NodeExecutorRegistry;
import com.relay.run.ApprovalRepository;
import com.relay.run.ApprovalStatus;
import com.relay.run.Run;
import com.relay.run.Step;
import com.relay.run.StepRepository;
import com.relay.run.StepStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Walks a run's node graph. The engine owns sequencing, template resolution, per-step persistence,
 * the step cap, the approval gate, retries with backoff, and the run state machine; node behavior
 * lives in {@link com.relay.engine.nodes}.
 *
 * <p>Not {@code @Transactional}: each step is committed independently through {@link EngineStore}
 * (persist-then-advance), so a crash mid-run leaves a consistent, resumable trace.
 */
@Service
public class RunEngine {

    private static final Logger log = LoggerFactory.getLogger(RunEngine.class);
    private static final int DEFAULT_MAX_STEPS = 1000;
    private static final long MAX_BACKOFF_MS = 3000;

    private final EngineStore store;
    private final NodeExecutorRegistry registry;
    private final TemplateResolver resolver;
    private final StepRepository steps;
    private final NodeCatalog catalog;
    private final ApprovalRepository approvals;
    private final int maxAttempts;
    private final long backoffBaseMs;

    public RunEngine(EngineStore store, NodeExecutorRegistry registry, TemplateResolver resolver,
                     StepRepository steps, NodeCatalog catalog, ApprovalRepository approvals,
                     @Value("${relay.worker.max-attempts:3}") int maxAttempts,
                     @Value("${relay.worker.retry-backoff-ms:200}") long backoffBaseMs) {
        this.store = store;
        this.registry = registry;
        this.resolver = resolver;
        this.steps = steps;
        this.catalog = catalog;
        this.approvals = approvals;
        this.maxAttempts = maxAttempts;
        this.backoffBaseMs = backoffBaseMs;
    }

    public void execute(String runId) {
        Run run = store.startRun(runId);
        JsonNode def = run.getDefinitionSnapshot();
        int maxSteps = def.path("limits").path("max_steps").asInt(DEFAULT_MAX_STEPS);

        // Rebuild the resolution context and sequence counter from committed steps (enables resume).
        Map<String, JsonNode> outputs = new HashMap<>();
        int seq = 0;
        int executed = run.getStepsExecuted();
        for (Step s : steps.findByRunIdOrderBySequenceAsc(runId)) {
            seq = Math.max(seq, s.getSequence());
            if (s.getStatus() == StepStatus.succeeded && s.getOutput() != null) {
                outputs.put(s.getNodeId(), s.getOutput());
            }
        }

        String current = run.getCurrentNodeId();
        while (current != null) {
            if (executed >= maxSteps) {
                log.warn("Run {} hit step cap {} at node {}", runId, maxSteps, current);
                store.failRun(runId, "step cap exceeded (max_steps=" + maxSteps + ")", "step_cap_exceeded");
                return;
            }

            JsonNode node = findNode(def, current);
            if (node == null) {
                store.failRun(runId, "run references unknown node '" + current + "'", "unknown_node");
                return;
            }
            String type = node.path("type").asText();

            // Approval gate — enforced by the engine, independent of any AI/user output.
            if (requiresApproval(type) && !approvals.existsByRunIdAndStatus(runId, ApprovalStatus.approved)) {
                Step blocked = new Step(runId, current, type, ++seq, StepStatus.failed);
                store.recordStep(runId, blocked, current);
                store.failRun(runId, "sensitive node '" + current + "' requires an approval that was not granted",
                        "approval_required");
                log.warn("Run {} blocked at {} — no approval on record", runId, current);
                return;
            }

            JsonNode root = resolver.buildRoot(run.getInput(), outputs);
            Step step = new Step(runId, current, type, ++seq, StepStatus.running);
            ExecResult result = executeWithRetries(run, node, type, root, step);

            switch (result.outcome()) {
                case CONTINUE -> {
                    step.setStatus(StepStatus.succeeded);
                    finishStep(step, result);
                    store.recordStep(runId, step, result.nextNodeId());
                    executed++;
                    if (result.output() != null) {
                        outputs.put(current, result.output());
                    }
                    current = result.nextNodeId();
                }
                case SLEEP -> {
                    step.setStatus(StepStatus.succeeded);
                    finishStep(step, result);
                    store.scheduleResume(runId, step, result.nextNodeId(), result.resumeAt());
                    log.info("Run {} sleeping at {} until {}", runId, current, result.resumeAt());
                    return;
                }
                case WAIT -> {
                    step.setStatus(StepStatus.waiting);
                    finishStep(step, result);
                    store.recordStep(runId, step, current);
                    store.markWaitingApproval(runId);
                    log.info("Run {} waiting for approval at {}", runId, current);
                    return;
                }
                case FAIL -> {
                    step.setStatus(StepStatus.failed);
                    finishStep(step, result);
                    store.recordStep(runId, step, current);
                    store.failRun(runId, result.errorMessage(), "node_failed");
                    return;
                }
            }
        }
        store.completeRun(runId);
        log.info("Run {} completed", runId);
    }

    /** Execute one node, retrying transient failures with exponential backoff. */
    private ExecResult executeWithRetries(Run run, JsonNode node, String type, JsonNode root, Step step) {
        int attempt = 0;
        ExecResult result;
        while (true) {
            attempt++;
            try {
                JsonNode resolvedParams = resolver.resolveJson(node.path("params"), root);
                step.setResolvedInput(resolvedParams);
                result = registry.forType(type).execute(new NodeContext(run, node, resolvedParams, root));
            } catch (TemplateException e) {
                result = ExecResult.fail(e.getMessage());
            } catch (RuntimeException e) {
                log.error("Node {} ({}) threw", step.getNodeId(), type, e);
                result = ExecResult.failRetryable(type + " node error: " + e.getMessage());
            }
            boolean canRetry = result.outcome() == ExecResult.Outcome.FAIL && result.retryable()
                    && attempt < maxAttempts;
            if (!canRetry) {
                break;
            }
            log.warn("Run {} node {} attempt {} failed (retryable): {}", run.getRunId(), step.getNodeId(),
                    attempt, result.errorMessage());
            backoff(attempt);
        }
        step.setAttempt(attempt);
        return result;
    }

    private void finishStep(Step step, ExecResult result) {
        step.setOutput(result.output());
        step.setIdempotencyKey(result.idempotencyKey());
        step.setTokensPrompt(result.tokensPrompt());
        step.setTokensCompletion(result.tokensCompletion());
        long elapsed = System.currentTimeMillis() - step.getStartedAt().toEpochMilli();
        step.setDurationMs((int) Math.max(0, elapsed));
    }

    private boolean requiresApproval(String type) {
        return catalog.node(type).map(NodeTypeSpec::requiresApproval).orElse(false);
    }

    private void backoff(int attempt) {
        long delay = Math.min(MAX_BACKOFF_MS, backoffBaseMs * (1L << (attempt - 1)));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JsonNode findNode(JsonNode def, String nodeId) {
        for (JsonNode node : def.path("nodes")) {
            if (nodeId.equals(node.path("id").asText())) {
                return node;
            }
        }
        return null;
    }
}
