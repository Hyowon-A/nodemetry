package com.nodemetry.backend.run;

import com.nodemetry.backend.node.NodeService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RunRegistry {

    private final TestRunRepository repository;
    private final NodeService nodeService;

    private volatile String currentRunId;
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong saved    = new AtomicLong();
    private final AtomicLong dupes    = new AtomicLong();

    public RunRegistry(TestRunRepository repository, NodeService nodeService) {
        this.repository = repository;
        this.nodeService = nodeService;
    }

    public synchronized TestRun startRun(StartRunRequest req) {
        nodeService.markAllKnownNodesOffline();

        received.set(0);
        saved.set(0);
        dupes.set(0);

        TestRun run = new TestRun();
        run.setRunId(req.runId());
        run.setLabel(req.label());
        run.setStartedAt(Instant.now());
        run.setQos(req.qos());
        run.setNodeCount(req.nodeCount());
        run.setIntervalSec(req.intervalSec());
        run.setDuplicateRate(req.duplicateRate());
        TestRun savedRun = repository.save(run);
        currentRunId = req.runId();
        return savedRun;
    }

    public synchronized TestRun endRun(String runId) {
        return repository.findByRunId(runId).map(run -> {
            run.setEndedAt(Instant.now());
            if (runId.equals(currentRunId)) {
                run.setTotalReceived(received.get());
                run.setTotalSaved(saved.get());
                run.setDuplicatesSkipped(dupes.get());
                currentRunId = null;
            }
            return repository.save(run);
        }).orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    }

    public void recordReceived() {
        recordReceived(currentRunId);
    }

    public void recordReceived(String runId) {
        if (runId != null && repository.incrementTotalReceived(runId) > 0) {
            if (runId.equals(currentRunId)) {
                received.incrementAndGet();
            }
        }
    }

    public void recordSaved() {
        recordSaved(currentRunId);
    }

    public void recordSaved(String runId) {
        if (runId != null && repository.incrementTotalSaved(runId) > 0) {
            if (runId.equals(currentRunId)) {
                saved.incrementAndGet();
            }
        }
    }

    public void recordDupe() {
        recordDupe(currentRunId);
    }

    public void recordDupe(String runId) {
        if (runId != null && repository.incrementDuplicatesSkipped(runId) > 0) {
            if (runId.equals(currentRunId)) {
                dupes.incrementAndGet();
            }
        }
    }

    public String getCurrentRunId() { return currentRunId; }
}
