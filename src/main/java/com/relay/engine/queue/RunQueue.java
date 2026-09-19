package com.relay.engine.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Durable run dispatch. A {@link QueueJob} row is the durable source of truth (survives restarts,
 * carries {@code available_at} for delayed work and a lease for crash recovery); Redis is a
 * low-latency notification that a job is ready. The Phase 4 worker consumes from both.
 */
@Service
public class RunQueue {

    public static final String QUEUE_KEY = "relay:run-queue";

    private static final Logger log = LoggerFactory.getLogger(RunQueue.class);

    private final QueueJobRepository jobs;
    private final StringRedisTemplate redis;

    public RunQueue(QueueJobRepository jobs, StringRedisTemplate redis) {
        this.jobs = jobs;
        this.redis = redis;
    }

    /** Enqueue a run for immediate execution. */
    @Transactional
    public QueueJob enqueue(String runId) {
        return enqueueAt(runId, Instant.now());
    }

    /** Enqueue a run to become available at {@code availableAt} (used by the delay node). */
    @Transactional
    public QueueJob enqueueAt(String runId, Instant availableAt) {
        QueueJob job = jobs.save(new QueueJob(runId, availableAt));
        // Fast path: nudge Redis with the job id. If it's a future (delayed) job, or the push
        // fails, the DB poller will pick it up — the QueueJob row is the durable source of truth.
        if (!availableAt.isAfter(Instant.now())) {
            nudge(job.getId());
        }
        return job;
    }

    /** Best-effort push of a job id onto the Redis dispatch list. */
    public void nudge(Long jobId) {
        try {
            redis.opsForList().leftPush(QUEUE_KEY, String.valueOf(jobId));
        } catch (RuntimeException e) {
            log.warn("Redis push failed for job {} — DB poller will recover it", jobId, e);
        }
    }
}
