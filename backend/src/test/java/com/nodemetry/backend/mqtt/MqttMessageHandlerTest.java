package com.nodemetry.backend.mqtt;

import com.nodemetry.backend.node.NodeService;
import com.nodemetry.backend.telemetry.TelemetryBatchIngestService;
import com.nodemetry.backend.telemetry.TelemetryMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MqttMessageHandlerTest {

    @Mock
    private TelemetryBatchIngestService batchIngestService;

    @Mock
    private NodeService nodeService;

    @InjectMocks
    private MqttMessageHandler handler;

    @Test
    void handleTelemetryParsesPayloadAndEnqueuesTelemetry() {
        String payload = """
                {
                  "messageId": "message-001",
                  "nodeId": "test-node-001",
                  "runId": "20260706T132045Z",
                  "temperatureRaw": 23.5,
                  "temperatureFiltered": 23.5,
                  "humidityRaw": 48.2,
                  "humidityFiltered": 48.2,
                  "battery": 87.0,
                  "light": 4200.0,
                  "rssi": -62.0,
                  "firmwareVersion": "firmware-1.0.0"
                }
                """;
        when(batchIngestService.enqueue(any())).thenReturn(true);

        handler.handleTelemetry("nodemetry/node-001/telemetry", payload);

        ArgumentCaptor<TelemetryMessage> messageCaptor = ArgumentCaptor.forClass(TelemetryMessage.class);
        verify(batchIngestService).enqueue(messageCaptor.capture());
        TelemetryMessage message = messageCaptor.getValue();

        assertThat(message.messageId()).isEqualTo("message-001");
        assertThat(message.nodeId()).isEqualTo("test-node-001");
        assertThat(message.runId()).isEqualTo("20260706T132045Z");
        assertThat(message.temperatureRaw()).isEqualTo(23.5);
        assertThat(message.temperatureFiltered()).isEqualTo(23.5);
        assertThat(message.humidityRaw()).isEqualTo(48.2);
        assertThat(message.humidityFiltered()).isEqualTo(48.2);
        assertThat(message.battery()).isEqualTo(87.0);
        assertThat(message.light()).isEqualTo(4200.0);
        assertThat(message.rssi()).isEqualTo(-62.0);
        assertThat(message.firmwareVersion()).isEqualTo("firmware-1.0.0");
    }

    @Test
    void handleTelemetryDoesNotThrowWhenQueueIsFull() {
        String payload = """
                {
                  "messageId": "message-001",
                  "nodeId": "test-node-001",
                  "runId": "20260706T132045Z",
                  "temperatureRaw": 23.5,
                  "temperatureFiltered": 23.5,
                  "humidityRaw": 48.2,
                  "humidityFiltered": 48.2,
                  "battery": 87.0,
                  "light": 4200.0,
                  "rssi": -62.0,
                  "firmwareVersion": "firmware-1.0.0"
                }
                """;
        when(batchIngestService.enqueue(any())).thenReturn(false);

        assertThatCode(() -> handler.handleTelemetry("nodemetry/node-001/telemetry", payload))
                .doesNotThrowAnyException();

        verify(batchIngestService).enqueue(any());
    }

    @Test
    void handleTelemetryDoesNotThrowOrEnqueueWhenPayloadIsInvalidJson() {
        String payload = "{not-json";

        assertThatCode(() -> handler.handleTelemetry("nodemetry/node-001/telemetry", payload))
                .doesNotThrowAnyException();

        verify(batchIngestService, never()).enqueue(any());
    }

    @Test
    void handleTelemetryAcceptsNullLight() {
        String payload = """
                {
                  "messageId": "message-002",
                  "nodeId": "test-node-001",
                  "runId": "20260706T132045Z",
                  "temperatureRaw": 23.5,
                  "temperatureFiltered": 23.5,
                  "humidityRaw": 48.2,
                  "humidityFiltered": 48.2,
                  "battery": 87.0,
                  "light": null,
                  "rssi": -62.0,
                  "firmwareVersion": "firmware-1.0.0"
                }
                """;
        when(batchIngestService.enqueue(any())).thenReturn(true);

        handler.handleTelemetry("nodemetry/node-001/telemetry", payload);

        ArgumentCaptor<TelemetryMessage> messageCaptor = ArgumentCaptor.forClass(TelemetryMessage.class);
        verify(batchIngestService).enqueue(messageCaptor.capture());
        assertThat(messageCaptor.getValue().light()).isNull();
    }

    @Test
    void handleTelemetryIgnoresRetainedMessages() {
        String payload = """
                {
                  "messageId": "message-001",
                  "nodeId": "test-node-001",
                  "runId": "20260706T132045Z",
                  "temperatureRaw": 23.5,
                  "temperatureFiltered": 23.5,
                  "humidityRaw": 48.2,
                  "humidityFiltered": 48.2,
                  "battery": 87.0,
                  "light": 4200.0,
                  "rssi": -62.0,
                  "firmwareVersion": "firmware-1.0.0"
                }
                """;

        handler.handleTelemetry("nodemetry/node-001/telemetry", payload, true);

        verifyNoInteractions(batchIngestService);
    }

    @Test
    void handleStatusDoesNotProcessTelemetry() {
        handler.handleStatus("nodemetry/node-001/status", "{\"status\":\"online\"}");

        verifyNoInteractions(batchIngestService);
    }

    @Test
    void handleStatusUpdatesNodeStatus() {
        handler.handleStatus("nodemetry/node-001/status", "{\"status\":\"online\"}");

        verify(nodeService).processStatusUpdate("node-001", "online");
    }

    @Test
    void handleStatusUsesOfflinePayload() {
        handler.handleStatus("nodemetry/node-001/status", "{\"status\":\"offline\"}");

        verify(nodeService).processStatusUpdate("node-001", "offline");
    }

    @Test
    void handleStatusIgnoresRetainedMessages() {
        handler.handleStatus("nodemetry/node-001/status", "{\"status\":\"online\"}", true);

        verifyNoInteractions(nodeService);
    }

    @Test
    void handleStatusDoesNotUpdateWhenPayloadIsMissingStatus() {
        assertThatCode(() -> handler.handleStatus("nodemetry/node-001/status", "{}"))
                .doesNotThrowAnyException();

        verifyNoInteractions(nodeService);
    }

    @Test
    void handleStatusDoesNotUpdateWhenPayloadHasInvalidStatus() {
        assertThatCode(() -> handler.handleStatus("nodemetry/node-001/status", "{\"status\":\"booting\"}"))
                .doesNotThrowAnyException();

        verifyNoInteractions(nodeService);
    }

    @Test
    void handleStatusDoesNotThrowOnMalformedTopic() {
        assertThatCode(() -> handler.handleStatus("bad-topic", "{}"))
                .doesNotThrowAnyException();

        verifyNoInteractions(nodeService);
    }

    @Test
    void handleStatusDoesNotPropagateServiceExceptions() {
        doThrow(new RuntimeException("db error"))
                .when(nodeService)
                .processStatusUpdate(any(), any());

        assertThatCode(() -> handler.handleStatus("nodemetry/node-001/status", "{\"status\":\"online\"}"))
                .doesNotThrowAnyException();
    }
}
