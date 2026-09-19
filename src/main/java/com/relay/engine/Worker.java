package com.relay.engine;

import com.relay.engine.queue.QueueDispatcher;
import com.relay.engine.queue.QueueJob;
import com.relay.engine.queue.RunQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The durable worker loop. A dedicated thread blocks on the Redis dispatch list; each job id is
 * leased in the DB, the run is executed by {@link RunEngine}, and the job is marked done. The API
 * never runs a workflow inline — everything flows through here.
 */
@Component
public class Worker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private final StringRedisTemplate redis;
    private final QueueDispatcher dispatcher;
    private final RunEngine engine;
    private final boolean enabled;

    private volatile boolean running;
    private ExecutorService executor;

    public Worker(StringRedisTemplate redis, QueueDispatcher dispatcher, RunEngine engine,
                  @Value("${relay.worker.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.dispatcher = dispatcher;
        this.engine = engine;
        this.enabled = enabled;
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("Worker disabled (relay.worker.enabled=false)");
            return;
        }
        running = true;
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "relay-worker");
            t.setDaemon(true);
            return t;
        });
        executor.submit(this::loop);
        log.info("Relay worker started");
    }

    private void loop() {
        while (running) {
            try {
                String jobIdStr = redis.opsForList().rightPop(RunQueue.QUEUE_KEY, Duration.ofSeconds(2));
                if (jobIdStr != null) {
                    process(Long.parseLong(jobIdStr));
                }
            } catch (Exception e) {
                // Never let the loop die; back off briefly on unexpected errors (e.g. Redis blip).
                log.error("Worker loop error", e);
                sleepQuietly();
            }
        }
    }

    private void process(long jobId) {
        Optional<QueueJob> leased = dispatcher.lease(jobId);
        if (leased.isEmpty()) {
            return; // already handled by another nudge/worker
        }
        String runId = leased.get().getRunId();
        try {
            engine.execute(runId);
            dispatcher.complete(jobId);
        } catch (Exception e) {
            log.error("Run {} (job {}) failed in worker", runId, jobId, e);
            dispatcher.fail(jobId);
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
        log.info("Relay worker stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
