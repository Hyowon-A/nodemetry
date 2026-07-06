package com.nodemetry.backend.telemetry;

import com.nodemetry.backend.node.SensorNode;
import com.nodemetry.backend.node.SensorNodeRepository;
import com.nodemetry.backend.run.RunRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryServiceTest {

    @Mock
    private SensorReadingRepository readingRepository;

    @Mock
    private SensorNodeRepository nodeRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private RunRegistry runRegistry;

    @InjectMocks
    private TelemetryService service;

    @Test
    void processTelemetryCreatesNodeAndReadingWhenNodeDoesNotExist() {
        TelemetryMessage message = validMessage();
        when(readingRepository.existsByMessageId(message.messageId())).thenReturn(false);
        when(nodeRepository.findByNodeId(message.nodeId())).thenReturn(Optional.empty());

        service.processTelemetry(message);

        ArgumentCaptor<SensorNode> nodeCaptor = ArgumentCaptor.forClass(SensorNode.class);
        verify(nodeRepository).save(nodeCaptor.capture());
        SensorNode savedNode = nodeCaptor.getValue();

        assertThat(savedNode.getNodeId()).isEqualTo(message.nodeId());
        assertThat(savedNode.getStatus()).isEqualTo("online");
        assertThat(savedNode.getBattery()).isEqualTo(message.battery());
        assertThat(savedNode.getRssi()).isEqualTo(message.rssi());
        assertThat(savedNode.getFirmwareVersion()).isEqualTo(message.firmwareVersion());
        assertThat(savedNode.getLastSeenAt()).isNotNull();

        ArgumentCaptor<SensorReading> readingCaptor = ArgumentCaptor.forClass(SensorReading.class);
        verify(readingRepository).save(readingCaptor.capture());
        SensorReading savedReading = readingCaptor.getValue();

        assertThat(savedReading.getMessageId()).isEqualTo(message.messageId());
        assertThat(savedReading.getNodeId()).isEqualTo(message.nodeId());
        assertThat(savedReading.getRunId()).isEqualTo(message.runId());
        assertThat(savedReading.getTemperature()).isEqualTo(message.temperature());
        assertThat(savedReading.getHumidity()).isEqualTo(message.humidity());
        assertThat(savedReading.getCo2()).isEqualTo(message.co2());
        assertThat(savedReading.getLight()).isEqualTo(message.light());
        assertThat(savedReading.getBattery()).isEqualTo(message.battery());
        assertThat(savedReading.getRssi()).isEqualTo(message.rssi());
        assertThat(savedReading.getFirmwareVersion()).isEqualTo(message.firmwareVersion());
        assertThat(savedReading.getMeasuredAt()).isNotNull();
        assertThat(savedReading.getReceivedAt()).isNotNull();
        verify(runRegistry).recordReceived();
        verify(runRegistry).recordSaved();
        verify(runRegistry, never()).recordDupe();
    }

    @Test
    void processTelemetryUpdatesExistingNodeHealthAndSavesReading() {
        TelemetryMessage message = validMessage();
        SensorNode existingNode = new SensorNode(message.nodeId());
        existingNode.updateHealth(10.0, -90.0, "old-firmware");

        when(readingRepository.existsByMessageId(message.messageId())).thenReturn(false);
        when(nodeRepository.findByNodeId(message.nodeId())).thenReturn(Optional.of(existingNode));

        service.processTelemetry(message);

        verify(nodeRepository).save(existingNode);
        assertThat(existingNode.getBattery()).isEqualTo(message.battery());
        assertThat(existingNode.getRssi()).isEqualTo(message.rssi());
        assertThat(existingNode.getFirmwareVersion()).isEqualTo(message.firmwareVersion());
        assertThat(existingNode.getStatus()).isEqualTo("online");

        ArgumentCaptor<SensorReading> readingCaptor = ArgumentCaptor.forClass(SensorReading.class);
        verify(readingRepository).save(readingCaptor.capture());
        assertThat(readingCaptor.getValue().getMessageId()).isEqualTo(message.messageId());
        verify(runRegistry).recordReceived();
        verify(runRegistry).recordSaved();
        verify(runRegistry, never()).recordDupe();
    }

    @Test
    void processTelemetrySkipsDuplicateMessage() {
        TelemetryMessage message = validMessage();
        when(readingRepository.existsByMessageId(message.messageId())).thenReturn(true);

        service.processTelemetry(message);

        verify(readingRepository).existsByMessageId(message.messageId());
        verify(readingRepository, never()).save(any());
        verifyNoInteractions(nodeRepository);
        verify(runRegistry).recordReceived();
        verify(runRegistry).recordDupe();
        verify(runRegistry, never()).recordSaved();
    }

    @Test
    void processTelemetryStoresNullLightForNodesWithoutLightSensor() {
        TelemetryMessage message = new TelemetryMessage(
                "message-002", "node-001", "run-001", 23.5, 48.2, 615.0, 87.0, -62.0, "firmware-1.0.0", null
        );
        when(readingRepository.existsByMessageId(message.messageId())).thenReturn(false);
        when(nodeRepository.findByNodeId(message.nodeId())).thenReturn(Optional.empty());

        service.processTelemetry(message);

        ArgumentCaptor<SensorReading> readingCaptor = ArgumentCaptor.forClass(SensorReading.class);
        verify(readingRepository).save(readingCaptor.capture());
        assertThat(readingCaptor.getValue().getLight()).isNull();
    }

    @ParameterizedTest // Check multiple null and empty values
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void processTelemetryRejectsMissingMessageId(String messageId) {
        TelemetryMessage message = message(
                messageId,
                "node-001",
                "firmware-1.0.0"
        );

        assertThatThrownBy(() -> service.processTelemetry(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("messageId is required");

        verifyNoInteractions(readingRepository, nodeRepository, runRegistry);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void processTelemetryRejectsMissingNodeId(String nodeId) {
        TelemetryMessage message = message(
                "message-001",
                nodeId,
                "firmware-1.0.0"
        );

        assertThatThrownBy(() -> service.processTelemetry(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nodeId is required");

        verifyNoInteractions(readingRepository, nodeRepository, runRegistry);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void processTelemetryRejectsMissingFirmwareVersion(String firmwareVersion) {
        TelemetryMessage message = message(
                "message-001",
                "node-001",
                firmwareVersion
        );

        assertThatThrownBy(() -> service.processTelemetry(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("firmwareVersion is required");

        verifyNoInteractions(readingRepository, nodeRepository, runRegistry);
    }

    private TelemetryMessage validMessage() {
        return message("message-001", "node-001", "firmware-1.0.0");
    }

    private TelemetryMessage message(String messageId, String nodeId, String firmwareVersion) {
        return new TelemetryMessage(
                messageId,
                nodeId,
                "run-001",
                23.5,
                48.2,
                615.0,
                87.0,
                -62.0,
                firmwareVersion,
                4200.0
        );
    }
}
