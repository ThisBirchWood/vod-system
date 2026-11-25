package com.ddf.vodsystem.vod;

import com.ddf.vodsystem.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VodRepository extends JpaRepository<Vod, Long>
{
    /**
     * Finds all VoDs owned by the given user.
     *
     * @param user the owning user
     * @return the user's VoDs
     */
    @Query("SELECT v FROM Vod v WHERE v.user = ?1")
    List<Vod> findByUser(User user);
}
