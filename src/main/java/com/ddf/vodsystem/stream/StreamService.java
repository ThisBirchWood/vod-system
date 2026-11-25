package com.ddf.vodsystem.stream;

import com.ddf.vodsystem.user.User;
import com.ddf.vodsystem.job.KeyNotFoundException;
import com.ddf.vodsystem.auth.ForbiddenException;
import com.ddf.vodsystem.auth.AuthGuard;
import com.ddf.vodsystem.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
     * @throws KeyNotFoundException      if no user owns {@code streamKey}
     * @throws AlreadyStreamingException if the user already has an active stream, including when a
     *                          concurrent start wins the race
     */
    public Stream startStream(String streamKey) {
        User user = resolveUser(streamKey);

        streamRepository.findByUserAndEndDateIsNull(user).ifPresent(existing -> {
            throw new AlreadyStreamingException("User " + user.getUsername() + " is already streaming.");
        });

        Instant now = Instant.now();
        Stream stream = new Stream();
        stream.setUser(user);
        stream.setStartDate(now);
        stream.setLastSeen(now);

        try {
            return streamRepository.saveAndFlush(stream);
        } catch (DataIntegrityViolationException e) {
            // A concurrent start won the race; the partial unique index allows one active stream per user
            if (streamRepository.findByUserAndEndDateIsNull(user).isPresent()) {
                throw new AlreadyStreamingException("User " + user.getUsername() + " is already streaming.");
            }
            throw e;
        }
    }

    /**
     * Ends the active stream for the user identified by the given stream key.
     *
     * @param streamKey the stream key resolving to the broadcasting user
     * @throws KeyNotFoundException           if no user owns {@code streamKey}
     * @throws NotStreamingException          if the user has no active stream
     */
    @Transactional
    public void endStream(String streamKey) {
        User user = resolveUser(streamKey);
        if (streamRepository.endActive(user, Instant.now()) == 0) {
            throw new NotStreamingException("No active stream found for user " + user.getUsername());
        }
    }

    /**
     * Records a liveness heartbeat on the active stream, refreshing its last-seen time.
     *
     * @param streamKey the stream key resolving to the broadcasting user
     * @throws KeyNotFoundException           if no user owns {@code streamKey}
     * @throws NotStreamingException          if the user has no active stream
     */
    @Transactional
    public void heartbeatStream(String streamKey) {
        User user = resolveUser(streamKey);
        if (streamRepository.heartbeatActive(user, Instant.now()) == 0) {
            throw new NotStreamingException("No active stream found for user " + user.getUsername());
        }
        logger.debug("Heartbeat sent for user {}", user.getUsername());
    }

    /**
     * Returns the given user's active (not-yet-ended) stream, if any.
     *
     * @param user the user whose active stream to look up
     * @return the active {@link Stream}, or empty if the user is not currently streaming
     */
    public Optional<Stream> getActiveStream(User user) {
        AuthGuard.requireUser(user);
        return streamRepository.findByUserAndEndDateIsNull(user);
    }

    /**
     * Returns the full stream history for the given user.
     *
     * @param userId the ID of the user whose history to retrieve
     * @param user   the user requesting the history
     * @return the user's streams, past and present
     * @throws ForbiddenException if {@code user} is not the user identified by {@code userId}
     */
    public List<Stream> getStreamHistory(Long userId, User user) {
        AuthGuard.requireUser(user);
        if (!user.getId().equals(userId)) {
            throw new ForbiddenException("You are not authorized to see these user streams");
        }

        return streamRepository.findByUser(user);
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
                .orElseThrow(() -> new KeyNotFoundException("Stream key not found: " + streamKey));
    }
}
