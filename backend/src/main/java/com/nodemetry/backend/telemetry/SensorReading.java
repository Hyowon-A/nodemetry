package com.nodemetry.backend.telemetry;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "sensor_readings",
        indexes = {
                @Index(name = "idx_readings_node_time", columnList = "node_id, received_at")
        }
)
public class SensorReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String messageId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    private Double temperature;
    private Double humidity;
    private Double co2;
    private Double battery;
    private Double rssi;
    private Double light;

    @Column(name = "firmware_version")
    private String firmwareVersion;

    @Column(name = "measured_at")
    private Instant measuredAt;

    @Column(name = "received_at")
    private Instant receivedAt = Instant.now();

    public SensorReading() {}

    public SensorReading(
            String messageId,
            String nodeId,
            Double temperature,
            Double humidity,
            Double co2,
            Double battery,
            Double rssi,
            String firmwareVersion,
            Double light
    ) {
        this.messageId = messageId;
        this.nodeId = nodeId;
        this.temperature = temperature;
        this.humidity = humidity;
        this.co2 = co2;
        this.battery = battery;
        this.rssi = rssi;
        this.firmwareVersion = firmwareVersion;
        this.light = light;
        this.measuredAt = Instant.now();
        this.receivedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public Double getTemperature() {
        return temperature;
    }

    public Double getHumidity() {
        return humidity;
    }

    public Double getCo2() {
        return co2;
    }

    public Double getBattery() {
        return battery;
    }

    public Double getRssi() { return rssi; }

    public Double getLight() { return light; }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}