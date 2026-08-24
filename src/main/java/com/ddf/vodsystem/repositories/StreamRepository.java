package com.ddf.vodsystem.repositories;

import com.ddf.vodsystem.entities.Stream;
import com.ddf.vodsystem.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface StreamRepository extends JpaRepository<Stream, Long> {
    /**
     * Finds the user's active (not-yet-ended) stream, if any.
     *
     * @param user the owning user
     * @return the active stream, or empty if none
     */
    Optional<Stream> findByUserAndEndDateIsNull(User user);

    /**
     * Finds all streams belonging to the given user.
     *
     * @param user the owning user
     * @return the user's streams
     */
    List<Stream> findByUser(User user);

    /**
     * Finds active streams whose last heartbeat predates the given cutoff, i.e. stale streams.
     *
     * @param cutoff the last-seen cutoff; streams last seen before this are returned
     * @return the stale active streams
     */
    List<Stream> findByEndDateIsNullAndLastSeenBefore(Instant cutoff);
}
