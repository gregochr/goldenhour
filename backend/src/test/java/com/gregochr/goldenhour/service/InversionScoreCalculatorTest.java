package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.model.AtmosphericData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InversionScoreCalculator}.
 */
class InversionScoreCalculatorTest {

    @Test
    @DisplayName("returns null when atmospheric data is null")
    void calculate_nullData_returnsNull() {
        assertThat(InversionScoreCalculator.calculate(null)).isNull();
    }

    @Test
    @DisplayName("returns null when temperature is missing")
    void calculate_missingTemperature_returnsNull() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(null)
                .dewPoint(5.0)
                .build();

        assertThat(InversionScoreCalculator.calculate(data)).isNull();
    }

    @Test
    @DisplayName("returns null when dew point is missing")
    void calculate_missingDewPoint_returnsNull() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(10.0)
                .dewPoint(null)
                .build();

        assertThat(InversionScoreCalculator.calculate(data)).isNull();
    }

    @Test
    @DisplayName("perfect inversion conditions yield high score")
    void calculate_perfectConditions_highScore() {
        // Small temp-dew gap (0.5°C), very light wind (1.0 m/s), high humidity (95%), low cloud 30%
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(6.0)
                .dewPoint(5.5)
                .windSpeed(new java.math.BigDecimal("1.0"))
                .humidity(95)
                .lowCloud(30)
                .build();

        Double score = InversionScoreCalculator.calculate(data);

        assertThat(score).isNotNull();
        // gap <= 1 = 4, wind <= 1.5 = 3, humidity >= 90 = 2, cloud 20-60 = 1 → total = 10
        assertThat(score).isEqualTo(10.0);
    }

    @Test
    @DisplayName("dry windy conditions yield low score")
    void calculate_dryWindy_lowScore() {
        // Large gap (8°C), strong wind (8 m/s), low humidity (50%), no low cloud
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(15.0)
                .dewPoint(7.0)
                .windSpeed(new java.math.BigDecimal("8.0"))
                .humidity(50)
                .lowCloud(5)
                .build();

        Double score = InversionScoreCalculator.calculate(data);

        assertThat(score).isNotNull();
        // gap > 6 = 0, wind > 5 = 0, humidity < 70 = 0, cloud < 20 = 0 → total = 0
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("moderate conditions yield moderate score")
    void calculate_moderateConditions_moderateScore() {
        // Gap 3°C, wind 2.5 m/s, humidity 82%, low cloud 40%
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(10.0)
                .dewPoint(7.0)
                .windSpeed(new java.math.BigDecimal("2.5"))
                .humidity(82)
                .lowCloud(40)
                .build();

        Double score = InversionScoreCalculator.calculate(data);

        assertThat(score).isNotNull();
        // gap 2-4 = 2, wind 1.5-3.0 = 2, humidity 80-90 = 1.5, cloud 20-60 = 1 → total = 6.5
        assertThat(score).isEqualTo(6.5);
    }

    @Test
    @DisplayName("score is capped at 10")
    void calculate_cappedAt10() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(5.0)
                .dewPoint(5.0)
                .windSpeed(new java.math.BigDecimal("0.5"))
                .humidity(98)
                .lowCloud(50)
                .build();

        Double score = InversionScoreCalculator.calculate(data);

        assertThat(score).isNotNull().isLessThanOrEqualTo(10.0);
    }

    @Test
    @DisplayName("overcast low cloud (>60%) gets partial cloud score")
    void calculate_overcastLowCloud_partialCloudScore() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(6.0)
                .dewPoint(5.5)
                .windSpeed(new java.math.BigDecimal("1.0"))
                .humidity(95)
                .lowCloud(80)
                .build();

        Double score = InversionScoreCalculator.calculate(data);

        // gap 0.5 = 4, wind 1.0 = 3, humidity 95 = 2, cloud 80 > 60 = 0.5 → 9.5
        assertThat(score).isNotNull().isEqualTo(9.5);
    }

    @Test
    @DisplayName("clear sky (no low cloud) gets zero cloud score")
    void calculate_clearSky_zeroCloudScore() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(6.0)
                .dewPoint(5.5)
                .windSpeed(new java.math.BigDecimal("1.0"))
                .humidity(95)
                .lowCloud(5)
                .build();

        Double score = InversionScoreCalculator.calculate(data);

        // gap 0.5 = 4, wind 1.0 = 3, humidity 95 = 2, cloud 5 < 20 = 0 → 9.0
        assertThat(score).isNotNull().isEqualTo(9.0);
    }
}
