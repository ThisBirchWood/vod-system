package com.ddf.vodsystem.controllers;

import com.ddf.vodsystem.dto.APIResponse;
import com.ddf.vodsystem.controllers.dto.Token;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.exceptions.NotAuthenticated;
import com.ddf.vodsystem.services.UserService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService userService;

    @Value("${jwt.expiration}")
    private long jwtExpiration;
    private static final String SUCCESS = "success";

    public UserController(UserService userService) {
        this.userService = userService;
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
     * Authenticates a user from a Google ID token and issues a session JWT.
     * <p>
     * The JWT is set as an HttpOnly {@code token} cookie (scoped by request security) and also
     * returned in the response body.
     *
     * @param token    the payload carrying the Google ID token to verify
     * @param request  the incoming request, used to decide cookie {@code Secure}/{@code SameSite} flags
     * @param response the response the session cookie is written to
     * @return {@code 200 OK} wrapping the issued JWT as a {@link Token}
     */
    @PostMapping("/login")
    public ResponseEntity<APIResponse<Token>> login(@RequestBody Token token,
		    				    HttpServletRequest request,
                                                    HttpServletResponse response) {
        String jwt = userService.login(token.token());

        ResponseCookie cookie = ResponseCookie.from("token", jwt)
                .httpOnly(true)
                .maxAge(jwtExpiration / 1000)
                .sameSite(request.isSecure() ? "None" : "Lax")
                .secure(request.isSecure())
                .path("/")
                .build();

        response.addHeader("Set-Cookie", cookie.toString());

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS, "Logged in successfully", new Token(jwt))
        );
    }

    /**
     * Logs the user out by clearing the session cookie.
     *
     * @param response the response the expired cookie is written to
     * @param request  the incoming request, used to match the original cookie's {@code Secure}/{@code SameSite} flags
     * @return {@code 200 OK} confirming logout
     */
    @PostMapping("/logout")
    public ResponseEntity<APIResponse<Void>> logout(HttpServletResponse response,
		    				    HttpServletRequest request) {
        ResponseCookie cookie = ResponseCookie.from("token", "")
                .httpOnly(true)
                .maxAge(0)
                .sameSite(request.isSecure() ? "None" : "Lax")
                .secure(request.isSecure())
                .path("/")
                .build();

        response.addHeader("Set-Cookie", cookie.toString());

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS, "Logged out successfully", null)
        );
    }
}
