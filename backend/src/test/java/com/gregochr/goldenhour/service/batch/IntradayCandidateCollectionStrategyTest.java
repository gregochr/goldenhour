package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IntradayCandidateCollectionStrategy}.
 *
 * <p>Pins the decision window to exactly {T sunset, T+1 sunrise, T+1 sunset}
 * and asserts the boundaries on both sides: T's already-passed sunrise is out,
 * and T+2 onward (the nightly run's job) is out.
 */
class IntradayCandidateCollectionStrategyTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /**
     * 2026-05-29 14:00 UTC. In {@code Europe/London} (BST, UTC+1) that is the
     * afternoon of 2026-05-29, so "today" (T) is 2026-05-29.
     */
    private static final Clock CLOCK =
            Clock.fixed(java.time.Instant.parse("2026-05-29T14:00:00Z"), ZoneOffset.UTC);

    private final IntradayCandidateCollectionStrategy strategy =
            new IntradayCandidateCollectionStrategy(CLOCK);

    private static final LocalDate TODAY = LocalDate.now(CLOCK.withZone(LONDON));

    @Test
    @DisplayName("T sunset is in the decision window")
    void todaySunset_included() {
        assertThat(strategy.includes(TODAY, TargetType.SUNSET)).isTrue();
    }

    @Test
    @DisplayName("T sunrise is NOT in the window — it is already in the past by afternoon")
    void todaySunrise_excluded() {
        assertThat(strategy.includes(TODAY, TargetType.SUNRISE)).isFalse();
    }

    @Test
    @DisplayName("T+1 sunrise is in the window")
    void tomorrowSunrise_included() {
        assertThat(strategy.includes(TODAY.plusDays(1), TargetType.SUNRISE)).isTrue();
    }

    @Test
    @DisplayName("T+1 sunset is in the window")
    void tomorrowSunset_included() {
        assertThat(strategy.includes(TODAY.plusDays(1), TargetType.SUNSET)).isTrue();
    }

    @Test
    @DisplayName("T+2 sunrise is out — beyond the ~36h actionable horizon (nightly's job)")
    void dayAfterTomorrowSunrise_excluded() {
        assertThat(strategy.includes(TODAY.plusDays(2), TargetType.SUNRISE)).isFalse();
    }

    @Test
    @DisplayName("T+2 sunset is out")
    void dayAfterTomorrowSunset_excluded() {
        assertThat(strategy.includes(TODAY.plusDays(2), TargetType.SUNSET)).isFalse();
    }

    @Test
    @DisplayName("a past date is out")
    void pastDate_excluded() {
        assertThat(strategy.includes(TODAY.minusDays(1), TargetType.SUNSET)).isFalse();
    }
}
