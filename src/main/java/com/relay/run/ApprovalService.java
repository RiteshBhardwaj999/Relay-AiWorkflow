package com.relay.run;

import com.relay.engine.queue.RunQueue;
import com.relay.run.dto.ApprovalView;
import com.relay.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Human decision workflow. Approving resumes the run (re-enqueued so the worker re-runs the
 * approval node, sees the decision, and proceeds); rejecting cancels the run. The engine's
 * requires_approval gate reads the {@code approved} record — this is the only way to satisfy it.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalRepository approvals;
    private final RunRepository runs;
    private final RunQueue queue;

    public ApprovalService(ApprovalRepository approvals, RunRepository runs, RunQueue queue) {
        this.approvals = approvals;
        this.runs = runs;
        this.queue = queue;
    }

    @Transactional(readOnly = true)
    public List<ApprovalView> list(ApprovalStatus status) {
        List<Approval> found = (status == null)
                ? approvals.findAll()
                : approvals.findByStatusOrderByCreatedAtAsc(status);
        return found.stream().map(ApprovalView::from).toList();
    }

    @Transactional
    public ApprovalView approve(String id, String decidedBy) {
        Approval approval = decide(id, ApprovalStatus.approved, decidedBy);
        queue.enqueue(approval.getRunId());
        log.info("Approval {} approved by {} — resuming run {}", id, decidedBy, approval.getRunId());
        return ApprovalView.from(approval);
    }

    @Transactional
    public ApprovalView reject(String id, String decidedBy) {
        Approval approval = decide(id, ApprovalStatus.rejected, decidedBy);
        runs.findById(approval.getRunId()).ifPresent(run -> {
            if (!run.getStatus().isTerminal()) {
                run.setStatus(RunStatus.cancelled);
                run.setFinishedAt(Instant.now());
                runs.save(run);
            }
        });
        log.info("Approval {} rejected by {} — cancelling run {}", id, decidedBy, approval.getRunId());
        return ApprovalView.from(approval);
    }

    private Approval decide(String id, ApprovalStatus decision, String decidedBy) {
        Approval approval = approvals.findById(id)
                .orElseThrow(() -> ApiException.notFound("No approval with id '" + id + "'"));
        if (approval.getStatus() != ApprovalStatus.pending) {
            throw ApiException.conflict("Approval '" + id + "' is already " + approval.getStatus());
        }
        approval.setStatus(decision);
        approval.setDecidedBy(decidedBy == null ? "console" : decidedBy);
        approval.setDecidedAt(Instant.now());
        return approvals.save(approval);
    }
}
