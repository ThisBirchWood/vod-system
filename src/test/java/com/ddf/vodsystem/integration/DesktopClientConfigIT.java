package com.ddf.vodsystem.integration;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The desktop client bootstraps from this endpoint before it has a session, so it must be public
 * and expose exactly the values configured for the integration profile.
 */
class DesktopClientConfigIT extends BaseIntegrationTest {

    private static final String CONFIG = "/api/v1/client/config";

    @Test
    void configIsAvailableWithoutAuthentication() throws Exception {
        mockMvc.perform(get(CONFIG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.googleClientId").value("integration-test-client-id"))
                .andExpect(jsonPath("$.data.rtmpUrl").value("rtmp://ingest.example.com:1935/live"))
                .andExpect(jsonPath("$.data.websiteUrl").value("http://localhost:5173"));
    }

    @Test
    void onlyReadsArePublic() throws Exception {
        mockMvc.perform(post(CONFIG))
                .andExpect(status().isUnauthorized());
    }
}
