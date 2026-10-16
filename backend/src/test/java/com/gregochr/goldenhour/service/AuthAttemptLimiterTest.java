package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.service.AuthAttemptLimiter.Window;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthAttemptLimiter}: the limit boundary, a window fixed from the FIRST attempt (never sliding),
 * the hourly cache outliving the quarter-hour one, key independence and {@code clear()}.
 */
class AuthAttemptLimiterTest {

    private AtomicLong nanos;
    private AuthAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        nanos = new AtomicLong();
        limiter = new AuthAttemptLimiter(nanos::get);
    }

    private void advance(Duration by) {
        nanos.addAndGet(by.toNanos());
    }

    @Test
    @DisplayName("the fifth attempt in the window is admitted and the sixth is refused")
    void limit_boundary() {
        for (int i = 1; i <= 5; i++) {
            assertThat(limiter.tryAcquire("k", 5, Window.QUARTER_HOUR))
                    .as("attempt %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire("k", 5, Window.QUARTER_HOUR)).isFalse();
    }

    @Test
    @DisplayName("a refused attempt is still counted, so hammering does not recover early")
    void refusedAttempts_stayRefused() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("k", 5, Window.QUARTER_HOUR);
        }
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire("k", 5, Window.QUARTER_HOUR)).isFalse();
        }
    }

    @Test
    @DisplayName("the window is fixed from the FIRST attempt: admitted again 15 minutes after it, not after the last")
    void window_isFixedFromTheFirstAttempt() {
        limiter.tryAcquire("k", 2, Window.QUARTER_HOUR);       // t = 0, creates the entry
        advance(Duration.ofMinutes(10));
        limiter.tryAcquire("k", 2, Window.QUARTER_HOUR);       // t = 10, second
        assertThat(limiter.tryAcquire("k", 2, Window.QUARTER_HOUR)).isFalse(); // t = 10, third refused

        advance(Duration.ofMinutes(4).plusSeconds(59));        // t = 14:59, still inside
        assertThat(limiter.tryAcquire("k", 2, Window.QUARTER_HOUR)).isFalse();

        advance(Duration.ofSeconds(1));                        // t = 15:00 from the first attempt
        assertThat(limiter.tryAcquire("k", 2, Window.QUARTER_HOUR))
                .as("one window after the FIRST attempt, not one window after the latest").isTrue();
    }

    @Test
    @DisplayName("a global key in the HOURLY cache is still refused at minute 16 and admitted at minute 61")
    void hourlyCache_outlivesTheQuarterHourOne() {
        limiter.tryAcquire("fallback:login:global", 1, Window.HOUR);
        assertThat(limiter.tryAcquire("fallback:login:global", 1, Window.HOUR)).isFalse();

        advance(Duration.ofMinutes(16));
        assertThat(limiter.tryAcquire("fallback:login:global", 1, Window.HOUR))
                .as("a quarter-hour cache would have forgotten it by now").isFalse();

        advance(Duration.ofMinutes(45));                       // minute 61
        assertThat(limiter.tryAcquire("fallback:login:global", 1, Window.HOUR)).isTrue();
    }

    @Test
    @DisplayName("the two windows are separate caches: the same key text in each counts independently")
    void windows_areIndependent() {
        assertThat(limiter.tryAcquire("same", 1, Window.QUARTER_HOUR)).isTrue();
        assertThat(limiter.tryAcquire("same", 1, Window.QUARTER_HOUR)).isFalse();
        assertThat(limiter.tryAcquire("same", 1, Window.HOUR)).isTrue();
    }

    @Test
    @DisplayName("different keys do not share a counter")
    void keys_areIndependent() {
        assertThat(limiter.tryAcquire("a", 1, Window.QUARTER_HOUR)).isTrue();
        assertThat(limiter.tryAcquire("a", 1, Window.QUARTER_HOUR)).isFalse();
        assertThat(limiter.tryAcquire("b", 1, Window.QUARTER_HOUR)).isTrue();
    }

    @Test
    @DisplayName("clear() forgets every counter in both windows")
    void clear_forgetsEverything() {
        limiter.tryAcquire("q", 1, Window.QUARTER_HOUR);
        limiter.tryAcquire("h", 1, Window.HOUR);
        assertThat(limiter.tryAcquire("q", 1, Window.QUARTER_HOUR)).isFalse();
        assertThat(limiter.tryAcquire("h", 1, Window.HOUR)).isFalse();

        limiter.clear();

        assertThat(limiter.tryAcquire("q", 1, Window.QUARTER_HOUR)).isTrue();
        assertThat(limiter.tryAcquire("h", 1, Window.HOUR)).isTrue();
    }

    @Test
    @DisplayName("normalise trims, lower-cases with Locale.ROOT and maps null to empty")
    void normalise_isTheSharedKeyForm() {
        assertThat(AuthAttemptLimiter.normalise("  A@X.com ")).isEqualTo("a@x.com");
        assertThat(AuthAttemptLimiter.normalise("a@x.com")).isEqualTo("a@x.com");
        // Locale.ROOT: the Turkish dotted capital I must not become a dotless ı under a default locale.
        assertThat(AuthAttemptLimiter.normalise("TITLE")).isEqualTo("title");
        assertThat(AuthAttemptLimiter.normalise(null)).isEmpty();
    }

    @Test
    @DisplayName("the window lengths are 15 minutes and one hour")
    void windowLengths() {
        assertThat(Window.QUARTER_HOUR.length()).isEqualTo(Duration.ofMinutes(15));
        assertThat(Window.HOUR.length()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("the public constructor builds a working limiter on the system clock")
    void defaultConstructor_works() {
        AuthAttemptLimiter real = new AuthAttemptLimiter();
        assertThat(real.tryAcquire("k", 1, Window.QUARTER_HOUR)).isTrue();
        assertThat(real.tryAcquire("k", 1, Window.QUARTER_HOUR)).isFalse();
    }
}
