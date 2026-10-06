package com.gregochr.goldenhour.service.ask;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The typed endpoint's per-user rate limit (plan §2.5 step 1): at most
 * {@code photocast.ask.rate-per-minute} requests (default 5) in any sliding 60 seconds, per user id.
 *
 * <p>In-memory and per-process (D-8: a restart forgets it, and this application is a single
 * instance). It is the very first thing a request meets, before the question is read and before the
 * snapshot is built, so a looping client costs one map operation.
 *
 * <p><b>Sliding window.</b> Each user keeps the instants of the requests still inside the window; a
 * request is allowed when fewer than the limit are, and only an <em>allowed</em> request is recorded
 * (a refused one does not push the window forward, so a client that backs off recovers on schedule). A
 * request exactly one window after an earlier one no longer counts it.
 *
 * <p><b>Bounded memory.</b> Users with no request in the last window are evicted, one atomic
 * per-key removal at a time, every {@value #SWEEP_EVERY} calls — so the map never holds more than the
 * users active in the last minute plus whatever arrived since the last sweep.
 */
@Component
public class AskRateLimiter {

    /** The window requests are counted over. */
    static final Duration WINDOW = Duration.ofSeconds(60);

    /** Idle users are evicted once every this many calls. */
    static final int SWEEP_EVERY = 256;

    private final AskProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<Long, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    /**
     * Creates the limiter.
     *
     * @param properties the Ask settings ({@code rate-per-minute})
     * @param clock      the application clock
     */
    public AskRateLimiter(AskProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Counts a request against a user's window.
     *
     * @param userId the user
     * @return true when the request is within the limit (and is now counted); false when the user has
     *         already made the limit's worth of requests in the last 60 seconds
     */
    public boolean tryAcquire(long userId) {
        long now = clock.millis();
        int limit = properties.getRatePerMinute();
        AtomicBoolean allowed = new AtomicBoolean();
        hits.compute(userId, (id, window) -> {
            Deque<Long> inWindow = window == null ? new ArrayDeque<>() : window;
            dropExpired(inWindow, now);
            if (inWindow.size() < limit) {
                inWindow.addLast(now);
                allowed.set(true);
            }
            return inWindow;
        });
        if (calls.incrementAndGet() % SWEEP_EVERY == 0) {
            sweep(now);
        }
        return allowed.get();
    }

    /**
     * How many users the limiter currently holds a window for.
     *
     * @return the number of tracked users
     */
    int trackedUsers() {
        return hits.size();
    }

    private static void dropExpired(Deque<Long> window, long now) {
        long windowMillis = WINDOW.toMillis();
        while (!window.isEmpty() && now - window.peekFirst() >= windowMillis) {
            window.pollFirst();
        }
    }

    /** Evicts every user with nothing left in the window; each removal is atomic for its key. */
    private void sweep(long now) {
        for (Long userId : hits.keySet()) {
            hits.computeIfPresent(userId, (id, window) -> {
                dropExpired(window, now);
                return window.isEmpty() ? null : window;
            });
        }
    }
}
