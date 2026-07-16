package com.nodemetry.backend.run;

import java.time.Instant;

public record VirtualNodeRunResponse(
        String runId,
        String label,
        Instant startedAt,
        Instant endedAt,
        int qos,
        int nodeCount,
        double intervalSec,
        double duplicateRate,
        long totalReceived,
        long totalSaved,
        long duplicatesSkipped,
        Long durationMs,
        Double throughputMsgPerSec,
        Double dupeRatePct,
        boolean running
) {
    public static VirtualNodeRunResponse from(VirtualNodeRun r) {
        Long durationMs = (r.getStartedAt() != null && r.getEndedAt() != null)
                ? r.getEndedAt().toEpochMilli() - r.getStartedAt().toEpochMilli()
                : null;
        Double throughput = (durationMs != null && durationMs > 0)
                ? r.getTotalSaved() * 1000.0 / durationMs
                : null;
        Double dupeRate = (r.getTotalReceived() > 0)
                ? r.getDuplicatesSkipped() * 100.0 / r.getTotalReceived()
                : null;
        return new VirtualNodeRunResponse(
                r.getRunId(),
                r.getLabel(),
                r.getStartedAt(),
                r.getEndedAt(),
                r.getQos(),
                r.getNodeCount(),
                r.getIntervalSec(),
                r.getDuplicateRate(),
                r.getTotalReceived(),
                r.getTotalSaved(),
                r.getDuplicatesSkipped(),
                durationMs,
                throughput,
                dupeRate,
                r.getEndedAt() == null
        );
    }
}
