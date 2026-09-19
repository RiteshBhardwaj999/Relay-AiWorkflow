package com.relay.engine.queue;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Atomic state transitions for queue jobs. {@link #lease(long)} flips a job {@code ready → leased}
 * in one transaction and returns empty if the job was already taken — this dedups the case where
 * both the fast Redis push and the DB poller nudge the same job.
 */
@Service
public class QueueDispatcher {

    /** How long a worker may hold a job before recovery may reclaim it. */
    public static final long LEASE_SECONDS = 120;

    private final QueueJobRepository jobs;

    public QueueDispatcher(QueueJobRepository jobs) {
        this.jobs = jobs;
    }

    /** Claim a job for execution. Returns empty if it no longer exists or is not {@code ready}. */
    @Transactional
    public Optional<QueueJob> lease(long jobId) {
        QueueJob job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != QueueJobStatus.ready) {
            return Optional.empty();
        }
        job.setStatus(QueueJobStatus.leased);
        job.setLeasedUntil(Instant.now().plusSeconds(LEASE_SECONDS));
        job.setAttempts(job.getAttempts() + 1);
        job.setUpdatedAt(Instant.now());
        return Optional.of(jobs.save(job));
    }

    @Transactional
    public void complete(long jobId) {
        jobs.findById(jobId).ifPresent(job -> {
            job.setStatus(QueueJobStatus.done);
            job.setUpdatedAt(Instant.now());
            jobs.save(job);
        });
    }

    @Transactional
    public void fail(long jobId) {
        jobs.findById(jobId).ifPresent(job -> {
            job.setStatus(QueueJobStatus.failed);
            job.setUpdatedAt(Instant.now());
            jobs.save(job);
        });
    }
}
