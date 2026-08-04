package com.nodemetry.backend.config;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(
        name = "ApiErrorResponse",
        description = "Standard error response shape returned by Spring error handling."
)
public record ApiErrorResponse(
        @Schema(example = "2026-07-16T12:00:00Z")
        Instant timestamp,

        @Schema(example = "404")
        int status,

        @Schema(example = "Not Found")
        String error,

        @Schema(example = "Node not found")
        String message,

        @Schema(example = "/api/v1/nodes/node-999")
        String path
) {}
