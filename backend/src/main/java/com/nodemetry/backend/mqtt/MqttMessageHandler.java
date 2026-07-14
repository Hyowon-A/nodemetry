package com.nodemetry.backend.mqtt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nodemetry.backend.node.NodeService;
import com.nodemetry.backend.telemetry.TelemetryBatchIngestService;
import com.nodemetry.backend.telemetry.TelemetryMessage;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class MqttMessageHandler {

    private record StatusMessage(String status) {}

    // Ignore unknown/legacy JSON keys so producers sending extra fields (or fields since
    // removed from the schema) are still ingested instead of dropped on a parse failure.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final TelemetryBatchIngestService batchIngestService;
    private final NodeService nodeService;

    public MqttMessageHandler(
            TelemetryBatchIngestService batchIngestService,
            NodeService nodeService
    ) {
        this.batchIngestService = batchIngestService;
        this.nodeService = nodeService;
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

        if (!batchIngestService.enqueue(message)) {
            System.err.println("Telemetry ingest queue full; dropped message");
            System.err.println("Topic: " + topic);
            System.err.println("Payload: " + payload);
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
