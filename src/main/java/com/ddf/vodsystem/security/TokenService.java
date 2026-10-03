package com.ddf.vodsystem.security;

import com.ddf.vodsystem.entities.RefreshToken;
import com.ddf.vodsystem.entities.TokenFamily;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.repositories.RefreshTokenRepository;
import com.ddf.vodsystem.repositories.TokenFamilyRepository;
import org.apache.commons.codec.binary.Hex;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class TokenService {
    private final TokenFamilyRepository tokenFamilyRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    private static final SecureRandom RNG = new SecureRandom();
    private final HexFormat hex = HexFormat.of();

    public TokenService (
            TokenFamilyRepository tokenFamilyRepository,
            RefreshTokenRepository refreshTokenRepository
    ) {
        this.tokenFamilyRepository = tokenFamilyRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public TokenFamily createTokenFamily(User user, long expiryTimeMs) {
        Instant now = Instant.now();

        TokenFamily tokenFamily = new TokenFamily();
        tokenFamily.setUser(user);
        tokenFamily.setCreatedAt(now);
        tokenFamily.setExpiresAt(now.plusMillis(expiryTimeMs));

        return tokenFamilyRepository.saveAndFlush(tokenFamily);
    }

    public RefreshToken createRefreshToken(
            byte[] token,
            TokenFamily tokenFamily,
            long expiryTimeMs
    ) {
        Instant now = Instant.now();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setTokenFamily(tokenFamily);
        refreshToken.setTokenHash(hashToken(token));
        refreshToken.setCreatedAt(now);
        refreshToken.setExpiresAt(now.plusMillis(expiryTimeMs));
        return refreshTokenRepository.saveAndFlush(refreshToken);
    }

    public Optional<RefreshToken> getRefreshTokenByHash(byte[] refreshHash) {
        return refreshTokenRepository.findByHash(refreshHash);
    }

    public Optional<TokenFamily> getFamilyByRefreshToken(RefreshToken refreshToken) {
        return tokenFamilyRepository.findById(refreshToken.getTokenFamily().getId());
    }

    public TokenFamily revokeTokenFamily(TokenFamily tokenFamily) {
        tokenFamily.setRevokedAt(Instant.now());
        return tokenFamilyRepository.saveAndFlush(tokenFamily);
    }

    public String bytesToHex(byte[] bytes) {
        return hex.formatHex(bytes);
    }

    public byte[] hexToBytes(String hexS) {
        return hex.parseHex(hexS);
    }

    public byte[] hashToken(byte[] token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token);  // 32 bytes
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
