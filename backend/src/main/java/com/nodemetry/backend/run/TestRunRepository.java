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
    boolean existsByRunId(String runId);
    List<TestRun> findAllByOrderByStartedAtDesc();

    // Absolute write (not increment) so it is idempotent while the run is active:
    // the in-memory LongAdders are the source of truth and this mirrors their
    // running totals. Ended runs are frozen by endRun and ignored here so late
    // readings cannot change run-history totals after the duration is done.
    @Transactional
    @Modifying
    @Query("""
            update TestRun r
            set r.totalSaved = :saved, r.duplicatesSkipped = :dupes
            where r.runId = :runId and r.endedAt is null
            """)
    int updateCounters(@Param("runId") String runId, @Param("saved") long saved, @Param("dupes") long dupes);
}
