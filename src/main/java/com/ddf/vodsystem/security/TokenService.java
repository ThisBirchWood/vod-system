package com.ddf.vodsystem.security;

import com.ddf.vodsystem.dto.properties.AuthProperties;
import com.ddf.vodsystem.entities.RefreshToken;
import com.ddf.vodsystem.entities.TokenFamily;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.exceptions.NotAuthenticated;
import com.ddf.vodsystem.repositories.RefreshTokenRepository;
import com.ddf.vodsystem.repositories.TokenFamilyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Issues, rotates and revokes opaque refresh tokens.
 * <p>
 * Each login starts a {@link TokenFamily}; every refresh token issued for that session belongs to it.
 * Only the SHA-256 hash of a token is stored. Presenting an already-used token after the grace
 * period is treated as theft and revokes the whole family.
 */
@Service
public class TokenService {
    private static final Logger logger = LoggerFactory.getLogger(TokenService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final Clock clock;

    private final TokenFamilyRepository tokenFamilyRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    private final Duration tokenFamilyExpiration;
    private final Duration refreshTokenExpiration;
    private final Duration refreshGracePeriod;

    /**
     * @param tokenFamilyRepository  stores token families (one per login session)
     * @param refreshTokenRepository stores hashed refresh tokens
     * @param props                  supplies token family lifetime, refresh token lifetime and reuse grace period
     * @param clock                  time source for all timestamps and expiry checks
     */
    public TokenService (
            TokenFamilyRepository tokenFamilyRepository,
            RefreshTokenRepository refreshTokenRepository,
            AuthProperties props,
            Clock clock
    ) {
        this.tokenFamilyRepository = tokenFamilyRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;

        this.refreshTokenExpiration = props.expiration().refreshToken();
        this.tokenFamilyExpiration = props.expiration().tokenFamily();
        this.refreshGracePeriod = props.expiration().gracePeriod();
    }

    /**
     * Starts a new session for a user by creating a token family and its first refresh token.
     *
     * @param user the user the session belongs to
     * @return the raw refresh token as a 64-character hex string; only its hash is persisted
     */
    @Transactional
    public String createSession(User user) {
        Instant now = Instant.now(clock);

        TokenFamily tokenFamily = new TokenFamily();
        tokenFamily.setUser(user);
        tokenFamily.setCreatedAt(now);
        tokenFamily.setExpiresAt(now.plusMillis(tokenFamilyExpiration.toMillis()));
        tokenFamilyRepository.save(tokenFamily);

        byte[] token = generateRandomBytes(32);

        createRefreshToken(
                token,
                tokenFamily
        );

        return HEX.formatHex(token);
    }

    /**
     * Exchanges a refresh token for a new one in the same family.
     * <p>
     * The presented token is marked used and a new token is issued. Presenting a used token again
     * within the grace period returns another new token without extending the grace period. Presenting
     * it after the grace period revokes the family; this revocation is committed even though the
     * method throws.
     *
     * @param hexToken the raw refresh token as a hex string
     * @return the owning user's ID and the new raw refresh token
     * @throws NotAuthenticated if the token is missing, malformed, unknown, expired, belongs to a revoked
     *                          or expired family, or is reused after the grace period
     */
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

    /**
     * Revokes a refresh token and its whole token family, ending the session. Does nothing if the
     * token is missing or unknown, and leaves existing revocation timestamps unchanged.
     *
     * @param hexToken the raw refresh token as a hex string, may be {@code null}
     * @throws NotAuthenticated if the token is not valid hex
     */
    @Transactional
    public void revoke(String hexToken) {
        if (hexToken == null || hexToken.isBlank()) return;

        byte[] hash;
        try {
            hash = sha256(HEX.parseHex(hexToken));
        } catch (IllegalArgumentException e) {
            throw new NotAuthenticated("Malformed refresh token");
        }

        Optional<RefreshToken> found = refreshTokenRepository.findByHash(hash);
        if (found.isEmpty()) return;

        Instant now = Instant.now(clock);
        RefreshToken refreshToken = found.get();
        TokenFamily family = refreshToken.getTokenFamily();

        if (family.getRevokedAt() == null) {
            family.setRevokedAt(now);
            tokenFamilyRepository.save(family);
        }

        if (refreshToken.getRevokedAt() == null) {
            refreshToken.setRevokedAt(now);
            refreshTokenRepository.save(refreshToken);
        }
    }

    /**
     * Scheduled cleanup that deletes token families that can no longer be refreshed, along with all
     * their refresh tokens.
     * <p>
     * Runs hourly. Used tokens in live families are kept, since reuse detection needs them.
     */
    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void deleteDeadSessions() {
        int deleted = tokenFamilyRepository.deleteDead(Instant.now(clock));
        if (deleted > 0) logger.info("Deleted {} dead token families", deleted);
    }

    /**
     * Result of a successful {@link #rotate(String)}.
     *
     * @param userId          ID of the user who owns the token family
     * @param newRefreshToken the newly issued raw refresh token as a hex string
     */
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
        refreshToken.setExpiresAt(now.plusMillis(refreshTokenExpiration.toMillis()));
        return refreshTokenRepository.save(refreshToken);
    }
}
