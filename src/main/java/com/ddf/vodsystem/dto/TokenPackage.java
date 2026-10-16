package com.ddf.vodsystem.dto;

public record TokenPackage (
        String refreshToken,
        String accessToken
) {
}
