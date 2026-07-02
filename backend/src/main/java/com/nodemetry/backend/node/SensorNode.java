package com.nodemetry.backend.node;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "nodes")
public class SensorNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_id", unique = true, nullable = false)
    private String nodeId;

    private String name;
    private String location;
    private String status = "offline";
    private Double battery;
    private Double rssi;

    @Column(name = "firmware_version")
    private String firmwareVersion;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public SensorNode() {}

    public SensorNode(String nodeId) {
        this.nodeId = nodeId;
        this.status = "offline";
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getName() {
        return name;
    }

    public String getLocation() {
        return location;
    }

    public String getStatus() {
        return status;
    }

    public Double getBattery() {
        return battery;
    }

    public Double getRssi() {
        return rssi;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void updateHealth(Double battery, Double rssi, String firmwareVersion) {
        this.status = "online";
        this.battery = battery;
        this.rssi = rssi;
        this.firmwareVersion = firmwareVersion;
        this.lastSeenAt = Instant.now();
    }

    public void markOnline() {
        this.status = "online";
        this.lastSeenAt = Instant.now();
    }

    public void markOffline() {
        this.status = "offline";
    }
}
