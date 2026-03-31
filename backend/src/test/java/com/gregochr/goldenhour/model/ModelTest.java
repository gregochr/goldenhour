package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
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
        AtmosphericData data = TestAtmosphericData.builder()
                .solarEventTime(eventTime)
                .lowCloud(20)
                .midCloud(60)
                .highCloud(40)
                .visibility(20000)
                .windSpeed(new BigDecimal("5.50"))
                .precipitation(new BigDecimal("0.10"))
                .temperature(12.5)
                .apparentTemperature(9.8)
                .precipProbability(30)
                .build();

        assertThat(data.locationName()).isEqualTo("Durham UK");
        assertThat(data.solarEventTime()).isEqualTo(eventTime);
        assertThat(data.targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(data.cloud().lowCloudPercent()).isEqualTo(20);
        assertThat(data.cloud().midCloudPercent()).isEqualTo(60);
        assertThat(data.cloud().highCloudPercent()).isEqualTo(40);
        assertThat(data.weather().visibilityMetres()).isEqualTo(20000);
        assertThat(data.weather().windSpeedMs()).isEqualByComparingTo("5.50");
        assertThat(data.weather().windDirectionDegrees()).isEqualTo(225);
        assertThat(data.weather().precipitationMm()).isEqualByComparingTo("0.10");
        assertThat(data.weather().humidityPercent()).isEqualTo(62);
        assertThat(data.weather().weatherCode()).isEqualTo(3);
        assertThat(data.aerosol().boundaryLayerHeightMetres()).isEqualTo(1200);
        assertThat(data.weather().shortwaveRadiationWm2()).isEqualByComparingTo("180.00");
        assertThat(data.aerosol().pm25()).isEqualByComparingTo("8.50");
        assertThat(data.aerosol().dustUgm3()).isEqualByComparingTo("2.10");
        assertThat(data.aerosol().aerosolOpticalDepth()).isEqualByComparingTo("0.120");
        assertThat(data.comfort().temperatureCelsius()).isEqualTo(12.5);
        assertThat(data.comfort().apparentTemperatureCelsius()).isEqualTo(9.8);
        assertThat(data.comfort().precipitationProbability()).isEqualTo(30);
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
    @DisplayName("withDirectionalCloud returns copy with directional data attached")
    void atmosphericData_withDirectionalCloud() {
        AtmosphericData base = TestAtmosphericData.defaults();
        DirectionalCloudData dc = new DirectionalCloudData(65, 20, 10, 5, 45, 30, null);

        AtmosphericData result = base.withDirectionalCloud(dc);

        assertThat(result.directionalCloud()).isEqualTo(dc);
        assertThat(result.locationName()).isEqualTo(base.locationName());
        assertThat(result.cloud()).isEqualTo(base.cloud());
        assertThat(result.tide()).isNull();
    }

    @Test
    @DisplayName("withTide returns copy with tide snapshot attached")
    void atmosphericData_withTide() {
        AtmosphericData base = TestAtmosphericData.defaults();
        TideSnapshot tide = new TideSnapshot(
                TideState.HIGH,
                LocalDateTime.of(2026, 6, 21, 18, 30),
                new BigDecimal("4.50"),
                LocalDateTime.of(2026, 6, 22, 0, 45),
                new BigDecimal("1.20"),
                true,
                LocalDateTime.of(2026, 6, 21, 18, 30),
                null, null, null, null, null);

        AtmosphericData result = base.withTide(tide);

        assertThat(result.tide()).isEqualTo(tide);
        assertThat(result.tide().tideState()).isEqualTo(TideState.HIGH);
        assertThat(result.tide().nextHighTideHeightMetres()).isEqualByComparingTo("4.50");
        assertThat(result.tide().tideAligned()).isTrue();
        assertThat(result.locationName()).isEqualTo(base.locationName());
        assertThat(result.directionalCloud()).isNull();
    }

    @Test
    @DisplayName("Records with identical components are equal")
    void records_withIdenticalComponents_areEqual() {
        SunsetEvaluation first = new SunsetEvaluation(null, 50, 60, "Moderate potential.");
        SunsetEvaluation second = new SunsetEvaluation(null, 50, 60, "Moderate potential.");

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    @DisplayName("TideSnapshot carries lunar and statistical fields")
    void tideSnapshot_lunarAndStatisticalFields() {
        TideSnapshot tide = new TideSnapshot(
                TideState.HIGH,
                LocalDateTime.of(2026, 6, 21, 18, 30),
                new BigDecimal("6.20"),
                LocalDateTime.of(2026, 6, 22, 0, 45),
                new BigDecimal("0.80"),
                true,
                LocalDateTime.of(2026, 6, 21, 18, 30),
                null,
                LunarTideType.KING_TIDE, "New Moon", true,
                TideStatisticalSize.EXTRA_EXTRA_HIGH);

        assertThat(tide.lunarTideType()).isEqualTo(LunarTideType.KING_TIDE);
        assertThat(tide.lunarPhase()).isEqualTo("New Moon");
        assertThat(tide.moonAtPerigee()).isTrue();
        assertThat(tide.statisticalSize()).isEqualTo(TideStatisticalSize.EXTRA_EXTRA_HIGH);
    }

    @Test
    @DisplayName("BriefingSlot.TideInfo.statisticalSize() derives from isKingTide")
    void tideInfo_statisticalSize_kingTide() {
        var info = new BriefingSlot.TideInfo("HIGH", true, null,
                new BigDecimal("6.2"), true, false,
                LunarTideType.KING_TIDE, "New Moon", true);

        assertThat(info.statisticalSize()).isEqualTo(TideStatisticalSize.EXTRA_EXTRA_HIGH);
    }

    @Test
    @DisplayName("BriefingSlot.TideInfo.statisticalSize() derives from isSpringTide")
    void tideInfo_statisticalSize_springTide() {
        var info = new BriefingSlot.TideInfo("HIGH", true, null,
                new BigDecimal("5.1"), false, true,
                LunarTideType.SPRING_TIDE, "Full Moon", false);

        assertThat(info.statisticalSize()).isEqualTo(TideStatisticalSize.EXTRA_HIGH);
    }

    @Test
    @DisplayName("BriefingSlot.TideInfo.statisticalSize() returns null for regular tide")
    void tideInfo_statisticalSize_regular() {
        var info = new BriefingSlot.TideInfo("MID", false, null,
                new BigDecimal("3.5"), false, false,
                LunarTideType.REGULAR_TIDE, "Waxing Crescent", false);

        assertThat(info.statisticalSize()).isNull();
    }

    @Test
    @DisplayName("BriefingSlot.TideInfo.NONE has null statisticalSize")
    void tideInfo_none_hasNullStatisticalSize() {
        assertThat(BriefingSlot.TideInfo.NONE.statisticalSize()).isNull();
        assertThat(BriefingSlot.TideInfo.NONE.lunarTideType()).isNull();
    }
}
