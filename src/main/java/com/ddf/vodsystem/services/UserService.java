package com.ddf.vodsystem.services;

import com.ddf.vodsystem.dto.TokenPackage;
import com.ddf.vodsystem.dto.properties.AuthProperties;
import com.ddf.vodsystem.entities.RefreshToken;
import com.ddf.vodsystem.entities.TokenFamily;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.exceptions.NotAuthenticated;
import com.ddf.vodsystem.repositories.UserRepository;
import com.ddf.vodsystem.security.JwtService;
import com.ddf.vodsystem.security.TokenService;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class UserService {
    private final GoogleIdTokenVerifier verifier;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    private final long tokenFamilyExpirationMs;
    private final long refreshTokenExpirationMs;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int REFRESH_GRACE_PERIOD_SECONDS = 5;
    private final TokenService tokenService;

    public UserService(UserRepository userRepository,
                       JwtService jwtService,
                       GoogleIdTokenVerifier verifier,
                       AuthProperties props,
                       TokenService tokenService) {
        this.userRepository = userRepository;
        this.verifier = verifier;
        this.jwtService = jwtService;
        this.tokenService = tokenService;

        this.refreshTokenExpirationMs = props.expiration().refreshToken().toMillis();
        this.tokenFamilyExpirationMs = props.expiration().tokenFamily().toMillis();
    }

    /**
     * Looks up a user by their database ID.
     *
     * @param userId the ID of the user to find
     * @return the matching {@link User}, or empty if none exists
     */
    public Optional<User> getUserById(Long userId) {
        return userRepository.findById(userId);
    }

    /**
     * Returns the user backing the current Spring Security authentication, if authenticated.
     *
     * @return the authenticated {@link User}, or empty if no user is authenticated
     */
    public Optional<User> getLoggedInUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof User user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /**
     * Verifies a Google ID token, creating or updating the corresponding user, and issues a session JWT.
     *
     * @param idToken the Google ID token to verify
     * @return a signed JWT for the authenticated user
     * @throws NotAuthenticated if the token is invalid or its subject is missing
     */
    @Transactional
    public TokenPackage login(String idToken) {
        GoogleIdToken googleIdToken = getGoogleIdToken(idToken);
        String googleId = googleIdToken.getPayload().getSubject();

        if (googleId == null) {
            throw new NotAuthenticated("Invalid ID token");
        }

        User googleUser = getGoogleUser(googleIdToken);
        User user = createOrUpdateUser(googleUser);

        byte[] rawRefresh = generateRandomBytes(32);
        TokenFamily tokenFamily = tokenService.createTokenFamily(user, tokenFamilyExpirationMs);
        tokenService.createRefreshToken(tokenService.hashToken(rawRefresh), tokenFamily, refreshTokenExpirationMs);

        String jwt = jwtService.generateToken(user.getId());
        return new TokenPackage(tokenService.bytesToHex(rawRefresh), jwt);
    }

    @Transactional(noRollbackFor = NotAuthenticated.class)
    public TokenPackage refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new NotAuthenticated("Missing refresh token");
        }

        // Check Refresh Token is real
        byte[] rawToken = tokenService.hexToBytes(refreshToken);
        RefreshToken token = tokenService.getRefreshTokenByHash(rawToken)
                .orElseThrow(() -> new NotAuthenticated("No such refresh token"));

        // Find Token Family
        Optional<TokenFamily> tokenFamily = tokenService.getFamilyByRefreshToken(token);

        if (tokenFamily.isEmpty()) {
            throw new IllegalStateException("Token must be tied to a family");
        }

        if (tokenFamily.get().getRevokedAt() != null) {
            throw new NotAuthenticated("Token family has been revoked");
        }

        Instant now = Instant.now();

        // Reuse Check
        if (token.getUsedAt() != null) {
            if (Duration.between(token.getUsedAt(), now).getSeconds() > REFRESH_GRACE_PERIOD_SECONDS) {
                tokenService.revokeTokenFamily(tokenFamily.get());
            }
            throw new NotAuthenticated("Refresh token already used");
        }

        // Expiry Check
        if (now.isAfter(token.getExpiresAt()) || now.isAfter(tokenFamily.get().getExpiresAt())) {
            throw new NotAuthenticated("Refresh token or family expired");
        }

        // Rotate
        token.setUsedAt(now);
        byte[] newRawToken = generateRandomBytes(32);
        tokenService.createRefreshToken(
                newRawToken,
                tokenFamily.get(),
                refreshTokenExpirationMs
        );

        String jwt = jwtService.generateToken(tokenFamily.get().getUser().getId());
        return new TokenPackage(tokenService.bytesToHex(newRawToken), jwt);
    }

    /**
     * Looks up a user by their stream key.
     *
     * @param streamKey the stream key to match
     * @return the matching {@link User}, or empty if none exists
     */
    public Optional<User> getUserByStreamKey(String streamKey) {
        return userRepository.findByStreamKey(streamKey);
    }

    private User createOrUpdateUser(User user) {
        Optional<User> existingUser = userRepository.findByGoogleId(user.getGoogleId());

        if (existingUser.isEmpty()) {
            user.setRole(0);
            user.setCreatedAt(Instant.now());
            user.setStreamKey(HexFormat.of().formatHex(generateRandomBytes(24)));
            return userRepository.saveAndFlush(user);
        }

        User existing = existingUser.get();
        existing.setEmail(user.getEmail());
        existing.setName(user.getName());
        existing.setProfilePictureUrl(user.getProfilePictureUrl());
        existing.setUsername(user.getUsername());
        return userRepository.saveAndFlush(existing);
    }

    private User getGoogleUser(GoogleIdToken idToken) {
        String googleId = idToken.getPayload().getSubject();
        String email = idToken.getPayload().getEmail();
        String name = (String) idToken.getPayload().get("name");
        String profilePictureUrl = (String) idToken.getPayload().get("picture");

        User user = new User();
        user.setGoogleId(googleId);
        user.setEmail(email);
        user.setName(name);
        user.setUsername(email);
        user.setProfilePictureUrl(profilePictureUrl);

        return user;
    }

    private GoogleIdToken getGoogleIdToken(String idToken) {
        try {
            return verifier.verify(idToken);
        } catch (GeneralSecurityException | IOException e) {
            throw new NotAuthenticated("Invalid ID token: " + e.getMessage());
        }
    }

    private byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }
}
