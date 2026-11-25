package com.ddf.vodsystem.media;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Properties of a video file as reported by ffprobe.
 *
 * @param duration      length in seconds
 * @param width         frame width in pixels
 * @param height        frame height in pixels
 * @param fps           frame rate, or {@code null} if ffprobe could not determine it
 * @param fileSizeBytes file size in bytes
 */
public record VideoMetadata(
        @NotNull @Positive Float duration,
        @NotNull @Positive Integer width,
        @NotNull @Positive Integer height,
        @Positive Float fps,
        @NotNull @Positive Long fileSizeBytes
) {
}
