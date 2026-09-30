package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SlotSignals#effectiveInversionScore()} — the ONE shared rule every
 * reader of the inversion signal uses (Phase 2 "record conditions for every place", owner
 * decision 2026-09-30; unified onto one helper in round 3, then corrected in round 4 — both
 * Codex P1s against PR #948).
 *
 * <p>Round 4 REVERSES this class's own round-3 assumption that a null
 * {@link SlotSignals.Readings#inversionScore()} always means "the calculator has not reached
 * this slot". A FRESH write can also produce a null reading — an ineligible location, or an
 * eligible one {@code InversionScoreCalculator.calculate} could not score (missing weather
 * inputs) — and {@code ForecastScoreWriter} leaves a stale Claude echo in {@code forecast_score}
 * in place indefinitely whenever a later evaluation carries no score, so falling back to the echo
 * on every null reading could revive a STRONG rating the current cycle's data no longer supports.
 * {@link SlotSignals.Readings#inversionScored()} is the fix: {@code true} on every row a
 * post-round-4 write produced (whatever the resulting score), {@code false} only for a row that
 * predates the flag. This class's fixtures now set BOTH the reading and the {@code scored} flag
 * independently, rather than inferring one from the other.
 *
 * <p>This is the helper-level counterpart to {@code InversionHotTopicStrategyTest}'s and
 * {@code ComingUpConditionsBuilderTest}'s own reader-level tests: those two classes build
 * fixtures in the identical shape and drive them through this one method, so the strategy and the
 * builder can never disagree about which value is "the" inversion score for a key — there is
 * exactly one place that decision is made.
 */
class SlotSignalsTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);

    private static SlotSignals signal(boolean scored, Double readingScore, Integer echoScore) {
        SlotSignals.Readings readings = new SlotSignals.Readings(
                null, null, null, null, null, null, null, null, null, null, null,
                readingScore, scored);
        SlotSignals.Scores scores = echoScore == null
                ? SlotSignals.Scores.EMPTY
                : new SlotSignals.Scores(echoScore, "STRONG", null, null);
        return new SlotSignals(new LocationEntity(), DATE, TargetType.SUNRISE, scores, readings);
    }

    @Test
    @DisplayName("REVERSES the round-3 (c9b891b6) expectation: scored=true with a NULL reading and "
            + "a strong echo (10) returns null (silent) — a fresh, authoritative null must NOT fall "
            + "back to the echo, unlike an absent (scored=false) row's null")
    void scoredTrue_nullReading_strongEcho_returnsNull() {
        assertThat(signal(true, null, 10).effectiveInversionScore()).isNull();
    }

    @Test
    @DisplayName("scored=true with a reading of 9 returns 9, regardless of the echo")
    void scoredTrue_readingNine_returnsNine() {
        assertThat(signal(true, 9.0, null).effectiveInversionScore()).isEqualTo(9.0);
    }

    @Test
    @DisplayName("scored=false with echo 10 (and no reading, since an unscored row never has one) "
            + "falls back to the echo")
    void scoredFalse_echoTen_returnsEcho() {
        assertThat(signal(false, null, 10).effectiveInversionScore()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("scored=false with no echo either returns null")
    void scoredFalse_echoNull_returnsNull() {
        assertThat(signal(false, null, null).effectiveInversionScore()).isNull();
    }

    @Test
    @DisplayName("scored=true, reading present, echo disagreeing HIGHER — the reading still wins")
    void scoredTrue_echoHigherThanReading_readingWins() {
        assertThat(signal(true, 8.0, 10).effectiveInversionScore()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("scored=true, reading present, echo disagreeing LOWER — the reading still wins")
    void scoredTrue_echoLowerThanReading_readingWins() {
        assertThat(signal(true, 10.0, 7).effectiveInversionScore()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("EMPTY readings is scored=false, so a key with only an echo still falls back to it")
    void emptyReadings_isUnscored_fallsBackToEcho() {
        SlotSignals.Readings empty = SlotSignals.Readings.EMPTY;
        assertThat(empty.inversionScored()).isFalse();
        SlotSignals signal = new SlotSignals(new LocationEntity(), DATE, TargetType.SUNRISE,
                new SlotSignals.Scores(10, "STRONG", null, null), empty);
        assertThat(signal.effectiveInversionScore()).isEqualTo(10.0);
    }
}
