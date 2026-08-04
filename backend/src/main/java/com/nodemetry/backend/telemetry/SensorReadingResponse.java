package com.nodemetry.backend.telemetry;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "SensorReading", description = "Persisted telemetry reading for a physical or virtual node.")
public record SensorReadingResponse(
        @Schema(description = "Unique idempotency key for this telemetry reading.", example = "node-001-20260716T120000Z-000001")
        String messageId,

        @Schema(description = "Node that produced this reading.", example = "node-001")
        String nodeId,

        @Schema(description = "Run identifier used to group readings.", example = "20260716T120000Z")
        String runId,

        @Schema(description = "Raw temperature in Celsius.", example = "23.91")
        Double temperatureRaw,

        @Schema(description = "Filtered temperature in Celsius.", example = "23.72")
        Double temperatureFiltered,

        @Schema(description = "Raw relative humidity percentage.", example = "48.2")
        Double humidityRaw,

        @Schema(description = "Filtered relative humidity percentage.", example = "48.0")
        Double humidityFiltered,

        @Schema(description = "Battery percentage.", example = "92.4")
        Double battery,

        @Schema(description = "Light level in lux.", example = "1240.5")
        Double light,

        @Schema(description = "RSSI signal strength in dBm.", example = "-61.8")
        Double rssi,

        @Schema(description = "Firmware version reported by the node.", example = "firmware-1.0.0")
        String firmwareVersion,

        @Schema(description = "Time the node measured the reading.", example = "2026-07-16T12:00:00Z")
        Instant measuredAt,

        @Schema(description = "Time the backend received and stored the reading.", example = "2026-07-16T12:00:01Z")
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
