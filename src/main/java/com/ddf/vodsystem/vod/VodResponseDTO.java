package com.ddf.vodsystem.vod;

import java.time.Instant;

public record VodResponseDTO(
        Long id,
        Long userId,
        String title,
        String description,
        Float duration,
        Instant createdAt
) {}
