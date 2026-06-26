package com.nodemetry.backend.telemetry;

import java.time.Instant;

public record SensorReadingResponse(
        String messageId,
        String nodeId,
        Double temperature,
        Double humidity,
        Double co2,
        Double battery,
        Double rssi,
        String firmwareVersion,
        Double light,
        Instant measuredAt,
        Instant receivedAt
) {
    public static SensorReadingResponse from(SensorReading reading) {
        return new SensorReadingResponse(
                reading.getMessageId(),
                reading.getNodeId(),
                reading.getTemperature(),
                reading.getHumidity(),
                reading.getCo2(),
                reading.getBattery(),
                reading.getRssi(),
                reading.getFirmwareVersion(),
                reading.getLight(),
                reading.getMeasuredAt(),
                reading.getReceivedAt()
        );
    }
}