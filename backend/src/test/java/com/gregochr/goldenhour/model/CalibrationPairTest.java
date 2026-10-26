package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CalibrationPair}'s projection constructors and its dedup key.
 */
class CalibrationPairTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 10);
    private static final LocalDateTime RUN = LocalDateTime.of(2026, 5, 9, 1, 0);

    private CalibrationPair rating(long userId) {
        return new CalibrationPair("Durham", DATE, TargetType.SUNSET, 1, RUN, EvaluationModel.HAIKU,
                3, 50, 60, 4, userId, null, null);
    }

    @Test
    @DisplayName("a rating pair is keyed on its reader and has no fiery-sky, golden-hour or trip actuals")
    void ratingConstructor_keysOnReaderAndLeavesOtherActualsNull() {
        CalibrationPair pair = rating(11L);

        assertThat(pair.outcomeKey()).isEqualTo("rating:11");
        assertThat(pair.actualRating()).isEqualTo(4);
        assertThat(pair.actualFierySky()).isNull();
        assertThat(pair.actualGoldenHour()).isNull();
        assertThat(pair.wentOut()).isNull();
    }

    @Test
    @DisplayName("slotHorizonKey separates outcomes of one slot but not runs of one forecast")
    void slotHorizonKey_includesOutcomeIdentity() {
        assertThat(rating(11L).slotHorizonKey()).isNotEqualTo(rating(12L).slotHorizonKey());
        // Same outcome, a later run of the same forecast: the same key, so the newest wins.
        CalibrationPair laterRun = new CalibrationPair("Durham", DATE, TargetType.SUNSET, 1,
                RUN.plusHours(12), EvaluationModel.HAIKU, 5, null, null, 4, 11L, null, null);
        assertThat(laterRun.slotHorizonKey()).isEqualTo(rating(11L).slotHorizonKey());
    }

    @Test
    @DisplayName("V187: mistLikely is true for the LIKELY band only - not POSSIBLE, NONE, FOGBOUND or null")
    void mistLikely_isTheLikelyBandOnly() {
        for (String band : new String[] {"POSSIBLE", "NONE", "FOGBOUND", "likely", null}) {
            assertThat(withMist(band).mistLikely()).as("band %s", band).isFalse();
        }
        assertThat(withMist("LIKELY").mistLikely()).isTrue();
    }

    @Test
    @DisplayName("V187: the rating projection carries the band and the tag")
    void projectionConstructors_carryBandAndTag() {
        CalibrationPair fromRating = new CalibrationPair("Durham", DATE, TargetType.SUNSET, 1, RUN,
                EvaluationModel.HAIKU, 3, 50, 60, 4, 11L, null, null, "LIKELY",
                com.gregochr.goldenhour.entity.SeenTag.MIST);

        assertThat(fromRating.mistPotential()).isEqualTo("LIKELY");
        assertThat(fromRating.seen()).isEqualTo(com.gregochr.goldenhour.entity.SeenTag.MIST);
        assertThat(fromRating.outcomeKey()).isEqualTo("rating:11");
    }

    private CalibrationPair withMist(String band) {
        return new CalibrationPair("Durham", DATE, TargetType.SUNSET, 1, RUN, EvaluationModel.HAIKU,
                3, null, null, 4, 11L, null, null, band, null);
    }
}
