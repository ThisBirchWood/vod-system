package com.ddf.vodsystem.marker;

import com.ddf.vodsystem.stream.Stream;
import com.ddf.vodsystem.user.User;
import com.ddf.vodsystem.auth.ForbiddenException;
import com.ddf.vodsystem.auth.AuthGuard;
import com.ddf.vodsystem.stream.NotStreamingException;
import com.ddf.vodsystem.stream.StreamService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class MarkerService {

    private final StreamService streamService;
    private final MarkerRepository markerRepository;

    @Value("${stream.max-length}")
    private Long maxStreamLength;

    public MarkerService(StreamService streamService, MarkerRepository markerRepository) {
        this.streamService = streamService;
        this.markerRepository = markerRepository;
    }

    /**
     * Returns the marker with the given ID, verifying ownership by {@code user}.
     *
     * @param id   the ID of the marker to retrieve
     * @param user the user requesting the marker
     * @return the matching {@link Marker}
     * @throws MarkerNotFoundException   if no marker with {@code id} exists
     * @throws ForbiddenException        if {@code user} does not own the marker
     */
    public Marker getMarkerById(Long id, User user) {
        AuthGuard.requireUser(user);
        Marker marker = markerRepository.findById(id)
                .orElseThrow(() -> new MarkerNotFoundException("Marker does not exist"));

        if (!user.getId().equals(marker.getUser().getId())) {
            throw new ForbiddenException("You are not authorized to access this marker");
        }

        return marker;
    }

    /**
     * Returns all markers belonging to the given user.
     *
     * @param user the owner of the markers
     * @return list of {@code user}'s markers
     */
    public List<Marker> getUserMarkers(User user) {
        AuthGuard.requireUser(user);
        return markerRepository.findByUser(user);
    }

    /**
     * Creates a marker timestamped now on the given user's active stream.
     *
     * @param message the marker's descriptive message
     * @param user    the user whose active stream to mark
     * @return the persisted {@link Marker}
     * @throws NotStreamingException if the user has no active stream to mark
     */
    public Marker create(String message, User user) {
        AuthGuard.requireUser(user);
        Stream stream = streamService.getActiveStream(user)
                .orElseThrow(() -> new NotStreamingException("User must be streaming to mark"));

        Marker marker = new Marker();
        marker.setStream(stream);
        marker.setUser(stream.getUser());
        marker.setMessage(message);
        marker.setTimestamp(Instant.now());
        return markerRepository.saveAndFlush(marker);
    }

    /**
     * Deletes a marker owned by {@code user}.
     *
     * @param id   the ID of the marker to delete
     * @param user the user requesting the deletion
     * @throws MarkerNotFoundException   if no marker with {@code id} exists
     * @throws ForbiddenException        if {@code user} does not own the marker
     */
    public void deleteMarker(Long id, User user) {
        Marker marker = getMarkerById(id, user);
        markerRepository.delete(marker);
    }

    /**
     * Scheduled cleanup that deletes markers older than the configured maximum stream length.
     * <p>
     * Runs periodically (every 6 minutes) to purge markers left behind by streams that have ended.
     */
    @Scheduled(fixedDelay = 360_000)
    @Transactional
    public void deleteOldMarkers() {
        Instant cutoff = Instant.now().minusSeconds(maxStreamLength);
        List<Marker> stale = markerRepository.findAllBefore(cutoff);
        markerRepository.deleteAllInBatch(stale);
    }
}
