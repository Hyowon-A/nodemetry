package com.nodemetry.backend.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nodemetry.backend.telemetry.TelemetryMessage;
import org.springframework.stereotype.Service;

@Service
public class MqttMessageHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void handleTelemetry(String topic, String payload) {
        try {
            TelemetryMessage message = objectMapper.readValue(payload, TelemetryMessage.class);

            System.out.println("=== Telemetry Received ===");
            System.out.println("Topic: " + topic);
            System.out.println("Node ID: " + message.nodeId());
            System.out.println("Message ID: " + message.messageId());
            System.out.println("Temperature: " + message.temperature());
            System.out.println("Humidity: " + message.humidity());
            System.out.println("CO2: " + message.co2());
            System.out.println("Light: " + message.light());
            System.out.println("Battery: " + message.battery());
            System.out.println("RSSI: " + message.rssi());
            System.out.println("Firmware: " + message.firmwareVersion());
            System.out.println("==========================");

        } catch (Exception e) {
            System.err.println("Failed to parse telemetry message");
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