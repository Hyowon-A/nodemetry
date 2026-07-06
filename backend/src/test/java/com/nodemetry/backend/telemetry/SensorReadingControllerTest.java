package com.nodemetry.backend.telemetry;

import com.nodemetry.backend.node.SensorNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SensorReadingControllerTest {

    @Mock
    private SensorNodeRepository nodeRepository;

    @Mock
    private SensorReadingRepository readingRepository;

    @Mock
    private RunTelemetryService runTelemetryService;

    private SensorReadingController controller;

    @BeforeEach
    void setUp() {
        controller = new SensorReadingController(nodeRepository, readingRepository, runTelemetryService);
    }

    @Test
    void getRunsForNodeReturnsRunIdsForKnownNode() {
        when(nodeRepository.existsByNodeId("node-001")).thenReturn(true);
        when(readingRepository.findRunIdsByNodeIdOrderByLatestReadingDesc("node-001"))
                .thenReturn(List.of("run-002", "run-001"));

        assertThat(controller.getRunsForNode("node-001"))
                .containsExactly("run-002", "run-001");
    }

    @Test
    void getReadingsForNodeRunDelegatesToNodeRunService() {
        List<SensorReadingResponse> expected = List.of();
        when(nodeRepository.existsByNodeId("node-001")).thenReturn(true);
        when(runTelemetryService.getReadingsForNodeRun("node-001", "run-001")).thenReturn(expected);

        assertThat(controller.getReadingsForNodeRun("node-001", "run-001")).isSameAs(expected);
    }

    @Test
    void getReadingsForNodeRunRejectsUnknownNode() {
        when(nodeRepository.existsByNodeId("missing-node")).thenReturn(false);

        assertThatThrownBy(() -> controller.getReadingsForNodeRun("missing-node", "run-001"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Node not found: missing-node");
        verifyNoInteractions(runTelemetryService);
    }
}
