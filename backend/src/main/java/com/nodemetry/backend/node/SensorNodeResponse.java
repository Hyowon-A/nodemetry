package com.nodemetry.backend.node;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "SensorNode", description = "Current status and health metadata for a physical sensor node.")
public record SensorNodeResponse(
        @Schema(description = "Stable node identifier.", example = "node-001")
        String nodeId,

        @Schema(description = "Optional display name.", example = "Kitchen sensor")
        String name,

        @Schema(description = "Optional deployment location.", example = "Kitchen")
        String location,

        @Schema(description = "Current node status.", example = "online", allowableValues = {"online", "offline"})
        String status,

        @Schema(description = "Latest battery percentage reported by the node.", example = "92.4")
        Double battery,

        @Schema(description = "Latest RSSI signal strength in dBm.", example = "-61.8")
        Double rssi,

        @Schema(description = "Firmware version reported by the node.", example = "firmware-1.0.0")
        String firmwareVersion,

        @Schema(description = "Last time the backend saw telemetry or online status for this node.", example = "2026-07-16T12:00:00Z")
        Instant lastSeenAt,

        @Schema(description = "Time this node was first created in the backend.", example = "2026-07-16T11:58:00Z")
        Instant createdAt
) {
    public static SensorNodeResponse from(SensorNode node) {
        return new SensorNodeResponse(
                node.getNodeId(),
                node.getName(),
                node.getLocation(),
                node.getStatus(),
                node.getBattery(),
                node.getRssi(),
                node.getFirmwareVersion(),
                node.getLastSeenAt(),
                node.getCreatedAt()
        );
    }
}
