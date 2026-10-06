package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AskRateLimiter}: 5 a minute per user in a sliding window, the boundary at exactly one window,
 * a refused request not extending the window, per-user isolation, eviction of idle users, and
 * thread-safety across threads.
 */
class AskRateLimiterTest {

    private static final Instant START = Instant.parse("2026-10-06T12:00:00Z");

    private MutableClock clock;
    private AskProperties properties;
    private AskRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        properties = new AskProperties();
        limiter = new AskRateLimiter(properties, clock);
    }

    @Test
    @DisplayName("the 5th request within 60 seconds is allowed and the 6th is refused")
    void fifthAllowedSixthRefused() {
        for (int i = 1; i <= 5; i++) {
            assertThat(limiter.tryAcquire(7L)).as("request %d", i).isTrue();
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(limiter.tryAcquire(7L)).as("request 6").isFalse();
        assertThat(limiter.tryAcquire(7L)).as("request 7").isFalse();
    }

    @Test
    @DisplayName("the window slides: at exactly 60 seconds after the first request one slot is free, 1 ms earlier "
            + "none is")
    void windowBoundary() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(7L)).isTrue();
        }
        clock.advance(Duration.ofMillis(59_999));
        assertThat(limiter.tryAcquire(7L)).isFalse();

        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire(7L)).isTrue();
        assertThat(limiter.tryAcquire(7L)).as("the other four expired at the same instant, one is now in").isTrue();
    }

    @Test
    @DisplayName("after a full minute of quiet the whole allowance is back")
    void resetsAfterAMinute() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire(7L);
        }
        assertThat(limiter.tryAcquire(7L)).isFalse();

        clock.advance(Duration.ofSeconds(60));

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(7L)).as("request %d", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire(7L)).isFalse();
    }

    @Test
    @DisplayName("a refused request is not recorded, so a client that keeps trying does not push its own window "
            + "forward")
    void refusedRequestsDoNotExtendTheWindow() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire(7L);
        }
        for (int second = 0; second < 59; second++) {
            clock.advance(Duration.ofSeconds(1));
            assertThat(limiter.tryAcquire(7L)).isFalse();
        }
        clock.advance(Duration.ofSeconds(1));

        assertThat(limiter.tryAcquire(7L)).isTrue();
    }

    @Test
    @DisplayName("users are limited independently")
    void usersAreIndependent() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire(1L);
        }
        assertThat(limiter.tryAcquire(1L)).isFalse();
        assertThat(limiter.tryAcquire(2L)).isTrue();
    }

    @Test
    @DisplayName("the limit is read from the properties on every call")
    void limitFollowsProperties() {
        properties.setRatePerMinute(1);
        assertThat(limiter.tryAcquire(7L)).isTrue();
        assertThat(limiter.tryAcquire(7L)).isFalse();

        properties.setRatePerMinute(2);
        assertThat(limiter.tryAcquire(7L)).isTrue();
        assertThat(limiter.tryAcquire(7L)).isFalse();
    }

    @Test
    @DisplayName("idle users are evicted by the periodic sweep, so memory is bounded by the users active in the "
            + "last minute")
    void idleUsersAreEvicted() {
        for (long user = 0; user < 100; user++) {
            limiter.tryAcquire(user);
        }
        assertThat(limiter.trackedUsers()).isEqualTo(100);

        clock.advance(Duration.ofSeconds(61));
        for (int i = 0; i < AskRateLimiter.SWEEP_EVERY; i++) {
            limiter.tryAcquire(1_000L);
        }

        assertThat(limiter.trackedUsers()).as("only the one active user remains").isEqualTo(1);
    }

    @Test
    @DisplayName("a user active in the last minute is not evicted by a sweep")
    void activeUsersSurviveTheSweep() {
        limiter.tryAcquire(5L);
        clock.advance(Duration.ofSeconds(30));
        for (int i = 0; i < AskRateLimiter.SWEEP_EVERY; i++) {
            limiter.tryAcquire(1_000L + i);
        }

        assertThat(limiter.trackedUsers()).isGreaterThan(1);
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.tryAcquire(5L)).isTrue();
        }
        assertThat(limiter.tryAcquire(5L)).as("the first request at 0s is still counted at 30s").isFalse();
    }

    @Test
    @DisplayName("many threads on one user get exactly the limit through")
    void exactlyTheLimitUnderContention() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Boolean> task = () -> {
                    go.await();
                    return limiter.tryAcquire(9L);
                };
                results.add(pool.submit(task));
            }
            go.countDown();
            int allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }
}
