package com.nodemetry.backend.run;

import java.time.Instant;

public record PhysicalNodeRunResponse(
        String runId,
        String nodeId,
        long messagesReceived,
        long messagesSaved,
        long duplicatesSkipped,
        Double throughput,
        Double avgProcessingMs,
        Instant firstMessageAt,
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
