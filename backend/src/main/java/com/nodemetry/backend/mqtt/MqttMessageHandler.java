package com.nodemetry.backend.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nodemetry.backend.telemetry.TelemetryMessage;
import com.nodemetry.backend.telemetry.SensorReadingService;
import org.springframework.stereotype.Service;

@Service
public class MqttMessageHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SensorReadingService sensorReadingService;

    public MqttMessageHandler(SensorReadingService sensorReadingService) {
        this.sensorReadingService = sensorReadingService;
    }

    public void handleTelemetry(String topic, String payload) {
        try {
            TelemetryMessage message = objectMapper.readValue(payload, TelemetryMessage.class);

            System.out.println("=== Telemetry Received ===");
            System.out.println("Topic: " + topic);
            System.out.println("Node ID: " + message.nodeId());
            System.out.println("Message ID: " + message.messageId());

            sensorReadingService.processTelemetry(message);

            System.out.println("==========================");

        } catch (Exception e) {
            System.err.println("Failed to process telemetry message");
            System.err.println("Topic: " + topic);
            System.err.println("Payload: " + payload);
            System.err.println("Error: " + e.getMessage());
        }
    }

    public void handleStatus(String topic, String payload) {
        System.out.println("=== Status Received ===");
        System.out.println("Topic: " + topic);
        System.out.println("Payload: " + payload);
        System.out.println("=======================");
    }
}