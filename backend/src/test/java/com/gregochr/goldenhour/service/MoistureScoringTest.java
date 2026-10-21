package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shared moisture scale both calculators read ("moisture is counted once"), including the
 * property that extracting it from {@link InversionScoreCalculator} moved none of that calculator's
 * outputs.
 */
class MoistureScoringTest {

    @ParameterizedTest(name = "gap {0} C scores {1}")
    @CsvSource({
        "-1.0, 5.0", "0.0, 5.0", "0.5, 5.0",
        "0.51, 4.0", "1.0, 4.0",
        "1.01, 3.0", "2.0, 3.0",
        "2.01, 2.0", "3.0, 2.0",
        "3.01, 1.0", "5.0, 1.0",
        "5.01, 0.0", "12.0, 0.0"
    })
    @DisplayName("steps the temperature-dew point gap onto the 0-5 scale at exactly the documented edges")
    void gapScore_steps(double gap, double expected) {
        assertThat(MoistureScoring.gapScore(gap)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the scale tops out at MAX_SCORE, which the valley mist calculator normalises by")
    void maxScoreIsTheTopStep() {
        assertThat(MoistureScoring.gapScore(0.0)).isEqualTo(MoistureScoring.MAX_SCORE);
    }

    @ParameterizedTest(name = "inversion with gap {0} C and 1 m/s wind scores {1}")
    @CsvSource({"0.4, 10.0", "0.9, 9.0", "1.9, 8.0", "2.9, 7.0", "4.9, 6.0", "5.5, 5.0"})
    @DisplayName("InversionScoreCalculator's score still moves one-for-one with the shared moisture step")
    void inversionCalculator_unchangedByTheExtraction(double gap, double expected) {
        // Calm (3.0) + a well-defined deck (2.0) + a +3 C reversal and a shallow stable layer, so
        // only the moisture step varies; the cap for the strongest reversal is 10.
        var data = TestAtmosphericData.builder()
                .temperature(6.0)
                .dewPoint(6.0 - gap)
                .windSpeed(new BigDecimal("1.0"))
                .lowCloud(30)
                .boundaryLayerHeight(150)
                .temperature925hPa(9.0)
                .build();

        assertThat(InversionScoreCalculator.calculate(data, 450)).isEqualTo(expected);
    }
}
