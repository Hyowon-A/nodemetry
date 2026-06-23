package com.nodemetry.backend.telemetry;

// DTO: MQTT payload JSON
public record TelemetryMessage(
        String messageId,
        String nodeId,
        Double temperature,
        Double humidity,
        Double co2,
        Double battery,
        Double rssi,
        String firmwareVersion
) {
}
