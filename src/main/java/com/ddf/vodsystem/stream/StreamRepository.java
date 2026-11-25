package com.ddf.vodsystem.stream;

import com.ddf.vodsystem.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** Sets last-seen on the user's active stream; returns 0 if there is none. */
    @Modifying
    @Query("UPDATE Stream s SET s.lastSeen = :now WHERE s.user = :user AND s.endDate IS NULL")
    int heartbeatActive(@Param("user") User user, @Param("now") Instant now);

    /** Ends the user's active stream; returns 0 if there is none. */
    @Modifying
    @Query("UPDATE Stream s SET s.endDate = :now WHERE s.user = :user AND s.endDate IS NULL")
    int endActive(@Param("user") User user, @Param("now") Instant now);
}
