package com.relay.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.run.Run;
import com.relay.run.RunRepository;
import com.relay.run.RunStatus;
import com.relay.engine.queue.QueueJob;
import com.relay.engine.queue.QueueJobRepository;
import com.relay.run.Step;
import com.relay.run.StepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Transactional boundary for the engine. Every method commits in its own transaction
 * ({@code REQUIRES_NEW}) so a step's completion — and the run's advance to the next node — is
 * durably persisted before the worker moves on. This is the persist-then-advance guarantee that
 * makes crash recovery exact (Phase 6 resumes purely from these committed rows).
 */
@Service
public class EngineStore {

    private final RunRepository runs;
    private final StepRepository steps;
    private final QueueJobRepository queueJobs;
    private final ObjectMapper mapper;

    public EngineStore(RunRepository runs, StepRepository steps, QueueJobRepository queueJobs, ObjectMapper mapper) {
        this.runs = runs;
        this.steps = steps;
        this.queueJobs = queueJobs;
        this.mapper = mapper;
    }

    /** Mark a run running and set its entry node if it has not started yet. Returns the run. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Run startRun(String runId) {
        Run run = runs.findById(runId).orElseThrow();
        run.setStatus(RunStatus.running);
        if (run.getCurrentNodeId() == null) {
            run.setCurrentNodeId(run.getDefinitionSnapshot().path("entry").asText(null));
        }
        return runs.save(run);
    }

    /** Persist a completed/failed/waiting step and advance the run pointer — atomically. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStep(String runId, Step step, String nextNodeId) {
        steps.save(step);
        Run run = runs.findById(runId).orElseThrow();
        run.setStepsExecuted(run.getStepsExecuted() + 1);
        run.setCurrentNodeId(nextNodeId);
        int tokens = orZero(step.getTokensPrompt()) + orZero(step.getTokensCompletion());
        if (tokens > 0) {
            run.setAiTokensUsed(run.getAiTokensUsed() + tokens);
        }
        runs.save(run);
    }

    /**
     * Durable delay: persist the delay step, advance past it, and insert a future-dated queue job —
     * all in one transaction. The run is not blocked on a thread; the poller re-dispatches it when
     * {@code resumeAt} arrives. Survives a crash because the queue job is committed here.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scheduleResume(String runId, Step step, String nextNodeId, Instant resumeAt) {
        steps.save(step);
        Run run = runs.findById(runId).orElseThrow();
        run.setStepsExecuted(run.getStepsExecuted() + 1);
        run.setCurrentNodeId(nextNodeId);
        runs.save(run);
        queueJobs.save(new QueueJob(runId, resumeAt));
    }

    /** Pause the run for human approval; the current node pointer is left in place for resume. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markWaitingApproval(String runId) {
        Run run = runs.findById(runId).orElseThrow();
        run.setStatus(RunStatus.waiting_approval);
        runs.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeRun(String runId) {
        Run run = runs.findById(runId).orElseThrow();
        run.setStatus(RunStatus.succeeded);
        run.setCurrentNodeId(null);
        run.setFinishedAt(Instant.now());
        runs.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRun(String runId, String message, String code) {
        Run run = runs.findById(runId).orElseThrow();
        run.setStatus(RunStatus.failed);
        run.setFinishedAt(Instant.now());
        ObjectNode error = mapper.createObjectNode();
        error.put("message", message);
        if (code != null) {
            error.put("code", code);
        }
        run.setError(error);
        runs.save(run);
    }

    private int orZero(Integer v) {
        return v == null ? 0 : v;
    }
}
