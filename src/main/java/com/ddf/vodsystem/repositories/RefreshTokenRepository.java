package com.ddf.vodsystem.repositories;

import com.ddf.vodsystem.entities.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.sql.Ref;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    @Query("SELECT r FROM RefreshToken r WHERE r.tokenHash = ?1")
    Optional<RefreshToken> findByHash(byte[] tokenHash);
}
