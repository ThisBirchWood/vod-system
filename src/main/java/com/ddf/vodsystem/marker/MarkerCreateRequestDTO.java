package com.ddf.vodsystem.marker;

import jakarta.validation.constraints.NotBlank;

public record MarkerCreateRequestDTO(
        @NotBlank String message
) {}
