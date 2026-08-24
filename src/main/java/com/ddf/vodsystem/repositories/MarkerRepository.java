package com.ddf.vodsystem.repositories;

import com.ddf.vodsystem.entities.Marker;
import com.ddf.vodsystem.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface MarkerRepository extends JpaRepository<Marker, Long> {
    /**
     * Finds all markers owned by the given user.
     *
     * @param user the owning user
     * @return the user's markers
     */
    @Query("SELECT m FROM Marker m WHERE m.user = ?1")
    List<Marker> findByUser(User user);

    /**
     * Finds all markers timestamped at or before the given instant.
     *
     * @param time the cutoff instant (inclusive)
     * @return markers at or before {@code time}
     */
    @Query("SELECT m FROM Marker m WHERE m.timestamp <= ?1")
    List<Marker> findAllBefore(Instant time);
}
