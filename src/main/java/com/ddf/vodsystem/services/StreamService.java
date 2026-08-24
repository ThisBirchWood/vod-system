package com.ddf.vodsystem.services;

import com.ddf.vodsystem.entities.Stream;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.exceptions.AlreadyStreaming;
import com.ddf.vodsystem.exceptions.KeyNotFound;
import com.ddf.vodsystem.exceptions.NotAuthenticated;
import com.ddf.vodsystem.repositories.StreamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class StreamService {
    private static final Logger logger = LoggerFactory.getLogger(StreamService.class);

    private static final int HEARTBEAT_TIMEOUT_SECONDS = 60;

    private final StreamRepository streamRepository;
    private final UserService userService;

    public StreamService(StreamRepository streamRepository,
                         UserService userService) {
        this.streamRepository = streamRepository;
        this.userService = userService;
    }

    /**
     * Starts a new stream for the user identified by the given stream key.
     *
     * @param streamKey the stream key resolving to the broadcasting user
     * @return the newly persisted {@link Stream}
     * @throws KeyNotFound      if no user owns {@code streamKey}
     * @throws AlreadyStreaming if the user already has an active stream
     */
    public Stream startStream(String streamKey) {
        User user = resolveUser(streamKey);

        streamRepository.findByUserAndEndDateIsNull(user).ifPresent(existing -> {
            throw new AlreadyStreaming("User " + user.getUsername() + " is already streaming.");
        });

        Instant now = Instant.now();
        Stream stream = new Stream();
        stream.setUser(user);
        stream.setStartDate(now);
        stream.setLastSeen(now);
        return streamRepository.saveAndFlush(stream);
    }

    /**
     * Ends the active stream for the user identified by the given stream key.
     *
     * @param streamKey the stream key resolving to the broadcasting user
     * @throws KeyNotFound           if no user owns {@code streamKey}
     * @throws IllegalStateException if the user has no active stream
     */
    public void endStream(String streamKey) {
        User user = resolveUser(streamKey);

        Stream stream = streamRepository.findByUserAndEndDateIsNull(user)
                .orElseThrow(() -> new IllegalStateException(
                        "No active stream found for user " + user.getUsername()));

        stream.setEndDate(Instant.now());
        streamRepository.saveAndFlush(stream);
    }

    /**
     * Records a liveness heartbeat on the active stream, refreshing its last-seen time.
     *
     * @param streamKey the stream key resolving to the broadcasting user
     * @throws KeyNotFound           if no user owns {@code streamKey}
     * @throws IllegalStateException if the user has no active stream
     */
    public void heartbeatStream(String streamKey) {
        User user = resolveUser(streamKey);

        Stream stream = streamRepository.findByUserAndEndDateIsNull(user)
                .orElseThrow(() -> new IllegalStateException(
                        "No active stream found for user " + user.getUsername()));

        stream.setLastSeen(Instant.now());
        streamRepository.saveAndFlush(stream);
    }

    /**
     * Returns the current user's active (not-yet-ended) stream, if any.
     *
     * @return the active {@link Stream}, or empty if the user is not currently streaming
     * @throws NotAuthenticated if no user session is present
     */
    public Optional<Stream> getActiveStream() {
        Optional<User> user = userService.getLoggedInUser();

        if (user.isEmpty()) {
            throw new NotAuthenticated("Log in to see user streams");
        }

        return streamRepository.findByUserAndEndDateIsNull(user.get());
    }

    /**
     * Returns the full stream history for the given user.
     *
     * @param userId the ID of the user whose history to retrieve
     * @return the user's streams, past and present
     * @throws IllegalArgumentException if no user with {@code userId} exists
     * @throws NotAuthenticated         if no user is authenticated, or the caller is not that user
     */
    public List<Stream> getStreamHistory(Long userId) {
        User streamUser = userService.getUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        Optional<User> user = userService.getLoggedInUser();

        if (user.isEmpty()) {
            throw new NotAuthenticated("Log in to see user streams");
        }

        if (!user.get().equals(streamUser)) {
            throw new NotAuthenticated("You are not authenticated to see these user streams");
        }

        return streamRepository.findByUser(streamUser);
    }

    /**
     * Scheduled sweep that ends streams which have missed heartbeats past the timeout.
     * <p>
     * Runs every 30 seconds; any active stream whose last heartbeat is older than
     * {@value #HEARTBEAT_TIMEOUT_SECONDS} seconds is marked ended.
     */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void endStaleStreams() {
        Instant cutoff = Instant.now().minusSeconds(HEARTBEAT_TIMEOUT_SECONDS);
        List<Stream> stale = streamRepository.findByEndDateIsNullAndLastSeenBefore(cutoff);
        if (stale.isEmpty()) return;

        Instant now = Instant.now();
        for (Stream stream : stale) {
            stream.setEndDate(now);
            logger.warn("Stream {} for user {} ended due to heartbeat timeout", stream.getId(), stream.getUser().getUsername());
        }
        streamRepository.saveAllAndFlush(stale);
    }

    private User resolveUser(String streamKey) {
        return userService.getUserByStreamKey(streamKey)
                .orElseThrow(() -> new KeyNotFound("Stream key not found: " + streamKey));
    }
}
