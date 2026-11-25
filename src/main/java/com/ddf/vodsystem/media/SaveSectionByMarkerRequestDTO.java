package com.ddf.vodsystem.media;

import jakarta.validation.constraints.NotNull;

public record SaveSectionByMarkerRequestDTO(
        @NotNull Long startMarkerId,
        @NotNull Long endMarkerId,
        String title,
        String description) {}
