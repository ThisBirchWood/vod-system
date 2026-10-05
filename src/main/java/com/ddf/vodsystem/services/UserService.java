package com.ddf.vodsystem.services;

import com.ddf.vodsystem.dto.GoogleUser;
import com.ddf.vodsystem.dto.TokenPackage;
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
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class UserService {
    private final GoogleVerifierService googleVerifierService;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final TokenService tokenService;

    public UserService(UserRepository userRepository,
                       JwtService jwtService,
                       GoogleVerifierService googleVerifierService,
                       TokenService tokenService) {
        this.userRepository = userRepository;
        this.googleVerifierService = googleVerifierService;
        this.jwtService = jwtService;
        this.tokenService = tokenService;
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

        String refreshToken = tokenService.createSession(user);
        String jwt = jwtService.generateToken(user.getId());

        return new TokenPackage(refreshToken, jwt);
    }

    public TokenPackage refresh(String refreshToken) {
        TokenService.Rotation rotation = tokenService.rotate(refreshToken);
        String jwt = jwtService.generateToken(rotation.userId());

        return new TokenPackage(rotation.newRefreshToken(), jwt);
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
        return userRepository.save(user);
    }

    private byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }
}
