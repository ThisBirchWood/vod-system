package com.ddf.vodsystem.dto.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.security")
public record AuthProperties (
        @NotBlank @Size(min = 32) String jwtSecret,
        @Valid @NotNull Expiration expiration
) {

    /**
     *
     * @param jwt
     * @param refreshToken
     * @param tokenFamily
     * @param gracePeriod
     */
    public record Expiration (
            @NotNull Duration jwt,
            @NotNull Duration refreshToken,
            @NotNull Duration tokenFamily,
            @NotNull Duration gracePeriod
    ) {}
}
