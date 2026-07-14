package com.nodemetry.backend.telemetry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunTelemetryServiceTest {

    @Mock
    private SensorReadingRepository readingRepository;

    private RunTelemetryService service;

    @BeforeEach
    void setUp() {
        service = new RunTelemetryService(readingRepository);
    }

    @Test
    void getReadingsForNodeRunReturnsAllReadings() {
        when(readingRepository.findByNodeIdAndRunIdOrderByReceivedAtDesc("node-001", "run-001"))
                .thenReturn(List.of(
                        reading("message-003"),
                        reading("message-002"),
                        reading("message-001")
                ));

        List<SensorReadingResponse> responses = service.getReadingsForNodeRun("node-001", "run-001");

        assertThat(responses)
                .extracting(SensorReadingResponse::messageId)
                .containsExactly("message-003", "message-002", "message-001");
    }

    private SensorReading reading(String messageId) {
        return new SensorReading(
                messageId,
                "node-001",
                "run-001",
                23.5,
                23.5,
                48.2,
                48.2,
                87.0,
                4200.0,
                -62.0,
                "firmware-1.0.0"
        );
    }
}
