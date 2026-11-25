package com.ddf.vodsystem.clip;

import jakarta.validation.constraints.NotNull;

public record ClipSectionRequestDTO(
        @NotNull float duration,
        String title,
        String description
) {}