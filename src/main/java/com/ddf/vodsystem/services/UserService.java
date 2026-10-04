package com.ddf.vodsystem.services;

import com.ddf.vodsystem.dto.GoogleUser;
import com.ddf.vodsystem.dto.TokenPackage;
import com.ddf.vodsystem.dto.properties.AuthProperties;
import com.ddf.vodsystem.entities.RefreshToken;
import com.ddf.vodsystem.entities.TokenFamily;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.exceptions.NotAuthenticated;
import com.ddf.vodsystem.repositories.UserRepository;
import com.ddf.vodsystem.security.GoogleVerifierService;
import com.ddf.vodsystem.security.JwtService;
import com.ddf.vodsystem.security.TokenService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class UserService {
    private final GoogleVerifierService googleVerifierService;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    private final long tokenFamilyExpirationMs;
    private final long refreshTokenExpirationMs;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int REFRESH_GRACE_PERIOD_SECONDS = 5;
    private final TokenService tokenService;

    public UserService(UserRepository userRepository,
                       JwtService jwtService,
                       GoogleVerifierService googleVerifierService,
                       AuthProperties props,
                       TokenService tokenService) {
        this.userRepository = userRepository;
        this.googleVerifierService = googleVerifierService;
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
        GoogleUser googleUser = googleVerifierService.verify(idToken);
        User user = createOrUpdateGoogleUser(googleUser);

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

    private User createOrUpdateGoogleUser(GoogleUser googleUser) {
        Optional<User> existingUser = userRepository.findByGoogleId(googleUser.googleId());
        User user;

        if (existingUser.isEmpty()) {
            user = new User();
            user.setGoogleId(googleUser.googleId());
            user.setUsername(googleUser.email());
            user.setRole(0);
            user.setCreatedAt(Instant.now());
            user.setStreamKey(HexFormat.of().formatHex(generateRandomBytes(24)));
        } else {
            user = existingUser.get();
        }

        user.setEmail(googleUser.email());
        user.setName(googleUser.name());
        user.setProfilePictureUrl(googleUser.profilePictureUrl());
        return userRepository.saveAndFlush(user);
    }

    private byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }
}
