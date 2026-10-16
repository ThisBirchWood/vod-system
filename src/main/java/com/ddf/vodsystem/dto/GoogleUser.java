package com.ddf.vodsystem.dto;

public record GoogleUser(
        String googleId,
        String email,
        String name,
        String profilePictureUrl
) {
}
