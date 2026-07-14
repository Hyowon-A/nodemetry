package com.nodemetry.backend.telemetry;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "sensor_readings",
        indexes = {
                @Index(name = "idx_readings_node_time", columnList = "node_id, received_at"),
                @Index(name = "idx_readings_run_time", columnList = "run_id, received_at"),
                @Index(name = "idx_readings_node_run_time", columnList = "node_id, run_id, received_at")
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

    @Column(name = "run_id")
    private String runId;

    private Double temperatureRaw;
    private Double temperatureFiltered;
    private Double humidityRaw;
    private Double humidityFiltered;
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
        this.messageId = messageId;
        this.nodeId = nodeId;
        this.runId = runId;
        this.temperatureRaw = temperatureRaw;
        this.temperatureFiltered = temperatureFiltered;
        this.humidityRaw = humidityRaw;
        this.humidityFiltered = humidityFiltered;
        this.battery = battery;
        this.light = light;
        this.rssi = rssi;
        this.firmwareVersion = firmwareVersion;
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

    public String getRunId() {
        return runId;
    }

    public Double getTemperatureRaw() {
        return temperatureRaw;
    }

    public Double getTemperatureFiltered() {
        return temperatureFiltered;
    }

    public Double getHumidityRaw() {
        return humidityRaw;
    }

    public Double getHumidityFiltered() {
        return humidityFiltered;
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
