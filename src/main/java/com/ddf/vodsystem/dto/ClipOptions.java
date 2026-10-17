package com.ddf.vodsystem.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ClipOptions {
    @NotBlank
    private String title;

    private String description;

    @NotNull
    @PositiveOrZero
    private Float startPoint;

    @NotNull @Positive
    private Float duration;

    @DecimalMin("1.0") @DecimalMax("120.0")
    private Float fps;

    @Positive
    private Integer width;

    @Positive
    private Integer height;

    @Positive
    private Long fileSize;
}
