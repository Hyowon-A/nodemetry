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
class TestRunRepositoryTest {

    @Autowired
    private TestRunRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void incrementMethodsPersistCountersForRunningRun() {
        repository.saveAndFlush(run("run-001", null));

        assertThat(repository.incrementTotalReceived("run-001")).isEqualTo(1);
        assertThat(repository.incrementTotalSaved("run-001")).isEqualTo(1);
        assertThat(repository.incrementDuplicatesSkipped("run-001")).isEqualTo(1);
        entityManager.clear();

        TestRun updated = repository.findByRunId("run-001").orElseThrow();
        assertThat(updated.getTotalReceived()).isEqualTo(1);
        assertThat(updated.getTotalSaved()).isEqualTo(1);
        assertThat(updated.getDuplicatesSkipped()).isEqualTo(1);
    }

    @Test
    void incrementMethodsSkipEndedRun() {
        repository.saveAndFlush(run("run-001", Instant.now()));

        assertThat(repository.incrementTotalReceived("run-001")).isZero();
        assertThat(repository.incrementTotalSaved("run-001")).isZero();
        assertThat(repository.incrementDuplicatesSkipped("run-001")).isZero();
        entityManager.clear();

        TestRun updated = repository.findByRunId("run-001").orElseThrow();
        assertThat(updated.getTotalReceived()).isZero();
        assertThat(updated.getTotalSaved()).isZero();
        assertThat(updated.getDuplicatesSkipped()).isZero();
    }

    private TestRun run(String runId, Instant endedAt) {
        TestRun run = new TestRun();
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
