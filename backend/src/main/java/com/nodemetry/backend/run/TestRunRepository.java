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

    // Absolute write (not increment) so it is idempotent: RunRegistry reconciles
    // its in-memory events against sensor_readings and mirrors the result here.
    // Ended runs are still written during the registry's post-end grace window so
    // late batches settle into the final totals; writes stop once the registry
    // evicts the run's counters.
    @Transactional
    @Modifying
    @Query("""
            update TestRun r
            set r.totalSaved = :saved, r.duplicatesSkipped = :dupes
            where r.runId = :runId
            """)
    int updateCounters(@Param("runId") String runId, @Param("saved") long saved, @Param("dupes") long dupes);
}
