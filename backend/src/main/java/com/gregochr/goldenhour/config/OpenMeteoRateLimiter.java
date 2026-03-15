package com.gregochr.goldenhour.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Token-bucket rate limiter for Open-Meteo API calls.
 *
 * <p>Limits the number of concurrent in-flight requests and enforces a minimum
 * inter-request delay to stay within Open-Meteo's free-tier minutely limit
 * (~600 requests/minute). Each call to {@link #acquire()} blocks until a permit
 * is available and the minimum spacing has elapsed.
 *
 * <p>Configured via {@code open-meteo.rate-limit.requests-per-second} (default 8).
 */
@Component
public class OpenMeteoRateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(OpenMeteoRateLimiter.class);

    private final Semaphore semaphore;
    private final long minIntervalNanos;
    private long lastRequestNanos;
    private final Object timingLock = new Object();

    /**
     * Constructs the rate limiter.
     *
     * @param requestsPerSecond maximum requests per second (default 8)
     */
    public OpenMeteoRateLimiter(
            @Value("${open-meteo.rate-limit.requests-per-second:8}") int requestsPerSecond) {
        this.semaphore = new Semaphore(requestsPerSecond);
        this.minIntervalNanos = TimeUnit.SECONDS.toNanos(1) / requestsPerSecond;
        this.lastRequestNanos = 0;
        LOG.info("Open-Meteo rate limiter initialised: {} req/s, {}ms min interval",
                requestsPerSecond, TimeUnit.NANOSECONDS.toMillis(minIntervalNanos));
    }

    /**
     * Acquires a rate-limit permit, blocking if necessary.
     *
     * <p>Ensures both concurrency limiting (via semaphore) and minimum spacing
     * between requests (via timing). Must be paired with {@link #release()}.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        semaphore.acquire();
        synchronized (timingLock) {
            long now = System.nanoTime();
            long elapsed = now - lastRequestNanos;
            if (elapsed < minIntervalNanos && lastRequestNanos != 0) {
                long sleepNanos = minIntervalNanos - elapsed;
                TimeUnit.NANOSECONDS.sleep(sleepNanos);
            }
            lastRequestNanos = System.nanoTime();
        }
    }

    /**
     * Releases a rate-limit permit after a request completes.
     */
    public void release() {
        semaphore.release();
    }
}
