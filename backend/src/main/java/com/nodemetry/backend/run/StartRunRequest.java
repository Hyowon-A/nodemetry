package com.nodemetry.backend.run;

public record StartRunRequest(
        String runId,
        String label,
        int qos,
        int nodeCount,
        double intervalSec,
        double duplicateRate
) {}
