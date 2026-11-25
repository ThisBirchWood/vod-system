package com.ddf.vodsystem.token;

public record TokenPackage (
        String refreshToken,
        String accessToken
) {
}
