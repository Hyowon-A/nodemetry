package com.nodemetry.backend.telemetry;

// DTO: MQTT payload JSON
public record TelemetryMessage(
        String messageId,
        String nodeId,
        String runId,
        Double temperatureRaw,
        Double temperatureFiltered,
        Double humidityRaw,
        Double humidityFiltered,
        Double battery,
        Double light,
        Double rssi,
        String firmwareVersion
) {
}
