package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the model record classes.
 *
 * <p>Verifies that records are correctly constructed and that
 * component accessors return the expected values.
 */
class ModelTest {

    @Test
    @DisplayName("AtmosphericData components are accessible after construction")
    void atmosphericData_componentsAccessible() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 2, 20, 17, 22);
        AtmosphericData data = new AtmosphericData(
                "Durham UK", eventTime, TargetType.SUNSET,
                20, 60, 40, 20000,
                new BigDecimal("5.50"), 225, new BigDecimal("0.10"),
                62, 3, 1200, new BigDecimal("180.00"),
                new BigDecimal("8.50"), new BigDecimal("2.10"), new BigDecimal("0.120"),
                null, null, null, null, null, null);

        assertThat(data.locationName()).isEqualTo("Durham UK");
        assertThat(data.solarEventTime()).isEqualTo(eventTime);
        assertThat(data.targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(data.lowCloudPercent()).isEqualTo(20);
        assertThat(data.midCloudPercent()).isEqualTo(60);
        assertThat(data.highCloudPercent()).isEqualTo(40);
        assertThat(data.visibilityMetres()).isEqualTo(20000);
        assertThat(data.windSpeedMs()).isEqualByComparingTo("5.50");
        assertThat(data.windDirectionDegrees()).isEqualTo(225);
        assertThat(data.precipitationMm()).isEqualByComparingTo("0.10");
        assertThat(data.humidityPercent()).isEqualTo(62);
        assertThat(data.weatherCode()).isEqualTo(3);
        assertThat(data.boundaryLayerHeightMetres()).isEqualTo(1200);
        assertThat(data.shortwaveRadiationWm2()).isEqualByComparingTo("180.00");
        assertThat(data.pm25()).isEqualByComparingTo("8.50");
        assertThat(data.dustUgm3()).isEqualByComparingTo("2.10");
        assertThat(data.aerosolOpticalDepth()).isEqualByComparingTo("0.120");
    }

    @Test
    @DisplayName("SunsetEvaluation (Sonnet) components are accessible after construction")
    void sunsetEvaluation_sonnet_componentsAccessible() {
        SunsetEvaluation evaluation = new SunsetEvaluation(null, 72, 80,
                "Good mid-level cloud above a clear horizon.");

        assertThat(evaluation.rating()).isNull();
        assertThat(evaluation.fierySkyPotential()).isEqualTo(72);
        assertThat(evaluation.goldenHourPotential()).isEqualTo(80);
        assertThat(evaluation.summary()).contains("clear horizon");
    }

    @Test
    @DisplayName("SunsetEvaluation (Haiku) components are accessible after construction")
    void sunsetEvaluation_haiku_componentsAccessible() {
        SunsetEvaluation evaluation = new SunsetEvaluation(4, null, null,
                "Good conditions with mid-level cloud.");

        assertThat(evaluation.rating()).isEqualTo(4);
        assertThat(evaluation.fierySkyPotential()).isNull();
        assertThat(evaluation.goldenHourPotential()).isNull();
        assertThat(evaluation.summary()).contains("Good conditions");
    }

    @Test
    @DisplayName("ForecastRequest components are accessible after construction")
    void forecastRequest_componentsAccessible() {
        LocalDate date = LocalDate.of(2026, 2, 20);
        ForecastRequest request = new ForecastRequest(54.7753, -1.5849, "Durham UK", date, TargetType.SUNSET);

        assertThat(request.latitude()).isEqualTo(54.7753);
        assertThat(request.longitude()).isEqualTo(-1.5849);
        assertThat(request.locationName()).isEqualTo("Durham UK");
        assertThat(request.date()).isEqualTo(date);
        assertThat(request.targetType()).isEqualTo(TargetType.SUNSET);
    }

    @Test
    @DisplayName("ActualOutcome components are accessible after construction")
    void actualOutcome_componentsAccessible() {
        LocalDate date = LocalDate.of(2026, 2, 20);
        ActualOutcome outcome = new ActualOutcome(
                54.7753, -1.5849, "Durham UK", date, TargetType.SUNSET,
                true, 65, 78, "Beautiful warm light.");

        assertThat(outcome.locationLat()).isEqualTo(54.7753);
        assertThat(outcome.locationLon()).isEqualTo(-1.5849);
        assertThat(outcome.locationName()).isEqualTo("Durham UK");
        assertThat(outcome.outcomeDate()).isEqualTo(date);
        assertThat(outcome.targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(outcome.wentOut()).isTrue();
        assertThat(outcome.fierySkyActual()).isEqualTo(65);
        assertThat(outcome.goldenHourActual()).isEqualTo(78);
        assertThat(outcome.notes()).isEqualTo("Beautiful warm light.");
    }

    @Test
    @DisplayName("Records with identical components are equal")
    void records_withIdenticalComponents_areEqual() {
        SunsetEvaluation first = new SunsetEvaluation(null, 50, 60, "Moderate potential.");
        SunsetEvaluation second = new SunsetEvaluation(null, 50, 60, "Moderate potential.");

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }
}
