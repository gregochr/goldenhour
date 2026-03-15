package com.gregochr.goldenhour.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenMeteoRateLimiter}.
 */
class OpenMeteoRateLimiterTest {

    @Test
    @DisplayName("acquire() and release() complete without error")
    void acquireAndRelease() throws InterruptedException {
        OpenMeteoRateLimiter limiter = new OpenMeteoRateLimiter(10);
        limiter.acquire();
        limiter.release();
    }

    @Test
    @DisplayName("rate limiter enforces minimum spacing between requests")
    void enforcesMinimumSpacing() throws InterruptedException {
        // 5 req/s = 200ms spacing
        OpenMeteoRateLimiter limiter = new OpenMeteoRateLimiter(5);

        limiter.acquire();
        limiter.release();

        Instant before = Instant.now();
        limiter.acquire();
        limiter.release();
        Duration elapsed = Duration.between(before, Instant.now());

        // Should have waited at least ~150ms (allowing some scheduling slack)
        assertThat(elapsed.toMillis()).isGreaterThanOrEqualTo(100);
    }

    @Test
    @DisplayName("concurrent acquires are bounded by semaphore permits")
    void concurrentAcquiresBounded() throws InterruptedException {
        OpenMeteoRateLimiter limiter = new OpenMeteoRateLimiter(2);

        // Acquire both permits
        limiter.acquire();
        limiter.acquire();

        // Third acquire should block — verify by trying with a timeout thread
        Thread blocked = new Thread(() -> {
            try {
                limiter.acquire();
                limiter.release();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        blocked.start();

        // Give it a moment — the thread should still be alive (blocked)
        Thread.sleep(50);
        assertThat(blocked.isAlive()).isTrue();

        // Release one permit — unblocks the waiting thread
        limiter.release();
        blocked.join(2000);
        assertThat(blocked.isAlive()).isFalse();

        limiter.release();
    }
}
