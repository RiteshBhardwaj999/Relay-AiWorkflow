package com.relay.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.engine.nodes.ExecResult;
import com.relay.engine.nodes.NodeContext;
import com.relay.engine.nodes.NodeExecutorRegistry;
import com.relay.run.Run;
import com.relay.run.Step;
import com.relay.run.StepRepository;
import com.relay.run.StepStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Walks a run's node graph. The engine owns sequencing, template resolution, per-step persistence,
 * the step cap, and the run state machine; node behavior lives in {@link com.relay.engine.nodes}.
 *
 * <p>Not {@code @Transactional}: each step is committed independently through {@link EngineStore}
 * (persist-then-advance), so a crash mid-run leaves a consistent, resumable trace.
 */
@Service
public class RunEngine {

    private static final Logger log = LoggerFactory.getLogger(RunEngine.class);
    private static final int DEFAULT_MAX_STEPS = 1000;

    private final EngineStore store;
    private final NodeExecutorRegistry registry;
    private final TemplateResolver resolver;
    private final StepRepository steps;

    public RunEngine(EngineStore store, NodeExecutorRegistry registry, TemplateResolver resolver,
                     StepRepository steps) {
        this.store = store;
        this.registry = registry;
        this.resolver = resolver;
        this.steps = steps;
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
            JsonNode root = resolver.buildRoot(run.getInput(), outputs);

            Step step = new Step(runId, current, type, ++seq, StepStatus.running);
            long start = System.currentTimeMillis();
            ExecResult result;
            try {
                JsonNode resolvedParams = resolver.resolveJson(node.path("params"), root);
                step.setResolvedInput(resolvedParams);
                result = registry.forType(type).execute(new NodeContext(run, node, resolvedParams, root));
            } catch (TemplateException e) {
                result = ExecResult.fail(e.getMessage());
            } catch (RuntimeException e) {
                log.error("Node {} ({}) threw", current, type, e);
                result = ExecResult.fail(type + " node error: " + e.getMessage());
            }
            step.setDurationMs((int) (System.currentTimeMillis() - start));
            step.setOutput(result.output());
            step.setIdempotencyKey(result.idempotencyKey());
            step.setTokensPrompt(result.tokensPrompt());
            step.setTokensCompletion(result.tokensCompletion());

            switch (result.outcome()) {
                case CONTINUE -> {
                    step.setStatus(StepStatus.succeeded);
                    store.recordStep(runId, step, result.nextNodeId());
                    executed++;
                    if (result.output() != null) {
                        outputs.put(current, result.output());
                    }
                    current = result.nextNodeId();
                }
                case SLEEP -> {
                    step.setStatus(StepStatus.succeeded);
                    store.scheduleResume(runId, step, result.nextNodeId(), result.resumeAt());
                    log.info("Run {} sleeping at {} until {}", runId, current, result.resumeAt());
                    return;
                }
                case WAIT -> {
                    step.setStatus(StepStatus.waiting);
                    store.recordStep(runId, step, current);
                    store.markWaitingApproval(runId);
                    return;
                }
                case FAIL -> {
                    step.setStatus(StepStatus.failed);
                    store.recordStep(runId, step, current);
                    store.failRun(runId, result.errorMessage(), "node_failed");
                    return;
                }
            }
        }
        store.completeRun(runId);
        log.info("Run {} completed", runId);
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
