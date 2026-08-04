package com.nodemetry.backend.run;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class PhysicalNodeRunRepositoryTest {

    @Autowired
    private PhysicalNodeRunRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void updateCountersSetsAbsoluteValues() {
        repository.saveAndFlush(row("run-001", "kitchen-03"));

        Instant lastMessageAt = Instant.parse("2026-07-16T09:00:00Z");
        assertThat(repository.updateCounters("run-001", "kitchen-03", 7, 5, 2, 1.5, lastMessageAt))
                .isEqualTo(1);
        entityManager.clear();

        PhysicalNodeRun updated = repository.findByRunIdAndNodeId("run-001", "kitchen-03").orElseThrow();
        assertThat(updated.getMessagesReceived()).isEqualTo(7);
        assertThat(updated.getMessagesSaved()).isEqualTo(5);
        assertThat(updated.getDuplicatesSkipped()).isEqualTo(2);
        assertThat(updated.getAvgProcessingMs()).isEqualTo(1.5);
        assertThat(updated.getLastMessageAt()).isEqualTo(lastMessageAt);
    }

    @Test
    void updateCountersHitsNothingForUnknownPair() {
        repository.saveAndFlush(row("run-001", "kitchen-03"));

        assertThat(repository.updateCounters("run-001", "kitchen-04", 1, 1, 0, null, Instant.now()))
                .isZero();
    }

    @Test
    void duplicateRunNodePairViolatesUniqueConstraint() {
        repository.saveAndFlush(row("run-001", "kitchen-03"));

        assertThatThrownBy(() -> repository.saveAndFlush(row("run-001", "kitchen-03")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private PhysicalNodeRun row(String runId, String nodeId) {
        PhysicalNodeRun row = new PhysicalNodeRun();
        row.setRunId(runId);
        row.setNodeId(nodeId);
        row.setMessagesReceived(1);
        row.setMessagesSaved(1);
        row.setFirstMessageAt(Instant.now());
        row.setLastMessageAt(Instant.now());
        return row;
    }
}
