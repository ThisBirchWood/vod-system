package com.ddf.vodsystem.services;

import com.ddf.vodsystem.entities.Marker;
import com.ddf.vodsystem.entities.Stream;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.exceptions.MarkerNotFound;
import com.ddf.vodsystem.exceptions.NotAuthenticated;
import com.ddf.vodsystem.exceptions.NotStreaming;
import com.ddf.vodsystem.repositories.MarkerRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class MarkerService {

    private final UserService userService;
    private final StreamService streamService;
    private final MarkerRepository markerRepository;

    @Value("${stream.max-length}")
    private Long maxStreamLength;

    public MarkerService(UserService userService, StreamService streamService, MarkerRepository markerRepository) {
        this.userService = userService;
        this.streamService = streamService;
        this.markerRepository = markerRepository;
    }

    /**
     * Returns the marker with the given ID, verifying ownership by the current user.
     *
     * @param id the ID of the marker to retrieve
     * @return the matching {@link Marker}
     * @throws MarkerNotFound   if no marker with {@code id} exists
     * @throws NotAuthenticated if the current user does not own the marker
     */
    public Marker getMarkerById(Long id) {
        Optional<Marker> marker = markerRepository.findById(id);

        if (marker.isEmpty()) {
            throw new MarkerNotFound("Marker does not exist");
        }

        Optional<User> user = userService.getLoggedInUser();
        if (user.isEmpty() || !user.get().equals(marker.get().getUser())) {
            throw new NotAuthenticated("User not authenticated for this marker");
        }

        return marker.get();
    }

    /**
     * Returns all markers belonging to the currently authenticated user.
     *
     * @return list of the current user's markers
     * @throws NotAuthenticated if no user session is present
     */
    public List<Marker> getUserMarkers() {
        Optional<User> user = userService.getLoggedInUser();

        if (user.isEmpty()) {
            throw new NotAuthenticated("Must be logged in to get markers");
        }

        return markerRepository.findByUser(user.get());
    }

    /**
     * Creates a marker timestamped now on the current user's active stream.
     *
     * @param message the marker's descriptive message
     * @return the persisted {@link Marker}
     * @throws NotStreaming if the user has no active stream to mark
     */
    public Marker create(String message) {
        Optional<Stream> stream = streamService.getActiveStream();

        if (stream.isEmpty()) {
            throw new NotStreaming("User must be streaming to mark");
        }

        Marker marker = new Marker();
        marker.setStream(stream.get());
        marker.setUser(stream.get().getUser());
        marker.setMessage(message);
        marker.setTimestamp(Instant.now());
        return markerRepository.saveAndFlush(marker);
    }

    /**
     * Deletes a marker owned by the current user.
     *
     * @param id the ID of the marker to delete
     * @throws MarkerNotFound   if no marker with {@code id} exists
     * @throws NotAuthenticated if the current user does not own the marker
     */
    public void deleteMarker(Long id) {
        Marker marker = getMarkerById(id);
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
