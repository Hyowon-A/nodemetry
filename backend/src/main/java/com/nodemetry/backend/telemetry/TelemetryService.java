package com.nodemetry.backend.telemetry;

import com.nodemetry.backend.node.SensorNode;
import com.nodemetry.backend.node.SensorNodeRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelemetryService {

    private final SensorReadingRepository readingRepository;
    private final SensorNodeRepository nodeRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public TelemetryService(
            SensorReadingRepository readingRepository,
            SensorNodeRepository nodeRepository,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.readingRepository = readingRepository;
        this.nodeRepository = nodeRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public void processTelemetry(TelemetryMessage message) {
        validate(message);

        // MQTT QoS 1 may redeliver messages, so skip readings already stored
        if (readingRepository.existsByMessageId(message.messageId())) {
            System.out.println("Duplicate message skipped: " + message.messageId());
            return;
        }

        // Reuse the existing node, or create it on first telemetry
        SensorNode node = nodeRepository
                .findByNodeId(message.nodeId())
                .orElseGet(() -> new SensorNode(message.nodeId()));

        node.updateHealth(
                message.battery(),
                message.rssi(),
                message.firmwareVersion()
        );

        nodeRepository.save(node);

        SensorReading reading = new SensorReading(
                message.messageId(),
                message.nodeId(),
                message.temperature(),
                message.humidity(),
                message.co2(),
                message.battery(),
                message.rssi(),
                message.firmwareVersion(),
                message.light()
        );

        readingRepository.save(reading);

        SensorReadingResponse response = SensorReadingResponse.from(reading);

        messagingTemplate.convertAndSend(
                "/topic/nodes/" + message.nodeId() + "/latest",
                response
        );

        messagingTemplate.convertAndSend(
                "/topic/readings",
                response
        );

        System.out.println("Saved telemetry: " + message.messageId());
    }

    private void validate(TelemetryMessage message) {
        if (message.messageId() == null || message.messageId().isBlank()) {
            throw new IllegalArgumentException("messageId is required");
        }

        if (message.nodeId() == null || message.nodeId().isBlank()) {
            throw new IllegalArgumentException("nodeId is required");
        }

        if (message.firmwareVersion() == null || message.firmwareVersion().isBlank()) {
            throw new IllegalArgumentException("firmwareVersion is required");
        }
    }
}