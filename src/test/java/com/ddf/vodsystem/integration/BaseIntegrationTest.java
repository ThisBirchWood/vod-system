package com.ddf.vodsystem.integration;

import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.repositories.UserRepository;
import com.ddf.vodsystem.security.GoogleVerifierService;
import com.ddf.vodsystem.security.JwtService;
import com.ddf.vodsystem.services.JobRegistryService;
import com.ddf.vodsystem.services.media.ThumbnailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Shared setup for integration tests. Extend this class and name the subclass {@code *IT}
 * so it is picked up by the Failsafe plugin ({@code ./mvnw verify}) rather than Surefire.
 *
 * <p>Provides:
 * <ul>
 *     <li>The full Spring context with {@link MockMvc}, using the {@code integration} profile</li>
 *     <li>A real Postgres database (Testcontainers) with Flyway migrations applied. The container
 *         is started once per JVM and shared by every subclass</li>
 *     <li>Throwaway media/stream/temp directories for the run</li>
 *     <li>A mocked {@link GoogleVerifierService}, so login can be driven without Google</li>
 *     <li>A spied {@link ThumbnailService}, which runs ffmpeg for real unless a test stubs it to fail</li>
 *     <li>A spied {@link JobRegistryService}</li>
 *     <li>Helpers for creating users and authenticating requests as them</li>
 *     <li>Database cleanup after every test</li>
 * </ul>
 *
 * <p>Subclasses should avoid declaring their own {@code @MockitoBean}s or extra
 * {@code @TestPropertySource}s where possible: each unique configuration creates a new
 * Spring context, which slows the suite down. Add shared mocks here instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
public abstract class BaseIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    private static final Path MEDIA_ROOT;

    static {
        // Started manually (not via @Container) so a single container outlives each test class
        POSTGRES.start();
        try {
            MEDIA_ROOT = Files.createTempDirectory("vodsystem-it-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void mediaDirectories(DynamicPropertyRegistry registry) {
        registry.add("media.users", () -> MEDIA_ROOT.resolve("users") + "/");
        registry.add("media.streams", () -> MEDIA_ROOT.resolve("streams") + "/");
        registry.add("media.temp.inputs", () -> MEDIA_ROOT.resolve("inputs") + "/");
        registry.add("media.temp.outputs", () -> MEDIA_ROOT.resolve("outputs") + "/");
    }

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected UserRepository userRepository;
    @Autowired protected JwtService jwtService;

    /** Reset after every test. Stub {@code verify(idToken)} to simulate a Google login. */
    @MockitoBean protected GoogleVerifierService googleVerifierService;

    /** Real ffmpeg by default and reset after every test. Stub it to simulate a thumbnail failure. */
    @MockitoSpyBean protected ThumbnailService thumbnailService;

    @MockitoSpyBean protected JobRegistryService jobRegistryService;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE markers, clips, vods, streams, refresh_tokens, token_families, users
                RESTART IDENTITY CASCADE
                """);
    }

    /** Root of the per-run media directories, for asserting on files written by the app. */
    protected static Path mediaRoot() {
        return MEDIA_ROOT;
    }

    /** Persists a user with unique identifiers. */
    protected User createUser() {
        return createUser("user-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /** Persists a user with the given username and otherwise unique identifiers. */
    protected User createUser(String username) {
        User user = new User();
        user.setGoogleId("google-" + UUID.randomUUID());
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setName(username);
        user.setRole(0);
        user.setStreamKey(UUID.randomUUID().toString().replace("-", ""));
        return userRepository.save(user);
    }

    /** A valid access token for the given user. */
    protected String accessTokenFor(User user) {
        return jwtService.generateToken(user.getId());
    }

    /**
     * Authenticates a MockMvc request as the given user via a real JWT, exercising {@code JwtFilter}.
     * Usage: {@code mockMvc.perform(get("/api/v1/clips").with(authenticatedAs(user)))}
     */
    protected RequestPostProcessor authenticatedAs(User user) {
        String token = accessTokenFor(user);
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }
}
