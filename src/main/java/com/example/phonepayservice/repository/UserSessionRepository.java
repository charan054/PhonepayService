package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {
    Optional<UserSession> findByTokenHash(String tokenHash);
    long deleteByTokenHash(String tokenHash);
    // housekeeping: forget a person's sessions that have already expired
    long deleteByPhnoAndExpiresAtBefore(long phno, Instant cutoff);
}
