package com.ddf.vodsystem.repositories;

import com.ddf.vodsystem.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * Finds a user by their Google account ID.
     *
     * @param googleId the Google subject identifier
     * @return the matching user, or empty if none
     */
    @Query("SELECT u FROM User u WHERE u.googleId = ?1")
    Optional<User> findByGoogleId(String googleId);

    /**
     * Finds a user by their stream key.
     *
     * @param streamKey the stream key to match
     * @return the matching user, or empty if none
     */
    @Query("SELECT u FROM User u WHERE u.streamKey = ?1")
    Optional<User> findByStreamKey(String streamKey);
}
