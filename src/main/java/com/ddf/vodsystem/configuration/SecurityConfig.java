package com.ddf.vodsystem.configuration;

import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;

import com.ddf.vodsystem.security.JwtFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final JwtFilter jwtFilter;

    public SecurityConfig(JwtFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    /**
     * Builds the security filter chain: stateless sessions, CSRF disabled, the {@link JwtFilter}
     * installed, and per-endpoint authorization rules.
     *
     * @param http the HTTP security builder to configure
     * @return the built {@link SecurityFilterChain}
     * @throws Exception if the security configuration cannot be built
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/api/v1/**").permitAll()
                        .requestMatchers("/api/v1/media/compress").permitAll() // compression available without authenticated
                        .requestMatchers("/api/v1/jobs/**").permitAll() // same with jobs
                        .requestMatchers("/api/v1/media/**").authenticated()
                        .requestMatchers("/api/v1/users/me", "/api/v1/users/logout").authenticated()
                        .requestMatchers("/api/v1/users/login").permitAll()
                        .requestMatchers("/api/v1/stream/start", "/api/v1/stream/stop", "/api/v1/stream/heartbeat").permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                );

        return http.build();
    }

}