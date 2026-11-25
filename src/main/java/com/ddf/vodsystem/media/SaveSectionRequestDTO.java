package com.ddf.vodsystem.media;

import java.time.Instant;
import jakarta.validation.constraints.NotNull;

public record SaveSectionRequestDTO(
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        String title,
        String description) {}
