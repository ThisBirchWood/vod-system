package com.ddf.vodsystem.stream;

public record StreamResponseDTO(
        boolean isStreaming,
        Long id
) {
}
