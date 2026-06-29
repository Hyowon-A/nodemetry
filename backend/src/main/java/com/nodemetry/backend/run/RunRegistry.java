package com.nodemetry.backend.run;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RunRegistry {

    private final TestRunRepository repository;

    private volatile String currentRunId;
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong saved    = new AtomicLong();
    private final AtomicLong dupes    = new AtomicLong();

    public RunRegistry(TestRunRepository repository) {
        this.repository = repository;
    }

    public synchronized TestRun startRun(StartRunRequest req) {
        currentRunId = req.runId();
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
        return repository.save(run);
    }

    public synchronized TestRun endRun(String runId) {
        return repository.findByRunId(runId).map(run -> {
            run.setEndedAt(Instant.now());
            run.setTotalReceived(received.get());
            run.setTotalSaved(saved.get());
            run.setDuplicatesSkipped(dupes.get());
            if (runId.equals(currentRunId)) currentRunId = null;
            return repository.save(run);
        }).orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    }

    public void recordReceived() { if (currentRunId != null) received.incrementAndGet(); }
    public void recordSaved()    { if (currentRunId != null) saved.incrementAndGet(); }
    public void recordDupe()     { if (currentRunId != null) dupes.incrementAndGet(); }

    public String getCurrentRunId() { return currentRunId; }
}
