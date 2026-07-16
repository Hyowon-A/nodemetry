package com.nodemetry.backend.run;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "EndRunRequest", description = "Request to close a virtual load-test run.")
public record EndRunRequest(
        @Schema(
                description = "Messages accepted by the backend for this run. Expected: configured number of expected messages. Received: messages accepted by the backend. Duplicates: repeated messageId values rejected. Unique received: received - duplicates. Saved: unique messages persisted successfully. Delivery percentage: received / expected × 100. Persistence percentage: saved / unique received × 100.",
                example = "5880"
        )
        Long totalReceived,

        @Schema(description = "Run end time as epoch milliseconds.", example = "1784203320000")
        Long endedAtEpochMs
) {}
