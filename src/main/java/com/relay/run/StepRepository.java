package com.relay.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StepRepository extends JpaRepository<Step, Long> {

    List<Step> findByRunIdOrderBySequenceAsc(String runId);

    Optional<Step> findByRunIdAndNodeIdAndStatus(String runId, String nodeId, StepStatus status);

    boolean existsByRunIdAndNodeIdAndStatus(String runId, String nodeId, StepStatus status);
}
