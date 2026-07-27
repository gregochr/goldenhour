package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.model.AtmosphericData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InversionScoreCalculator}.
 *
 * <p>The score composes three independent surface terms — moisture (0–5), wind (0–3) and the cloud
 * deck (0–2) — behind three gates: low cloud outside 15–80 %, no measured temperature reversal
 * aloft, and a stable layer deeper than the viewpoint each cap the result below the band they
 * would otherwise reach.
 *
 * <p>The STRONG band that drives the inversion hot topic starts at 9, so these tests care most
 * about what can and cannot reach it. Every threshold is exercised at the boundary, one below and
 * one above, and sweeps over the whole input space pin the two invariants that matter: no vetoed
 * cloud field can score STRONG, and neither can any location without a measured reversal.
 */
class InversionScoreCalculatorTest {

    /** Elevation passed to the calculator throughout — well above {@link #STABLE_LAYER_M}. */
    private static final int ELEVATION_M = 500;

    /** A shallow nocturnal stable layer, so the clearance gate passes by default. */
    private static final int STABLE_LAYER_M = 150;

    /** Baseline surface temperature; the temp–dew gap is added on top of it. */
    private static final double BASE_TEMP_C = 5.0;

    /** A well-defined reversal, so the lapse gate does not cap the surface-term tests. */
    private static final double STRONG_REVERSAL_C = 3.0;

    /** Runs the calculator at {@link #ELEVATION_M}. */
    private static Double score(AtmosphericData data) {
        return InversionScoreCalculator.calculate(data, ELEVATION_M);
    }

    /**
     * Partial builder for the three surface inputs the score reads, with no vertical profile.
     *
     * @param gap      temperature minus dew point in °C
     * @param windMs   10 m wind speed in m/s
     * @param lowCloud low cloud cover percentage
     * @return the builder, for the caller to add profile data
     */
    private static TestAtmosphericData surface(double gap, String windMs, int lowCloud) {
        return TestAtmosphericData.builder()
                .temperature(BASE_TEMP_C + gap)
                .dewPoint(BASE_TEMP_C)
                .windSpeed(new BigDecimal(windMs))
                .lowCloud(lowCloud)
                .boundaryLayerHeight(STABLE_LAYER_M);
    }

    /**
     * Builds atmospheric data for the three surface inputs, with a well-defined reversal and a
     * shallow stable layer so both vertical gates pass and only the surface terms vary.
     *
     * @param gap      temperature minus dew point in °C
     * @param windMs   10 m wind speed in m/s
     * @param lowCloud low cloud cover percentage
     * @return the atmospheric data
     */
    private static AtmosphericData conditions(double gap, String windMs, int lowCloud) {
        return surface(gap, windMs, lowCloud)
                .temperature925hPa(BASE_TEMP_C + gap + STRONG_REVERSAL_C)
                .build();
    }

    // ── Null guards ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns null when atmospheric data is null")
    void calculate_nullData_returnsNull() {
        assertThat(InversionScoreCalculator.calculate(null, ELEVATION_M)).isNull();
    }

    @Test
    @DisplayName("returns null when temperature is missing")
    void calculate_missingTemperature_returnsNull() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(null)
                .dewPoint(5.0)
                .build();

        assertThat(score(data)).isNull();
    }

    @Test
    @DisplayName("returns null when dew point is missing")
    void calculate_missingDewPoint_returnsNull() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(10.0)
                .dewPoint(null)
                .build();

        assertThat(score(data)).isNull();
    }

    @Test
    @DisplayName("returns null when weather data is null")
    void calculate_nullWeather_returnsNull() {
        AtmosphericData data = new AtmosphericData(
                "Test", null, null, new com.gregochr.goldenhour.model.CloudData(10, 50, 30),
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
        assertThat(score(data)).isNull();
    }

    @Test
    @DisplayName("returns null when cloud data is null")
    void calculate_nullCloud_returnsNull() {
        AtmosphericData data = new AtmosphericData(
                "Test", null, null, null,
                new com.gregochr.goldenhour.model.WeatherData(
                        25000, new BigDecimal("3.50"), 225,
                        BigDecimal.ZERO, 62, 3,
                        new BigDecimal("180.00"), null, 1013.25, null, null, null, null, null),
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
        assertThat(score(data)).isNull();
    }

    // ── Scale endpoints ─────────────────────────────────────────────────────

    @Test
    @DisplayName("saturated + calm + well-defined deck scores the full 10")
    void calculate_idealConditions_scoresTen() {
        // moisture 5 (gap 0.4) + wind 3 (1.0 m/s) + cloud 2 (45%) = 10
        assertThat(score(conditions(0.4, "1.0", 45)))
                .isEqualTo(10.0);
    }

    @Test
    @DisplayName("dry, windy, cloudless conditions score zero")
    void calculate_dryWindyClear_scoresZero() {
        // moisture 0 (gap 8) + wind 0 (8 m/s), then cloud-vetoed at 5% → min(0, 6) = 0
        assertThat(score(conditions(8.0, "8.0", 5))).isEqualTo(0.0);
    }

    // ── The double-count that made every dawn score 9 ───────────────────────

    @Nested
    @DisplayName("moisture is counted once")
    class MoistureCountedOnce {

        @Test
        @DisplayName("relative humidity does not move the score — it is the temp-dew gap restated")
        void humidity_doesNotAffectScore() {
            AtmosphericData dry = surface(0.4, "1.0", 45)
                    .temperature925hPa(BASE_TEMP_C + 0.4 + STRONG_REVERSAL_C)
                    .humidity(40)
                    .build();
            AtmosphericData humid = surface(0.4, "1.0", 45)
                    .temperature925hPa(BASE_TEMP_C + 0.4 + STRONG_REVERSAL_C)
                    .humidity(99)
                    .build();

            // Both reach the top of the scale — the point is that humidity moved nothing.
            assertThat(score(dry)).isEqualTo(10.0).isEqualTo(score(humid));
        }

        @Test
        @DisplayName("moisture alone cannot exceed 5 of the 10 points")
        void moisture_capsAtFive() {
            // Saturated but windy (>5 m/s → 0) with an ideal deck: 5 + 0 + 2 = 7.
            assertThat(score(conditions(0.0, "9.0", 45)))
                    .isEqualTo(7.0);
        }

        @Test
        @DisplayName("a calm, saturated morning no longer reaches STRONG on its own")
        void calmAndSaturated_withoutCloud_cannotReachStrong() {
            // The regression: moisture(4) + wind(3) + humidity(2) used to total 9 with no cloud
            // at all, so every calm humid dawn fired the STRONG hot topic.
            assertThat(score(conditions(0.0, "0.5", 0)))
                    .isLessThan(9.0);
        }
    }

    // ── The cloud veto ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("low cloud gate")
    class LowCloudGate {

        @Test
        @DisplayName("overcast: the viewpoint is inside the cloud, so the score is vetoed")
        void overcast_vetoed() {
            // Previously 9.5 → reported as a STRONG 9/10 for a location sitting in the murk.
            Double score = score(conditions(0.5, "1.0", 100));

            assertThat(score).isEqualTo(InversionScoreCalculator.VETOED_CEILING);
            assertThat(score).isLessThan(9.0);
        }

        @Test
        @DisplayName("cloudless: there is no cloud to form a sea, so the score is vetoed")
        void cloudless_vetoed() {
            // Previously 9.0 → a STRONG "sea of clouds" with no cloud in it.
            Double score = score(conditions(0.5, "1.0", 0));

            assertThat(score).isEqualTo(InversionScoreCalculator.VETOED_CEILING);
            assertThat(score).isLessThan(9.0);
        }

        @Test
        @DisplayName("boundary: 15% is not vetoed, 14% is")
        void lowerBoundary() {
            assertThat(score(conditions(0.4, "1.0", 15)))
                    .isEqualTo(9.0);
            assertThat(score(conditions(0.4, "1.0", 14)))
                    .isEqualTo(InversionScoreCalculator.VETOED_CEILING);
        }

        @Test
        @DisplayName("boundary: 80% is not vetoed, 81% is")
        void upperBoundary() {
            assertThat(score(conditions(0.4, "1.0", 80)))
                    .isEqualTo(9.0);
            assertThat(score(conditions(0.4, "1.0", 81)))
                    .isEqualTo(InversionScoreCalculator.VETOED_CEILING);
        }

        @Test
        @DisplayName("a vetoed score still orders by moisture and wind below the ceiling")
        void vetoed_ordersBelowCeiling() {
            Double calmSaturated = score(conditions(0.4, "1.0", 100));
            Double windyDry = score(conditions(6.0, "7.0", 100));

            assertThat(calmSaturated).isEqualTo(InversionScoreCalculator.VETOED_CEILING);
            assertThat(windyDry).isEqualTo(0.0);
            assertThat(calmSaturated).isGreaterThan(windyDry);
        }

        @Test
        @DisplayName("boundary: 25% earns the full deck score, 24% the shoulder score")
        void idealDeckLowerBoundary() {
            assertThat(score(conditions(0.4, "1.0", 25)))
                    .isEqualTo(10.0);
            assertThat(score(conditions(0.4, "1.0", 24)))
                    .isEqualTo(9.0);
        }

        @Test
        @DisplayName("boundary: 65% earns the full deck score, 66% the shoulder score")
        void idealDeckUpperBoundary() {
            assertThat(score(conditions(0.4, "1.0", 65)))
                    .isEqualTo(10.0);
            assertThat(score(conditions(0.4, "1.0", 66)))
                    .isEqualTo(9.0);
        }
    }

    // ── The vertical profile: is there actually an inversion? ───────────────

    @Nested
    @DisplayName("lapse gate — a measured temperature reversal")
    class LapseGate {

        /** Ideal surface conditions (would score 10) with the given 925 hPa temperature. */
        private Double withTemp925(Double temp925) {
            return score(surface(0.4, "1.0", 45).temperature925hPa(temp925).build());
        }

        /** Ideal surface conditions with a reversal of {@code reversal} °C at 925 hPa. */
        private Double withReversal(double reversal) {
            return withTemp925(BASE_TEMP_C + 0.4 + reversal);
        }

        @Test
        @DisplayName("temperature falling with height is an ordinary lapse — no inversion, vetoed")
        void normalLapse_vetoed() {
            // Perfect surface conditions, but the air aloft is colder: no inversion exists.
            assertThat(withReversal(-6.5))
                    .isEqualTo(InversionScoreCalculator.VETOED_CEILING);
        }

        @Test
        @DisplayName("boundary: a 0.0°C reversal is not vetoed, -0.01°C is")
        void reversalSignBoundary() {
            assertThat(withReversal(0.0))
                    .isEqualTo(InversionScoreCalculator.MARGINAL_CEILING);
            assertThat(withReversal(-0.01))
                    .isEqualTo(InversionScoreCalculator.VETOED_CEILING);
        }

        @Test
        @DisplayName("boundary: a 2.0°C reversal reaches STRONG, 1.99°C caps at MODERATE")
        void wellDefinedReversalBoundary() {
            assertThat(withReversal(InversionScoreCalculator.STRONG_REVERSAL_CELSIUS))
                    .isEqualTo(10.0);
            assertThat(withReversal(InversionScoreCalculator.STRONG_REVERSAL_CELSIUS - 0.01))
                    .isEqualTo(InversionScoreCalculator.MARGINAL_CEILING);
        }

        @Test
        @DisplayName("no pressure-level data caps below STRONG rather than claiming it")
        void noProfile_capsAtMarginal() {
            // This is the heart of the "always 9/10 strong" complaint: with no vertical profile
            // there is no evidence of an inversion, so STRONG must be unreachable — but the
            // feature should degrade, not vanish, so MODERATE stays available.
            Double result = withTemp925(null);

            assertThat(result).isEqualTo(InversionScoreCalculator.MARGINAL_CEILING);
            assertThat(result).isLessThan(9.0);
        }

        @Test
        @DisplayName("850 hPa is used when 925 hPa is absent")
        void fallsBackTo850hPa() {
            AtmosphericData data = surface(0.4, "1.0", 45)
                    .temperature925hPa(null)
                    .temperature850hPa(BASE_TEMP_C + 0.4 + STRONG_REVERSAL_C)
                    .build();

            assertThat(score(data)).isEqualTo(10.0);
        }

        @Test
        @DisplayName("925 hPa wins when both levels are present")
        void prefers925hPaOver850hPa() {
            // 925 shows no reversal, 850 shows a strong one. The nearer level decides, so the
            // score is vetoed rather than lifted by the level 1500 m up.
            AtmosphericData data = surface(0.4, "1.0", 45)
                    .temperature925hPa(BASE_TEMP_C + 0.4 - 1.0)
                    .temperature850hPa(BASE_TEMP_C + 0.4 + 5.0)
                    .build();

            assertThat(score(data)).isEqualTo(InversionScoreCalculator.VETOED_CEILING);
        }

        @Test
        @DisplayName("reversalCelsius reports the difference, or null with no profile")
        void reversalCelsius_reportsTheDifference() {
            AtmosphericData reversed = surface(0.0, "1.0", 45)
                    .temperature925hPa(BASE_TEMP_C + 2.5)
                    .build();

            assertThat(InversionScoreCalculator.reversalCelsius(reversed.weather(), BASE_TEMP_C))
                    .isEqualTo(2.5);
            assertThat(InversionScoreCalculator.reversalCelsius(
                    surface(0.0, "1.0", 45).build().weather(), BASE_TEMP_C)).isNull();
        }
    }

    @Nested
    @DisplayName("clearance gate — the viewpoint must stand above the stable layer")
    class ClearanceGate {

        /** Ideal surface conditions and a strong reversal, at the given layer depth/elevation. */
        private Double at(int boundaryLayerMetres, Integer elevationMetres) {
            AtmosphericData data = surface(0.4, "1.0", 45)
                    .temperature925hPa(BASE_TEMP_C + 0.4 + STRONG_REVERSAL_C)
                    .boundaryLayerHeight(boundaryLayerMetres)
                    .build();
            return InversionScoreCalculator.calculate(data, elevationMetres);
        }

        @Test
        @DisplayName("a viewpoint above a shallow nocturnal layer scores freely")
        void aboveTheLayer_notVetoed() {
            assertThat(at(150, 500)).isEqualTo(10.0);
        }

        @Test
        @DisplayName("a deep mixed layer swallows the viewpoint — vetoed")
        void insideTheLayer_vetoed() {
            // The daytime case: a 1200 m mixed layer over a 500 m fell is not an inversion you
            // can stand above, whatever the surface readings say.
            assertThat(at(1200, 500)).isEqualTo(InversionScoreCalculator.VETOED_CEILING);
        }

        @Test
        @DisplayName("boundary: elevation one metre above the layer clears it, equal does not")
        void clearanceBoundary() {
            assertThat(at(499, 500)).isEqualTo(10.0);
            assertThat(at(500, 500)).isEqualTo(InversionScoreCalculator.VETOED_CEILING);
        }

        @Test
        @DisplayName("unknown elevation skips the gate rather than vetoing on absent data")
        void nullElevation_gateSkipped() {
            assertThat(at(1200, null)).isEqualTo(10.0);
        }
    }

    // ── Term boundaries ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("moisture bands (wind and cloud held at their maxima)")
    class MoistureBands {

        /** Wind 3 + cloud 2 = 5, so the returned score is the moisture term + 5. */
        private Double at(double gap) {
            return score(conditions(gap, "1.0", 45));
        }

        @Test
        @DisplayName("gap 0.5 scores 5, gap 0.51 scores 4")
        void saturatedBoundary() {
            assertThat(at(0.5)).isEqualTo(10.0);
            assertThat(at(0.51)).isEqualTo(9.0);
        }

        @Test
        @DisplayName("gap 1.0 scores 4, gap 1.01 scores 3")
        void nearSaturatedBoundary() {
            assertThat(at(1.0)).isEqualTo(9.0);
            assertThat(at(1.01)).isEqualTo(8.0);
        }

        @Test
        @DisplayName("gap 2.0 scores 3, gap 2.01 scores 2")
        void moistBoundary() {
            assertThat(at(2.0)).isEqualTo(8.0);
            assertThat(at(2.01)).isEqualTo(7.0);
        }

        @Test
        @DisplayName("gap 3.0 scores 2, gap 3.01 scores 1")
        void moderateBoundary() {
            assertThat(at(3.0)).isEqualTo(7.0);
            assertThat(at(3.01)).isEqualTo(6.0);
        }

        @Test
        @DisplayName("gap 5.0 scores 1, gap 5.01 scores 0")
        void dryBoundary() {
            assertThat(at(5.0)).isEqualTo(6.0);
            assertThat(at(5.01)).isEqualTo(5.0);
        }
    }

    @Nested
    @DisplayName("wind bands (moisture and cloud held at their maxima)")
    class WindBands {

        /** Moisture 5 + cloud 2 = 7, so the returned score is the wind term + 7. */
        private Double at(String windMs) {
            return score(conditions(0.4, windMs, 45));
        }

        @Test
        @DisplayName("wind 1.5 scores 3, wind 1.51 scores 2")
        void calmBoundary() {
            assertThat(at("1.5")).isEqualTo(10.0);
            assertThat(at("1.51")).isEqualTo(9.0);
        }

        @Test
        @DisplayName("wind 3.0 scores 2, wind 3.01 scores 1")
        void lightBoundary() {
            assertThat(at("3.0")).isEqualTo(9.0);
            assertThat(at("3.01")).isEqualTo(8.0);
        }

        @Test
        @DisplayName("wind 5.0 scores 1, wind 5.01 scores 0")
        void breezyBoundary() {
            assertThat(at("5.0")).isEqualTo(8.0);
            assertThat(at("5.01")).isEqualTo(7.0);
        }
    }

    // ── Invariants over the whole input space ───────────────────────────────

    @Nested
    @DisplayName("invariants across the input space")
    class Invariants {

        /** A per-sample assertion applied by {@link #sweep}. */
        @FunctionalInterface
        interface ScoreCheck {
            void accept(double gap, double wind, int cloud, Double score);
        }

        /** Sweeps gap 0–8 °C, wind 0–8 m/s and low cloud 0–100 %, applying {@code check}. */
        private void sweep(ScoreCheck check) {
            for (int gapStep = 0; gapStep <= 32; gapStep++) {
                double gap = gapStep * 0.25;
                for (int windStep = 0; windStep <= 32; windStep++) {
                    double wind = windStep * 0.25;
                    String windMs = String.valueOf(wind);
                    for (int cloud = 0; cloud <= 100; cloud++) {
                        check.accept(gap, wind, cloud,
                                score(conditions(gap, windMs, cloud)));
                    }
                }
            }
        }

        @Test
        @DisplayName("the score never leaves the 0–10 scale")
        void scoreStaysInRange() {
            sweep((gap, wind, cloud, score) ->
                    assertThat(score)
                            .as("gap %s, wind %s, cloud %d", gap, wind, cloud)
                            .isBetween(0.0, 10.0));
        }

        @Test
        @DisplayName("no cloud field outside 15–80% can reach the STRONG band")
        void vetoedCloudNeverReachesStrong() {
            sweep((gap, wind, cloud, score) -> {
                if (cloud < InversionScoreCalculator.MIN_CLOUD_PERCENT
                        || cloud > InversionScoreCalculator.MAX_CLOUD_PERCENT) {
                    assertThat(score)
                            .as("vetoed cloud %d%% (gap %s, wind %s) must not read STRONG",
                                    cloud, gap, wind)
                            .isLessThan(9.0);
                }
            });
        }

        @Test
        @DisplayName("with no vertical profile, nothing anywhere in the space reaches STRONG")
        void withoutProfile_nothingReachesStrong() {
            // The single strongest guard against the original complaint: no combination of
            // surface readings can produce a STRONG inversion claim on its own.
            for (int gapStep = 0; gapStep <= 32; gapStep++) {
                double gap = gapStep * 0.25;
                for (int windStep = 0; windStep <= 32; windStep++) {
                    String windMs = String.valueOf(windStep * 0.25);
                    for (int cloud = 0; cloud <= 100; cloud++) {
                        assertThat(score(surface(gap, windMs, cloud).build()))
                                .as("gap %s, wind %s, cloud %d with no profile", gap, windMs, cloud)
                                .isLessThan(9.0);
                    }
                }
            }
        }

        @Test
        @DisplayName("reaching STRONG requires all three legs — moisture, light wind and a deck")
        void strongRequiresAllThreeLegs() {
            sweep((gap, wind, cloud, score) -> {
                if (score >= 9.0) {
                    assertThat(gap)
                            .as("STRONG at cloud %d%% needs near-saturation", cloud)
                            .isLessThanOrEqualTo(1.0);
                    assertThat(wind)
                            .as("STRONG at cloud %d%% needs light wind", cloud)
                            .isLessThanOrEqualTo(3.0);
                    assertThat(cloud)
                            .as("STRONG needs an actual cloud deck")
                            .isBetween(InversionScoreCalculator.MIN_CLOUD_PERCENT,
                                    InversionScoreCalculator.MAX_CLOUD_PERCENT);
                }
            });
        }
    }
}
