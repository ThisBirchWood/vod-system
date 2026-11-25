package com.ddf.vodsystem.job;

import java.time.Instant;

public record JobResponseDTO (
        String uuid,
        float progress,
        boolean isComplete,
        JobState state,
        String errorOutput,
        Instant createdAt
) {
}
