package com.ddf.vodsystem.controllers;

import com.ddf.vodsystem.dto.APIResponse;
import com.ddf.vodsystem.controllers.dto.SessionResponse;
import com.ddf.vodsystem.controllers.dto.Token;
import com.ddf.vodsystem.dto.TokenPackage;
import com.ddf.vodsystem.dto.properties.AuthProperties;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.exceptions.NotAuthenticated;
import com.ddf.vodsystem.services.UserService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService userService;

    private final Duration jwtExpiration;
    private final Duration refreshTokenExpiration;
    private static final String SUCCESS = "success";

    private static final String ACCESS_COOKIE_PATH = "/";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/users";

    public UserController(UserService userService,
                          AuthProperties props) {
        this.userService = userService;
        this.jwtExpiration = props.expiration().jwt();
        this.refreshTokenExpiration = props.expiration().refreshToken();
    }

    /**
     * Returns the currently authenticated user's profile.
     *
     * @return {@code 200 OK} wrapping the authenticated {@link User}
     * @throws NotAuthenticated if no valid session is present
     */
    @GetMapping("/me")
    public ResponseEntity<APIResponse<User>> user() {
        Optional<User> user = userService.getLoggedInUser();

        if (user.isEmpty()) {
            throw new NotAuthenticated("User not authenticated");
        }

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS, "User retrieved successfully", user.get())
        );
    }

    /**
     * Authenticates a user from a Google ID token and starts a session.
     * <p>
     * Sets two HttpOnly cookies: {@code accessToken} (the JWT, path {@code /}) and {@code refreshToken}
     * (path {@code /api/v1/users}). Neither token is returned in the response body.
     *
     * @param token    the payload carrying the Google ID token to verify
     * @param request  the incoming request, used to decide the cookies' {@code Secure} flag
     * @param response the response the cookies are written to
     * @return {@code 200 OK} wrapping the access token lifetime as a {@link SessionResponse}
     * @throws NotAuthenticated if the Google ID token is rejected
     */
    @PostMapping("/login")
    public ResponseEntity<APIResponse<SessionResponse>> login(@RequestBody Token token,
                                                           HttpServletRequest request,
                                                           HttpServletResponse response) {
        TokenPackage tokenPackage = userService.login(token.token());
        addCookies(request, response, tokenPackage.accessToken(), tokenPackage.refreshToken());

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS, "Logged in successfully", sessionResponse())
        );
    }

    /**
     * Exchanges the {@code refreshToken} cookie for a new refresh token and JWT, replacing both cookies.
     *
     * @param refreshToken the refresh token from the {@code refreshToken} cookie, if present
     * @param request      the incoming request, used to decide the cookies' {@code Secure} flag
     * @param response     the response the new cookies are written to
     * @return {@code 200 OK} wrapping the new access token lifetime as a {@link SessionResponse}
     * @throws NotAuthenticated if the refresh token is missing or rejected
     */
    @PostMapping("/refresh")
    public ResponseEntity<APIResponse<SessionResponse>> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        TokenPackage tokenPackage = userService.refresh(refreshToken);
        addCookies(request, response, tokenPackage.accessToken(), tokenPackage.refreshToken());

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS, "Refreshed tokens successfully", sessionResponse())
        );
    }

    /**
     * Logs the user out by revoking the session tied to the {@code refreshToken} cookie and clearing
     * both auth cookies. Succeeds even if no refresh token cookie is present.
     *
     * @param refreshToken the refresh token from the {@code refreshToken} cookie, if present
     * @param request      the incoming request, used to decide the cookies' {@code Secure} flag
     * @param response     the response the expired cookies are written to
     * @return {@code 200 OK} confirming logout
     */
    @PostMapping("/logout")
    public ResponseEntity<APIResponse<Void>> logout(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        userService.logout(refreshToken);
        writeCookies(request, response, "", Duration.ZERO, "", Duration.ZERO);

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS, "Logged out successfully", null)
        );
    }

    private SessionResponse sessionResponse() {
        return new SessionResponse(jwtExpiration.getSeconds());
    }

    private void addCookies(
            HttpServletRequest request,
            HttpServletResponse response,
            String accessToken,
            String refreshToken) {
        writeCookies(request, response, accessToken, jwtExpiration, refreshToken, refreshTokenExpiration);
    }


    private void writeCookies(
            HttpServletRequest request,
            HttpServletResponse response,
            String accessToken,
            Duration accessTokenMaxAge,
            String refreshToken,
            Duration refreshTokenMaxAge) {
        ResponseCookie accessTokenCookie = ResponseCookie.from("accessToken", accessToken)
                .httpOnly(true)
                .maxAge(accessTokenMaxAge)
                .sameSite("Lax")
                .secure(request.isSecure())
                .path(ACCESS_COOKIE_PATH)
                .build();

        ResponseCookie refreshTokenCookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .maxAge(refreshTokenMaxAge)
                .sameSite("Lax")
                .secure(request.isSecure())
                .path(REFRESH_COOKIE_PATH)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, accessTokenCookie.toString());
    }
}
