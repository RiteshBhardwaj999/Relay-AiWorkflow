package com.relay.engine;

import com.relay.engine.queue.QueueJob;
import com.relay.engine.queue.QueueJobRepository;
import com.relay.engine.queue.QueueJobStatus;
import com.relay.engine.queue.RunQueue;
import com.relay.run.Run;
import com.relay.run.RunRepository;
import com.relay.run.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Crash recovery. On startup, any job a dead worker was holding is reclaimed and every unfinished
 * run is guaranteed a live queue job; the poller then re-dispatches due jobs and the engine resumes
 * from the last committed step. Because side effects use stable idempotency keys, re-executing the
 * node that was in flight at crash time replays rather than duplicates.
 *
 * <p>Runs (queued/running) are recovered; {@code waiting_approval} runs are intentionally left
 * paused until a human decides.
 */
@Component
@Order(20)
public class RecoveryService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final QueueJobRepository jobs;
    private final RunRepository runs;
    private final RunQueue queue;

    public RecoveryService(QueueJobRepository jobs, RunRepository runs, RunQueue queue) {
        this.jobs = jobs;
        this.runs = runs;
        this.queue = queue;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 1. Any job still 'leased' belongs to a worker that no longer exists — hand it back.
        List<QueueJob> orphaned = jobs.findByStatus(QueueJobStatus.leased);
        for (QueueJob job : orphaned) {
            job.setStatus(QueueJobStatus.ready);
            job.setLeasedUntil(null);
            job.setUpdatedAt(Instant.now());
            jobs.save(job);
        }

        // 2. Every unfinished run must have a live job so it can make progress again.
        int reEnqueued = 0;
        for (Run run : runs.findByStatusIn(List.of(RunStatus.queued, RunStatus.running))) {
            boolean hasLiveJob = jobs.findByRunId(run.getRunId()).stream()
                    .anyMatch(j -> j.getStatus() == QueueJobStatus.ready || j.getStatus() == QueueJobStatus.leased);
            if (!hasLiveJob) {
                queue.enqueue(run.getRunId());
                reEnqueued++;
            }
        }
        if (!orphaned.isEmpty() || reEnqueued > 0) {
            log.info("Recovery: reclaimed {} orphaned job(s), re-enqueued {} interrupted run(s)",
                    orphaned.size(), reEnqueued);
        }
        // Future-dated (delay) jobs are left alone; the poller dispatches them when they come due.
    }

    /** Runtime safety net: reclaim leases whose holder died without a restart. */
    @Scheduled(fixedDelayString = "${relay.worker.lease-reclaim-ms:30000}")
    @Transactional
    public void reclaimExpiredLeases() {
        List<QueueJob> expired = jobs.findByStatusAndLeasedUntilLessThan(QueueJobStatus.leased, Instant.now());
        for (QueueJob job : expired) {
            job.setStatus(QueueJobStatus.ready);
            job.setLeasedUntil(null);
            job.setUpdatedAt(Instant.now());
            jobs.save(job);
            log.warn("Reclaimed expired lease on job {} (run {})", job.getId(), job.getRunId());
        }
    }
}
