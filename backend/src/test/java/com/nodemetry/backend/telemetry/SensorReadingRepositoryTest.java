package com.nodemetry.backend.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class SensorReadingRepositoryTest {

    @Autowired
    private SensorReadingRepository repository;

    // The ingestion path relies on this: dedup is enforced by the unique
    // constraint on messageId, and because the id is IDENTITY-generated the INSERT
    // runs at save() time, so a redelivered messageId throws right there (not at
    // some later commit) — letting the caller record it as a duplicate.
    @Test
    void duplicateMessageIdThrowsAtSaveTime() {
        repository.save(reading("dup-001"));

        assertThatThrownBy(() -> repository.save(reading("dup-001")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void distinctMessageIdsSaveCleanly() {
        assertThatCode(() -> {
            repository.save(reading("uniq-001"));
            repository.save(reading("uniq-002"));
        }).doesNotThrowAnyException();
    }

    private SensorReading reading(String messageId) {
        return new SensorReading(
                messageId, "node-001", "run-001",
                23.5, 23.5, 48.2, 48.2, 87.0, 4200.0, -62.0, "firmware-1.0.0"
        );
    }
}
