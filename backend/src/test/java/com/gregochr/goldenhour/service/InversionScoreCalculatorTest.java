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
    @DisplayName("returns null when weather data is null")
    void calculate_nullWeather_returnsNull() {
        AtmosphericData data = new AtmosphericData(
                "Test", null, null, new com.gregochr.goldenhour.model.CloudData(10, 50, 30),
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(InversionScoreCalculator.calculate(data)).isNull();
    }

    @Test
    @DisplayName("returns null when cloud data is null")
    void calculate_nullCloud_returnsNull() {
        AtmosphericData data = new AtmosphericData(
                "Test", null, null, null,
                new com.gregochr.goldenhour.model.WeatherData(
                        25000, new java.math.BigDecimal("3.50"), 225,
                        java.math.BigDecimal.ZERO, 62, 3,
                        new java.math.BigDecimal("180.00"), null, 1013.25),
                null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(InversionScoreCalculator.calculate(data)).isNull();
    }

    @Test
    @DisplayName("boundary: gap exactly 1.0 scores 4 (top tier)")
    void calculate_gapExactlyOne_topTier() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(6.0)
                .dewPoint(5.0)
                .windSpeed(new java.math.BigDecimal("1.0"))
                .humidity(95)
                .lowCloud(30)
                .build();

        Double score = InversionScoreCalculator.calculate(data);
        // gap = 1.0 → 4, wind = 1.0 → 3, humidity 95 → 2, cloud 30 → 1 = 10
        assertThat(score).isEqualTo(10.0);
    }

    @Test
    @DisplayName("boundary: gap 1.01 scores 3 (second tier)")
    void calculate_gapJustAboveOne_secondTier() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(6.01)
                .dewPoint(5.0)
                .windSpeed(new java.math.BigDecimal("1.0"))
                .humidity(95)
                .lowCloud(30)
                .build();

        Double score = InversionScoreCalculator.calculate(data);
        // gap = 1.01 → 3, wind = 1.0 → 3, humidity 95 → 2, cloud 30 → 1 = 9
        assertThat(score).isEqualTo(9.0);
    }

    @Test
    @DisplayName("boundary: wind exactly 1.5 scores 3 (top wind tier)")
    void calculate_windExactlyOneFive_topWindTier() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(6.0)
                .dewPoint(5.0)
                .windSpeed(new java.math.BigDecimal("1.5"))
                .humidity(95)
                .lowCloud(30)
                .build();

        Double score = InversionScoreCalculator.calculate(data);
        // gap = 1.0 → 4, wind = 1.5 → 3, humidity 95 → 2, cloud 30 → 1 = 10
        assertThat(score).isEqualTo(10.0);
    }

    @Test
    @DisplayName("boundary: humidity exactly 70 scores 1 (lowest non-zero tier)")
    void calculate_humidityExactly70_lowestTier() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(15.0)
                .dewPoint(7.0)
                .windSpeed(new java.math.BigDecimal("8.0"))
                .humidity(70)
                .lowCloud(5)
                .build();

        Double score = InversionScoreCalculator.calculate(data);
        // gap = 8 → 0, wind = 8 → 0, humidity 70 → 1.0, cloud 5 → 0 = 1.0
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    @DisplayName("boundary: humidity 69 scores 0 (below threshold)")
    void calculate_humidity69_zeroHumidityScore() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(15.0)
                .dewPoint(7.0)
                .windSpeed(new java.math.BigDecimal("8.0"))
                .humidity(69)
                .lowCloud(5)
                .build();

        Double score = InversionScoreCalculator.calculate(data);
        // gap = 8 → 0, wind = 8 → 0, humidity 69 → 0, cloud 5 → 0 = 0
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("boundary: low cloud exactly 20 scores 1 (in optimal range)")
    void calculate_lowCloud20_inOptimalRange() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(15.0)
                .dewPoint(7.0)
                .windSpeed(new java.math.BigDecimal("8.0"))
                .humidity(50)
                .lowCloud(20)
                .build();

        Double score = InversionScoreCalculator.calculate(data);
        // gap = 8 → 0, wind = 8 → 0, humidity 50 → 0, cloud 20 → 1.0 = 1.0
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    @DisplayName("boundary: low cloud exactly 60 scores 1 (top of optimal range)")
    void calculate_lowCloud60_topOfOptimalRange() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(15.0)
                .dewPoint(7.0)
                .windSpeed(new java.math.BigDecimal("8.0"))
                .humidity(50)
                .lowCloud(60)
                .build();

        Double score = InversionScoreCalculator.calculate(data);
        // gap = 8 → 0, wind = 8 → 0, humidity 50 → 0, cloud 60 → 1.0 = 1.0
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    @DisplayName("boundary: low cloud 61 scores 0.5 (overcast range)")
    void calculate_lowCloud61_overcastRange() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(15.0)
                .dewPoint(7.0)
                .windSpeed(new java.math.BigDecimal("8.0"))
                .humidity(50)
                .lowCloud(61)
                .build();

        Double score = InversionScoreCalculator.calculate(data);
        // gap = 8 → 0, wind = 8 → 0, humidity 50 → 0, cloud 61 → 0.5 = 0.5
        assertThat(score).isEqualTo(0.5);
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
