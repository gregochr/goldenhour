package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CalibrationPair}'s two projection constructors and its dedup key.
 */
class CalibrationPairTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 10);
    private static final LocalDateTime RUN = LocalDateTime.of(2026, 5, 9, 1, 0);

    private CalibrationPair oldTable(long outcomeId) {
        return new CalibrationPair("Durham", DATE, TargetType.SUNSET, 1, RUN, EvaluationModel.HAIKU,
                3, 50, 60, 4, 70, 80, true, outcomeId);
    }

    private CalibrationPair rating(long userId) {
        return new CalibrationPair("Durham", DATE, TargetType.SUNSET, 1, RUN, EvaluationModel.HAIKU,
                3, 50, 60, 4, userId);
    }

    @Test
    @DisplayName("an actual_outcome pair is keyed on its outcome row and keeps every column")
    void oldTableConstructor_keysOnOutcomeRow() {
        CalibrationPair pair = oldTable(7L);

        assertThat(pair.outcomeKey()).isEqualTo("outcome:7");
        assertThat(pair.actualFierySky()).isEqualTo(70);
        assertThat(pair.wentOut()).isTrue();
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
        assertThat(rating(11L).slotHorizonKey()).isNotEqualTo(oldTable(11L).slotHorizonKey());
        assertThat(oldTable(7L).slotHorizonKey()).isNotEqualTo(oldTable(8L).slotHorizonKey());
        // Same outcome, a later run of the same forecast: the same key, so the newest wins.
        CalibrationPair laterRun = new CalibrationPair("Durham", DATE, TargetType.SUNSET, 1,
                RUN.plusHours(12), EvaluationModel.HAIKU, 5, null, null, 4, 11L);
        assertThat(laterRun.slotHorizonKey()).isEqualTo(rating(11L).slotHorizonKey());
    }
}
