package com.ddf.vodsystem.security;

import com.ddf.vodsystem.dto.GoogleUser;
import com.ddf.vodsystem.exceptions.NotAuthenticated;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;

@Service
public class GoogleVerifierService {
    private final GoogleIdTokenVerifier verifier;

    public GoogleVerifierService(GoogleIdTokenVerifier verifier) {
        this.verifier = verifier;
    }

    public GoogleUser verify(String idToken) {
        GoogleIdToken token;
        try {
            token = verifier.verify(idToken);
        } catch (GeneralSecurityException | IOException e) {
            throw new NotAuthenticated("Invalid ID token: " + e.getMessage());
        }

        String googleId = token.getPayload().getSubject();

        if (googleId == null) {
            throw new NotAuthenticated("Google ID does not exist");
        }

        return new GoogleUser(
                googleId,
                token.getPayload().getEmail(),
                (String) token.getPayload().get("name"),
                (String) token.getPayload().get("picture")
        );
    }
}
