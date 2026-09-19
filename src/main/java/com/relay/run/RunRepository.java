package com.relay.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RunRepository extends JpaRepository<Run, String> {

    List<Run> findByStatusOrderByStartedAtDesc(RunStatus status);

    List<Run> findByStatusIn(List<RunStatus> statuses);

    List<Run> findTop100ByOrderByStartedAtDesc();
}
