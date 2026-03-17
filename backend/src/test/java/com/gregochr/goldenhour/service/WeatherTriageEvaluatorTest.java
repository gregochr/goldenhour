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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WeatherTriageEvaluator}.
 */
class WeatherTriageEvaluatorTest {

    private WeatherTriageEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new WeatherTriageEvaluator();
    }

    @Test
    @DisplayName("Clear conditions pass triage")
    void clearConditions_passes() {
        AtmosphericData data = buildData(20, 0, 30000, BigDecimal.ZERO, null);
        assertThat(evaluator.evaluate(data)).isEmpty();
    }

    @Test
    @DisplayName("Observer low cloud > 80% triggers HIGH_CLOUD when no directional data")
    void highObserverCloud_triages() {
        AtmosphericData data = buildData(85, 0, 30000, BigDecimal.ZERO, null);
        Optional<TriageResult> result = evaluator.evaluate(data);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.HIGH_CLOUD);
        assertThat(result.get().reason()).contains("85%");
        assertThat(result.get().reason()).startsWith("Low cloud cover");
    }

    @Test
    @DisplayName("Solar low cloud > 80% triggers HIGH_CLOUD when directional data present")
    void highSolarCloud_triages() {
        DirectionalCloudData dc = new DirectionalCloudData(90, 10, 5, 20, 10, 5);
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, dc);
        Optional<TriageResult> result = evaluator.evaluate(data);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.HIGH_CLOUD);
        assertThat(result.get().reason()).contains("90%");
        assertThat(result.get().reason()).startsWith("Solar horizon low cloud");
    }

    @Test
    @DisplayName("Solar low cloud exactly 80% passes triage (threshold is >80)")
    void solarCloudAtBoundary_passes() {
        DirectionalCloudData dc = new DirectionalCloudData(80, 10, 5, 20, 10, 5);
        AtmosphericData data = buildData(10, 0, 30000, BigDecimal.ZERO, dc);
        assertThat(evaluator.evaluate(data)).isEmpty();
    }

    @Test
    @DisplayName("Precipitation > 2mm triggers PRECIPITATION")
    void highPrecipitation_triages() {
        AtmosphericData data = buildData(20, 0, 30000, new BigDecimal("2.5"), null);
        Optional<TriageResult> result = evaluator.evaluate(data);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.PRECIPITATION);
    }

    @Test
    @DisplayName("Precipitation exactly 2.0mm passes triage (threshold is >2)")
    void precipAtBoundary_passes() {
        AtmosphericData data = buildData(20, 0, 30000, new BigDecimal("2.0"), null);
        assertThat(evaluator.evaluate(data)).isEmpty();
    }

    @Test
    @DisplayName("Visibility < 5000m triggers LOW_VISIBILITY")
    void lowVisibility_triages() {
        AtmosphericData data = buildData(20, 0, 4000, BigDecimal.ZERO, null);
        Optional<TriageResult> result = evaluator.evaluate(data);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.LOW_VISIBILITY);
        assertThat(result.get().reason()).contains("4000");
    }

    @Test
    @DisplayName("Visibility exactly 5000m passes triage (threshold is <5000)")
    void visibilityAtBoundary_passes() {
        AtmosphericData data = buildData(20, 0, 5000, BigDecimal.ZERO, null);
        assertThat(evaluator.evaluate(data)).isEmpty();
    }

    @Test
    @DisplayName("HIGH_CLOUD takes priority over PRECIPITATION and LOW_VISIBILITY")
    void priorityOrder_cloudFirst() {
        AtmosphericData data = buildData(85, 0, 3000, new BigDecimal("5.0"), null);
        Optional<TriageResult> result = evaluator.evaluate(data);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.HIGH_CLOUD);
    }

    @Test
    @DisplayName("PRECIPITATION takes priority over LOW_VISIBILITY")
    void priorityOrder_precipBeforeVisibility() {
        AtmosphericData data = buildData(20, 0, 3000, new BigDecimal("5.0"), null);
        Optional<TriageResult> result = evaluator.evaluate(data);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.PRECIPITATION);
    }

    @Test
    @DisplayName("Falls back to observer cloud when directional data has low solar cloud")
    void directionalLowSolar_usesDirectional() {
        DirectionalCloudData dc = new DirectionalCloudData(20, 10, 5, 20, 10, 5);
        AtmosphericData data = buildData(90, 0, 30000, BigDecimal.ZERO, dc);
        // Directional solar cloud is 20%, observer is 90%. Should use directional (20%) and pass.
        assertThat(evaluator.evaluate(data)).isEmpty();
    }

    private AtmosphericData buildData(int lowCloud, int highCloud, int visibility,
            BigDecimal precip, DirectionalCloudData dc) {
        return new AtmosphericData(
                "Test Location",
                LocalDateTime.of(2026, 3, 15, 6, 30),
                TargetType.SUNRISE,
                new CloudData(lowCloud, 20, highCloud),
                new WeatherData(visibility, new BigDecimal("3.0"), 180, precip,
                        60, 1, BigDecimal.ZERO),
                new AerosolData(BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("0.1"), 250),
                new ComfortData(10.0, 8.0, 20),
                dc,
                null,
                null
        );
    }
}
