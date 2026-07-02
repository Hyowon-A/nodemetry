package com.nodemetry.backend.run;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface TestRunRepository extends JpaRepository<TestRun, Long> {
    Optional<TestRun> findByRunId(String runId);
    List<TestRun> findAllByOrderByStartedAtDesc();

    @Transactional
    @Modifying
    @Query("update TestRun r set r.totalReceived = r.totalReceived + 1 where r.runId = :runId and r.endedAt is null")
    int incrementTotalReceived(@Param("runId") String runId);

    @Transactional
    @Modifying
    @Query("update TestRun r set r.totalSaved = r.totalSaved + 1 where r.runId = :runId and r.endedAt is null")
    int incrementTotalSaved(@Param("runId") String runId);

    @Transactional
    @Modifying
    @Query("update TestRun r set r.duplicatesSkipped = r.duplicatesSkipped + 1 where r.runId = :runId and r.endedAt is null")
    int incrementDuplicatesSkipped(@Param("runId") String runId);
}
