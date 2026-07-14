package com.nodemetry.backend.telemetry;

import java.time.Instant;

public record SensorReadingResponse(
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
        String firmwareVersion,
        Instant measuredAt,
        Instant receivedAt
) {
    public static SensorReadingResponse from(SensorReading reading) {
        return new SensorReadingResponse(
                reading.getMessageId(),
                reading.getNodeId(),
                reading.getRunId(),
                reading.getTemperatureRaw(),
                reading.getTemperatureFiltered(),
                reading.getHumidityRaw(),
                reading.getHumidityFiltered(),
                reading.getBattery(),
                reading.getLight(),
                reading.getRssi(),
                reading.getFirmwareVersion(),
                reading.getMeasuredAt(),
                reading.getReceivedAt()
        );
    }
}
