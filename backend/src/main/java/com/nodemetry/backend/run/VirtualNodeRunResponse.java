package com.nodemetry.backend.run;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(
        name = "VirtualNodeRun",
        description = "Aggregate load-test run metrics for virtual nodes. Expected: configured number of expected messages. Received: messages accepted by the backend. Duplicates: repeated messageId values rejected. Unique received: received - duplicates. Saved: unique messages persisted successfully. Delivery percentage: received / expected × 100. Persistence percentage: saved / unique received × 100."
)
public record VirtualNodeRunResponse(
        @Schema(description = "Run identifier.", example = "20260716T120000Z")
        String runId,

        @Schema(description = "Human-readable run label.", example = "SHARED - 5 connections - 5s interval")
        String label,

        @Schema(description = "Run start time.", example = "2026-07-16T12:00:00Z")
        Instant startedAt,

        @Schema(description = "Run end time, or null while running.", example = "2026-07-16T12:02:00Z")
        Instant endedAt,

        @Schema(description = "MQTT QoS used by the simulator.", example = "1", allowableValues = {"0", "1", "2"})
        int qos,

        @Schema(description = "Configured number of virtual nodes.", example = "250")
        int nodeCount,

        @Schema(description = "Configured seconds between publishes per node.", example = "5.0")
        double intervalSec,

        @Schema(description = "Configured fraction of intentional duplicate messageId publishes.", example = "0.05")
        double duplicateRate,

        @Schema(description = "Messages accepted by the backend.", example = "5880")
        long totalReceived,

        @Schema(description = "Unique messages persisted successfully.", example = "5860")
        long totalSaved,

        @Schema(description = "Repeated messageId values rejected.", example = "20")
        long duplicatesSkipped,

        @Schema(description = "Measured run duration in milliseconds.", example = "120000")
        Long durationMs,

        @Schema(description = "Persisted unique messages per second over the measured run duration.", example = "48.8")
        Double throughputMsgPerSec,

        @Schema(description = "Duplicate percentage calculated as duplicatesSkipped / totalReceived × 100.", example = "0.34")
        Double dupeRatePct,

        @Schema(description = "Whether the run has not been ended yet.", example = "false")
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
