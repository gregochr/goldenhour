package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudData;
import com.gregochr.goldenhour.model.ComfortData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.TriageResult;
import com.gregochr.goldenhour.model.TriageRule;
import com.gregochr.goldenhour.model.WeatherData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WeatherTriageEvaluator}.
 */
class WeatherTriageEvaluatorTest {

    /** A horizon beyond the near term, where rule 1 keeps its 80% cut. */
    private static final int FAR_DAYS_AHEAD = 3;

    private WeatherTriageEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new WeatherTriageEvaluator();
    }

    @Test
    @DisplayName("Clear conditions pass triage")
    void clearConditions_passes() {
        AtmosphericData data = buildData(20, 0, 30000, BigDecimal.ZERO, null);
        assertThat(evaluator.evaluate(data, FAR_DAYS_AHEAD)).isEmpty();
    }

    @Test
    @DisplayName("Observer low cloud > 80% triggers HIGH_CLOUD when no directional data")
    void highObserverCloud_triages() {
        AtmosphericData data = buildData(85, 0, 30000, BigDecimal.ZERO, null);
        Optional<TriageResult> result = evaluator.evaluate(data, FAR_DAYS_AHEAD);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.HIGH_CLOUD);
        assertThat(result.get().reason()).contains("85%");
        assertThat(result.get().reason()).startsWith("Low cloud cover");
    }

    @Test
    @DisplayName("Solar low cloud > 80% triggers HIGH_CLOUD when directional data present")
    void highSolarCloud_triages() {
        DirectionalCloudData dc = new DirectionalCloudData(90, 10, 5, 20, 10, 5, null);
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, dc);
        Optional<TriageResult> result = evaluator.evaluate(data, FAR_DAYS_AHEAD);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.HIGH_CLOUD);
        assertThat(result.get().reason()).contains("90%");
        assertThat(result.get().reason()).startsWith("Solar horizon low cloud");
    }

    @Test
    @DisplayName("Solar low cloud exactly 80% passes triage (threshold is >80)")
    void solarCloudAtBoundary_passes() {
        DirectionalCloudData dc = new DirectionalCloudData(80, 10, 5, 20, 10, 5, null);
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, dc);
        assertThat(evaluator.evaluate(data, FAR_DAYS_AHEAD)).isEmpty();
    }

    @Test
    @DisplayName("Precipitation > 2mm triggers PRECIPITATION")
    void highPrecipitation_triages() {
        AtmosphericData data = buildData(20, 0, 30000, new BigDecimal("2.5"), null);
        Optional<TriageResult> result = evaluator.evaluate(data, FAR_DAYS_AHEAD);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.PRECIPITATION);
        assertThat(result.get().reason()).contains("2.5");
    }

    @Test
    @DisplayName("Precipitation exactly 2.0mm passes triage (threshold is >2)")
    void precipAtBoundary_passes() {
        AtmosphericData data = buildData(20, 0, 30000, new BigDecimal("2.0"), null);
        assertThat(evaluator.evaluate(data, FAR_DAYS_AHEAD)).isEmpty();
    }

    @Test
    @DisplayName("Visibility < 5000m triggers LOW_VISIBILITY")
    void lowVisibility_triages() {
        AtmosphericData data = buildData(20, 0, 4000, BigDecimal.ZERO, null);
        Optional<TriageResult> result = evaluator.evaluate(data, FAR_DAYS_AHEAD);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.LOW_VISIBILITY);
        assertThat(result.get().reason()).contains("4000");
    }

    @Test
    @DisplayName("valley-mist M1b: the visibility reason text is recognised by the helper the honesty "
            + "filter reads it with — the evaluator's own output round-trips, other rules do not")
    void lowVisibilityReason_roundTripsThroughTheSharedConstants() {
        String visibility = evaluator.evaluate(buildData(20, 0, 4000, BigDecimal.ZERO, null), FAR_DAYS_AHEAD)
                .orElseThrow().reason();
        String rain = evaluator.evaluate(buildData(20, 0, 30000, new BigDecimal("5.0"), null), FAR_DAYS_AHEAD)
                .orElseThrow().reason();
        String cloud = evaluator.evaluate(buildData(95, 0, 30000, BigDecimal.ZERO, null), FAR_DAYS_AHEAD)
                .orElseThrow().reason();

        assertThat(WeatherTriageEvaluator.isLowVisibilityReason(visibility)).isTrue();
        assertThat(WeatherTriageEvaluator.isLowVisibilityReason(rain)).isFalse();
        assertThat(WeatherTriageEvaluator.isLowVisibilityReason(cloud)).isFalse();
        assertThat(WeatherTriageEvaluator.isLowVisibilityReason(null)).isFalse();
    }

    @Test
    @DisplayName("Visibility exactly 5000m passes triage (threshold is <5000)")
    void visibilityAtBoundary_passes() {
        AtmosphericData data = buildData(20, 0, 5000, BigDecimal.ZERO, null);
        assertThat(evaluator.evaluate(data, FAR_DAYS_AHEAD)).isEmpty();
    }

    @Test
    @DisplayName("HIGH_CLOUD takes priority over PRECIPITATION and LOW_VISIBILITY")
    void priorityOrder_cloudFirst() {
        AtmosphericData data = buildData(85, 0, 3000, new BigDecimal("5.0"), null);
        Optional<TriageResult> result = evaluator.evaluate(data, FAR_DAYS_AHEAD);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.HIGH_CLOUD);
    }

    @Test
    @DisplayName("PRECIPITATION takes priority over LOW_VISIBILITY")
    void priorityOrder_precipBeforeVisibility() {
        AtmosphericData data = buildData(20, 0, 3000, new BigDecimal("5.0"), null);
        Optional<TriageResult> result = evaluator.evaluate(data, FAR_DAYS_AHEAD);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.PRECIPITATION);
    }

    @Test
    @DisplayName("Observer low cloud 81% triggers HIGH_CLOUD (one above threshold)")
    void observerCloud81_triages() {
        AtmosphericData data = buildData(81, 0, 30000, BigDecimal.ZERO, null);
        Optional<TriageResult> result = evaluator.evaluate(data, FAR_DAYS_AHEAD);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.HIGH_CLOUD);
    }

    @Test
    @DisplayName("Solar low cloud 81% triggers HIGH_CLOUD (one above threshold)")
    void solarCloud81_triages() {
        DirectionalCloudData dc = new DirectionalCloudData(81, 10, 5, 20, 10, 5, null);
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, dc);
        Optional<TriageResult> result = evaluator.evaluate(data, FAR_DAYS_AHEAD);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.HIGH_CLOUD);
    }

    @Test
    @DisplayName("Precipitation 2.1mm triggers PRECIPITATION (one step above threshold)")
    void precip2point1_triages() {
        AtmosphericData data = buildData(20, 0, 30000, new BigDecimal("2.1"), null);
        Optional<TriageResult> result = evaluator.evaluate(data, FAR_DAYS_AHEAD);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.PRECIPITATION);
    }

    @Test
    @DisplayName("Visibility 4999m triggers LOW_VISIBILITY (one below threshold)")
    void visibility4999_triages() {
        AtmosphericData data = buildData(20, 0, 4999, BigDecimal.ZERO, null);
        Optional<TriageResult> result = evaluator.evaluate(data, FAR_DAYS_AHEAD);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.LOW_VISIBILITY);
    }

    @Test
    @DisplayName("Null precipitation does not trigger PRECIPITATION rule")
    void nullPrecipitation_passes() {
        AtmosphericData data = buildData(20, 0, 30000, null, null);
        assertThat(evaluator.evaluate(data, FAR_DAYS_AHEAD)).isEmpty();
    }

    @Test
    @DisplayName("Falls back to observer cloud when directional data has low solar cloud")
    void directionalLowSolar_usesDirectional() {
        DirectionalCloudData dc = new DirectionalCloudData(20, 10, 5, 20, 10, 5, null);
        AtmosphericData data = buildData(90, 0, 30000, BigDecimal.ZERO, dc);
        // Directional solar cloud is 20%, observer is 90%. Should use directional (20%) and pass.
        assertThat(evaluator.evaluate(data, FAR_DAYS_AHEAD)).isEmpty();
    }

    // ── The near-term 60% line ────────────────────────────────────────────────────────────────

    private static DirectionalCloudData solar(int near, Integer far) {
        return new DirectionalCloudData(near, 10, 5, 20, 10, 5, far);
    }

    @ParameterizedTest(name = "T+{0}: solar low cloud 60% passes")
    @ValueSource(ints = {0, 1})
    @DisplayName("T+0 and T+1 pass at exactly 60% solar low cloud (the cut is over 60)")
    void nearTerm_atSixty_passes(int daysAhead) {
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, solar(60, null));
        assertThat(evaluator.evaluate(data, daysAhead)).isEmpty();
    }

    @ParameterizedTest(name = "T+{0}: solar low cloud 61% stands down")
    @ValueSource(ints = {0, 1})
    @DisplayName("T+0 and T+1 stand down at 61% solar low cloud")
    void nearTerm_atSixtyOne_standsDown(int daysAhead) {
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, solar(61, null));
        Optional<TriageResult> result = evaluator.evaluate(data, daysAhead);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.HIGH_CLOUD);
    }

    @ParameterizedTest(name = "T+{0}: solar low cloud 80% passes")
    @ValueSource(ints = {2, 3, 7})
    @DisplayName("T+2 and beyond keep the 80% cut: 80% passes")
    void farTerm_atEighty_passes(int daysAhead) {
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, solar(80, null));
        assertThat(evaluator.evaluate(data, daysAhead)).isEmpty();
    }

    @ParameterizedTest(name = "T+{0}: solar low cloud 81% stands down")
    @ValueSource(ints = {2, 3, 7})
    @DisplayName("T+2 and beyond stand down at 81%")
    void farTerm_atEightyOne_standsDown(int daysAhead) {
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, solar(81, null));
        assertThat(evaluator.evaluate(data, daysAhead)).isPresent();
    }

    @ParameterizedTest(name = "T+{0}: 70% solar cloud passes at T+2 and beyond")
    @ValueSource(ints = {2, 3})
    @DisplayName("T+2 and beyond pass at 70%, which the near term stands down")
    void farTerm_atSeventy_passes(int daysAhead) {
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, solar(70, null));
        assertThat(evaluator.evaluate(data, daysAhead)).isEmpty();
    }

    @Test
    @DisplayName("a negative daysAhead (a retried past slot) takes the near-term cut")
    void negativeDaysAhead_isNearTerm() {
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, solar(70, null));
        assertThat(evaluator.evaluate(data, -1)).isPresent();
    }

    @Test
    @DisplayName("T+0 at 70% with a thin strip (far 30%) passes: the exemption keeps the 80% line")
    void nearTerm_thinStrip_passes() {
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, solar(70, 30));
        assertThat(evaluator.evaluate(data, 0)).isEmpty();
    }

    @Test
    @DisplayName("T+1 thin strip above 80% still stands down")
    void nearTerm_thinStripAboveEighty_standsDown() {
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, solar(85, 30));
        assertThat(evaluator.evaluate(data, 1)).isPresent();
    }

    @Test
    @DisplayName("T+0 at 70% with far cloud too (far 60%, a blanket) stands down")
    void nearTerm_blanket_standsDown() {
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, solar(70, 60));
        Optional<TriageResult> result = evaluator.evaluate(data, 0);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.HIGH_CLOUD);
    }

    @Test
    @DisplayName("T+0 at 70% with a drop of only 29 points to far is not a strip: stands down")
    void nearTerm_dropOfTwentyNine_standsDown() {
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, solar(70, 41));
        assertThat(evaluator.evaluate(data, 0)).isPresent();
    }

    @Test
    @DisplayName("T+0 at 70% with a drop of exactly 30 points is a strip: passes")
    void nearTerm_dropOfThirty_passes() {
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, solar(70, 40));
        assertThat(evaluator.evaluate(data, 0)).isEmpty();
    }

    @Test
    @DisplayName("observer fallback (no directional data): 61% stands down at T+0, passes at T+2")
    void observerFallback_followsTheHorizonCut() {
        AtmosphericData data = buildData(61, 0, 30000, BigDecimal.ZERO, null);
        Optional<TriageResult> nearTerm = evaluator.evaluate(data, 0);
        assertThat(nearTerm).isPresent();
        assertThat(nearTerm.get().reason()).startsWith("Low cloud cover 61%");
        assertThat(evaluator.evaluate(data, 2)).isEmpty();
    }

    @Test
    @DisplayName("the reason text keeps its format at the 60% line")
    void nearTerm_reasonTextUnchanged() {
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, solar(65, null));
        assertThat(evaluator.evaluate(data, 0).orElseThrow().reason())
                .isEqualTo("Solar horizon low cloud 65% — sun blocked");
    }

    @Test
    @DisplayName("rules 2 and 3 are unchanged at T+0")
    void nearTerm_rulesTwoAndThree_unchanged() {
        Optional<TriageResult> rain = evaluator.evaluate(
                buildData(20, 0, 30000, new BigDecimal("2.5"), null), 0);
        assertThat(rain.orElseThrow().rule()).isEqualTo(TriageRule.PRECIPITATION);
        assertThat(evaluator.evaluate(
                buildData(20, 0, 30000, new BigDecimal("2.0"), null), 0)).isEmpty();
        Optional<TriageResult> fog = evaluator.evaluate(
                buildData(20, 0, 4000, BigDecimal.ZERO, null), 0);
        assertThat(fog.orElseThrow().rule()).isEqualTo(TriageRule.LOW_VISIBILITY);
        assertThat(evaluator.evaluate(
                buildData(60, 0, 30000, BigDecimal.ZERO, null), 0)).isEmpty();
    }

    private AtmosphericData buildData(int lowCloud, int highCloud, int visibility,
            BigDecimal precip, DirectionalCloudData dc) {
        return new AtmosphericData(
                "Test Location",
                LocalDateTime.of(2026, 3, 15, 6, 30),
                TargetType.SUNRISE,
                new CloudData(lowCloud, 20, highCloud),
                new WeatherData(visibility, new BigDecimal("3.0"), 180, precip,
                        60, 1, BigDecimal.ZERO, null, 1013.0, null, null, null, null, null, null),
                new AerosolData(BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("0.1"), 250),
                new ComfortData(10.0, 8.0, 20),
                dc,
                null,
                null,
                null
        );
    }
}
