package com.relay.engine.queue;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Safety net for dispatch. Every second it re-nudges Redis for any {@code ready} job whose time has
 * come — covering delayed (delay-node) jobs, jobs whose fast-path Redis push failed, and jobs
 * enqueued while Redis was briefly unavailable. Duplicate nudges are harmless: {@link QueueDispatcher#lease}
 * only lets one worker claim a job.
 */
@Component
public class QueuePoller {

    private final QueueJobRepository jobs;
    private final RunQueue runQueue;

    public QueuePoller(QueueJobRepository jobs, RunQueue runQueue) {
        this.jobs = jobs;
        this.runQueue = runQueue;
    }

    @Scheduled(fixedDelayString = "${relay.worker.poll-interval-ms:1000}")
    @Transactional(readOnly = true)
    public void nudgeReadyJobs() {
        List<QueueJob> due = jobs.findByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAsc(
                QueueJobStatus.ready, Instant.now());
        for (QueueJob job : due) {
            runQueue.nudge(job.getId());
        }
    }
}
