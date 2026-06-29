package com.nodemetry.backend.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TestRunRepository extends JpaRepository<TestRun, Long> {
    Optional<TestRun> findByRunId(String runId);
    List<TestRun> findAllByOrderByStartedAtDesc();
}
