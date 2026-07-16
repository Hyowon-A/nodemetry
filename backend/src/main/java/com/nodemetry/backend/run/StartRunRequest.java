package com.nodemetry.backend.run;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "StartRunRequest", description = "Request to register a virtual load-test run before simulator telemetry starts.")
public record StartRunRequest(
        @Schema(description = "Run identifier used by simulator telemetry.", example = "20260716T120000Z")
        String runId,

        @Schema(description = "Human-readable run label.", example = "shared - 5 connections - 5s interval")
        String label,

        @Schema(description = "MQTT QoS used by the simulator.", example = "1", allowableValues = {"0", "1", "2"})
        int qos,

        @Schema(description = "Configured number of virtual nodes.", example = "250")
        int nodeCount,

        @Schema(description = "Configured seconds between publishes per node.", example = "5.0")
        double intervalSec,

        @Schema(description = "Configured fraction of messages that intentionally reuse a messageId.", example = "0.05")
        double duplicateRate
) {}
