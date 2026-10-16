package com.ddf.vodsystem.services;

import com.ddf.vodsystem.dto.GoogleUser;
import com.ddf.vodsystem.dto.TokenPackage;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.exceptions.NotAuthenticated;
import com.ddf.vodsystem.repositories.UserRepository;
import com.ddf.vodsystem.security.GoogleVerifierService;
import com.ddf.vodsystem.security.JwtService;
import com.ddf.vodsystem.security.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @Mock private GoogleVerifierService googleVerifierService;
    @Mock private JwtService jwtService;
    @Mock private UserRepository userRepository;
    @Mock private TokenService tokenService;

    @Captor ArgumentCaptor<User> userCaptor;

    private static final String ID_TOKEN = "google-id-token";
    private static final String REFRESH_TOKEN = "deadbeefdeadbeefdeadbeefdeadbeef";
    private static final String NEW_REFRESH_TOKEN = "cafebabecafebabecafebabecafebabe";
    private static final String JWT = "header.payload.signature";
    private static final String GOOGLE_ID = "google-id";

    UserService userService;

    User createUser(Long id, String username, String email) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        return user;
    }

    GoogleUser googleUser() {
        return new GoogleUser(
                GOOGLE_ID,
                "user@example.com",
                "My User",
                "https://lol.com/1"
        );
    }

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                jwtService,
                googleVerifierService,
                tokenService
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("getUserById()")
    class getUserById {
        @Test
        void existingId_returnsUser() {
            User user = createUser(1L, "user", "user@example.com");
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            Optional<User> result = userService.getUserById(1L);

            assertTrue(result.isPresent());
            assertSame(user, result.get());
        }

        @Test
        void unknownId_returnsEmpty() {
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            assertTrue(userService.getUserById(1L).isEmpty());
        }
    }

    @Nested
    @DisplayName("getLoggedInUser()")
    class getLoggedInUser {
        @Test
        void authenticatedUserPrincipal_returnsUser() {
            User user = createUser(1L, "user", "user@example.com");
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user, null, List.of())
            );

            Optional<User> result = userService.getLoggedInUser();

            assertTrue(result.isPresent());
            assertSame(user, result.get());
        }

        @Test
        void noAuthentication_returnsEmpty() {
            assertTrue(userService.getLoggedInUser().isEmpty());
        }

        @Test
        void unauthenticated_returnsEmpty() {
            User user = createUser(1L, "user", "user@example.com");
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user, null)
            );

            assertTrue(userService.getLoggedInUser().isEmpty());
        }

        @Test
        void nonUserPrincipal_returnsEmpty() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("anonymous", null, List.of())
            );

            assertTrue(userService.getLoggedInUser().isEmpty());
        }
    }

    @Nested
    @DisplayName("login()")
    class login {
        @Test
        void newUser_savesUserToRepo() {
            when(googleVerifierService.verify(ID_TOKEN)).thenReturn(googleUser());
            when(userRepository.findByGoogleId(GOOGLE_ID)).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.login(ID_TOKEN);

            verify(userRepository).save(userCaptor.capture());
            User saved = userCaptor.getValue();
            assertEquals(GOOGLE_ID, saved.getGoogleId());
            assertEquals("user@example.com", saved.getEmail());
            assertEquals("user@example.com", saved.getUsername());
            assertEquals("My User", saved.getName());
            assertEquals("https://lol.com/1", saved.getProfilePictureUrl());
            assertEquals(0, saved.getRole());
            assertNotNull(saved.getCreatedAt());
        }

        @Test
        void newUser_generatesHexStreamKeyOf48Characters() {
            when(googleVerifierService.verify(ID_TOKEN)).thenReturn(googleUser());
            when(userRepository.findByGoogleId(GOOGLE_ID)).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.login(ID_TOKEN);

            verify(userRepository).save(userCaptor.capture());
            String streamKey = userCaptor.getValue().getStreamKey();
            assertEquals(48, streamKey.length());
            assertTrue(streamKey.matches("[0-9a-f]+"), () -> "not hex: " + streamKey);
        }

        @Test
        void existingUser_updatesUserInfo() {
            User existingUser = createUser(1L, "old-username", "old@example.com");
            existingUser.setGoogleId(GOOGLE_ID);
            existingUser.setName("Old Name");
            existingUser.setProfilePictureUrl("https://lol.com/old");
            existingUser.setStreamKey("123");

            when(googleVerifierService.verify(ID_TOKEN)).thenReturn(googleUser());
            when(userRepository.findByGoogleId(GOOGLE_ID)).thenReturn(Optional.of(existingUser));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.login(ID_TOKEN);

            verify(userRepository).save(userCaptor.capture());
            User saved = userCaptor.getValue();
            assertSame(existingUser, saved);
            assertEquals(1L, saved.getId());
            assertEquals(GOOGLE_ID, saved.getGoogleId());
            assertEquals("user@example.com", saved.getEmail());
            assertEquals("My User", saved.getName());
            assertEquals("https://lol.com/1", saved.getProfilePictureUrl());
            assertEquals("old-username", saved.getUsername());
            assertEquals("123", saved.getStreamKey());
        }

        @Test
        void validToken_returnsRefreshTokenAndJwt() {
            when(googleVerifierService.verify(ID_TOKEN)).thenReturn(googleUser());
            when(userRepository.findByGoogleId(GOOGLE_ID)).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(tokenService.createSession(any())).thenReturn(REFRESH_TOKEN);
            when(jwtService.generateToken(any())).thenReturn(JWT);

            TokenPackage tokenPackage = userService.login(ID_TOKEN);

            assertEquals(REFRESH_TOKEN, tokenPackage.refreshToken());
            assertEquals(JWT, tokenPackage.accessToken());
        }

        @Test
        void validToken_createsSessionForSavedUser() {
            User savedUser = createUser(42L, "user@example.com", "user@example.com");

            when(googleVerifierService.verify(ID_TOKEN)).thenReturn(googleUser());
            when(userRepository.findByGoogleId(GOOGLE_ID)).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenReturn(savedUser);

            userService.login(ID_TOKEN);

            verify(tokenService).createSession(savedUser);
            verify(jwtService).generateToken(42L);
        }

        @Test
        void invalidToken_throwsNotAuthenticated() {
            when(googleVerifierService.verify(ID_TOKEN))
                    .thenThrow(new NotAuthenticated("Invalid ID token"));

            assertThrows(
                    NotAuthenticated.class,
                    () -> userService.login(ID_TOKEN)
            );

            verifyNoInteractions(userRepository, tokenService, jwtService);
        }
    }

    @Nested
    @DisplayName("refresh()")
    class refresh {
        @Test
        void validToken_returnsRotatedTokenAndJwt() {
            when(tokenService.rotate(REFRESH_TOKEN))
                    .thenReturn(new TokenService.Rotation(1L, NEW_REFRESH_TOKEN));
            when(jwtService.generateToken(1L)).thenReturn(JWT);

            TokenPackage tokenPackage = userService.refresh(REFRESH_TOKEN);

            assertEquals(NEW_REFRESH_TOKEN, tokenPackage.refreshToken());
            assertEquals(JWT, tokenPackage.accessToken());
        }

        @Test
        void invalidToken_throwsNotAuthenticated() {
            when(tokenService.rotate(REFRESH_TOKEN))
                    .thenThrow(new NotAuthenticated("Invalid refresh token"));

            assertThrows(
                    NotAuthenticated.class,
                    () -> userService.refresh(REFRESH_TOKEN)
            );

            verifyNoInteractions(jwtService);
        }
    }

    @Nested
    @DisplayName("logout()")
    class logout {
        @Test
        void revokesRefreshToken() {
            userService.logout(REFRESH_TOKEN);

            verify(tokenService).revoke(REFRESH_TOKEN);
        }

        @Test
        void doesNotIssueTokens() {
            userService.logout(REFRESH_TOKEN);

            verify(tokenService, never()).createSession(any());
            verify(tokenService, never()).rotate(any());
            verifyNoInteractions(jwtService);
        }
    }
}
