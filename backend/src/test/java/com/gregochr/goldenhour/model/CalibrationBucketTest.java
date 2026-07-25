package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CalibrationBucket}.
 */
class CalibrationBucketTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 10);

    @Test
    @DisplayName("of() reports a perfect forecast as zero error and full agreement")
    void of_perfectForecast_reportsZeroError() {
        var bucket = CalibrationBucket.of("ALL", List.of(pair(4, 4), pair(2, 2)));

        assertThat(bucket.sampleCount()).isEqualTo(2);
        assertThat(bucket.meanRatingError()).isZero();
        assertThat(bucket.meanAbsoluteRatingError()).isZero();
        assertThat(bucket.exactMatchRate()).isEqualTo(1.0);
        assertThat(bucket.withinOneRate()).isEqualTo(1.0);
        assertThat(bucket.missedOpportunities()).isZero();
        assertThat(bucket.wastedTrips()).isZero();
    }

    @Test
    @DisplayName("of() signs the mean error so optimism and pessimism are distinguishable")
    void of_signedErrorSeparatesOptimismFromPessimism() {
        // Both forecasts are one star out, but in opposite directions: the mean absolute error
        // cannot tell them apart, the signed mean can — and it is the signed one that says
        // whether the scoring rules lean toward sending people out or keeping them home.
        var optimistic = CalibrationBucket.of("OPT", List.of(pair(4, 3), pair(5, 4)));
        var pessimistic = CalibrationBucket.of("PESS", List.of(pair(3, 4), pair(4, 5)));

        assertThat(optimistic.meanAbsoluteRatingError())
                .isEqualTo(pessimistic.meanAbsoluteRatingError());
        assertThat(optimistic.meanRatingError()).isEqualTo(1.0);
        assertThat(pessimistic.meanRatingError()).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("of() counts a missed opportunity when a low forecast met an excellent sky")
    void of_lowForecastGoodSky_countsMissedOpportunity() {
        // The exact shape an absolute rating ceiling produces: forced to 1, sky delivered 5.
        var bucket = CalibrationBucket.of("ALL", List.of(pair(1, 5), pair(2, 4)));

        assertThat(bucket.missedOpportunities()).isEqualTo(2);
        assertThat(bucket.wastedTrips()).isZero();
    }

    @Test
    @DisplayName("of() counts a wasted trip when a high forecast met a dud sky")
    void of_highForecastPoorSky_countsWastedTrip() {
        var bucket = CalibrationBucket.of("ALL", List.of(pair(5, 1), pair(4, 2)));

        assertThat(bucket.wastedTrips()).isEqualTo(2);
        assertThat(bucket.missedOpportunities()).isZero();
    }

    @Test
    @DisplayName("of() does not count near-misses either side of the decision boundary")
    void of_nearBoundary_countsNeither() {
        // 3 is neither "stay home" (<=2) nor "worth it" (>=4), so nothing here is a decision error.
        var bucket = CalibrationBucket.of("ALL", List.of(pair(3, 5), pair(2, 3), pair(3, 1)));

        assertThat(bucket.missedOpportunities()).isZero();
        assertThat(bucket.wastedTrips()).isZero();
    }

    @Test
    @DisplayName("of() reports exact-match and within-one rates independently")
    void of_reportsMatchRates() {
        var bucket = CalibrationBucket.of("ALL",
                List.of(pair(3, 3), pair(3, 4), pair(1, 5)));

        assertThat(bucket.exactMatchRate()).isEqualTo(0.33);
        assertThat(bucket.withinOneRate()).isEqualTo(0.67);
    }

    @Test
    @DisplayName("of() averages fiery-sky error only over pairs that captured both scores")
    void of_fierySkyError_ignoresPairsMissingEitherScore() {
        var withScores = new CalibrationPair("Durham", DATE, TargetType.SUNSET, 1,
                LocalDateTime.of(2026, 5, 9, 1, 0), EvaluationModel.HAIKU,
                4, 80, 70, 4, 60, 50, true);
        var missingActual = new CalibrationPair("Durham", DATE, TargetType.SUNSET, 1,
                LocalDateTime.of(2026, 5, 9, 1, 0), EvaluationModel.HAIKU,
                4, 80, 70, 4, null, null, true);

        var bucket = CalibrationBucket.of("ALL", List.of(withScores, missingActual));

        assertThat(bucket.sampleCount()).isEqualTo(2);
        assertThat(bucket.meanAbsoluteFierySkyError()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("of() reports null metrics rather than dividing by zero on an empty bucket")
    void of_emptyBucket_reportsNullMetrics() {
        var bucket = CalibrationBucket.of("T+7", List.of());

        assertThat(bucket.sampleCount()).isZero();
        assertThat(bucket.meanRatingError()).isNull();
        assertThat(bucket.meanAbsoluteRatingError()).isNull();
        assertThat(bucket.exactMatchRate()).isNull();
        assertThat(bucket.meanAbsoluteFierySkyError()).isNull();
        assertThat(CalibrationBucket.of("T+7", null).sampleCount()).isZero();
    }

    private CalibrationPair pair(int predicted, int actual) {
        return new CalibrationPair("Durham", DATE, TargetType.SUNSET, 1,
                LocalDateTime.of(2026, 5, 9, 1, 0), EvaluationModel.HAIKU,
                predicted, null, null, actual, null, null, true);
    }
}
