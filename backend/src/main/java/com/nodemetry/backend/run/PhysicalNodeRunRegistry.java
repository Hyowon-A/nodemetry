package com.nodemetry.backend.run;

import com.nodemetry.backend.telemetry.SensorReadingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

@Component
public class PhysicalNodeRunRegistry {

    private final PhysicalNodeRunRepository repository;
    private final SensorReadingRepository readingRepository;
    private final List<String> virtualNodePrefixes;
    private final long idleEvictMs;

    // Per-(run, node) event counters kept in memory so the ingest thread can
    // record a batch with cheap adder increments instead of a DB round-trip per
    // message. Like RunRegistry, the events are not the source of truth: a
    // scheduled task reconciles them against sensor_readings before mirroring
    // totals into the physical_node_runs row. Unlike load-test runs, physical
    // runIds arrive unannounced in the telemetry itself and have no end event,
    // so rows are created lazily on first sight and counters are evicted after
    // an idle window instead of a post-end grace window.
    private final Map<NodeRunKey, NodeCounters> counters = new ConcurrentHashMap<>();

    public record NodeRunKey(String runId, String nodeId) {}

    private static final class NodeCounters {
        final LongAdder savedEvents = new LongAdder();
        final LongAdder dupeEvents = new LongAdder();
        final LongAdder processedEvents = new LongAdder();
        final DoubleAdder processingMsTotal = new DoubleAdder();
        volatile long firstSeenMillis;
        volatile long lastSeenMillis;
        long lastFlushedSaved = -1;
        long lastFlushedDupes = -1;
    }

    public PhysicalNodeRunRegistry(
            PhysicalNodeRunRepository repository,
            SensorReadingRepository readingRepository,
            @Value("${run.metrics.virtual-node-prefixes:vnode-}") List<String> virtualNodePrefixes,
            @Value("${run.metrics.physical-idle-evict-ms:60000}") long idleEvictMs
    ) {
        this.repository = repository;
        this.readingRepository = readingRepository;
        this.virtualNodePrefixes = List.copyOf(virtualNodePrefixes);
        this.idleEvictMs = idleEvictMs;
    }

    public boolean isPhysicalNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return false;
        }
        for (String prefix : virtualNodePrefixes) {
            if (nodeId.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    public void recordBatch(String runId, String nodeId, long saved, long dupes, double perMessageMs) {
        if (runId == null || runId.isBlank() || !isPhysicalNode(nodeId)) {
            return;
        }
        long events = saved + dupes;
        if (events <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        NodeCounters c = counters.computeIfAbsent(new NodeRunKey(runId, nodeId), key -> {
            NodeCounters fresh = new NodeCounters();
            fresh.firstSeenMillis = now;
            return fresh;
        });
        if (saved > 0) {
            c.savedEvents.add(saved);
        }
        if (dupes > 0) {
            c.dupeEvents.add(dupes);
        }
        if (perMessageMs >= 0) {
            c.processedEvents.add(events);
            c.processingMsTotal.add(perMessageMs * events);
        }
        c.lastSeenMillis = now;
    }

    // Mirror DB-reconciled totals into physical_node_runs roughly once a second.
    // saved is what sensor_readings actually holds for the (run, node) pair;
    // dupes is the local events that did not add a row, so an insert won by
    // another instance cancels out instead of surfacing as a phantom duplicate.
    // Entries idle past the evict window are dropped once flushed clean.
    @Scheduled(fixedDelay = 1000)
    public void flushCounters() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<NodeRunKey, NodeCounters>> it = counters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<NodeRunKey, NodeCounters> entry = it.next();
            NodeRunKey key = entry.getKey();
            NodeCounters c = entry.getValue();

            long saved = readingRepository.countByNodeIdAndRunId(key.nodeId(), key.runId());
            long events = c.savedEvents.sum() + c.dupeEvents.sum();
            long dupes = Math.max(0, events - saved);

            boolean changed = saved != c.lastFlushedSaved || dupes != c.lastFlushedDupes;
            if (changed) {
                long processed = c.processedEvents.sum();
                Double avgMs = processed == 0 ? null : c.processingMsTotal.sum() / processed;
                upsert(key, saved + dupes, saved, dupes, avgMs,
                        Instant.ofEpochMilli(c.firstSeenMillis),
                        Instant.ofEpochMilli(c.lastSeenMillis));
                c.lastFlushedSaved = saved;
                c.lastFlushedDupes = dupes;
            }

            if (!changed && now - c.lastSeenMillis >= idleEvictMs) {
                it.remove();
            }
        }
    }

    private void upsert(NodeRunKey key, long received, long saved, long dupes,
                        Double avgMs, Instant firstMessageAt, Instant lastMessageAt) {
        int updated = repository.updateCounters(
                key.runId(), key.nodeId(), received, saved, dupes, avgMs, lastMessageAt);
        if (updated > 0) {
            return;
        }
        PhysicalNodeRun row = new PhysicalNodeRun();
        row.setRunId(key.runId());
        row.setNodeId(key.nodeId());
        row.setMessagesReceived(received);
        row.setMessagesSaved(saved);
        row.setDuplicatesSkipped(dupes);
        row.setAvgProcessingMs(avgMs);
        row.setFirstMessageAt(firstMessageAt);
        row.setLastMessageAt(lastMessageAt);
        try {
            repository.save(row);
        } catch (DataIntegrityViolationException raced) {
            // Another instance sharing the broker and database inserted the row
            // between our update and save; fall back to the idempotent write.
            repository.updateCounters(
                    key.runId(), key.nodeId(), received, saved, dupes, avgMs, lastMessageAt);
        }
    }
}
