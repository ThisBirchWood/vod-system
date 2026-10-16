package com.ddf.vodsystem.security;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

@Configuration
public class GoogleAuthConfig {

    /**
     * Provides the verifier used to validate Google ID tokens issued for this application's client ID.
     *
     * @param googleClientId the OAuth client ID that tokens must be issued for
     * @return the configured {@link GoogleIdTokenVerifier}
     */
    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier(
            @Value("${spring.security.oauth2.client.registration.google.client-id}") String googleClientId) {
        return new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
                .setAudience(Collections.singletonList(googleClientId))
                .build();
    }
}