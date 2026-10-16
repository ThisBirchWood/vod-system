package com.ddf.vodsystem.controllers.dto;

/**
 * Session metadata returned after login or refresh. The tokens themselves are only ever sent as
 * HttpOnly cookies; this tells the client when to refresh them.
 *
 * @param accessTokenExpiresIn seconds until the access token cookie expires
 */
public record SessionResponse(
        long accessTokenExpiresIn
) {}
