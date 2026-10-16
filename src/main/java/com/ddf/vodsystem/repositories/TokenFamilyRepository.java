package com.ddf.vodsystem.repositories;

import com.ddf.vodsystem.entities.TokenFamily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface TokenFamilyRepository extends JpaRepository<TokenFamily, Long> {
    /**
     * Deletes families that can no longer be refreshed: revoked, expired, or with every refresh
     * token expired. Their refresh tokens are removed by the {@code ON DELETE CASCADE} foreign key.
     *
     * @return the number of families deleted
     */
    @Modifying
    @Query("""
            DELETE FROM TokenFamily f
            WHERE f.revokedAt IS NOT NULL
               OR f.expiresAt <= ?1
               OR NOT EXISTS (
                   SELECT 1 FROM RefreshToken r
                   WHERE r.tokenFamily = f AND r.expiresAt > ?1
               )
            """)
    int deleteDead(Instant now);
}
