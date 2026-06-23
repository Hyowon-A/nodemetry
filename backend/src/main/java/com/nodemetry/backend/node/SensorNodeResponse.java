package com.nodemetry.backend.node;

import java.time.Instant;

public record SensorNodeResponse(
        String nodeId,
        String name,
        String location,
        String status,
        Double battery,
        Double rssi,
        String firmwareVersion,
        Instant lastSeenAt,
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