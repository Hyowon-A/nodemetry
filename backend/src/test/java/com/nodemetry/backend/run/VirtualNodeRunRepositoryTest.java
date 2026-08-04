package com.nodemetry.backend.run;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class VirtualNodeRunRepositoryTest {

    @Autowired
    private VirtualNodeRunRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void updateCountersSetsAbsoluteValuesForRunningRun() {
        repository.saveAndFlush(run("run-001", null));

        assertThat(repository.updateCounters("run-001", 5, 2)).isEqualTo(1);
        entityManager.clear();

        VirtualNodeRun updated = repository.findByRunId("run-001").orElseThrow();
        assertThat(updated.getTotalReceived()).isZero();
        assertThat(updated.getTotalSaved()).isEqualTo(5);
        assertThat(updated.getDuplicatesSkipped()).isEqualTo(2);
    }

    @Test
    void updateCountersAlsoUpdatesEndedRun() {
        // RunRegistry keeps reconciling for a grace window after a run ends so
        // late batches settle into the final totals; the write must not be
        // blocked by endedAt being set.
        VirtualNodeRun run = run("run-001", Instant.now());
        run.setTotalSaved(4);
        run.setDuplicatesSkipped(1);
        repository.saveAndFlush(run);

        assertThat(repository.updateCounters("run-001", 7, 3)).isEqualTo(1);
        entityManager.clear();

        VirtualNodeRun updated = repository.findByRunId("run-001").orElseThrow();
        assertThat(updated.getTotalSaved()).isEqualTo(7);
        assertThat(updated.getDuplicatesSkipped()).isEqualTo(3);
    }

    private VirtualNodeRun run(String runId, Instant endedAt) {
        VirtualNodeRun run = new VirtualNodeRun();
        run.setRunId(runId);
        run.setLabel("Load test");
        run.setStartedAt(Instant.now());
        run.setEndedAt(endedAt);
        run.setQos(1);
        run.setNodeCount(3);
        run.setIntervalSec(0.5);
        run.setDuplicateRate(0.1);
        return run;
    }
}
