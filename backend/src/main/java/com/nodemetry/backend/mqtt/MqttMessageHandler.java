package com.nodemetry.backend.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nodemetry.backend.node.NodeService;
import com.nodemetry.backend.run.RunRegistry;
import com.nodemetry.backend.telemetry.TelemetryMessage;
import com.nodemetry.backend.telemetry.TelemetryService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class MqttMessageHandler {

    private record StatusMessage(String status) {}

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TelemetryService telemetryService;
    private final NodeService nodeService;
    private final RunRegistry runRegistry;

    public MqttMessageHandler(
            TelemetryService telemetryService,
            NodeService nodeService,
            RunRegistry runRegistry
    ) {
        this.telemetryService = telemetryService;
        this.nodeService = nodeService;
        this.runRegistry = runRegistry;
    }

    public void handleTelemetry(String topic, String payload) {
        handleTelemetry(topic, payload, false);
    }

    public void handleTelemetry(String topic, String payload, boolean retained) {
        if (retained) {
            System.out.println("Ignored retained telemetry message on topic: " + topic);
            return;
        }

        TelemetryMessage message;
        try {
            message = objectMapper.readValue(payload, TelemetryMessage.class);
        } catch (Exception e) {
            System.err.println("Failed to parse telemetry message");
            System.err.println("Topic: " + topic);
            System.err.println("Payload: " + payload);
            System.err.println("Error: " + e.getMessage());
            return;
        }

        try {
            telemetryService.processTelemetry(message);
            runRegistry.recordSaved(message.runId());
        } catch (DataIntegrityViolationException dup) {
            // Redelivered messageId hit the unique constraint — an expected QoS 1
            // duplicate, not an error. Keep it off the error/console path.
            runRegistry.recordDupe(message.runId());
        } catch (Exception e) {
            System.err.println("Failed to process telemetry message");
            System.err.println("Topic: " + topic);
            System.err.println("Payload: " + payload);
            System.err.println("Error: " + e.getMessage());
        }
    }

    public void handleStatus(String topic, String payload) {
        handleStatus(topic, payload, false);
    }

    public void handleStatus(String topic, String payload, boolean retained) {
        if (retained) {
            System.out.println("Ignored retained status message on topic: " + topic);
            return;
        }

        try {
            String[] parts = topic.split("/");
            if (parts.length < 3) {
                System.err.println("Malformed status topic: " + topic);
                return;
            }

            String nodeId = parts[1];
            StatusMessage statusMessage = objectMapper.readValue(payload, StatusMessage.class);
            String status = normalizeStatus(statusMessage.status());
            if (status == null) {
                System.err.println("Missing or invalid status payload on topic: " + topic);
                return;
            }

            boolean updated = nodeService.processStatusUpdate(nodeId, status);

            if (updated) System.out.println("Status updated for node: " + nodeId);

        } catch (Exception e) {
            System.err.println("Failed to process status message on topic: " + topic);
            System.err.println("Error: " + e.getMessage());
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }

        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return ("online".equals(normalized) || "offline".equals(normalized)) ? normalized : null;
    }
}
