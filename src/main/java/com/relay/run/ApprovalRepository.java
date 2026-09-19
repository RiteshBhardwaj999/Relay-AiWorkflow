package com.relay.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalRepository extends JpaRepository<Approval, String> {

    List<Approval> findByStatusOrderByCreatedAtAsc(ApprovalStatus status);

    List<Approval> findByRunId(String runId);

    boolean existsByRunIdAndStatus(String runId, ApprovalStatus status);
}
