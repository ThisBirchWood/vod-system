package com.ddf.vodsystem.repositories;

import com.ddf.vodsystem.entities.Clip;
import com.ddf.vodsystem.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClipRepository extends JpaRepository<Clip, Long> {
    /**
     * Finds all clips owned by the given user.
     *
     * @param user the owning user
     * @return the user's clips
     */
    @Query("SELECT c FROM Clip c WHERE c.user = ?1")
    List<Clip> findByUser(User user);
}


