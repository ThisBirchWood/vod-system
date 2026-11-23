package com.ddf.vodsystem.integration;

import com.ddf.vodsystem.entities.Stream;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.repositories.StreamRepository;
import com.ddf.vodsystem.services.StreamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of the stream lifecycle: the nginx RTMP callbacks ({@code on_publish},
 * {@code on_publish_done}, {@code on_update}), the stale-stream reaper, and the user-facing
 * read endpoints.
 *
 * <p>The callback endpoints are {@code permitAll} and identified only by stream key, so none of
 * the callback requests here carry a JWT. They rely on the backend being reachable only by
 * nginx, not on authentication.
 */
class StreamingIT extends BaseIntegrationTest {

    private static final String START = "/api/v1/stream/start";
    private static final String STOP = "/api/v1/stream/stop";
    private static final String HEARTBEAT = "/api/v1/stream/heartbeat";
    private static final String CURRENT = "/api/v1/stream/current";
    private static final String HISTORY = "/api/v1/stream/history/";

    @Autowired private StreamRepository streamRepository;
    @Autowired private StreamService streamService;

    @Nested
    @DisplayName("nginx callbacks")
    class Callbacks {

        @Test
        void startCreatesActiveStream() throws Exception {
            User user = createUser();
            Instant before = Instant.now();

            mockMvc.perform(callback(START, user.getStreamKey()))
                    .andExpect(status().isOk());

            assertThat(streamRepository.count()).isEqualTo(1);
            Stream stream = activeStream(user);
            assertThat(stream.getStartDate()).isCloseTo(before, within(5, ChronoUnit.SECONDS));
            assertThat(stream.getLastSeen()).isEqualTo(stream.getStartDate());
            assertThat(stream.getEndDate()).isNull();
        }

        @Test
        void heartbeatAdvancesLastSeen() throws Exception {
            User user = createUser();
            Stream stream = startStream(user);
            Instant backdated = setLastSeen(stream, Instant.now().minusSeconds(30));

            mockMvc.perform(callback(HEARTBEAT, user.getStreamKey()))
                    .andExpect(status().isOk());

            Stream updated = activeStream(user);
            assertThat(updated.getId()).isEqualTo(stream.getId());
            assertThat(updated.getLastSeen()).isAfter(backdated);
        }

        @Test
        void stopEndsStream() throws Exception {
            User user = createUser();
            Stream stream = startStream(user);

            mockMvc.perform(callback(STOP, user.getStreamKey()))
                    .andExpect(status().isOk());

            assertThat(streamRepository.findByUserAndEndDateIsNull(user)).isEmpty();
            assertThat(streamRepository.findById(stream.getId()).orElseThrow().getEndDate()).isNotNull();
        }

        @Test
        void restartAfterStopCreatesNewStream() throws Exception {
            User user = createUser();
            Stream first = startStream(user);
            stopStream(user);

            Stream second = startStream(user);

            assertThat(second.getId()).isNotEqualTo(first.getId());
            assertThat(streamRepository.findByUser(user)).hasSize(2);
            assertThat(activeStream(user).getId()).isEqualTo(second.getId());
        }

        @Test
        void startWhileLiveReturns409AndKeepsOneStream() throws Exception {
            User user = createUser();
            Stream stream = startStream(user);

            mockMvc.perform(callback(START, user.getStreamKey()))
                    .andExpect(status().isConflict());

            assertThat(streamRepository.count()).isEqualTo(1);
            assertThat(activeStream(user).getId()).isEqualTo(stream.getId());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {START, STOP, HEARTBEAT})
        void unknownKeyReturns404(String path) throws Exception {
            createUser();

            mockMvc.perform(callback(path, "not-a-real-stream-key"))
                    .andExpect(status().isNotFound());

            assertThat(streamRepository.count()).isZero();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {START, STOP, HEARTBEAT})
        void missingKeyReturns400(String path) throws Exception {
            mockMvc.perform(post(path))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void stopWithoutActiveStreamReturns409() throws Exception {
            User user = createUser();

            mockMvc.perform(callback(STOP, user.getStreamKey()))
                    .andExpect(status().isConflict());
        }

        /**
         * Also covers a heartbeat arriving after the reaper has ended the stream. Any non-2xx
         * response makes nginx drop the publisher, which is what we want for a stream we consider
         * dead.
         */
        @Test
        void heartbeatWithoutActiveStreamReturns409() throws Exception {
            User user = createUser();

            mockMvc.perform(callback(HEARTBEAT, user.getStreamKey()))
                    .andExpect(status().isConflict());
        }

        @Test
        void concurrentStartsCreateOnlyOneStream() throws Exception {
            User user = createUser();
            int threads = 8;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);

            try {
                List<Future<Integer>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return mockMvc.perform(callback(START, user.getStreamKey()))
                                .andReturn().getResponse().getStatus();
                    }));
                }
                ready.await();
                go.countDown();

                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> result : results) {
                    statuses.add(result.get(30, TimeUnit.SECONDS));
                }

                assertThat(statuses).containsOnlyOnce(200);
                assertThat(statuses).filteredOn(s -> s != 200).containsOnly(409);
                assertThat(streamRepository.findByUser(user)).hasSize(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("Stale stream reaper")
    class Reaper {

        @Test
        void streamPastHeartbeatTimeoutIsEnded() throws Exception {
            User user = createUser();
            Stream stream = startStream(user);
            setLastSeen(stream, Instant.now().minusSeconds(120));

            streamService.endStaleStreams();

            assertThat(streamRepository.findById(stream.getId()).orElseThrow().getEndDate()).isNotNull();
            mockMvc.perform(get(CURRENT).with(authenticatedAs(user)))
                    .andExpect(jsonPath("$.data.isStreaming", is(false)));
        }

        @Test
        void streamWithinHeartbeatTimeoutStaysLive() throws Exception {
            User user = createUser();
            Stream stream = startStream(user);
            // Well inside the 60s timeout, so a slow run can't push it over
            setLastSeen(stream, Instant.now().minusSeconds(30));

            streamService.endStaleStreams();

            assertThat(streamRepository.findById(stream.getId()).orElseThrow().getEndDate()).isNull();
        }

        @Test
        void alreadyEndedStreamKeepsOriginalEndTime() throws Exception {
            User user = createUser();
            Stream stream = startStream(user);
            stopStream(user);
            setLastSeen(stream, Instant.now().minusSeconds(120));
            Instant endedAt = streamRepository.findById(stream.getId()).orElseThrow().getEndDate();

            streamService.endStaleStreams();

            assertThat(streamRepository.findById(stream.getId()).orElseThrow().getEndDate()).isEqualTo(endedAt);
        }

        @Test
        void startAfterReapCreatesNewStream() throws Exception {
            User user = createUser();
            Stream reaped = startStream(user);
            setLastSeen(reaped, Instant.now().minusSeconds(120));
            streamService.endStaleStreams();

            Stream fresh = startStream(user);

            assertThat(fresh.getId()).isNotEqualTo(reaped.getId());
        }

        @Test
        void onlyStaleStreamsAreEnded() throws Exception {
            User staleUser = createUser();
            User liveUser = createUser();
            Stream stale = startStream(staleUser);
            Stream live = startStream(liveUser);
            setLastSeen(stale, Instant.now().minusSeconds(120));

            streamService.endStaleStreams();

            assertThat(streamRepository.findById(stale.getId()).orElseThrow().getEndDate()).isNotNull();
            assertThat(streamRepository.findById(live.getId()).orElseThrow().getEndDate()).isNull();
        }
    }

    @Nested
    @DisplayName("GET /stream/current")
    class Current {

        @Test
        void notLiveReportsNoStream() throws Exception {
            User user = createUser();

            mockMvc.perform(get(CURRENT).with(authenticatedAs(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isStreaming", is(false)))
                    .andExpect(jsonPath("$.data.id", nullValue()));
        }

        @Test
        void liveReportsActiveStreamId() throws Exception {
            User user = createUser();
            Stream stream = startStream(user);

            mockMvc.perform(get(CURRENT).with(authenticatedAs(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isStreaming", is(true)))
                    .andExpect(jsonPath("$.data.id", is(stream.getId().intValue())));
        }

        @Test
        void reportsOnlyTheCallersStream() throws Exception {
            User streamer = createUser();
            User viewer = createUser();
            startStream(streamer);

            mockMvc.perform(get(CURRENT).with(authenticatedAs(viewer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isStreaming", is(false)));
        }

        @Test
        void reflectsFullLifecycle() throws Exception {
            User user = createUser();

            startStream(user);
            mockMvc.perform(get(CURRENT).with(authenticatedAs(user)))
                    .andExpect(jsonPath("$.data.isStreaming", is(true)));

            stopStream(user);
            mockMvc.perform(get(CURRENT).with(authenticatedAs(user)))
                    .andExpect(jsonPath("$.data.isStreaming", is(false)));
        }
    }

    @Nested
    @DisplayName("GET /stream/history/{userId}")
    class History {

        @Test
        void ownHistoryReturnsPastAndCurrentStreams() throws Exception {
            User user = createUser();
            Stream past = startStream(user);
            stopStream(user);
            Stream current = startStream(user);

            String body = mockMvc.perform(get(HISTORY + user.getId()).with(authenticatedAs(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()", is(2)))
                    .andExpect(jsonPath("$.data[*].id",
                            containsInAnyOrder(past.getId().intValue(), current.getId().intValue())))
                    .andExpect(jsonPath("$.data[0].user").doesNotExist())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).as("owner details must not leak through the stream")
                    .doesNotContain(user.getStreamKey())
                    .doesNotContain(user.getGoogleId());
        }

        @Test
        void historyExcludesOtherUsersStreams() throws Exception {
            User user = createUser();
            User other = createUser();
            startStream(other);

            mockMvc.perform(get(HISTORY + user.getId()).with(authenticatedAs(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()", is(0)));
        }

        @Test
        void anotherUsersHistoryIsRejected() throws Exception {
            User owner = createUser();
            User snooper = createUser();
            startStream(owner);

            mockMvc.perform(get(HISTORY + owner.getId()).with(authenticatedAs(snooper)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // Helpers

    private static MockHttpServletRequestBuilder callback(String path, String streamKey) {
        return post(path).param("name", streamKey);
    }

    private Stream startStream(User user) throws Exception {
        mockMvc.perform(callback(START, user.getStreamKey())).andExpect(status().isOk());
        return activeStream(user);
    }

    private void stopStream(User user) throws Exception {
        mockMvc.perform(callback(STOP, user.getStreamKey())).andExpect(status().isOk());
    }

    private Stream activeStream(User user) {
        return streamRepository.findByUserAndEndDateIsNull(user)
                .orElseThrow(() -> new AssertionError("No active stream for user " + user.getId()));
    }

    /** Sets a stream's last heartbeat directly in the database, returning the value written. */
    private Instant setLastSeen(Stream stream, Instant lastSeen) {
        Instant truncated = lastSeen.truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update("UPDATE streams SET last_seen = ? WHERE id = ?", Timestamp.from(truncated), stream.getId());
        return truncated;
    }
}
