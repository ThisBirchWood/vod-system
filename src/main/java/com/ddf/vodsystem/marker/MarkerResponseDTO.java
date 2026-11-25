package com.ddf.vodsystem.marker;

import java.time.Instant;

public record MarkerResponseDTO(
        Long id,
        Long userId,
        Long streamId,
        String message,
        Instant timestamp
) {}
