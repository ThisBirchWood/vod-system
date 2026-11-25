package com.ddf.vodsystem.desktop;

/**
 * Settings the desktop client needs before it can sign in and stream, so users never have to
 * enter them by hand.
 *
 * @param googleClientId the OAuth client ID the backend verifies Google ID tokens against
 * @param rtmpUrl        the public RTMP ingest URL, or {@code null} to let the client derive it
 *                       from the server's host
 * @param websiteUrl     the web frontend, where users find the clips they captured
 */
public record DesktopClientConfigDTO(
        String googleClientId,
        String rtmpUrl,
        String websiteUrl
) {}
