package com.nodemetry.backend.run;

import com.nodemetry.backend.node.NodeService;
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

    private volatile String currentRunId;

    // Per-run counters kept in memory so the single MQTT ingest thread can record
    // a save/dupe with a cheap LongAdder increment instead of a DB round-trip per
    // message. A scheduled task mirrors the running totals into the TestRun row.
    private final Map<String, RunCounters> counters = new ConcurrentHashMap<>();

    private static final class RunCounters {
        final LongAdder saved = new LongAdder();
        final LongAdder dupes = new LongAdder();
        volatile boolean ended;
        long lastFlushedSaved = -1;
        long lastFlushedDupes = -1;
    }

    public RunRegistry(TestRunRepository repository, NodeService nodeService) {
        this.repository = repository;
        this.nodeService = nodeService;
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
            // Stamp the freshest in-memory counts onto the row we're saving, then
            // freeze the run counters. Late readings may still be stored, but they
            // should not move the run-history totals after the run has ended.
            RunCounters c = counters.get(runId);
            if (c != null) {
                c.ended = true;
                run.setTotalSaved(c.saved.sum());
                run.setDuplicatesSkipped(c.dupes.sum());
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
        if (c != null && !c.ended && count > 0) {
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
        if (c != null && !c.ended && count > 0) {
            c.dupes.add(count);
        }
    }

    // Mirror the in-memory counters into the DB roughly once a second so the
    // per-run saved/dupe totals the UI reads stay fresh without a write per
    // message. Only writes active runs whose totals moved; evicts ended runs once
    // their final values have been stamped by endRun.
    @Scheduled(fixedDelay = 1000)
    public void flushCounters() {
        Iterator<Map.Entry<String, RunCounters>> it = counters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RunCounters> entry = it.next();
            String runId = entry.getKey();
            RunCounters c = entry.getValue();
            if (c.ended) {
                it.remove();
                continue;
            }

            long saved = c.saved.sum();
            long dupes = c.dupes.sum();

            boolean changed = saved != c.lastFlushedSaved || dupes != c.lastFlushedDupes;
            if (changed) {
                repository.updateCounters(runId, saved, dupes);
                c.lastFlushedSaved = saved;
                c.lastFlushedDupes = dupes;
            }
        }
    }

    public String getCurrentRunId() { return currentRunId; }
}
