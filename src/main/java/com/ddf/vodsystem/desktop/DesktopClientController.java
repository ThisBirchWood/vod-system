package com.ddf.vodsystem.desktop;

import com.ddf.vodsystem.common.dto.APIResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/client")
public class DesktopClientController {
    private static final String SUCCESS = "success";

    private final DesktopClientConfigDTO config;

    public DesktopClientController(
            @Value("${spring.security.oauth2.client.registration.google.client-id}") String googleClientId,
            @Value("${client.rtmp-url:}") String rtmpUrl,
            @Value("${frontend.url}") String websiteUrl) {
        this.config = new DesktopClientConfigDTO(googleClientId, rtmpUrl.isBlank() ? null : rtmpUrl, websiteUrl);
    }

    /**
     * Returns the public settings the desktop client bootstraps from. Requires no authentication:
     * the client needs the Google client ID in order to sign in at all.
     *
     * @return {@code 200 OK} wrapping the {@link DesktopClientConfigDTO}
     */
    @GetMapping("/config")
    public ResponseEntity<APIResponse<DesktopClientConfigDTO>> getConfig() {
        return ResponseEntity.ok(new APIResponse<>(SUCCESS, "Client configuration retrieved", config));
    }
}
