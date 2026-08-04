package com.nodemetry.backend.telemetry;

import com.nodemetry.backend.run.PhysicalNodeRunRegistry;
import com.nodemetry.backend.run.PhysicalNodeRunRegistry.NodeRunKey;
import com.nodemetry.backend.run.RunRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.LongAdder;

@Service
public class TelemetryBatchIngestService {

    private final BlockingQueue<TelemetryMessage> queue;
    private final int batchSize;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final RunRegistry runRegistry;
    private final PhysicalNodeRunRegistry physicalNodeRunRegistry;
    private final LongAdder dropped = new LongAdder();
    private final LongAdder invalid = new LongAdder();

    public TelemetryBatchIngestService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            SimpMessagingTemplate messagingTemplate,
            RunRegistry runRegistry,
            PhysicalNodeRunRegistry physicalNodeRunRegistry,
            @Value("${telemetry.ingest.queue-capacity:50000}") int queueCapacity,
            @Value("${telemetry.ingest.batch-size:500}") int batchSize
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.messagingTemplate = messagingTemplate;
        this.runRegistry = runRegistry;
        this.physicalNodeRunRegistry = physicalNodeRunRegistry;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.batchSize = Math.max(1, batchSize);
    }

    public boolean enqueue(TelemetryMessage message) {
        boolean accepted = queue.offer(message);
        if (!accepted) {
            dropped.increment();
        }
        return accepted;
    }

    public long droppedCount() {
        return dropped.sum();
    }

    public long invalidCount() {
        return invalid.sum();
    }

    public int queuedCount() {
        return queue.size();
    }

    @Scheduled(fixedDelayString = "${telemetry.ingest.flush-interval-ms:100}")
    public void drainScheduled() {
        drainOnce();
    }

    int drainOnce() {
        List<TelemetryMessage> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        long startNanos = System.nanoTime();
        BatchResult result = ingest(batch);
        double perMessageMs = (System.nanoTime() - startNanos) / 1_000_000.0 / batch.size();
        publish(result.insertedReadings());
        recordRunCounters(result, perMessageMs);
        return batch.size();
    }

    @PreDestroy
    void drainRemaining() {
        while (drainOnce() > 0) {
            // Keep draining until shutdown catches up with the queue.
        }
    }

    BatchResult ingest(List<TelemetryMessage> rawBatch) {
        Map<String, QueuedReading> uniqueByMessageId = new LinkedHashMap<>();
        Map<String, Long> dupesByRunId = new LinkedHashMap<>();
        Map<NodeRunKey, Long> dupesByRunNode = new LinkedHashMap<>();

        for (TelemetryMessage message : rawBatch) {
            if (isValid(message)) {
                Instant now = Instant.now();
                QueuedReading reading = new QueuedReading(message, now, now);
                if (uniqueByMessageId.putIfAbsent(message.messageId(), reading) != null) {
                    dupesByRunId.merge(message.runId(), 1L, Long::sum);
                    dupesByRunNode.merge(new NodeRunKey(message.runId(), message.nodeId()), 1L, Long::sum);
                }
            } else {
                invalid.increment();
            }
        }

        List<QueuedReading> readings = new ArrayList<>(uniqueByMessageId.values());
        if (readings.isEmpty()) {
            return new BatchResult(List.of(), Map.of(), dupesByRunId, Map.of(), dupesByRunNode);
        }

        return transactionTemplate.execute(status -> {
            List<QueuedReading> newReadings = excludeExistingReadings(readings, dupesByRunId, dupesByRunNode);
            if (newReadings.isEmpty()) {
                return new BatchResult(List.of(), Map.of(), dupesByRunId, Map.of(), dupesByRunNode);
            }

            upsertNodes(newReadings);
            insertReadings(newReadings);
            return summarizeInserted(newReadings, dupesByRunId, dupesByRunNode);
        });
    }

    private void upsertNodes(List<QueuedReading> readings) {
        Map<String, QueuedReading> latestByNode = new LinkedHashMap<>();
        for (QueuedReading reading : readings) {
            latestByNode.put(reading.message().nodeId(), reading);
        }

        List<QueuedReading> latest = List.copyOf(latestByNode.values());

        jdbcTemplate.batchUpdate("""
                update nodes
                set status = 'online',
                    battery = ?,
                    rssi = ?,
                    firmware_version = ?,
                    last_seen_at = ?
                where node_id = ?
                """, latest, latest.size(), (ps, reading) -> {
            TelemetryMessage message = reading.message();
            ps.setObject(1, message.battery());
            ps.setObject(2, message.rssi());
            ps.setString(3, message.firmwareVersion());
            ps.setTimestamp(4, Timestamp.from(reading.receivedAt()));
            ps.setString(5, message.nodeId());
        });

        jdbcTemplate.batchUpdate("""
                insert into nodes (node_id, status, battery, rssi, firmware_version, last_seen_at, created_at)
                select ?, 'online', ?, ?, ?, ?, ?
                where not exists (select 1 from nodes where node_id = ?)
                """, latest, latest.size(), (ps, reading) -> {
            TelemetryMessage message = reading.message();
            Timestamp receivedAt = Timestamp.from(reading.receivedAt());
            ps.setString(1, message.nodeId());
            ps.setObject(2, message.battery());
            ps.setObject(3, message.rssi());
            ps.setString(4, message.firmwareVersion());
            ps.setTimestamp(5, receivedAt);
            ps.setTimestamp(6, receivedAt);
            ps.setString(7, message.nodeId());
        });
    }

    private void insertReadings(List<QueuedReading> readings) {
        jdbcTemplate.batchUpdate("""
                insert into sensor_readings (
                    message_id, node_id, run_id, temperature_raw, temperature_filtered,
                    humidity_raw, humidity_filtered, battery, light, rssi, firmware_version,
                    measured_at, received_at
                )
                select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                where not exists (select 1 from sensor_readings where message_id = ?)
                """, readings, readings.size(), (ps, reading) -> {
            TelemetryMessage message = reading.message();
            ps.setString(1, message.messageId());
            ps.setString(2, message.nodeId());
            ps.setString(3, message.runId());
            ps.setObject(4, message.temperatureRaw());
            ps.setObject(5, message.temperatureFiltered());
            ps.setObject(6, message.humidityRaw());
            ps.setObject(7, message.humidityFiltered());
            ps.setObject(8, message.battery());
            ps.setObject(9, message.light());
            ps.setObject(10, message.rssi());
            ps.setString(11, message.firmwareVersion());
            ps.setTimestamp(12, Timestamp.from(reading.measuredAt()));
            ps.setTimestamp(13, Timestamp.from(reading.receivedAt()));
            ps.setString(14, message.messageId());
        });
    }

    private List<QueuedReading> excludeExistingReadings(
            List<QueuedReading> readings,
            Map<String, Long> dupesByRunId,
            Map<NodeRunKey, Long> dupesByRunNode
    ) {
        Set<String> existingMessageIds = existingMessageIds(readings);
        if (existingMessageIds.isEmpty()) {
            return readings;
        }

        List<QueuedReading> newReadings = new ArrayList<>();
        for (QueuedReading reading : readings) {
            TelemetryMessage message = reading.message();
            if (existingMessageIds.contains(message.messageId())) {
                dupesByRunId.merge(message.runId(), 1L, Long::sum);
                dupesByRunNode.merge(new NodeRunKey(message.runId(), message.nodeId()), 1L, Long::sum);
            } else {
                newReadings.add(reading);
            }
        }
        return newReadings;
    }

    private Set<String> existingMessageIds(List<QueuedReading> readings) {
        if (readings.isEmpty()) {
            return Set.of();
        }

        StringBuilder sql = new StringBuilder("select message_id from sensor_readings where message_id in (");
        Object[] params = new Object[readings.size()];
        for (int i = 0; i < readings.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
            params[i] = readings.get(i).message().messageId();
        }
        sql.append(")");

        return new LinkedHashSet<>(jdbcTemplate.queryForList(sql.toString(), String.class, params));
    }

    private BatchResult summarizeInserted(
            List<QueuedReading> readings,
            Map<String, Long> dupesByRunId,
            Map<NodeRunKey, Long> dupesByRunNode
    ) {
        List<SensorReadingResponse> insertedReadings = new ArrayList<>();
        Map<String, Long> savedByRunId = new LinkedHashMap<>();
        Map<NodeRunKey, Long> savedByRunNode = new LinkedHashMap<>();

        for (QueuedReading reading : readings) {
            TelemetryMessage message = reading.message();
            insertedReadings.add(reading.toResponse());
            savedByRunId.merge(message.runId(), 1L, Long::sum);
            savedByRunNode.merge(new NodeRunKey(message.runId(), message.nodeId()), 1L, Long::sum);
        }

        return new BatchResult(insertedReadings, savedByRunId, dupesByRunId, savedByRunNode, dupesByRunNode);
    }

    private void publish(List<SensorReadingResponse> insertedReadings) {
        for (SensorReadingResponse response : insertedReadings) {
            messagingTemplate.convertAndSend(
                    "/topic/nodes/" + response.nodeId() + "/latest",
                    response
            );

            messagingTemplate.convertAndSend(
                    "/topic/readings",
                    response
            );
        }
    }

    private void recordRunCounters(BatchResult result, double perMessageMs) {
        result.savedByRunId().forEach(runRegistry::recordSaved);
        result.dupesByRunId().forEach(runRegistry::recordDupe);

        Set<NodeRunKey> keys = new LinkedHashSet<>(result.savedByRunNode().keySet());
        keys.addAll(result.dupesByRunNode().keySet());
        for (NodeRunKey key : keys) {
            physicalNodeRunRegistry.recordBatch(
                    key.runId(),
                    key.nodeId(),
                    result.savedByRunNode().getOrDefault(key, 0L),
                    result.dupesByRunNode().getOrDefault(key, 0L),
                    perMessageMs
            );
        }
    }

    private boolean isValid(TelemetryMessage message) {
        return TelemetryMessageValidator.isValid(message);
    }

    private record QueuedReading(
            TelemetryMessage message,
            Instant measuredAt,
            Instant receivedAt
    ) {
        SensorReadingResponse toResponse() {
            return new SensorReadingResponse(
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
                    message.firmwareVersion(),
                    measuredAt,
                    receivedAt
            );
        }
    }

    record BatchResult(
            List<SensorReadingResponse> insertedReadings,
            Map<String, Long> savedByRunId,
            Map<String, Long> dupesByRunId,
            Map<NodeRunKey, Long> savedByRunNode,
            Map<NodeRunKey, Long> dupesByRunNode
    ) {}
}
