package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SurvivorSignals#effectiveInversionScore()} — the ONE shared rule every
 * reader of the inversion signal uses (round 3 of Phase 2 "record conditions for every place",
 * owner decision 2026-09-30, a Codex P1 against PR #948's second cut).
 *
 * <p>This is the helper-level counterpart to {@code InversionHotTopicStrategyTest}'s and
 * {@code ComingUpConditionsBuilderTest}'s own reader-level tests: those two classes build
 * fixtures in the identical shape ({@link SurvivorSignals.Readings#inversionScore()} and
 * {@link SurvivorSignals.Scores#inversion()} set independently) and drive them through this one
 * method, so the strategy and the builder can never disagree about which value is "the" inversion
 * score for a key — there is exactly one place that decision is made.
 */
class SurvivorSignalsTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);

    private static SurvivorSignals signal(Double readingScore, Integer echoScore) {
        SurvivorSignals.Readings readings = readingScore == null
                ? SurvivorSignals.Readings.EMPTY
                : new SurvivorSignals.Readings(
                        null, null, null, null, null, null, null, null, null, null, null, readingScore);
        SurvivorSignals.Scores scores = echoScore == null
                ? SurvivorSignals.Scores.EMPTY
                : new SurvivorSignals.Scores(echoScore, "STRONG", null, null);
        return new SurvivorSignals(new LocationEntity(), DATE, TargetType.SUNRISE, scores, readings);
    }

    @Test
    @DisplayName("reading present, echo null -> the reading")
    void readingPresent_echoNull_returnsReading() {
        assertThat(signal(9.0, null).effectiveInversionScore()).isEqualTo(9.0);
    }

    @Test
    @DisplayName("reading null, echo present -> the echo, widened to Double")
    void readingNull_echoPresent_returnsEcho() {
        assertThat(signal(null, 10).effectiveInversionScore()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("both null -> null")
    void bothNull_returnsNull() {
        assertThat(signal(null, null).effectiveInversionScore()).isNull();
    }

    @Test
    @DisplayName("both present, agreeing -> the (shared) value")
    void bothPresent_agreeing_returnsValue() {
        assertThat(signal(9.0, 9).effectiveInversionScore()).isEqualTo(9.0);
    }

    @Test
    @DisplayName("both present, echo HIGHER than the reading -> the reading still wins")
    void bothPresent_echoHigher_readingWins() {
        assertThat(signal(8.0, 10).effectiveInversionScore()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("both present, echo LOWER than the reading -> the reading still wins")
    void bothPresent_echoLower_readingWins() {
        assertThat(signal(10.0, 7).effectiveInversionScore()).isEqualTo(10.0);
    }
}
