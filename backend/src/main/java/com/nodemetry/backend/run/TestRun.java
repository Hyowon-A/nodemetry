package com.nodemetry.backend.run;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "test_runs")
public class TestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", unique = true, nullable = false)
    private String runId;

    private String label;
    private Instant startedAt;
    private Instant endedAt;
    private int qos;

    @Column(name = "node_count")
    private int nodeCount;

    @Column(name = "interval_sec")
    private double intervalSec;

    @Column(name = "duplicate_rate")
    private double duplicateRate;

    @Column(name = "total_received")
    private long totalReceived;

    @Column(name = "total_saved")
    private long totalSaved;

    @Column(name = "duplicates_skipped")
    private long duplicatesSkipped;

    public TestRun() {}

    public Long getId() { return id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }

    public int getQos() { return qos; }
    public void setQos(int qos) { this.qos = qos; }

    public int getNodeCount() { return nodeCount; }
    public void setNodeCount(int nodeCount) { this.nodeCount = nodeCount; }

    public double getIntervalSec() { return intervalSec; }
    public void setIntervalSec(double intervalSec) { this.intervalSec = intervalSec; }

    public double getDuplicateRate() { return duplicateRate; }
    public void setDuplicateRate(double duplicateRate) { this.duplicateRate = duplicateRate; }

    public long getTotalReceived() { return totalReceived; }
    public void setTotalReceived(long totalReceived) { this.totalReceived = totalReceived; }

    public long getTotalSaved() { return totalSaved; }
    public void setTotalSaved(long totalSaved) { this.totalSaved = totalSaved; }

    public long getDuplicatesSkipped() { return duplicatesSkipped; }
    public void setDuplicatesSkipped(long duplicatesSkipped) { this.duplicatesSkipped = duplicatesSkipped; }
}
