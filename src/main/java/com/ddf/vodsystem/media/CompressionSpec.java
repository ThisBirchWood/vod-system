package com.ddf.vodsystem.media;

import jakarta.validation.constraints.*;

/**
 * Client-supplied parameters for a compression job: the trim window, optional target
 * resolution/fps/size, and the title/description used if the output is persisted as a clip.
 */
public record CompressionSpec(
        @NotBlank String title,
        String description,
        @NotNull @PositiveOrZero Float startPoint,
        @NotNull @Positive Float duration,
        @DecimalMin("1.0") @DecimalMax("120.0") Float fps,
        @Positive Integer width,
        @Positive Integer height,
        @Positive Long fileSize
) {
}
