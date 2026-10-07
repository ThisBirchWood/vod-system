package com.ddf.vodsystem.security;

import com.ddf.vodsystem.dto.properties.AuthProperties;
import com.ddf.vodsystem.entities.RefreshToken;
import com.ddf.vodsystem.entities.TokenFamily;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.exceptions.NotAuthenticated;
import com.ddf.vodsystem.repositories.RefreshTokenRepository;
import com.ddf.vodsystem.repositories.TokenFamilyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class TokenService {
    private static final SecureRandom RNG = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final Clock clock;

    private final TokenFamilyRepository tokenFamilyRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    private final Duration tokenFamilyExpirationMs;
    private final Duration refreshTokenExpirationMs;
    private final Duration refreshGracePeriod;

    public TokenService (
            TokenFamilyRepository tokenFamilyRepository,
            RefreshTokenRepository refreshTokenRepository,
            AuthProperties props,
            Clock clock
    ) {
        this.tokenFamilyRepository = tokenFamilyRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;

        this.refreshTokenExpirationMs = props.expiration().refreshToken();
        this.tokenFamilyExpirationMs = props.expiration().tokenFamily();
        this.refreshGracePeriod = props.expiration().gracePeriod();
    }

    @Transactional
    public String createSession(User user) {
        Instant now = Instant.now(clock);

        TokenFamily tokenFamily = new TokenFamily();
        tokenFamily.setUser(user);
        tokenFamily.setCreatedAt(now);
        tokenFamily.setExpiresAt(now.plusMillis(tokenFamilyExpirationMs.toMillis()));
        tokenFamilyRepository.save(tokenFamily);

        byte[] token = generateRandomBytes(32);

        createRefreshToken(
                token,
                tokenFamily
        );

        return HEX.formatHex(token);
    }

    @Transactional(noRollbackFor = NotAuthenticated.class)
    public Rotation rotate(String hexToken) {
        if (hexToken == null || hexToken.isBlank()) {
            throw new NotAuthenticated("Missing refresh token");
        }

        byte[] hash;
        try {
            hash = sha256(HEX.parseHex(hexToken));
        } catch (IllegalArgumentException e) {
            throw new NotAuthenticated("Malformed refresh token");
        }

        Instant now = Instant.now(clock);

        // Check Token is real
        RefreshToken refreshToken = refreshTokenRepository.findByHash(hash)
                .orElseThrow(() -> new NotAuthenticated("No such refresh token"));

        // Find Token Family
        TokenFamily tokenFamily = refreshToken.getTokenFamily();

        if (tokenFamily.getRevokedAt() != null) {
            throw new NotAuthenticated("Token family has been revoked");
        }

        // Reuse Check
        if (refreshToken.getUsedAt() != null && now.isAfter(refreshToken.getUsedAt().plus(refreshGracePeriod))) {
            tokenFamily.setRevokedAt(now);
            tokenFamilyRepository.save(tokenFamily);
            throw new NotAuthenticated("Refresh token already being used");
        }

        // Expiry Check
        if (
                now.isAfter(refreshToken.getExpiresAt()) ||
                now.isAfter(tokenFamily.getExpiresAt())
        ) {
            throw new NotAuthenticated("Refresh token or family expired");
        }

        // Invalidate old token, unless we're within the grace period, otherwise the grace gets extended
        if (refreshToken.getUsedAt() == null) {
            refreshToken.setUsedAt(now);
            refreshTokenRepository.save(refreshToken);
        }

        byte[] newRawToken = generateRandomBytes(32);
        createRefreshToken(
                newRawToken,
                tokenFamily
        );

        return new Rotation(tokenFamily.getUser().getId(), HEX.formatHex(newRawToken));
    }

    public record Rotation(Long userId, String newRefreshToken) {}

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        RNG.nextBytes(bytes);
        return bytes;
    }


    private RefreshToken createRefreshToken(
            byte[] token,
            TokenFamily tokenFamily
    ) {
        Instant now = Instant.now(clock);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setTokenFamily(tokenFamily);
        refreshToken.setTokenHash(sha256(token));
        refreshToken.setCreatedAt(now);
        refreshToken.setExpiresAt(now.plusMillis(refreshTokenExpirationMs.toMillis()));
        return refreshTokenRepository.save(refreshToken);
    }

}
