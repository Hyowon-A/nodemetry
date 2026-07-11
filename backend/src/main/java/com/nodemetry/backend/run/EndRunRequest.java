package com.nodemetry.backend.run;

public record EndRunRequest(
        Long totalReceived,
        Long endedAtEpochMs
) {}
