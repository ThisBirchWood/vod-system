package com.ddf.vodsystem.auth;

public record GoogleUser(
        String googleId,
        String email,
        String name,
        String profilePictureUrl
) {
}
