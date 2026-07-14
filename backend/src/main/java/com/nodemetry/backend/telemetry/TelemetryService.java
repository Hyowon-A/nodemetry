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

    // Dedup is handled by the unique constraint on SensorReading.messageId: because
    // the id is IDENTITY-generated, save() flushes the INSERT immediately, so a
    // redelivered (QoS 1) messageId throws DataIntegrityViolationException here and
    // the caller records it as a duplicate. That avoids an existsBy SELECT on the
    // hot path per message.
    @Transactional
    public void processTelemetry(TelemetryMessage message) {
        validate(message);

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
                message.runId(),
                message.temperatureRaw(),
                message.temperatureFiltered(),
                message.humidityRaw(),
                message.humidityFiltered(),
                message.battery(),
                message.light(),
                message.rssi(),
                message.firmwareVersion()
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
    }

    private void validate(TelemetryMessage message) {
        if (message.messageId() == null || message.messageId().isBlank()) {
            throw new IllegalArgumentException("messageId is required");
        }

        if (message.nodeId() == null || message.nodeId().isBlank()) {
            throw new IllegalArgumentException("nodeId is required");
        }

        if (message.runId() == null || message.runId().isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }

        if (message.firmwareVersion() == null || message.firmwareVersion().isBlank()) {
            throw new IllegalArgumentException("firmwareVersion is required");
        }
    }
}
