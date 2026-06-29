package com.nodemetry.backend.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nodemetry.backend.node.NodeService;
import com.nodemetry.backend.telemetry.TelemetryMessage;
import com.nodemetry.backend.telemetry.TelemetryService;
import org.springframework.stereotype.Service;

@Service
public class MqttMessageHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TelemetryService telemetryService;
    private final NodeService nodeService;

    public MqttMessageHandler(TelemetryService telemetryService, NodeService nodeService) {
        this.telemetryService = telemetryService;
        this.nodeService = nodeService;
    }

    public void handleTelemetry(String topic, String payload) {
        try {
            TelemetryMessage message = objectMapper.readValue(payload, TelemetryMessage.class);

            System.out.println("=== Telemetry Received ===");
            System.out.println("Topic: " + topic);
            System.out.println("Node ID: " + message.nodeId());
            System.out.println("Message ID: " + message.messageId());

            telemetryService.processTelemetry(message);

            System.out.println("==========================");

        } catch (Exception e) {
            System.err.println("Failed to process telemetry message");
            System.err.println("Topic: " + topic);
            System.err.println("Payload: " + payload);
            System.err.println("Error: " + e.getMessage());
        }
    }

    public void handleStatus(String topic, String payload) {
        try {
            String[] parts = topic.split("/");
            if (parts.length < 3) {
                System.err.println("Malformed status topic: " + topic);
                return;
            }

            String nodeId = parts[1];
            boolean updated = nodeService.processStatusUpdate(nodeId, "online");

            if (updated) System.out.println("Status updated for node: " + nodeId);

        } catch (Exception e) {
            System.err.println("Failed to process status message on topic: " + topic);
            System.err.println("Error: " + e.getMessage());
        }
    }
}