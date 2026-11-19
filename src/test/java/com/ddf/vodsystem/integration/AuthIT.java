package com.ddf.vodsystem.integration;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.ddf.vodsystem.controllers.dto.Token;
import com.ddf.vodsystem.dto.GoogleUser;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.exceptions.NotAuthenticated;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of authentication through the real filter chain, cookies and database:
 * Google login, the JWT filter, refresh rotation and reuse detection, logout and the access
 * rules in {@code SecurityConfig}.
 *
 * <p>Token edge cases (expiry, grace period boundaries, hashing) are covered by
 * {@code TokenServiceTest}; this class only checks what needs the full stack.
 */
class AuthIT extends BaseIntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String LOGIN = "/api/v1/users/login";
    private static final String REFRESH = "/api/v1/users/refresh";
    private static final String LOGOUT = "/api/v1/users/logout";

    private static final String ISSUER = "vodsystem";
    private static final String VALID_ID_TOKEN = "valid-google-id-token";

    @Value("${app.security.jwt-secret}")
    private String jwtSecret;

    @Nested
    @DisplayName("POST /users/login")
    class Login {

        @Test
        void firstLoginCreatesUserAndStartsSession() throws Exception {
            GoogleUser google = stubGoogle("alice@example.com");

            MvcResult result = mockMvc.perform(loginRequest(VALID_ID_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessTokenExpiresIn", is(300)))
                    .andReturn();

            Session session = sessionFrom(result);
            assertThat(result.getResponse().getContentAsString())
                    .as("tokens are only delivered as cookies")
                    .doesNotContain(session.accessToken())
                    .doesNotContain(session.refreshToken());

            User user = userRepository.findByGoogleId(google.googleId()).orElseThrow();
            assertThat(user.getEmail()).isEqualTo(google.email());
            assertThat(jwtService.validateTokenAndGetUserId(session.accessToken())).isEqualTo(user.getId());
        }

        @Test
        void repeatLoginUpdatesExistingUserInsteadOfDuplicating() throws Exception {
            String googleId = "google-" + UUID.randomUUID();
            when(googleVerifierService.verify(anyString()))
                    .thenReturn(new GoogleUser(googleId, "old@example.com", "Old Name", "https://pic/old"));
            login();
            User first = userRepository.findByGoogleId(googleId).orElseThrow();

            when(googleVerifierService.verify(anyString()))
                    .thenReturn(new GoogleUser(googleId, "new@example.com", "New Name", "https://pic/new"));
            login();

            assertThat(userRepository.count()).isEqualTo(1);
            User updated = userRepository.findByGoogleId(googleId).orElseThrow();
            assertThat(updated.getId()).isEqualTo(first.getId());
            assertThat(updated.getEmail()).isEqualTo("new@example.com");
            assertThat(updated.getUsername()).isEqualTo(first.getUsername());
            assertThat(updated.getStreamKey()).isEqualTo(first.getStreamKey());
        }

        @Test
        void rejectedGoogleTokenReturns401AndCreatesNothing() throws Exception {
            when(googleVerifierService.verify(anyString())).thenThrow(new NotAuthenticated("rejected"));

            mockMvc.perform(loginRequest("forged-token"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

            assertThat(userRepository.count()).isZero();
        }

        @Test
        void loginIsReachableWithAnExpiredAccessTokenPresent() throws Exception {
            stubGoogle("alice@example.com");

            mockMvc.perform(loginRequest(VALID_ID_TOKEN)
                            .cookie(new Cookie("accessToken", expiredJwtFor(createUser().getId()))))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Auth cookie attributes")
    class Cookies {

        @Test
        void cookiesAreHttpOnlyLaxAndScoped() throws Exception {
            stubGoogle("alice@example.com");
            List<String> cookies = setCookies(mockMvc.perform(loginRequest(VALID_ID_TOKEN)).andReturn());

            assertThat(cookieHeader(cookies, "accessToken"))
                    .contains("HttpOnly", "SameSite=Lax", "Path=/;", "Max-Age=300")
                    .doesNotContain("Secure");
            // The refresh token must not be sent to every API endpoint
            assertThat(cookieHeader(cookies, "refreshToken"))
                    .contains("HttpOnly", "SameSite=Lax", "Path=/api/v1/users", "Max-Age=" + 7 * 24 * 60 * 60)
                    .doesNotContain("Secure");
        }

        @Test
        void cookiesAreSecureOverHttps() throws Exception {
            stubGoogle("alice@example.com");
            List<String> cookies = setCookies(
                    mockMvc.perform(loginRequest(VALID_ID_TOKEN).secure(true)).andReturn());

            assertThat(cookieHeader(cookies, "accessToken")).contains("Secure");
            assertThat(cookieHeader(cookies, "refreshToken")).contains("Secure");
        }
    }

    @Nested
    @DisplayName("Access token validation")
    class AccessToken {

        @Test
        void validTokenAuthenticatesViaHeaderOrCookie() throws Exception {
            User user = createUser("bob");

            mockMvc.perform(get(ME).with(authenticatedAs(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id", is(user.getId().intValue())));
            mockMvc.perform(get(ME).cookie(new Cookie("accessToken", accessTokenFor(user))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id", is(user.getId().intValue())));
        }

        @Test
        void bearerHeaderTakesPrecedenceOverCookie() throws Exception {
            User headerUser = createUser("header-user");
            User cookieUser = createUser("cookie-user");

            mockMvc.perform(get(ME)
                            .with(authenticatedAs(headerUser))
                            .cookie(new Cookie("accessToken", accessTokenFor(cookieUser))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id", is(headerUser.getId().intValue())));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.ddf.vodsystem.integration.AuthIT#invalidTokens")
        void invalidTokenIsRejected(String description, BiFunction<Long, String, String> factory) throws Exception {
            String token = factory.apply(createUser().getId(), jwtSecret);

            mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get(ME).cookie(new Cookie("accessToken", token)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void nonBearerAuthorizationSchemeIsIgnored() throws Exception {
            mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Basic " + accessTokenFor(createUser())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void tokenForDeletedUserIsRejected() throws Exception {
            User user = createUser();
            String token = accessTokenFor(user);
            userRepository.delete(user);

            mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /users/refresh")
    class Refresh {

        @Test
        void refreshRotatesTokensRepeatedly() throws Exception {
            stubGoogle("alice@example.com");
            Session s1 = login();
            Session s2 = refresh(s1.refreshToken());
            Session s3 = refresh(s2.refreshToken());

            assertThat(s3.refreshToken()).isNotIn(s1.refreshToken(), s2.refreshToken());
            assertThat(jwtService.validateTokenAndGetUserId(s3.accessToken()))
                    .isEqualTo(jwtService.validateTokenAndGetUserId(s1.accessToken()));
            mockMvc.perform(get(ME).cookie(new Cookie("accessToken", s3.accessToken())))
                    .andExpect(status().isOk());
        }

        @Test
        void refreshWorksWithExpiredAccessToken() throws Exception {
            stubGoogle("alice@example.com");
            Session session = login();
            Long userId = jwtService.validateTokenAndGetUserId(session.accessToken());

            mockMvc.perform(post(REFRESH)
                            .cookie(new Cookie("refreshToken", session.refreshToken()))
                            .cookie(new Cookie("accessToken", expiredJwtFor(userId))))
                    .andExpect(status().isOk());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.ddf.vodsystem.integration.AuthIT#invalidRefreshTokens")
        void invalidRefreshTokenReturns401(String description, String refreshToken) throws Exception {
            mockMvc.perform(withRefreshCookie(post(REFRESH), refreshToken))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        }

        @Test
        void reuseAfterGracePeriodRevokesOnlyThatSession() throws Exception {
            stubGoogle("alice@example.com");
            Session laptop = login();
            Session phone = login();
            Session rotated = refresh(laptop.refreshToken());

            // Push the original token's use beyond the grace period
            jdbcTemplate.update("UPDATE refresh_tokens SET used_at = ? WHERE used_at IS NOT NULL",
                    Timestamp.from(Instant.now().minusSeconds(60)));

            mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", laptop.refreshToken())))
                    .andExpect(status().isUnauthorized());

            // The revocation is committed despite the failed request, killing the newer token too
            mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", rotated.refreshToken())))
                    .andExpect(status().isUnauthorized());
            // Other sessions are unaffected
            refresh(phone.refreshToken());
        }
    }

    @Nested
    @DisplayName("POST /users/logout")
    class Logout {

        @Test
        void logoutEndsCurrentSessionAndClearsCookies() throws Exception {
            stubGoogle("alice@example.com");
            Session laptop = login();
            Session phone = login();

            MvcResult result = mockMvc.perform(post(LOGOUT)
                            .cookie(new Cookie("refreshToken", laptop.refreshToken())))
                    .andExpect(status().isOk())
                    .andReturn();

            List<String> cookies = setCookies(result);
            assertThat(cookieHeader(cookies, "accessToken")).contains("Max-Age=0", "Path=/;");
            assertThat(cookieHeader(cookies, "refreshToken")).contains("Max-Age=0", "Path=/api/v1/users");

            mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", laptop.refreshToken())))
                    .andExpect(status().isUnauthorized());
            refresh(phone.refreshToken());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.ddf.vodsystem.integration.AuthIT#invalidRefreshTokens")
        void logoutWithInvalidRefreshTokenStillSucceedsAndClearsCookies(String description, String refreshToken)
                throws Exception {
            MvcResult result = mockMvc.perform(withRefreshCookie(post(LOGOUT), refreshToken))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(cookieHeader(setCookies(result), "accessToken")).contains("Max-Age=0");
        }

        /**
         * Documents current, intended behaviour (see {@code UserService#logout}): access tokens are
         * stateless and stay valid until they expire. If server-side JWT revocation is ever added,
         * flip this assertion.
         */
        @Test
        void accessTokenRemainsValidUntilExpiryAfterLogout() throws Exception {
            stubGoogle("alice@example.com");
            Session session = login();

            mockMvc.perform(post(LOGOUT).cookie(new Cookie("refreshToken", session.refreshToken())))
                    .andExpect(status().isOk());

            mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Endpoint access rules")
    class AccessRules {

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.ddf.vodsystem.integration.AuthIT#protectedEndpoints")
        void protectedEndpointRejectsAnonymousWith401(HttpMethod method, String path) throws Exception {
            mockMvc.perform(request(method, path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void corsPreflightFromFrontendIsAllowedWithoutAuthentication() throws Exception {
            mockMvc.perform(preflight("http://localhost:5173"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        }

        @Test
        void corsPreflightFromUnknownOriginIsRejected() throws Exception {
            mockMvc.perform(preflight("https://evil.example"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }

        private MockHttpServletRequestBuilder preflight(String origin) {
            return options("/api/v1/clips")
                    .header(HttpHeaders.ORIGIN, origin)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        }
    }

    // Test data

    static Stream<Arguments> invalidTokens() {
        Instant future = Instant.now().plusSeconds(60);
        return Stream.of(
                invalidToken("expired", (id, secret) ->
                        jwt(id, ISSUER, Instant.now().minusSeconds(60), Algorithm.HMAC256(secret))),
                invalidToken("signed with wrong secret", (id, secret) ->
                        jwt(id, ISSUER, future, Algorithm.HMAC256("a-completely-different-secret-of-32-chars"))),
                invalidToken("wrong issuer", (id, secret) ->
                        jwt(id, "someone-else", future, Algorithm.HMAC256(secret))),
                invalidToken("missing userId claim", (id, secret) ->
                        jwt(null, ISSUER, future, Algorithm.HMAC256(secret))),
                invalidToken("no expiry", (id, secret) ->
                        jwt(id, ISSUER, null, Algorithm.HMAC256(secret))),
                invalidToken("alg=none", (id, secret) ->
                        jwt(id, ISSUER, future, Algorithm.none())),
                invalidToken("tampered payload", (id, secret) -> {
                    String[] parts = jwt(id, ISSUER, future, Algorithm.HMAC256(secret)).split("\\.");
                    String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                            ("{\"userId\":" + (id + 1) + ",\"iss\":\"" + ISSUER + "\",\"exp\":"
                                    + future.getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
                    return parts[0] + "." + forged + "." + parts[2];
                }),
                invalidToken("garbage", (id, secret) -> "not.a.jwt")
        );
    }

    static Stream<Arguments> invalidRefreshTokens() {
        return Stream.of(
                Arguments.of("missing", null),
                Arguments.of("malformed", "not-hex!"),
                Arguments.of("unknown", "0".repeat(64))
        );
    }

    /** One representative endpoint per controller, plus an unmapped path to prove default-deny. */
    static Stream<Arguments> protectedEndpoints() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, ME),
                Arguments.of(HttpMethod.GET, "/api/v1/clips"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/vods/1"),
                Arguments.of(HttpMethod.POST, "/api/v1/markers"),
                Arguments.of(HttpMethod.POST, "/api/v1/media/clip"),
                Arguments.of(HttpMethod.GET, "/api/v1/stream/current"),
                Arguments.of(HttpMethod.GET, "/api/v1/does-not-exist")
        );
    }

    private static Arguments invalidToken(String description, BiFunction<Long, String, String> factory) {
        return Arguments.of(description, factory);
    }

    // Helpers

    private record Session(String accessToken, String refreshToken) {}

    private static String jwt(Long userId, String issuer, Instant expiresAt, Algorithm algorithm) {
        JWTCreator.Builder builder = JWT.create().withIssuer(issuer);
        if (userId != null) builder.withClaim("userId", userId);
        if (expiresAt != null) builder.withExpiresAt(Date.from(expiresAt));
        return builder.sign(algorithm);
    }

    private String expiredJwtFor(Long userId) {
        return jwt(userId, ISSUER, Instant.now().minusSeconds(60), Algorithm.HMAC256(jwtSecret));
    }

    private GoogleUser stubGoogle(String email) {
        GoogleUser user = new GoogleUser("google-" + UUID.randomUUID(), email, "Test User", "https://pic/" + email);
        when(googleVerifierService.verify(VALID_ID_TOKEN)).thenReturn(user);
        return user;
    }

    private MockHttpServletRequestBuilder loginRequest(String idToken) throws Exception {
        return post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new Token(idToken)));
    }

    private static MockHttpServletRequestBuilder withRefreshCookie(
            MockHttpServletRequestBuilder request, String refreshToken) {
        return refreshToken == null ? request : request.cookie(new Cookie("refreshToken", refreshToken));
    }

    private Session login() throws Exception {
        return sessionFrom(mockMvc.perform(loginRequest(VALID_ID_TOKEN)).andExpect(status().isOk()).andReturn());
    }

    private Session refresh(String refreshToken) throws Exception {
        return sessionFrom(mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", refreshToken)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private static Session sessionFrom(MvcResult result) {
        List<String> cookies = setCookies(result);
        return new Session(cookieValue(cookies, "accessToken"), cookieValue(cookies, "refreshToken"));
    }

    private static List<String> setCookies(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
    }

    private static String cookieHeader(List<String> setCookies, String name) {
        return setCookies.stream()
                .filter(c -> c.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Set-Cookie for " + name + " in " + setCookies));
    }

    private static String cookieValue(List<String> setCookies, String name) {
        String header = cookieHeader(setCookies, name);
        int end = header.indexOf(';');
        return header.substring(name.length() + 1, end < 0 ? header.length() : end);
    }
}
