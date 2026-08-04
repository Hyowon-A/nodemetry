package com.nodemetry.backend.run;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "physical_node_runs",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_physical_node_runs_run_node",
                columnNames = {"run_id", "node_id"}
        ),
        indexes = @Index(name = "idx_physical_node_runs_last_msg", columnList = "last_message_at")
)
public class PhysicalNodeRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    // messagesSaved mirrors what sensor_readings actually holds for the
    // (run, node) pair; duplicatesSkipped is the reconciled local dupe events;
    // messagesReceived is always their sum.
    @Column(name = "messages_received")
    private long messagesReceived;

    @Column(name = "messages_saved")
    private long messagesSaved;

    @Column(name = "duplicates_skipped")
    private long duplicatesSkipped;

    @Column(name = "avg_processing_ms")
    private Double avgProcessingMs;

    @Column(name = "first_message_at")
    private Instant firstMessageAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    public PhysicalNodeRun() {}

    public Long getId() { return id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public long getMessagesReceived() { return messagesReceived; }
    public void setMessagesReceived(long messagesReceived) { this.messagesReceived = messagesReceived; }

    public long getMessagesSaved() { return messagesSaved; }
    public void setMessagesSaved(long messagesSaved) { this.messagesSaved = messagesSaved; }

    public long getDuplicatesSkipped() { return duplicatesSkipped; }
    public void setDuplicatesSkipped(long duplicatesSkipped) { this.duplicatesSkipped = duplicatesSkipped; }

    public Double getAvgProcessingMs() { return avgProcessingMs; }
    public void setAvgProcessingMs(Double avgProcessingMs) { this.avgProcessingMs = avgProcessingMs; }

    public Instant getFirstMessageAt() { return firstMessageAt; }
    public void setFirstMessageAt(Instant firstMessageAt) { this.firstMessageAt = firstMessageAt; }

    public Instant getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(Instant lastMessageAt) { this.lastMessageAt = lastMessageAt; }
}
