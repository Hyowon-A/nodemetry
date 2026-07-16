package com.nodemetry.backend.run;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "PhysicalNodeRun", description = "Per-node persisted ingestion metrics for a physical node within a run.")
public record PhysicalNodeRunResponse(
        @Schema(description = "Run identifier.", example = "20260716T120000Z")
        String runId,

        @Schema(description = "Physical sensor node identifier.", example = "node-001")
        String nodeId,

        @Schema(description = "Messages accepted by the backend for this node/run.", example = "1200")
        long messagesReceived,

        @Schema(description = "Unique messages persisted successfully for this node/run.", example = "1196")
        long messagesSaved,

        @Schema(description = "Repeated messageId values rejected for this node/run.", example = "4")
        long duplicatesSkipped,

        @Schema(description = "Persisted unique messages per second over the node/run time span.", example = "9.9")
        Double throughput,

        @Schema(description = "Average ingest processing time in milliseconds.", example = "1.7")
        Double avgProcessingMs,

        @Schema(description = "First message time for this node/run.", example = "2026-07-16T12:00:00Z")
        Instant firstMessageAt,

        @Schema(description = "Latest message time for this node/run.", example = "2026-07-16T12:02:00Z")
        Instant lastMessageAt
) {
    public static PhysicalNodeRunResponse from(PhysicalNodeRun r) {
        Long spanMs = (r.getFirstMessageAt() != null && r.getLastMessageAt() != null)
                ? r.getLastMessageAt().toEpochMilli() - r.getFirstMessageAt().toEpochMilli()
                : null;
        Double throughput = (spanMs != null && spanMs > 0)
                ? r.getMessagesSaved() * 1000.0 / spanMs
                : null;
        return new PhysicalNodeRunResponse(
                r.getRunId(),
                r.getNodeId(),
                r.getMessagesReceived(),
                r.getMessagesSaved(),
                r.getDuplicatesSkipped(),
                throughput,
                r.getAvgProcessingMs(),
                r.getFirstMessageAt(),
                r.getLastMessageAt()
        );
    }
}
