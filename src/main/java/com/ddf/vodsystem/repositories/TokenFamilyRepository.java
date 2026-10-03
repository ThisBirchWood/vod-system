package com.ddf.vodsystem.repositories;

import com.ddf.vodsystem.entities.TokenFamily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface TokenFamilyRepository extends JpaRepository<TokenFamily, Long> {
}
