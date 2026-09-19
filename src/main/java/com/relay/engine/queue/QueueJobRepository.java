package com.relay.engine.queue;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface QueueJobRepository extends JpaRepository<QueueJob, Long> {

    List<QueueJob> findByRunId(String runId);

    List<QueueJob> findByStatus(QueueJobStatus status);

    /** Ready jobs whose availability time has arrived — candidates for dispatch. */
    List<QueueJob> findByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAsc(
            QueueJobStatus status, Instant time);

    /** Leased jobs whose lease has expired — reclaimed by recovery (Phase 6). */
    List<QueueJob> findByStatusAndLeasedUntilLessThan(QueueJobStatus status, Instant time);
}
