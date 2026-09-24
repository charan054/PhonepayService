package com.example.phonepayservice.configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A simple in-memory sliding-window limiter: at most maxRequests per key within window. Not distributed - fine
 * for a single instance of this service.
 */
public class RateLimiter {
    // How often a call to allow() piggybacks a sweep of every key's deque, so a key that stops being used
    // entirely (an address that never returns) eventually leaves the map instead of sitting there forever.
    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(10);

    private final int maxRequests;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> nextCleanup;

    public RateLimiter(int maxRequests, Duration window, Clock clock) {
        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = clock;
        this.nextCleanup = new AtomicReference<>(clock.instant().plus(CLEANUP_INTERVAL));
    }

    /** True and counted if this key still has budget left in the current window; false if it has none left. */
    public boolean allow(String key) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        cleanUpStaleKeysIfDue(now, cutoff);
        Deque<Instant> timestamps = hits.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxRequests) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    // Pruning inside allow() only ever touches the ONE key being called with, so a key that is never called again
    // (an abandoned or one-off address) would otherwise keep its now-empty deque in this map forever. This sweeps
    // every key instead, but only rarely, piggybacked on whichever request happens to be due for it - no
    // background thread, no lifecycle to manage.
    private void cleanUpStaleKeysIfDue(Instant now, Instant cutoff) {
        Instant due = nextCleanup.get();
        if (now.isBefore(due) || !nextCleanup.compareAndSet(due, now.plus(CLEANUP_INTERVAL))) {
            return;
        }
        hits.forEach((key, timestamps) -> {
            synchronized (timestamps) {
                while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                    timestamps.pollFirst();
                }
                if (timestamps.isEmpty()) {
                    hits.remove(key, timestamps);
                }
            }
        });
    }

    /** For tests only: how many keys this limiter is currently holding a deque for. */
    int trackedKeyCount() {
        return hits.size();
    }
}
