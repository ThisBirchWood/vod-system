package com.ddf.vodsystem.auth;

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
            throw new NotAuthenticatedException("Invalid ID token: " + e.getMessage());
        }

        if (token == null) {
            throw new NotAuthenticatedException("Invalid ID token");
        }

        String googleId = token.getPayload().getSubject();

        if (googleId == null) {
            throw new NotAuthenticatedException("Google ID does not exist");
        }

        if (!Boolean.TRUE.equals(token.getPayload().getEmailVerified())) {
            throw new NotAuthenticatedException("Email not verified");
        }

        return new GoogleUser(
                googleId,
                token.getPayload().getEmail(),
                (String) token.getPayload().get("name"),
                (String) token.getPayload().get("picture")
        );
    }
}
