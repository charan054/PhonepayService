package com.example.phonepayservice.configuration;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    /** A clock the test can move, to slide the window without waiting. */
    private static class MovableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovableClock clock = new MovableClock();

    @Test
    void allowsUpToTheLimit_thenRefuses() {
        RateLimiter limiter = new RateLimiter(3, Duration.ofMinutes(1), clock);

        assertTrue(limiter.allow("same-key"));
        assertTrue(limiter.allow("same-key"));
        assertTrue(limiter.allow("same-key"));
        assertFalse(limiter.allow("same-key"), "the fourth call within the window must be refused");
    }

    @Test
    void differentKeys_haveIndependentBudgets() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), clock);

        assertTrue(limiter.allow("alice"));
        assertFalse(limiter.allow("alice"));
        assertTrue(limiter.allow("bob"), "bob has never made a request, so his budget is untouched");
    }

    @Test
    void oncePastTheWindow_theEarlierCallsNoLongerCount() {
        RateLimiter limiter = new RateLimiter(2, Duration.ofMinutes(1), clock);

        assertTrue(limiter.allow("same-key"));
        assertTrue(limiter.allow("same-key"));
        assertFalse(limiter.allow("same-key"));

        clock.now = NOW.plus(Duration.ofMinutes(1)).plusSeconds(1);

        assertTrue(limiter.allow("same-key"), "both earlier calls have now slid out of the window");
    }

    @Test
    void aCallExactlyAtTheWindowBoundary_stillCounts() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), clock);
        assertTrue(limiter.allow("same-key"));

        clock.now = NOW.plus(Duration.ofMinutes(1));   // exactly one window later, not yet past it

        assertFalse(limiter.allow("same-key"), "a call exactly window-old has not yet expired");
    }

    // ---------- the map must not grow forever ----------

    @Test
    void aKeyThatIsNeverCalledAgain_isEventuallyRemovedFromTheMap() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), clock);
        limiter.allow("scanner-ip");
        assertEquals(1, limiter.trackedKeyCount());

        // Nobody ever calls allow("scanner-ip") again, but its one hit has long since expired and a cleanup
        // sweep - piggybacked on some unrelated request - is now due.
        clock.now = NOW.plus(Duration.ofMinutes(11));
        limiter.allow("someone-else");

        assertEquals(1, limiter.trackedKeyCount(), "scanner-ip should be gone, leaving only someone-else");
    }

    @Test
    void cleanupSweep_doesNotRemoveAKeyThatIsStillWithinItsWindow() {
        RateLimiter limiter = new RateLimiter(5, Duration.ofMinutes(15), clock);
        limiter.allow("alice");

        // Past the cleanup interval, but alice's hit is still within her (longer) 15-minute window.
        clock.now = NOW.plus(Duration.ofMinutes(11));
        limiter.allow("bob");

        assertEquals(2, limiter.trackedKeyCount(), "alice is still active and must not be swept away");
    }
}
