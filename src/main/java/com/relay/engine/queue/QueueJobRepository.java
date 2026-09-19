package com.relay.engine.queue;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QueueJobRepository extends JpaRepository<QueueJob, Long> {

    List<QueueJob> findByRunId(String runId);

    List<QueueJob> findByStatus(QueueJobStatus status);
}
