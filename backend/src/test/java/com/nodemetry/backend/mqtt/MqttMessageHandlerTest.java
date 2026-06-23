package com.nodemetry.backend.mqtt;

import com.nodemetry.backend.telemetry.TelemetryService;
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

@ExtendWith(MockitoExtension.class)
class MqttMessageHandlerTest {

    @Mock
    private TelemetryService telemetryService;

    @InjectMocks
    private MqttMessageHandler handler;

    @Test
    void handleTelemetryParsesPayloadAndProcessesTelemetry() {
        String payload = """
                {
                  "messageId": "message-001",
                  "nodeId": "test-node-001",
                  "temperature": 23.5,
                  "humidity": 48.2,
                  "co2": 615.0,
                  "battery": 87.0,
                  "rssi": -62.0,
                  "firmwareVersion": "firmware-1.0.0"
                }
                """;

        handler.handleTelemetry("nodemetry/node-001/telemetry", payload);

        ArgumentCaptor<TelemetryMessage> messageCaptor = ArgumentCaptor.forClass(TelemetryMessage.class);
        verify(telemetryService).processTelemetry(messageCaptor.capture());
        TelemetryMessage message = messageCaptor.getValue();

        assertThat(message.messageId()).isEqualTo("message-001");
        assertThat(message.nodeId()).isEqualTo("test-node-001");
        assertThat(message.temperature()).isEqualTo(23.5);
        assertThat(message.humidity()).isEqualTo(48.2);
        assertThat(message.co2()).isEqualTo(615.0);
        assertThat(message.battery()).isEqualTo(87.0);
        assertThat(message.rssi()).isEqualTo(-62.0);
        assertThat(message.firmwareVersion()).isEqualTo("firmware-1.0.0");
    }

    @Test
    void handleTelemetryDoesNotThrowOrProcessWhenPayloadIsInvalidJson() {
        String payload = "{not-json";

        assertThatCode(() -> handler.handleTelemetry("nodemetry/node-001/telemetry", payload))
                .doesNotThrowAnyException();

        verify(telemetryService, never()).processTelemetry(any());
    }

    @Test
    void handleTelemetryDoesNotPropagateServiceExceptions() {
        String payload = """
                {
                  "messageId": "message-001",
                  "nodeId": "test-node-001",
                  "temperature": 23.5,
                  "humidity": 48.2,
                  "co2": 615.0,
                  "battery": 87.0,
                  "rssi": -62.0,
                  "firmwareVersion": "firmware-1.0.0"
                }
                """;
        doThrow(new IllegalArgumentException("messageId is required"))
                .when(telemetryService)
                .processTelemetry(any());

        assertThatCode(() -> handler.handleTelemetry("nodemetry/node-001/telemetry", payload))
                .doesNotThrowAnyException();

        verify(telemetryService).processTelemetry(any());
    }

    @Test
    void handleStatusDoesNotProcessTelemetry() {
        handler.handleStatus("nodemetry/node-001/status", "{\"status\":\"online\"}");

        verifyNoInteractions(telemetryService);
    }
}
