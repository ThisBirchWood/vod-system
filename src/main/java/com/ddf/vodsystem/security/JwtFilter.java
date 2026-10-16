package com.ddf.vodsystem.security;

import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.services.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Component
public class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserService userService;
    private static final Logger logger = LoggerFactory.getLogger(JwtFilter.class);

    public JwtFilter(JwtService jwtService,
                     UserService userService) {
        this.jwtService = jwtService;
        this.userService = userService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String jwt = null;

        // 1. Try to get the JWT from the Authorization header
        String authorizationHeader = request.getHeader("Authorization");
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            logger.debug("JWT found in Authorization header");
            jwt = authorizationHeader.substring(7);
        }

        // 2. If no JWT was found in the header, try to get it from a cookie
        if (jwt == null) {
            logger.debug("JWT not found in Authorization header, checking cookies");
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                jwt = Arrays.stream(cookies)
                        .filter(cookie -> "accessToken".equals(cookie.getName()))
                        .map(Cookie::getValue)
                        .findFirst()
                        .orElse(null);
            }
        }

        Optional<Authentication> authentication = getAuthentication(jwt);
        authentication.ifPresent(auth -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
        });

        filterChain.doFilter(request, response);
    }

    private Optional<Authentication> getAuthentication(String jwt) {
        if (jwt == null || jwt.isEmpty()) {
            return Optional.empty();
        }

        Long userId = jwtService.validateTokenAndGetUserId(jwt);
        if (userId == null) {
            return Optional.empty();
        }

        Optional<User> user = userService.getUserById(userId);
        if (user.isEmpty()) {
            return Optional.empty();
        }

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                user.get(),
                jwt,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        return Optional.of(authentication);
    }
}
