package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EveningCandidateCollectionStrategy}: tomorrow's sunrise and nothing else,
 * with "today" fixed when the strategy is built.
 */
class EveningCandidateCollectionStrategyTest {

    /** 2026-10-20 20:00 UTC, which is 21:00 in London (BST): today is the 20th. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T20:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final EveningCandidateCollectionStrategy strategy = new EveningCandidateCollectionStrategy(CLOCK);

    @Test
    @DisplayName("tomorrow's sunrise is the only slot admitted")
    void tomorrowSunrise_isTheOnlySlot() {
        assertThat(strategy.includes(TODAY.plusDays(1), TargetType.SUNRISE)).isTrue();
        assertThat(strategy.includes(TODAY.plusDays(1), TargetType.SUNSET)).isFalse();
        assertThat(strategy.includes(TODAY, TargetType.SUNRISE)).isFalse();
        assertThat(strategy.includes(TODAY, TargetType.SUNSET)).isFalse();
        assertThat(strategy.includes(TODAY.plusDays(2), TargetType.SUNRISE)).isFalse();
        assertThat(strategy.includes(TODAY.minusDays(1), TargetType.SUNRISE)).isFalse();
        assertThat(strategy.includes(TODAY.plusDays(1), TargetType.HOURLY)).isFalse();
    }

    @Test
    @DisplayName("it keeps the 'today' it was built with when the clock moves on")
    void keepsItsReferenceDate() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T20:00:00Z"));
        EveningCandidateCollectionStrategy built = new EveningCandidateCollectionStrategy(clock);

        clock.set(Instant.parse("2026-10-22T09:00:00Z"));

        assertThat(built.includes(TODAY.plusDays(1), TargetType.SUNRISE)).isTrue();
        assertThat(built.includes(LocalDate.of(2026, 10, 23), TargetType.SUNRISE)).isFalse();
    }

    @Test
    @DisplayName("today is the London civil date: 23:30 UTC in summer is already tomorrow in London")
    void todayIsTheLondonDate() {
        Clock lateBst = Clock.fixed(Instant.parse("2026-06-15T23:30:00Z"), ZoneOffset.UTC);
        EveningCandidateCollectionStrategy built = new EveningCandidateCollectionStrategy(lateBst);

        assertThat(built.includes(LocalDate.of(2026, 6, 17), TargetType.SUNRISE)).isTrue();
        assertThat(built.includes(LocalDate.of(2026, 6, 16), TargetType.SUNRISE)).isFalse();
    }

    @Test
    @DisplayName("it bypasses the cache gate: a deliberate refresh of one event")
    void bypassesTheCacheGate() {
        assertThat(strategy.bypassesCacheGate()).isTrue();
    }

    /** A clock whose instant a test can move. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
