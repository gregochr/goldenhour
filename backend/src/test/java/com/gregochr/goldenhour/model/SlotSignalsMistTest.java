package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SlotSignals#effectiveMistPotential()} (valley-mist-plan M2): the
 * {@code effectiveInversionScore()} rule on the BAND. A row the valley-mist writer reached decides,
 * a deliberate null included; the Claude echo only stands in for a row it never reached.
 */
class SlotSignalsMistTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 22);

    private static SlotSignals signal(boolean scored, MistPotential readingBand, String echoBand) {
        SlotSignals.Readings readings = new SlotSignals.Readings(
                null, null, null, null, null, null, null, null, null, null, null,
                null, false, readingBand == null ? null : 7.0, scored, readingBand);
        SlotSignals.Scores scores = echoBand == null
                ? SlotSignals.Scores.EMPTY
                : new SlotSignals.Scores(null, null, null, null, echoBand);
        return new SlotSignals(new LocationEntity(), DATE, TargetType.SUNRISE, scores, readings);
    }

    @Test
    @DisplayName("scored=true with a NULL band and a LIKELY echo returns null: a fresh null is an answer")
    void scoredTrue_nullBand_likelyEcho_returnsNull() {
        assertThat(signal(true, null, "LIKELY").effectiveMistPotential()).isNull();
    }

    @Test
    @DisplayName("scored=true returns the calculator's band regardless of a disagreeing echo")
    void scoredTrue_bandWins() {
        assertThat(signal(true, MistPotential.POSSIBLE, "LIKELY").effectiveMistPotential())
                .isEqualTo(MistPotential.POSSIBLE);
        assertThat(signal(true, MistPotential.FOGBOUND, "LIKELY").effectiveMistPotential())
                .isEqualTo(MistPotential.FOGBOUND);
        assertThat(signal(true, MistPotential.LIKELY, "NONE").effectiveMistPotential())
                .isEqualTo(MistPotential.LIKELY);
    }

    @Test
    @DisplayName("scored=false falls back to Claude's echo")
    void scoredFalse_fallsBackToEcho() {
        assertThat(signal(false, null, "LIKELY").effectiveMistPotential())
                .isEqualTo(MistPotential.LIKELY);
        assertThat(signal(false, null, "FOGBOUND").effectiveMistPotential())
                .isEqualTo(MistPotential.FOGBOUND);
    }

    @Test
    @DisplayName("scored=false with no echo returns null")
    void scoredFalse_noEcho_returnsNull() {
        assertThat(signal(false, null, null).effectiveMistPotential()).isNull();
    }

    @Test
    @DisplayName("scored=false with an unrecognised echo name returns null, never throws")
    void scoredFalse_unrecognisedEcho_returnsNull() {
        assertThat(signal(false, null, "Moderate").effectiveMistPotential()).isNull();
    }

    @Test
    @DisplayName("EMPTY readings are scored=false, so a key with only an echo still falls back to it")
    void emptyReadings_fallBackToEcho() {
        SlotSignals onlyEcho = new SlotSignals(new LocationEntity(), DATE, TargetType.SUNRISE,
                new SlotSignals.Scores(null, null, null, null, "LIKELY"), SlotSignals.Readings.EMPTY);

        assertThat(onlyEcho.effectiveMistPotential()).isEqualTo(MistPotential.LIKELY);
    }

    @Test
    @DisplayName("the four-argument Scores constructor carries no mist echo")
    void legacyScoresConstructor_hasNoEcho() {
        assertThat(new SlotSignals.Scores(9, "STRONG", 4, "x").mistBand()).isNull();
        assertThat(SlotSignals.Scores.EMPTY.mistBand()).isNull();
    }
}
