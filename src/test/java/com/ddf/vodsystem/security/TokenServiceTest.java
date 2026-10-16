package com.ddf.vodsystem.security;

import com.ddf.vodsystem.dto.properties.AuthProperties;
import com.ddf.vodsystem.entities.RefreshToken;
import com.ddf.vodsystem.entities.TokenFamily;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.exceptions.NotAuthenticated;
import com.ddf.vodsystem.repositories.RefreshTokenRepository;
import com.ddf.vodsystem.repositories.TokenFamilyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
    private static final String rawToken = "deadbeefdeadbeefdeadbeefdeadbeef";

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
                        Duration.ofSeconds(5)
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

    @Nested
    @DisplayName("createSession()")
    class createSession {
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

    // Rotate
    @Nested
    @DisplayName("rotate()")
    class rotate {
        // Input val
        @Test
        void nullToken_throwsNotAuthenticated() {
            assertThrows(
                    NotAuthenticated.class,
                    () -> tokenService.rotate(null)
            );
        }

        @Test
        void blankToken_throwsNotAuthenticated() {
            assertThrows(
                    NotAuthenticated.class,
                    () -> tokenService.rotate("")
            );
        }

        // Invalid cases
        @Test
        void unknownToken_throwsNotAuthenticated() {
            when(refreshTokenRepository.findByHash(any())).thenReturn(
                    Optional.empty()
            );

            assertThrows(
                    NotAuthenticated.class,
                    () -> tokenService.rotate(rawToken)
            );
        }

        @Test
        void revokedFamily_throwsNotAuthenticated() {
            TokenFamily tokenFamily = activeFamily();
            tokenFamily.setRevokedAt(Instant.now(clock));
            RefreshToken refreshToken = tokenIn(tokenFamily);

            clock.advance(Duration.ofMinutes(1));

            when(refreshTokenRepository.findByHash(any())).thenReturn(
                    Optional.of(refreshToken)
            );

            assertThrows(
                    NotAuthenticated.class,
                    () -> tokenService.rotate(rawToken)
            );
        }

        // Happy Path
        @Test
        void unusedToken_returnsUserIdAndRefreshToken() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);

            when(refreshTokenRepository.findByHash(any()))
                    .thenReturn(Optional.of(refreshToken));

            TokenService.Rotation rotation = tokenService.rotate(rawToken);

            assertEquals(1L, rotation.userId());
            assertNotEquals(rawToken, rotation.newRefreshToken());
        }

        @Test
        void unsedToken_savesRefreshToken() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);

            when(refreshTokenRepository.findByHash(any()))
                    .thenReturn(Optional.of(refreshToken));

            tokenService.rotate(rawToken);

            verify(refreshTokenRepository, times(2)).save(tokenCaptor.capture());

            RefreshToken newToken = tokenCaptor.getAllValues().stream()
                    .filter(t -> t.getUsedAt() == null)
                    .reduce((a, b) -> { throw new AssertionError("expected exactly one unused token saved"); })
                    .orElseThrow(() -> new AssertionError("no unused token was saved"));

            assertNotEquals(refreshToken.getTokenHash(), newToken.getTokenHash());
            assertEquals(refreshToken.getTokenFamily().getUser(), newToken.getTokenFamily().getUser());        // same owner
            assertEquals(refreshToken.getTokenFamily(), newToken.getTokenFamily()); // same rotation family, if you model one
            assertNull(newToken.getUsedAt());
        }

        @Test
        void ununsedToken_savesOldTokenWithUsedAt() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);

            when(refreshTokenRepository.findByHash(any()))
                    .thenReturn(Optional.of(refreshToken));

            tokenService.rotate(rawToken);

            verify(refreshTokenRepository, times(2)).save(tokenCaptor.capture());

            RefreshToken savedToken = tokenCaptor.getAllValues().stream()
                    .filter(t -> t.getUsedAt() != null)
                    .reduce((a, b) -> { throw new AssertionError("expected exactly one unused token saved"); })
                    .orElseThrow(() -> new AssertionError("used_at field not set in any token"));

            assertEquals(savedToken.getUsedAt(), clock.instant());
        }

        @Test
        void unusedToken_doesNotRevokeFamily() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);

            when(refreshTokenRepository.findByHash(any()))
                    .thenReturn(Optional.of(refreshToken));

            tokenService.rotate(rawToken);

            verify(refreshTokenRepository, times(2)).save(tokenCaptor.capture());
            RefreshToken newToken = tokenCaptor.getAllValues().stream()
                    .filter(t -> t.getUsedAt() == null)
                    .reduce((a, b) -> { throw new AssertionError("expected exactly one unused token saved"); })
                    .orElseThrow(() -> new AssertionError("no unused token was saved"));
            assertNull(newToken.getTokenFamily().getRevokedAt());
        }

        @Test
        void unusedToken_newTokenIsDifferentFromOldToken() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);
            refreshToken.setTokenHash(rawToken.getBytes());

            when(refreshTokenRepository.findByHash(any()))
                    .thenReturn(Optional.of(refreshToken));

            TokenService.Rotation rotation = tokenService.rotate(rawToken);
            assertNotEquals(rawToken, rotation.newRefreshToken());
        }

        // Grace period checks
        @Test
        void usedWithinGrace_returnsNewToken() {
            Instant usedAt = Instant.now(clock);

            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);
            refreshToken.setTokenHash(rawToken.getBytes());
            refreshToken.setUsedAt(usedAt);

            when(refreshTokenRepository.findByHash(any()))
                    .thenReturn(Optional.of(refreshToken));

            // within grace: 3s since usedAt, grace is 5s, and nothing has expired yet
            clock.advance(Duration.ofSeconds(3));

            TokenService.Rotation rotation = tokenService.rotate(rawToken);

            assertEquals(1L, rotation.userId());
            assertNotEquals(rawToken, rotation.newRefreshToken());
            assertNull(tokenFamily.getRevokedAt());
            verify(tokenFamilyRepository, never()).save(any());

            // grace is not extended by a replay, and only the new token is written
            assertEquals(usedAt, refreshToken.getUsedAt());

            verify(refreshTokenRepository, times(1)).save(tokenCaptor.capture());
            RefreshToken newToken = tokenCaptor.getValue();
            assertNull(newToken.getUsedAt());
            assertSame(tokenFamily, newToken.getTokenFamily());
            assertNotEquals(refreshToken.getTokenHash(), newToken.getTokenHash());
        }

        @Test
        void usedAtExactGraceBoundary_returnsNewToken() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);
            refreshToken.setUsedAt(clock.instant());
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            clock.advance(authProperties.expiration().gracePeriod());

            TokenService.Rotation rotation = tokenService.rotate(rawToken);

            assertEquals(1L, rotation.userId());
            assertNull(tokenFamily.getRevokedAt());
        }

        // Reuse after grace period
        @Test
        void usedAfterGrace_throwsNotAuthenticated() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);
            refreshToken.setUsedAt(clock.instant());
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            clock.advance(Duration.ofSeconds(6));

            assertThrows(NotAuthenticated.class, () -> tokenService.rotate(rawToken));
        }

        @Test
        void usedAfterGrace_revokesFamily() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);
            refreshToken.setUsedAt(clock.instant());
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            clock.advance(Duration.ofSeconds(6));

            assertThrows(NotAuthenticated.class, () -> tokenService.rotate(rawToken));

            verify(tokenFamilyRepository).save(familyCaptor.capture());
            assertSame(tokenFamily, familyCaptor.getValue());
            assertEquals(clock.instant(), tokenFamily.getRevokedAt());
        }

        @Test
        void usedAfterGrace_doesNotIssueNewToken() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);
            refreshToken.setUsedAt(clock.instant());
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            clock.advance(Duration.ofSeconds(6));

            assertThrows(NotAuthenticated.class, () -> tokenService.rotate(rawToken));

            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        void usedAfterGrace_subsequentRotateRejectedWithoutResaving() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);
            refreshToken.setUsedAt(clock.instant());
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            clock.advance(Duration.ofSeconds(6));
            assertThrows(NotAuthenticated.class, () -> tokenService.rotate(rawToken));

            assertThrows(NotAuthenticated.class, () -> tokenService.rotate(rawToken));
            verify(tokenFamilyRepository, times(1)).save(any());
        }

        // Expiry
        @Test
        void expiredToken_throwsNotAuthenticated() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);
            refreshToken.setExpiresAt(clock.instant().minusSeconds(1));
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            assertThrows(NotAuthenticated.class, () -> tokenService.rotate(rawToken));
        }

        @Test
        void expiredFamily_throwsNotAuthenticated() {
            TokenFamily tokenFamily = activeFamily();
            tokenFamily.setExpiresAt(clock.instant().minusSeconds(1));
            RefreshToken refreshToken = tokenIn(tokenFamily);
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            assertThrows(NotAuthenticated.class, () -> tokenService.rotate(rawToken));
        }

        @Test
        void expiredToken_doesNotSaveOrRevoke() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            clock.advance(Duration.ofSeconds(11));

            assertThrows(NotAuthenticated.class, () -> tokenService.rotate(rawToken));

            verify(refreshTokenRepository, never()).save(any());
            verify(tokenFamilyRepository, never()).save(any());
            assertNull(refreshToken.getUsedAt());
            assertNull(tokenFamily.getRevokedAt());
        }

        @Test
        void tokenAtExactExpiry_returnsNewToken() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            clock.advance(Duration.ofSeconds(10));

            TokenService.Rotation rotation = tokenService.rotate(rawToken);

            assertEquals(1L, rotation.userId());
        }

        // Ordering
        @Test
        void revokedFamilyAndUsedAfterGrace_doesNotRevokeAgain() {
            TokenFamily tokenFamily = activeFamily();
            Instant revokedAt = clock.instant();
            tokenFamily.setRevokedAt(revokedAt);
            RefreshToken refreshToken = tokenIn(tokenFamily);
            refreshToken.setUsedAt(clock.instant());
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            clock.advance(Duration.ofSeconds(6));

            assertThrows(NotAuthenticated.class, () -> tokenService.rotate(rawToken));

            assertEquals(revokedAt, tokenFamily.getRevokedAt());
            verify(tokenFamilyRepository, never()).save(any());
        }

        @Test
        void revokedAndExpiredFamily_throwsNotAuthenticated() {
            TokenFamily tokenFamily = activeFamily();
            tokenFamily.setRevokedAt(clock.instant());
            RefreshToken refreshToken = tokenIn(tokenFamily);
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            clock.advance(Duration.ofSeconds(11));

            assertThrows(NotAuthenticated.class, () -> tokenService.rotate(rawToken));

        }

        @Test
        void expiredAndUsedAfterGrace_revokesFamily() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);
            refreshToken.setUsedAt(clock.instant());
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            clock.advance(Duration.ofSeconds(11));

            assertThrows(NotAuthenticated.class, () -> tokenService.rotate(rawToken));

            assertEquals(clock.instant(), tokenFamily.getRevokedAt());
            verify(tokenFamilyRepository).save(tokenFamily);
        }

        @Test
        void expiredWithinGrace_doesNotRevokeFamily() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);
            refreshToken.setExpiresAt(clock.instant().plusSeconds(2));
            refreshToken.setUsedAt(clock.instant());
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            clock.advance(Duration.ofSeconds(3));

            assertThrows(NotAuthenticated.class, () -> tokenService.rotate(rawToken));

            assertNull(tokenFamily.getRevokedAt());
            verify(tokenFamilyRepository, never()).save(any());
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        void unknownToken_doesNotTouchRepositoriesFurther() {
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.empty());

            assertThrows(NotAuthenticated.class, () -> tokenService.rotate(rawToken));

            verify(refreshTokenRepository, never()).save(any());
            verifyNoInteractions(tokenFamilyRepository);
        }
    }

    @Nested
    @DisplayName("revoke()")
    class revoke {
        @Test
        void knownToken_revokesTokenAndFamily() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            tokenService.revoke(rawToken);

            assertEquals(clock.instant(), tokenFamily.getRevokedAt());
            assertEquals(clock.instant(), refreshToken.getRevokedAt());
        }

        @Test
        void knownToken_savesTokenAndFamily() {
            TokenFamily tokenFamily = activeFamily();
            RefreshToken refreshToken = tokenIn(tokenFamily);
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            tokenService.revoke(rawToken);

            verify(tokenFamilyRepository).save(tokenFamily);
            verify(refreshTokenRepository).save(refreshToken);
        }

        @Test
        void knownToken_looksUpBySingleSha256Hash() throws Exception {
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.empty());

            tokenService.revoke(rawToken);

            byte[] expectedHash = MessageDigest.getInstance("SHA-256").digest(HEX.parseHex(rawToken));
            verify(refreshTokenRepository).findByHash(expectedHash);
        }

        @Test
        void alreadyRevoked_keepsOriginalTimestampsAndDoesNotSave() {
            Instant revokedAt = clock.instant();
            TokenFamily tokenFamily = activeFamily();
            tokenFamily.setRevokedAt(revokedAt);
            RefreshToken refreshToken = tokenIn(tokenFamily);
            refreshToken.setRevokedAt(revokedAt);
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.of(refreshToken));

            clock.advance(Duration.ofMinutes(1));
            tokenService.revoke(rawToken);

            assertEquals(revokedAt, tokenFamily.getRevokedAt());
            assertEquals(revokedAt, refreshToken.getRevokedAt());
            verify(tokenFamilyRepository, never()).save(any());
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        void unknownToken_doesNothing() {
            when(refreshTokenRepository.findByHash(any())).thenReturn(Optional.empty());

            tokenService.revoke(rawToken);

            verify(refreshTokenRepository, never()).save(any());
            verifyNoInteractions(tokenFamilyRepository);
        }

        @Test
        void nullToken_doesNothing() {
            tokenService.revoke(null);

            verifyNoInteractions(refreshTokenRepository, tokenFamilyRepository);
        }

        @Test
        void blankToken_doesNothing() {
            tokenService.revoke("");

            verifyNoInteractions(refreshTokenRepository, tokenFamilyRepository);
        }

        @Test
        void malformedToken_throwsNotAuthenticated() {
            assertThrows(
                    NotAuthenticated.class,
                    () -> tokenService.revoke("not-hex")
            );

            verifyNoInteractions(refreshTokenRepository, tokenFamilyRepository);
        }
    }

    private TokenFamily activeFamily() {
        TokenFamily tokenFamily = new TokenFamily();
        tokenFamily.setId(1L);
        tokenFamily.setUser(createUser(1L, "user", "user"));
        tokenFamily.setExpiresAt(clock.instant().plusSeconds(10));
        return tokenFamily;
    }

    private RefreshToken tokenIn(TokenFamily tokenFamily) {
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setTokenFamily(tokenFamily);
        refreshToken.setExpiresAt(clock.instant().plusSeconds(10));
        return refreshToken;
    }
}
