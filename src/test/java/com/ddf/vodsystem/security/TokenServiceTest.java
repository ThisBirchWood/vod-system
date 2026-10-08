package com.ddf.vodsystem.security;

import com.ddf.vodsystem.dto.properties.AuthProperties;
import com.ddf.vodsystem.entities.RefreshToken;
import com.ddf.vodsystem.entities.TokenFamily;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.repositories.RefreshTokenRepository;
import com.ddf.vodsystem.repositories.TokenFamilyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
        this.now = start;
    }

    void advance(Duration d) {
        now = now.plus(d);
    }

    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
}


@ExtendWith(MockitoExtension.class)
class TokenServiceTest {
    @Mock private TokenFamilyRepository tokenFamilyRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;

    @Captor ArgumentCaptor<TokenFamily> familyCaptor;
    @Captor ArgumentCaptor<RefreshToken> tokenCaptor;

    private static final HexFormat HEX = HexFormat.of();

    AuthProperties authProperties;
    TokenService tokenService;
    MutableClock clock;

    User createUser(Long id, String username, String email) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        return user;
    }

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties(
                "a-secret",
                new AuthProperties.Expiration(
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(10)
                )
        );

        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

        tokenService = new TokenService(
                tokenFamilyRepository,
                refreshTokenRepository,
                authProperties,
                clock
        );
    }

    @Test
    void createSession_createsTokenFamily() {
        User user = createUser(1L, "user", "user@gmail.com");

        tokenService.createSession(user);

        verify(tokenFamilyRepository).save(familyCaptor.capture());
        verify(refreshTokenRepository).save(tokenCaptor.capture());

        TokenFamily tokenFamily = familyCaptor.getValue();
        RefreshToken refreshToken = tokenCaptor.getValue();

        assertSame(tokenFamily.getUser(), user);
        assertSame(refreshToken.getTokenFamily(), tokenFamily);
        assertEquals(
                tokenFamily.getExpiresAt(),
                clock.instant().plusMillis(authProperties.expiration().tokenFamily().toMillis())
        );
    }

    @Test
    void createSession_savesRefreshTokenCreatedAtAndExpires() {
        User user = createUser(1L, "user", "user");

        tokenService.createSession(user);

        verify(refreshTokenRepository).save(tokenCaptor.capture());
        RefreshToken refreshToken = tokenCaptor.getValue();

        assertEquals(refreshToken.getCreatedAt(), clock.instant());
        assertEquals(
                refreshToken.getExpiresAt(),
                clock.instant().plusMillis(authProperties.expiration().refreshToken().toMillis())
        );
    }

    @Test
    void createSession_returnsHexTokenOf64Characters() {
        User user = createUser(1L, "user", "user");

        String token = tokenService.createSession(user);

        assertEquals(64, token.length());
        assertTrue(token.matches("[0-9a-fA-F]+"), () -> "not hex: " + token);
    }

    @Test
    void createSession_doesNotStoreRawToken() {
        User user = createUser(1L, "user", "user");

        String token = tokenService.createSession(user);

        verify(refreshTokenRepository).save(tokenCaptor.capture());
        RefreshToken refreshToken = tokenCaptor.getValue();

        assertNotEquals(refreshToken.getTokenHash(), HEX.parseHex(token));
    }

}