package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.UserSession;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class UserSessionRepositoryTest {

    private static final long ASHA = 9876543210L;
    private static final long RAVI = 9123456789L;
    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    @Autowired
    private UserSessionRepository repository;
    @Autowired
    private EntityManager entityManager;

    private UserSession session(String hash, long phno, Instant expiresAt) {
        UserSession s = new UserSession();
        s.setTokenHash(hash);
        s.setPhno(phno);
        s.setCreatedAt(NOW.minusSeconds(60));
        s.setExpiresAt(expiresAt);
        return s;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void findByTokenHash_findsTheSession() {
        repository.save(session("hash-a", ASHA, NOW.plusSeconds(1800)));
        flushAndClear();

        UserSession found = repository.findByTokenHash("hash-a").orElseThrow();

        assertEquals(ASHA, found.getPhno());
        assertEquals(NOW.plusSeconds(1800), found.getExpiresAt());
    }

    @Test
    void findByTokenHash_unknownHash_isEmpty() {
        assertTrue(repository.findByTokenHash("nothing").isEmpty());
    }

    @Test
    void database_rejectsTwoSessionsWithTheSameTokenHash() {
        repository.saveAndFlush(session("same", ASHA, NOW.plusSeconds(60)));

        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(session("same", RAVI, NOW.plusSeconds(60))));
    }

    @Test
    void oneMayHaveSeveralSessionsAtOnce_forExampleOnPhoneAndLaptop() {
        repository.save(session("phone", ASHA, NOW.plusSeconds(1800)));
        repository.save(session("laptop", ASHA, NOW.plusSeconds(1800)));
        flushAndClear();

        assertEquals(2, repository.count());
    }

    @Test
    void deleteByTokenHash_removesOnlyThatSession() {
        repository.save(session("phone", ASHA, NOW.plusSeconds(1800)));
        repository.save(session("laptop", ASHA, NOW.plusSeconds(1800)));
        flushAndClear();

        long removed = repository.deleteByTokenHash("phone");
        flushAndClear();

        assertEquals(1, removed);
        assertTrue(repository.findByTokenHash("phone").isEmpty());
        assertTrue(repository.findByTokenHash("laptop").isPresent());
    }

    @Test
    void deleteByPhnoAndExpiresAtBefore_removesOnlyThatPersonsExpiredSessions() {
        repository.save(session("asha-old", ASHA, NOW.minusSeconds(1)));        // expired
        repository.save(session("asha-live", ASHA, NOW.plusSeconds(1800)));     // still valid
        repository.save(session("ravi-old", RAVI, NOW.minusSeconds(1)));        // expired, but someone else's
        flushAndClear();

        long removed = repository.deleteByPhnoAndExpiresAtBefore(ASHA, NOW);
        flushAndClear();

        assertEquals(1, removed);
        assertTrue(repository.findByTokenHash("asha-old").isEmpty());
        assertTrue(repository.findByTokenHash("asha-live").isPresent());
        assertTrue(repository.findByTokenHash("ravi-old").isPresent());
    }
}
