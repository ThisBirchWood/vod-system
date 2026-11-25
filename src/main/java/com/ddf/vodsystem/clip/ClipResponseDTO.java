package com.ddf.vodsystem.clip;

import java.time.Instant;

public record ClipResponseDTO(
        Long id,
        Long userId,
        String title,
        String description,
        Float duration,
        Instant createdAt
) {}