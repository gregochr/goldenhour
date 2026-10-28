package com.gregochr.goldenhour.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The in-memory attempt counters behind the Turnstile fallback and the one-time-token endpoints
 * (login-registration plan §2 #4).
 *
 * <p>Two Caffeine caches, one per {@link Window}, each {@code expireAfterWrite} and bounded to
 * {@value #MAX_ENTRIES} keys. A key's counter is created by its first attempt and incremented in
 * place, so the entry's write time is that first attempt and the window is <em>fixed</em> from it: a
 * locked key recovers one window after its first attempt and never slides. Per-endpoint ceilings
 * ({@code fallback:<endpoint>:global}, {@code set-password:global}) belong in {@link Window#HOUR},
 * everything else in {@link Window#QUARTER_HOUR}; a single quarter-hour cache would have reset an
 * hourly ceiling four times an hour.
 *
 * <p>Counters live in memory only: a restart forgets them, which is acceptable for a rate limit on a
 * single-instance application (the same trade {@code UserSettingsService.pendingDriveTimeAttempts}
 * records). A refused attempt is still counted, so a client that keeps hammering stays locked until
 * the window expires.
 */
@Component
public class AuthAttemptLimiter {

    /** No cache holds more than this many keys. */
    static final long MAX_ENTRIES = 100_000;

    /** The two fixed windows a counter can live in. */
    public enum Window {
        /** A fifteen-minute window, for per-client, per-identifier and per-token counters. */
        QUARTER_HOUR(Duration.ofMinutes(15)),
        /** A one-hour window, for the per-endpoint global ceilings. */
        HOUR(Duration.ofHours(1));

        private final Duration length;

        Window(Duration length) {
            this.length = length;
        }

        /**
         * Returns how long a counter lasts from its first attempt.
         *
         * @return the window length
         */
        public Duration length() {
            return length;
        }
    }

    private final Cache<String, AtomicInteger> quarterHour;
    private final Cache<String, AtomicInteger> hour;

    /**
     * Creates the limiter on the system clock.
     */
    @Autowired
    public AuthAttemptLimiter() {
        this(Ticker.systemTicker());
    }

    /**
     * Creates the limiter on a supplied time source (tests).
     *
     * @param ticker the time source the caches expire against
     */
    AuthAttemptLimiter(Ticker ticker) {
        this.quarterHour = newCache(Window.QUARTER_HOUR, ticker);
        this.hour = newCache(Window.HOUR, ticker);
    }

    private static Cache<String, AtomicInteger> newCache(Window window, Ticker ticker) {
        return Caffeine.newBuilder()
                .expireAfterWrite(window.length())
                .maximumSize(MAX_ENTRIES)
                .ticker(ticker)
                .build();
    }

    /**
     * Counts one attempt against a key.
     *
     * @param key    the counter's key; the caller builds it from exactly the value its lookup uses
     * @param limit  how many attempts the window admits
     * @param window the window the key lives in
     * @return true when this attempt is within the limit; false when the limit has already been
     *         reached in the window (the attempt is counted either way)
     */
    public boolean tryAcquire(String key, int limit, Window window) {
        Cache<String, AtomicInteger> cache = window == Window.HOUR ? hour : quarterHour;
        return cache.get(key, k -> new AtomicInteger()).incrementAndGet() <= limit;
    }

    /**
     * Reports whether a key's counter has reached a limit, without counting anything. It uses
     * {@code getIfPresent}, so it never creates or increments an entry: an unknown key is not spent.
     *
     * @param key    the counter's key
     * @param limit  the count at which the key is spent
     * @param window the window the key lives in
     * @return true when the key's current count in the window is at least {@code limit}
     */
    public boolean isSpent(String key, int limit, Window window) {
        Cache<String, AtomicInteger> cache = window == Window.HOUR ? hour : quarterHour;
        AtomicInteger counter = cache.getIfPresent(key);
        return counter != null && counter.get() >= limit;
    }

    /**
     * Counts one failure against a key, creating its counter if absent. Shares the counter
     * {@link #tryAcquire} uses for the same key. Incrementing the counter in place does not rewrite the
     * cache entry, so the fixed window still runs from the key's first count.
     *
     * @param key    the counter's key
     * @param window the window the key lives in
     */
    public void recordFailure(String key, Window window) {
        Cache<String, AtomicInteger> cache = window == Window.HOUR ? hour : quarterHour;
        cache.get(key, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * Forgets every counter (tests).
     */
    public void clear() {
        quarterHour.invalidateAll();
        hour.invalidateAll();
    }

    /**
     * Normalises an identifier or email for use in a key: trimmed and lower-cased with
     * {@link Locale#ROOT}, so {@code " A@x"} and {@code "a@x"} share a bucket. A null is the empty string.
     *
     * @param identifier the raw identifier
     * @return the key form
     */
    public static String normalise(String identifier) {
        return identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
    }
}
