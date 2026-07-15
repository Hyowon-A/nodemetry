package com.nodemetry.backend.run;

import com.nodemetry.backend.node.NodeService;
import com.nodemetry.backend.telemetry.SensorReadingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

@Component
public class RunRegistry {

    private final TestRunRepository repository;
    private final NodeService nodeService;
    private final SensorReadingRepository readingRepository;
    private final long endGraceMs;

    private volatile String currentRunId;

    // Per-run event counters kept in memory so the single MQTT ingest thread can
    // record a save/dupe with a cheap LongAdder increment instead of a DB
    // round-trip per message. The events are not the source of truth for the run
    // totals: another ingest instance sharing the broker and database (e.g. the
    // production backend during local dev) may win the insert race for a message
    // and leave this instance counting a "duplicate" for a reading that was in
    // fact stored. A scheduled task therefore reconciles the events against
    // sensor_readings before mirroring totals into the TestRun row.
    private final Map<String, RunCounters> counters = new ConcurrentHashMap<>();

    private static final class RunCounters {
        final LongAdder saved = new LongAdder();
        final LongAdder dupes = new LongAdder();
        volatile long endedAtMillis; // 0 while the run is active
        long lastFlushedSaved = -1;
        long lastFlushedDupes = -1;
    }

    public RunRegistry(
            TestRunRepository repository,
            NodeService nodeService,
            SensorReadingRepository readingRepository,
            @Value("${run.metrics.end-grace-ms:15000}") long endGraceMs
    ) {
        this.repository = repository;
        this.nodeService = nodeService;
        this.readingRepository = readingRepository;
        this.endGraceMs = endGraceMs;
    }

    public synchronized TestRun startRun(StartRunRequest req) {
        nodeService.markAllKnownNodesOffline();

        TestRun run = new TestRun();
        run.setRunId(req.runId());
        run.setLabel(req.label());
        run.setStartedAt(Instant.now());
        run.setQos(req.qos());
        run.setNodeCount(req.nodeCount());
        run.setIntervalSec(req.intervalSec());
        run.setDuplicateRate(req.duplicateRate());
        TestRun savedRun = repository.save(run);
        counters.put(req.runId(), new RunCounters());
        currentRunId = req.runId();
        return savedRun;
    }

    public synchronized TestRun endRun(String runId) {
        return endRun(runId, null);
    }

    public synchronized TestRun endRun(String runId, EndRunRequest req) {
        return repository.findByRunId(runId).map(run -> {
            Instant endedAt = req != null && req.endedAtEpochMs() != null
                    ? Instant.ofEpochMilli(req.endedAtEpochMs())
                    : Instant.now();
            run.setEndedAt(endedAt);
            if (req != null && req.totalReceived() != null) {
                run.setTotalReceived(Math.max(0, req.totalReceived()));
            }
            // Stamp DB-reconciled totals onto the row we're saving. The counters
            // stay registered for a grace window so flushCounters can settle late
            // batches (this instance's queue drain, other instances' in-flight
            // inserts) into the final totals before evicting the run.
            RunCounters c = counters.get(runId);
            run.setTotalSaved(readingRepository.countByRunId(runId));
            if (c != null) {
                c.endedAtMillis = System.currentTimeMillis();
                long processed = c.saved.sum() + c.dupes.sum();
                run.setDuplicatesSkipped(Math.max(0, processed - run.getTotalSaved()));
                c.lastFlushedSaved = run.getTotalSaved();
                c.lastFlushedDupes = run.getDuplicatesSkipped();
            }
            if (runId.equals(currentRunId)) {
                currentRunId = null;
            }
            return repository.save(run);
        }).orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    }

    public synchronized void recordSaved() {
        recordSaved(currentRunId);
    }

    public synchronized void recordSaved(String runId) {
        recordSaved(runId, 1);
    }

    public synchronized void recordSaved(String runId, long count) {
        RunCounters c = runId == null ? null : counters.get(runId);
        if (c != null && count > 0) {
            c.saved.add(count);
        }
    }

    public synchronized void recordDupe() {
        recordDupe(currentRunId);
    }

    public synchronized void recordDupe(String runId) {
        recordDupe(runId, 1);
    }

    public synchronized void recordDupe(String runId, long count) {
        RunCounters c = runId == null ? null : counters.get(runId);
        if (c != null && count > 0) {
            c.dupes.add(count);
        }
    }

    // Mirror DB-reconciled totals into the TestRun row roughly once a second so
    // the per-run saved/dupe totals the UI reads stay fresh without a write per
    // message. saved is what sensor_readings actually holds for the run; dupes is
    // the local events that did not add a row, so an insert won by another
    // instance cancels out instead of surfacing as a phantom duplicate. Ended
    // runs keep reconciling for a grace window (late queue drains, other
    // instances' in-flight batches), then get evicted.
    @Scheduled(fixedDelay = 1000)
    public void flushCounters() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, RunCounters>> it = counters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RunCounters> entry = it.next();
            String runId = entry.getKey();
            RunCounters c = entry.getValue();

            long saved = readingRepository.countByRunId(runId);
            long processed = c.saved.sum() + c.dupes.sum();
            long dupes = Math.max(0, processed - saved);

            boolean changed = saved != c.lastFlushedSaved || dupes != c.lastFlushedDupes;
            if (changed) {
                repository.updateCounters(runId, saved, dupes);
                c.lastFlushedSaved = saved;
                c.lastFlushedDupes = dupes;
            }

            if (c.endedAtMillis != 0 && now - c.endedAtMillis >= endGraceMs) {
                it.remove();
            }
        }
    }

    public String getCurrentRunId() { return currentRunId; }
}
