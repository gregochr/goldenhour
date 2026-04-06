package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
}
