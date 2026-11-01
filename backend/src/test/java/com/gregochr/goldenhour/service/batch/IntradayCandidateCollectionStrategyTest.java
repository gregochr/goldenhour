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

    @org.junit.jupiter.params.ParameterizedTest(name = "eveningDue={0}, T+{1} {2} -> defers={3}")
    @org.junit.jupiter.params.provider.CsvSource({
            "true,  0, SUNSET,  false",
            "true,  0, SUNRISE, false",
            "true,  1, SUNRISE, true",
            "true,  1, SUNSET,  false",
            "true,  2, SUNRISE, false",
            "false, 0, SUNSET,  false",
            "false, 1, SUNRISE, false",
            "false, 1, SUNSET,  false"})
    @DisplayName("only T+1 sunrise is deferred, and only when tonight's evening refresh is due")
    void defersRatedSlot_table(boolean eveningDue, int daysAhead, TargetType type, boolean expected) {
        IntradayCandidateCollectionStrategy s = new IntradayCandidateCollectionStrategy(CLOCK, eveningDue);

        assertThat(s.defersRatedSlot(TODAY.plusDays(daysAhead), type)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the single-argument constructor never defers")
    void singleArgConstructor_neverDefers() {
        assertThat(strategy.defersRatedSlot(TODAY.plusDays(1), TargetType.SUNRISE)).isFalse();
    }
}
