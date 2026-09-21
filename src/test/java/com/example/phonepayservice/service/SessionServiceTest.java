package com.example.phonepayservice.service;

import com.example.phonepayservice.entity.UserSession;
import com.example.phonepayservice.exception.UserNotRegisteredException;
import com.example.phonepayservice.repository.UserSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    /** A clock the test can move, to expire sessions without waiting. */
    private static class MovableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Mock
    private UserSessionRepository repository;
    private MovableClock clock;
    private SessionService service;

    @BeforeEach
    void setUp() {
        clock = new MovableClock();
        service = new SessionService(repository, clock, 30);
    }

    private UserSession savedSession() {
        ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    // ---------- start ----------

    @Test
    void start_returnsATokenAndStoresOnlyItsHash() {
        SessionService.IssuedSession issued = service.start(PHNO);

        UserSession stored = savedSession();
        assertFalse(issued.token().isBlank());
        assertNotEquals(issued.token(), stored.getTokenHash(), "the raw token must never be stored");
        assertEquals(64, stored.getTokenHash().length());   // SHA-256 as hex
        assertFalse(stored.getTokenHash().contains(issued.token()));
        assertEquals(PHNO, stored.getPhno());
    }

    @Test
    void start_expiresAfterTheConfiguredTime() {
        SessionService.IssuedSession issued = service.start(PHNO);

        assertEquals(NOW.plus(Duration.ofMinutes(30)), issued.expiresAt());
        assertEquals(NOW.plus(Duration.ofMinutes(30)), savedSession().getExpiresAt());
        assertEquals(NOW, savedSession().getCreatedAt());
    }

    @Test
    void start_givesEveryLoginADifferentUnguessableToken() {
        String first = service.start(PHNO).token();
        String second = service.start(PHNO).token();

        assertNotEquals(first, second);
        assertTrue(first.length() >= 40, "256 random bits, Base64 encoded");
    }

    @Test
    void start_forgetsThatPersonsAlreadyExpiredSessions() {
        service.start(PHNO);

        verify(repository).deleteByPhnoAndExpiresAtBefore(PHNO, NOW);
    }

    // ---------- authenticate ----------

    @Test
    void authenticate_returnsThePhoneNumberBehindAValidToken() {
        String token = service.start(PHNO).token();
        UserSession stored = savedSession();
        when(repository.findByTokenHash(stored.getTokenHash())).thenReturn(Optional.of(stored));

        assertEquals(PHNO, service.authenticate(token));
    }

    @Test
    void authenticate_unknownToken_isRejected() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        UserNotRegisteredException ex = assertThrows(UserNotRegisteredException.class, () -> service.authenticate("made-up-token"));

        assertEquals("Invalid session. Please login again.", ex.getMessage());
    }

    @Test
    void authenticate_missingOrBlankToken_isRejectedWithoutTouchingTheDatabase() {
        assertThrows(UserNotRegisteredException.class, () -> service.authenticate(null));
        assertThrows(UserNotRegisteredException.class, () -> service.authenticate(""));
        assertThrows(UserNotRegisteredException.class, () -> service.authenticate("   "));

        verify(repository, never()).findByTokenHash(any());
    }

    @Test
    void authenticate_expiredToken_isRejectedAndRemoved() {
        String token = service.start(PHNO).token();
        UserSession stored = savedSession();
        when(repository.findByTokenHash(stored.getTokenHash())).thenReturn(Optional.of(stored));
        clock.now = NOW.plus(Duration.ofMinutes(31));

        UserNotRegisteredException ex = assertThrows(UserNotRegisteredException.class, () -> service.authenticate(token));

        assertEquals("Session expired. Please login again.", ex.getMessage());
        verify(repository).delete(stored);
    }

    @Test
    void authenticate_tokenStillWorksUntilTheLastMoment() {
        String token = service.start(PHNO).token();
        UserSession stored = savedSession();
        when(repository.findByTokenHash(stored.getTokenHash())).thenReturn(Optional.of(stored));
        clock.now = NOW.plus(Duration.ofMinutes(29)).plusSeconds(59);

        assertEquals(PHNO, service.authenticate(token));
    }

    // ---------- end ----------

    @Test
    void end_removesTheSession() {
        String token = service.start(PHNO).token();
        String storedHash = savedSession().getTokenHash();

        service.end(token);

        verify(repository).deleteByTokenHash(storedHash);
    }

    @Test
    void end_withNoToken_doesNothing() {
        service.end(null);
        service.end(" ");

        verify(repository, never()).deleteByTokenHash(any());
    }
}
