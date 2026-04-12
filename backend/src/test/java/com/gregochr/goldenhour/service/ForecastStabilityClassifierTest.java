package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ForecastStabilityClassifier}.
 */
class ForecastStabilityClassifierTest {

    private static final String GRID_KEY = "54.7500,-1.6250";

    private ForecastStabilityClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new ForecastStabilityClassifier();
    }

    @Test
    @DisplayName("High pressure with low precip classifies as SETTLED")
    void highPressureLowPrecip_settled() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                steadyPressure(1025.0), lowPrecipProbs(), clearWeatherCodes(), calmGusts());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        assertThat(result.evaluationWindowDays()).isEqualTo(3);
        assertThat(result.reason()).contains("High pressure dominant");
    }

    @Test
    @DisplayName("Deep low with rapid pressure fall classifies as UNSETTLED")
    void deepLowRapidFall_unsettled() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                fallingPressure(985.0, -8.0), highPrecipProbs(), activeWeatherCodes(), gustyWind());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.stability()).isEqualTo(ForecastStability.UNSETTLED);
        assertThat(result.evaluationWindowDays()).isEqualTo(0);
        assertThat(result.reason()).contains("frontal passage");
    }

    @Test
    @DisplayName("Moderate pressure fall with some precip classifies as TRANSITIONAL")
    void moderateFall_transitional() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                fallingPressure(1010.0, -4.0), moderatePrecipProbs(),
                clearWeatherCodes(), calmGusts());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.stability()).isEqualTo(ForecastStability.TRANSITIONAL);
        assertThat(result.evaluationWindowDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("Null hourly data returns TRANSITIONAL with reason")
    void nullHourly_transitional() {
        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, null);

        assertThat(result.stability()).isEqualTo(ForecastStability.TRANSITIONAL);
        assertThat(result.reason()).contains("Insufficient");
    }

    @Test
    @DisplayName("Rising pressure stabilises score")
    void risingPressure_reducesScore() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                risingPressure(1015.0, 4.0), lowPrecipProbs(), clearWeatherCodes(), calmGusts());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        assertThat(result.reason()).contains("rising");
    }

    @Test
    @DisplayName("Active weather codes in T+2 to T+3 window add unsettled score")
    void activeCodesLate_addScore() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                steadyPressure(1010.0), moderatePrecipProbs(), activeWeatherCodes(), calmGusts());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.reason()).contains("Active weather codes");
    }

    @Test
    @DisplayName("High gust variance adds to unsettled score")
    void highGustVariance_addScore() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                steadyPressure(1010.0), lowPrecipProbs(), clearWeatherCodes(), gustyWind());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.reason()).contains("gust variance");
    }

    @Test
    @DisplayName("Variance calculation is correct")
    void variance_calculation() {
        double v = classifier.variance(List.of(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0));
        assertThat(v).isCloseTo(4.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("ForecastStability enum window days are correct")
    void enumWindowDays() {
        assertThat(ForecastStability.SETTLED.evaluationWindowDays()).isEqualTo(3);
        assertThat(ForecastStability.TRANSITIONAL.evaluationWindowDays()).isEqualTo(1);
        assertThat(ForecastStability.UNSETTLED.evaluationWindowDays()).isEqualTo(0);
    }

    // ── Null / missing signal tests ──

    @Test
    @DisplayName("Null pressure list does not affect classification")
    void nullPressure_noEffect() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                null, lowPrecipProbs(), clearWeatherCodes(), calmGusts());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
    }

    @Test
    @DisplayName("Null precipitation list does not affect classification")
    void nullPrecip_noEffect() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                steadyPressure(1025.0), null, clearWeatherCodes(), calmGusts());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
    }

    @Test
    @DisplayName("Null gust list does not affect classification")
    void nullGusts_noEffect() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                steadyPressure(1025.0), lowPrecipProbs(), clearWeatherCodes(), null);

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
    }

    @Test
    @DisplayName("Null weather code list does not affect classification")
    void nullWeatherCodes_noEffect() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                steadyPressure(1025.0), lowPrecipProbs(), null, calmGusts());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
    }

    // ── Short array / boundary tests ──

    @Test
    @DisplayName("Too few hours returns TRANSITIONAL")
    void tooFewHours_transitional() {
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(List.of("2026-04-03T00:00", "2026-04-03T01:00"));
        hourly.setPressureMsl(List.of(1020.0, 1020.0));

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.stability()).isEqualTo(ForecastStability.TRANSITIONAL);
        assertThat(result.reason()).contains("Insufficient");
    }

    @Test
    @DisplayName("Weather codes array shorter than 48 hours skips code signal")
    void shortWeatherCodes_skipsSignal() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                steadyPressure(1010.0), lowPrecipProbs(),
                Collections.nCopies(40, 63), calmGusts());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.reason()).doesNotContain("Active weather codes");
    }

    @Test
    @DisplayName("Active codes only in T+0 to T+1 window do not trigger signal")
    void activeCodesEarly_noSignal() {
        List<Integer> codes = new ArrayList<>(Collections.nCopies(48, 65));
        codes.addAll(Collections.nCopies(48, 0));
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                steadyPressure(1010.0), lowPrecipProbs(), codes, calmGusts());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.reason()).doesNotContain("Active weather codes");
    }

    // ── Signal combination tests ──

    @Test
    @DisplayName("Opposing signals cancel out: deep low + rising pressure + low precip")
    void opposingSignals_cancelOut() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                risingPressure(988.0, 5.0), lowPrecipProbs(), clearWeatherCodes(), calmGusts());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        // Deep low (+2), rising (-1), low precip (-1), high pressure (not triggered)
        // = score 0 → SETTLED
        assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
    }

    @Test
    @DisplayName("Moderate pressure (1010 steady) with high precip+variance = TRANSITIONAL")
    void moderatePressureHighPrecip_transitional() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                steadyPressure(1010.0), highPrecipProbs(), clearWeatherCodes(), calmGusts());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.stability()).isEqualTo(ForecastStability.TRANSITIONAL);
    }

    @Test
    @DisplayName("High pressure at exactly 1018 hPa with stable delta → reason contains 'High pressure dominant'")
    void highPressureAt1018Boundary_reducesScore() {
        // pressure = 1018, delta ≈ 0 → qualifies for the >= 1018 && >= -1 branch
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                steadyPressure(1018.0), lowPrecipProbs(), clearWeatherCodes(), calmGusts());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.reason()).contains("High pressure dominant");
        assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
    }

    @Test
    @DisplayName("Pressure at 1017 hPa (just below 1018 threshold) → no 'High pressure dominant' signal")
    void pressureJustBelow1018_noHighPressureSignal() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                steadyPressure(1017.0), lowPrecipProbs(), clearWeatherCodes(), calmGusts());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.reason()).doesNotContain("High pressure dominant");
    }

    @Test
    @DisplayName("Precip max > 70% with high variance → reason contains 'uncertain timing'")
    void highPrecipWithHighVariance_uncertainTimingInReason() {
        // alternating 90/10 → max=90 > 70, variance=1600 > 400
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                steadyPressure(1010.0), highPrecipProbs(), clearWeatherCodes(), calmGusts());

        GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

        assertThat(result.reason()).contains("timing uncertain");
        assertThat(result.reason()).contains("90%");
    }

    // ── Result field population ──

    @Test
    @DisplayName("Result carries correct grid cell key and coordinates")
    void resultFieldPopulation() {
        OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                steadyPressure(1020.0), lowPrecipProbs(), clearWeatherCodes(), calmGusts());

        GridCellStabilityResult result = classifier.classify("55.0000,-2.0000", 55.0, -2.0, hourly);

        assertThat(result.gridCellKey()).isEqualTo("55.0000,-2.0000");
        assertThat(result.gridLat()).isEqualTo(55.0);
        assertThat(result.gridLng()).isEqualTo(-2.0);
    }

    @Test
    @DisplayName("Variance of empty list is zero")
    void variance_emptyList() {
        assertThat(classifier.variance(List.of())).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Variance of single value is zero")
    void variance_singleValue() {
        assertThat(classifier.variance(List.of(5.0))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Variance of identical values is zero")
    void variance_identicalValues() {
        assertThat(classifier.variance(List.of(7.0, 7.0, 7.0))).isEqualTo(0.0);
    }

    // ── Nested boundary / mutation-targeted tests ──

    @Nested
    @DisplayName("classify() — data-sufficiency and score threshold boundaries")
    class ClassifyThresholdTests {

        @Test
        @DisplayName("exactly 24 hours is sufficient — classify proceeds (24 NOT < 24)")
        void exactly24Hours_isClassified() {
            // Kills: changing < to <= on MIN_HOURS_REQUIRED would reject valid 24-hour data
            OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
            hourly.setTime(nTimes(24));
            hourly.setPressureMsl(Collections.nCopies(24, 1010.0));

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).doesNotContain("Insufficient");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        }

        @Test
        @DisplayName("23 hours triggers Insufficient guard (23 < 24)")
        void only23Hours_returnsTransitionalInsufficientData() {
            OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
            hourly.setTime(nTimes(23));
            hourly.setPressureMsl(Collections.nCopies(23, 1010.0));

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.stability()).isEqualTo(ForecastStability.TRANSITIONAL);
            assertThat(result.reason()).contains("Insufficient");
        }

        @Test
        @DisplayName("score=4 classifies UNSETTLED — at UNSETTLED_THRESHOLD; pressure += mutant changes result")
        void score4_rapidFallAndGust_isUnsettled() {
            // Rapid pressure fall (-7 hPa/24h) → +3; gusty wind → +1; total = 4 → UNSETTLED
            // If += assessPressure becomes -=: score = -3 + 1 = -2 → SETTLED (kills line 72 mutant)
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    fallingPressure(1010.0, -7.0), null, null, gustyWind());

            assertThat(classifier.classify(GRID_KEY, 54.75, -1.625, hourly).stability())
                    .isEqualTo(ForecastStability.UNSETTLED);
        }

        @Test
        @DisplayName("score=3 classifies TRANSITIONAL — codes+gust; gust += mutant changes result")
        void score3_codesAndGust_isTransitional() {
            // Active weather codes → +2; gusty wind → +1; total = 3 → TRANSITIONAL
            // If += assessGustVariance becomes -=: score = 2 - 1 = 1 → SETTLED (kills line 75 mutant)
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), null, activeWeatherCodes(), gustyWind());

            assertThat(classifier.classify(GRID_KEY, 54.75, -1.625, hourly).stability())
                    .isEqualTo(ForecastStability.TRANSITIONAL);
        }

        @Test
        @DisplayName("score=2 classifies TRANSITIONAL — codes only; codes += mutant changes result")
        void score2_activeCodesOnly_isTransitional() {
            // Active weather codes → +2; all others zero; total = 2 → TRANSITIONAL
            // If += assessWeatherCodes becomes -=: score = -2 → SETTLED (kills line 74 mutant)
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), null, activeWeatherCodes(), null);

            assertThat(classifier.classify(GRID_KEY, 54.75, -1.625, hourly).stability())
                    .isEqualTo(ForecastStability.TRANSITIONAL);
        }

        @Test
        @DisplayName("score=1 classifies SETTLED — just below TRANSITIONAL_THRESHOLD")
        void score1_moderatePrecipOnly_isSettled() {
            // Moderate precip (55%) → +1; total = 1 → SETTLED
            // Kills: changing >= 2 to >= 1 on TRANSITIONAL_THRESHOLD would flip this to TRANSITIONAL
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), moderatePrecipProbs(), null, null);

            assertThat(classifier.classify(GRID_KEY, 54.75, -1.625, hourly).stability())
                    .isEqualTo(ForecastStability.SETTLED);
        }

        @Test
        @DisplayName("precip score=3 alone is TRANSITIONAL; precip += mutant changes result")
        void precipScore3_alone_isTransitional() {
            // Alternating 90/10 → max=90 > 70, variance ≈ 1600 > 400 → +3; total = 3 → TRANSITIONAL
            // If += assessPrecipitation becomes -=: score = -3 → SETTLED (kills line 73 mutant)
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), highPrecipProbs(), null, null);

            assertThat(classifier.classify(GRID_KEY, 54.75, -1.625, hourly).stability())
                    .isEqualTo(ForecastStability.TRANSITIONAL);
        }
    }

    @Nested
    @DisplayName("assessPressure() — threshold boundaries and delta direction")
    class AssessPressureTests {

        @Test
        @DisplayName("empty pressure list produces no signal (returns 0)")
        void emptyPressures_returns0() {
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    Collections.emptyList(), null, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).doesNotContain("falling").doesNotContain("rising");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        }

        @Test
        @DisplayName("rapid fall delta=-7 scores +3 and signals 'frontal passage'")
        void rapidFall_delta7_scores3_frontPassageSignal() {
            // delta = -7 < RAPID_FALL_HPA (-6.0) → +3 → total 3 → TRANSITIONAL
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    fallingPressure(1010.0, -7.0), null, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.stability()).isEqualTo(ForecastStability.TRANSITIONAL);
            assertThat(result.reason()).contains("frontal passage");
        }

        @Test
        @DisplayName("delta=-6.0 is NOT rapid fall (strict <) — falls to moderate branch (+1)")
        void rapidFall_atExactMinus6_isModerateNotRapid() {
            // -6.0 is NOT < -6.0 → no rapid fall; but -6.0 < -3.0 → moderate fall (+1)
            // Kills: changing < to <= on RAPID_FALL_HPA would promote to rapid (+3)
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    fallingPressure(1010.0, -6.0), null, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).contains("easing");
            assertThat(result.reason()).doesNotContain("frontal passage");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED); // score=1
        }

        @Test
        @DisplayName("delta=-3.001 just triggers moderate fall (+1)")
        void moderateFall_justBelowMinus3_scores1() {
            // -3.001 < MODERATE_FALL_HPA (-3.0) → moderate fall (+1) → SETTLED
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    fallingPressure(1010.0, -3.001), null, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).contains("easing");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        }

        @Test
        @DisplayName("delta=-3.0 is NOT moderate fall (strict <) — no fall signal")
        void moderateFall_atExactMinus3_noFallScore() {
            // -3.0 is NOT < -3.0 → no fall; -3.0 > 2.0? No → no rising; score = 0
            // Kills: changing < to <= on MODERATE_FALL_HPA would add +1
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    fallingPressure(1010.0, -3.0), null, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).doesNotContain("falling").doesNotContain("easing");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED); // score=0
        }

        @Test
        @DisplayName("delta=+2.001 triggers rising pressure signal (-1)")
        void risingPressure_justAbovePlus2_scoresNegative1() {
            // +2.001 > RISING_HPA (+2.0) → score -=1 → total -1 → SETTLED
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    fallingPressure(1010.0, 2.001), null, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).contains("stabilising");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        }

        @Test
        @DisplayName("delta=+2.0 is NOT rising (strict >) — no rising signal")
        void risingPressure_atExactPlus2_noRisingScore() {
            // +2.0 is NOT > +2.0 → no rising signal; score = 0
            // Kills: changing > to >= on RISING_HPA would incorrectly deduct -1
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    fallingPressure(1010.0, 2.0), null, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).doesNotContain("rising").doesNotContain("stabilising");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED); // score=0
        }

        @Test
        @DisplayName("pressure 989.9 (< 990.0) triggers deep low signal (+2)")
        void deepLow_989dot9_scores2() {
            // 989.9 < DEEP_LOW_THRESHOLD (990.0) → +2 → total 2 → TRANSITIONAL
            // Kills: changing < to <= would miss this case
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(989.9), null, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.stability()).isEqualTo(ForecastStability.TRANSITIONAL);
            assertThat(result.reason()).contains("Deep low");
        }

        @Test
        @DisplayName("pressure exactly 990.0 is NOT a deep low (strict <) — no deep low signal")
        void deepLow_atExact990_noDeepLowScore() {
            // 990.0 is NOT < 990.0 → no deep low signal; score = 0 → SETTLED
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(990.0), null, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).doesNotContain("Deep low");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        }

        @Test
        @DisplayName("high pressure 1018 with delta=-1.0 qualifies for bonus (delta >= -1.0)")
        void highPressure1018_deltaMinus1_qualifiesForBonus() {
            // pressureNow=1018 >= 1018, delta=-1.0 >= -1.0 → score -= 1 → SETTLED
            // Also: -1.0 NOT < -3.0, so no fall signal either
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    fallingPressure(1018.0, -1.0), null, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).contains("High pressure dominant");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        }

        @Test
        @DisplayName("high pressure 1018 with delta=-1.001 does NOT qualify (delta < -1.0)")
        void highPressure1018_deltaMinus1dot001_noHighPressureBonus() {
            // delta = -1.001 < -1.0 → condition delta >= -1.0 fails → no high pressure bonus
            // -1.001 is NOT < -3.0, so no moderate fall either; score = 0 → SETTLED
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    fallingPressure(1018.0, -1.001), null, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).doesNotContain("High pressure dominant");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED); // score=0
        }

        @Test
        @DisplayName("delta = pressure24h − pressureNow (subtraction not addition)")
        void deltaCalculation_usesSubtraction() {
            // pressureNow=1010 (index 0), pressure24h=1002 (index 24), delta = -8 < -6 → rapid fall → +3
            // If PIT flips subtraction to addition: delta = 2012 → rising → -1 (SETTLED instead)
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    fallingPressure(1010.0, -8.0), null, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.stability()).isEqualTo(ForecastStability.TRANSITIONAL);
            assertThat(result.reason()).contains("frontal passage");
        }

        @Test
        @DisplayName("high pressure score (-1) cancels moderate precip (+1) — increment mutant changes result")
        void highPressure_moderatePrecip_scoresCancelToSettled() {
            // pressureNow=1025 >= 1018, delta=0.0 >= -1.0 → high pressure → score -= 1
            // moderatePrecipProbs() (55% steady) → +1; total = 0 → SETTLED
            // If PIT mutates score -= 1 to score += 1: +1 + 1 = 2 → TRANSITIONAL (kills L117 mutant)
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1025.0), moderatePrecipProbs(), null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).contains("High pressure dominant");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED); // score=0
        }
    }

    @Nested
    @DisplayName("assessPrecipitation() — threshold boundaries and branch isolation")
    class AssessPrecipitationTests {

        @Test
        @DisplayName("empty precipitation list produces no signal (returns 0)")
        void emptyPrecipProbs_returns0() {
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), Collections.emptyList(), null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).doesNotContain("precip").doesNotContain("precipitation");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        }

        @Test
        @DisplayName("maxProb=71 with high variance (alternating 71/1) triggers high branch (+3)")
        void highPrecip71_highVariance_scores3() {
            // alternating 71/1 → max=71 > 70, variance = 35² = 1225 > 400 → +3 → TRANSITIONAL
            List<Integer> probs = new ArrayList<>();
            for (int i = 0; i < 96; i++) {
                probs.add(i % 2 == 0 ? 71 : 1);
            }
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), probs, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).contains("timing uncertain");
            assertThat(result.stability()).isEqualTo(ForecastStability.TRANSITIONAL);
        }

        @Test
        @DisplayName("maxProb=71 with LOW variance (all 71) falls to moderate branch (+1), not high")
        void highPrecip71_lowVariance_isModerateNotHigh() {
            // All 71 → max=71 > 70 but variance=0 NOT > 400 → not high branch
            // Falls to: 71 > 50 → moderate → +1 → SETTLED
            List<Integer> probs = Collections.nCopies(96, 71);
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), probs, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).contains("Significant precip probability");
            assertThat(result.reason()).doesNotContain("timing uncertain");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        }

        @Test
        @DisplayName("maxProb exactly 70 does NOT trigger high branch (strict >) — falls to moderate")
        void highPrecip_exactly70_notHighBranch() {
            // max=70, NOT > 70 → not high branch; 70 > 50 → moderate (+1) → SETTLED
            // Kills: changing > to >= on HIGH_PRECIP_PROB
            List<Integer> probs = Collections.nCopies(96, 70);
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), probs, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).doesNotContain("timing uncertain");
            assertThat(result.reason()).contains("Significant");
        }

        @Test
        @DisplayName("maxProb=70 with HIGH variance does NOT trigger high branch — strict > not >=")
        void highPrecip70_highVariance_isModerateNotHigh() {
            // alternating 70/0: max=70 (NOT > 70), variance=35²=1225 (> 400)
            // With mutation > → >=: 70>=70 AND 1225>400 → high branch (+3) → TRANSITIONAL
            // Without mutation: 70>70 false → moderate (+1) → SETTLED
            // Kills: changing > to >= on HIGH_PRECIP_PROB when variance is high
            List<Integer> probs = new ArrayList<>();
            for (int i = 0; i < 96; i++) {
                probs.add(i % 2 == 0 ? 70 : 0);
            }
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), probs, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).doesNotContain("timing uncertain");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED); // score=1
        }

        @Test
        @DisplayName("maxProb=71 with variance exactly 400 does NOT trigger high branch — strict > not >=")
        void highPrecip71_varianceExactly400_isModerateNotHigh() {
            // alternating 71/31: max=71>70, mean=51, variance=20²=400 (NOT > 400)
            // With mutation > → >= on variance: 400>=400 → high branch (+3) → TRANSITIONAL
            // Without mutation: 400>400 false → moderate (+1) → SETTLED
            List<Integer> probs = new ArrayList<>();
            for (int i = 0; i < 96; i++) {
                probs.add(i % 2 == 0 ? 71 : 31);
            }
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), probs, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).doesNotContain("timing uncertain");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED); // score=1
        }

        @Test
        @DisplayName("maxProb=51 triggers moderate branch (+1)")
        void moderateProb51_scores1() {
            // 51 > 50 → moderate branch (+1) → SETTLED
            List<Integer> probs = Collections.nCopies(96, 51);
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), probs, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).contains("Significant precip probability");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        }

        @Test
        @DisplayName("maxProb exactly 50 does NOT trigger moderate branch (strict >) — no signal")
        void moderateProb_exactly50_notModerateBranch() {
            // max=50, NOT > 50; 50 < 20? No → no precip signal; score = 0
            // Kills: changing > to >= on MODERATE_PRECIP_PROB
            List<Integer> probs = Collections.nCopies(96, 50);
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), probs, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).doesNotContain("precip").doesNotContain("precipitation");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        }

        @Test
        @DisplayName("maxProb=19 triggers low-probability branch (-1)")
        void lowProb19_scoresNegative1() {
            // 19 < LOW_PRECIP_PROB (20) → -1 → SETTLED
            List<Integer> probs = Collections.nCopies(96, 19);
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), probs, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).contains("Low precipitation probability");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        }

        @Test
        @DisplayName("maxProb exactly 20 does NOT trigger low branch (strict <) — no signal")
        void lowProb_exactly20_notLowBranch() {
            // max=20, NOT < 20 → no low signal; 20 NOT > 50 → no moderate signal; score = 0
            // Kills: changing < to <= on LOW_PRECIP_PROB
            List<Integer> probs = Collections.nCopies(96, 20);
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), probs, null, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).doesNotContain("Low precipitation");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        }
    }

    @Nested
    @DisplayName("assessWeatherCodes() — size guard and active-code threshold")
    class AssessWeatherCodesTests {

        @Test
        @DisplayName("empty weather codes list returns 0")
        void emptyWeatherCodes_returns0() {
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), null, Collections.emptyList(), null);

            assertThat(classifier.classify(GRID_KEY, 54.75, -1.625, hourly).reason())
                    .doesNotContain("Active weather codes");
        }

        @Test
        @DisplayName("exactly 48 codes triggers size <= 48 guard — active code at index 47 is ignored")
        void exactly48Codes_skipped() {
            // codes.size() = 48 <= 48 → early return 0
            List<Integer> codes = new ArrayList<>(Collections.nCopies(47, 0));
            codes.add(65); // active but inside the skipped window
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), null, codes, null);

            assertThat(classifier.classify(GRID_KEY, 54.75, -1.625, hourly).reason())
                    .doesNotContain("Active weather codes");
        }

        @Test
        @DisplayName("exactly 49 codes with active code at index 48 scores 2")
        void exactly49Codes_activeCodeAtIndex48_scores2() {
            // 49 > 48 → not skipped; subList(48, 49) = [65], 65 >= 60 → active → +2 → TRANSITIONAL
            // Kills: changing <= to < on the size guard would process 48-entry lists too
            List<Integer> codes = new ArrayList<>(Collections.nCopies(48, 0));
            codes.add(65); // index 48: active in T+2 window
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), null, codes, null);

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).contains("Active weather codes");
            assertThat(result.stability()).isEqualTo(ForecastStability.TRANSITIONAL); // score=2
        }

        @Test
        @DisplayName("WMO code 60 at index 48 is active (60 >= ACTIVE_WEATHER_CODE_MIN)")
        void codeExactly60_isActive() {
            // Kills: changing >= to > on ACTIVE_WEATHER_CODE_MIN would skip code 60
            List<Integer> codes = new ArrayList<>(Collections.nCopies(48, 0));
            codes.add(60); // exactly at threshold
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), null, codes, null);

            assertThat(classifier.classify(GRID_KEY, 54.75, -1.625, hourly).reason())
                    .contains("Active weather codes");
        }

        @Test
        @DisplayName("WMO code 59 at index 48 is NOT active (59 < 60) — no signal")
        void codeExactly59_isNotActive() {
            List<Integer> codes = new ArrayList<>(Collections.nCopies(48, 0));
            codes.add(59); // just below threshold
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), null, codes, null);

            assertThat(classifier.classify(GRID_KEY, 54.75, -1.625, hourly).reason())
                    .doesNotContain("Active weather codes");
        }
    }

    @Nested
    @DisplayName("assessGustVariance() — variance threshold boundary")
    class AssessGustVarianceTests {

        @Test
        @DisplayName("empty gusts list produces no signal (returns 0)")
        void emptyGusts_returns0() {
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), null, null, Collections.emptyList());

            assertThat(classifier.classify(GRID_KEY, 54.75, -1.625, hourly).reason())
                    .doesNotContain("gust");
        }

        @Test
        @DisplayName("gust variance > 100 scores 1 and emits signal")
        void gustVarianceAbove100_scores1() {
            // gustyWind() alternates 30/3 → variance ≈ 182 > 100 → score=1; total=1 → SETTLED
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), null, null, gustyWind());

            GridCellStabilityResult result = classifier.classify(GRID_KEY, 54.75, -1.625, hourly);

            assertThat(result.reason()).contains("gust variance");
            assertThat(result.stability()).isEqualTo(ForecastStability.SETTLED);
        }

        @Test
        @DisplayName("gust variance exactly 100.0 does NOT score (strict >) ")
        void gustVarianceExactly100_doesNotScore() {
            // [0.0, 20.0]: mean=10, variance = ((0-10)² + (20-10)²) / 2 = 100.0, NOT > 100 → 0
            // Kills: changing > to >= on HIGH_GUST_VARIANCE
            List<Double> gusts = List.of(0.0, 20.0);
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), null, null, gusts);

            assertThat(classifier.classify(GRID_KEY, 54.75, -1.625, hourly).reason())
                    .doesNotContain("gust variance");
        }

        @Test
        @DisplayName("single gust value has variance 0 — returns 0")
        void singleGustValue_varianceZero_returns0() {
            List<Double> gusts = List.of(15.0);
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), null, null, gusts);

            assertThat(classifier.classify(GRID_KEY, 54.75, -1.625, hourly).reason())
                    .doesNotContain("gust variance");
        }

        @Test
        @DisplayName("calm uniform gusts have variance 0 — returns 0")
        void calmUniformGusts_varianceZero_returns0() {
            // calmGusts() = all 5.0 → variance = 0 → score = 0
            OpenMeteoForecastResponse.Hourly hourly = buildHourly(
                    steadyPressure(1010.0), null, null, calmGusts());

            assertThat(classifier.classify(GRID_KEY, 54.75, -1.625, hourly).reason())
                    .doesNotContain("gust variance");
        }
    }

    // ── Builders ──

    private OpenMeteoForecastResponse.Hourly buildHourly(List<Double> pressures,
            List<Integer> precipProbs, List<Integer> weatherCodes, List<Double> gusts) {
        OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
        List<String> times = new ArrayList<>();
        for (int i = 0; i < 96; i++) {
            times.add("2026-04-03T" + String.format("%02d", i % 24) + ":00");
        }
        h.setTime(times);
        h.setPressureMsl(pressures);
        h.setPrecipitationProbability(precipProbs);
        h.setWeatherCode(weatherCodes);
        h.setWindGusts10m(gusts);
        return h;
    }

    private List<Double> steadyPressure(double value) {
        return Collections.nCopies(96, value);
    }

    private List<Double> fallingPressure(double start, double deltaPerDay) {
        List<Double> p = new ArrayList<>();
        for (int i = 0; i < 96; i++) {
            p.add(start + (deltaPerDay * i / 24.0));
        }
        return p;
    }

    private List<Double> risingPressure(double start, double deltaPerDay) {
        return fallingPressure(start, deltaPerDay);
    }

    private List<Integer> lowPrecipProbs() {
        return Collections.nCopies(96, 5);
    }

    private List<Integer> moderatePrecipProbs() {
        return Collections.nCopies(96, 55);
    }

    private List<Integer> highPrecipProbs() {
        List<Integer> probs = new ArrayList<>();
        for (int i = 0; i < 96; i++) {
            probs.add(i % 2 == 0 ? 90 : 10);
        }
        return probs;
    }

    private List<Integer> clearWeatherCodes() {
        return Collections.nCopies(96, 0);
    }

    private List<Integer> activeWeatherCodes() {
        List<Integer> codes = new ArrayList<>(Collections.nCopies(48, 0));
        codes.addAll(Collections.nCopies(48, 63));
        return codes;
    }

    private List<Double> calmGusts() {
        return Collections.nCopies(72, 5.0);
    }

    private List<Double> gustyWind() {
        List<Double> gusts = new ArrayList<>();
        for (int i = 0; i < 72; i++) {
            gusts.add(i % 2 == 0 ? 30.0 : 3.0);
        }
        return gusts;
    }

    private List<String> nTimes(int n) {
        List<String> times = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            times.add("2026-04-03T" + String.format("%02d", i % 24) + ":00");
        }
        return times;
    }
}
