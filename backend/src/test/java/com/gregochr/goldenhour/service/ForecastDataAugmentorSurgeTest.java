package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CoastalParameters;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.TideStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForecastDataAugmentor#augmentWithStormSurge}.
 */
@ExtendWith(MockitoExtension.class)
class ForecastDataAugmentorSurgeTest {

    @Mock
    private OpenMeteoService openMeteoService;

    @Mock
    private SolarService solarService;

    @Mock
    private TideService tideService;

    @Mock
    private WeatherAugmentedTideService weatherAugmentedTideService;

    @Mock
    private SurgeCalibrationLogger surgeCalibrationLogger;

    private ForecastDataAugmentor augmentor;

    private static final CoastalParameters CRASTER_COASTAL = new CoastalParameters(80, 250_000, 30, true);
    private static final LocalDateTime HIGH_TIDE_TIME = LocalDateTime.of(2026, 3, 30, 19, 15);

    @BeforeEach
    void setUp() {
        augmentor = new ForecastDataAugmentor(openMeteoService, solarService, tideService,
                new LunarPhaseService(), weatherAugmentedTideService, surgeCalibrationLogger);
    }

    private AtmosphericData baseDataWithTide() {
        TideSnapshot tide = new TideSnapshot(
                TideState.HIGH, HIGH_TIDE_TIME, BigDecimal.valueOf(4.80),
                LocalDateTime.of(2026, 3, 30, 13, 0), BigDecimal.valueOf(1.20),
                true, HIGH_TIDE_TIME, LocalDateTime.of(2026, 3, 30, 13, 0),
                null, null, null, null);
        return TestAtmosphericData.builder()
                .locationName("Craster")
                .tide(tide)
                .build();
    }

    private OpenMeteoForecastResponse buildForecastResponse(double pressure, double windSpeed,
            int windDir) {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(List.of(
                "2026-03-30T18:00", "2026-03-30T19:00", "2026-03-30T20:00"));
        hourly.setSurfacePressure(List.of(pressure, pressure, pressure));
        hourly.setWindSpeed10m(List.of(windSpeed, windSpeed, windSpeed));
        hourly.setWindDirection10m(List.of(windDir, windDir, windDir));
        response.setHourly(hourly);
        return response;
    }

    @Test
    @DisplayName("Non-coastal location returns unchanged data")
    void nonCoastalReturnsUnchanged() {
        AtmosphericData base = TestAtmosphericData.defaults();
        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CoastalParameters.NON_TIDAL, 1L, "Inland",
                buildForecastResponse(1013, 5, 180));

        assertThat(result.surge()).isNull();
        verify(weatherAugmentedTideService, never()).augment(
                anyDouble(), anyDouble(), anyDouble(), any(), anyString(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("Coastal location without tide data returns unchanged")
    void noTideDataReturnsUnchanged() {
        AtmosphericData base = TestAtmosphericData.defaults(); // no tide
        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CRASTER_COASTAL, 1L, "Craster",
                buildForecastResponse(990, 15, 60));

        assertThat(result.surge()).isNull();
    }

    @Test
    @DisplayName("Null forecast response returns unchanged")
    void nullForecastResponseReturnsUnchanged() {
        AtmosphericData base = baseDataWithTide();
        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CRASTER_COASTAL, 1L, "Craster", null);

        assertThat(result.surge()).isNull();
    }

    @Test
    @DisplayName("Significant surge populates all surge fields")
    void significantSurgePopulatesFields() {
        AtmosphericData base = baseDataWithTide();
        OpenMeteoForecastResponse response = buildForecastResponse(990, 15, 60);

        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Test surge");
        when(tideService.getTideStats(1L)).thenReturn(Optional.empty());
        when(weatherAugmentedTideService.augment(
                anyDouble(), anyDouble(), anyDouble(), any(), anyString(), anyDouble(), anyDouble()))
                .thenReturn(new WeatherAugmentedTideService.AugmentedTideResult(
                        surge, 3.95, 5.15));

        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CRASTER_COASTAL, 1L, "Craster", response);

        assertThat(result.surge()).isNotNull();
        assertThat(result.surge().totalSurgeMetres()).isEqualTo(0.35);
        assertThat(result.surge().riskLevel()).isEqualTo(TideRiskLevel.MODERATE);
        assertThat(result.adjustedRangeMetres()).isEqualTo(3.95);
        assertThat(result.astronomicalRangeMetres()).isCloseTo(3.6, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("King tide detected from tide stats")
    void kingTideDetected() {
        AtmosphericData base = baseDataWithTide();
        OpenMeteoForecastResponse response = buildForecastResponse(990, 15, 60);

        // p95HighMetres = 4.70 → tide height 4.80 exceeds it → KING_TIDE
        // springTideThreshold = 4.40 → tide height 4.80 exceeds it
        TideStats stats = new TideStats(
                BigDecimal.valueOf(4.00), BigDecimal.valueOf(5.20),
                BigDecimal.valueOf(0.80), BigDecimal.valueOf(0.50),
                100L, BigDecimal.valueOf(3.20),
                BigDecimal.valueOf(4.30), BigDecimal.valueOf(4.50),
                BigDecimal.valueOf(4.70), 15L,
                BigDecimal.valueOf(0.15), BigDecimal.valueOf(4.40),
                BigDecimal.valueOf(4.70), 5L);

        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.HIGH, "King tide surge");
        when(tideService.getTideStats(1L)).thenReturn(Optional.of(stats));
        when(weatherAugmentedTideService.augment(
                anyDouble(), anyDouble(), anyDouble(), any(),
                org.mockito.ArgumentMatchers.eq("KING_TIDE"),
                anyDouble(), anyDouble()))
                .thenReturn(new WeatherAugmentedTideService.AugmentedTideResult(
                        surge, 3.95, 5.15));

        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CRASTER_COASTAL, 1L, "Craster", response);

        assertThat(result.surge()).isNotNull();
        assertThat(result.surge().riskLevel()).isEqualTo(TideRiskLevel.HIGH);
    }

    @Test
    @DisplayName("Extracts weather at high-tide time, not solar event time")
    void extractsWeatherAtHighTideTime() {
        AtmosphericData base = baseDataWithTide();
        // High tide at 19:15 — nearest slot is 19:00 (index 1)
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(List.of(
                "2026-03-30T18:00", "2026-03-30T19:00", "2026-03-30T20:00"));
        hourly.setSurfacePressure(List.of(1013.0, 985.0, 1010.0)); // 985 at 19:00
        hourly.setWindSpeed10m(List.of(3.0, 18.0, 5.0)); // 18 at 19:00
        hourly.setWindDirection10m(List.of(180, 60, 270)); // 60 at 19:00
        response.setHourly(hourly);

        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.28, 0.15, 0.43, 985.0, 18.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Test");
        when(tideService.getTideStats(1L)).thenReturn(Optional.empty());
        when(weatherAugmentedTideService.augment(
                org.mockito.ArgumentMatchers.eq(985.0),
                org.mockito.ArgumentMatchers.eq(18.0),
                org.mockito.ArgumentMatchers.eq(60.0),
                any(), anyString(), anyDouble(), anyDouble()))
                .thenReturn(new WeatherAugmentedTideService.AugmentedTideResult(
                        surge, 4.03, 5.23));

        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CRASTER_COASTAL, 1L, "Craster", response);

        assertThat(result.surge()).isNotNull();
        // Verify pressure=985, wind=18, dir=60 were passed (the 19:00 slot values)
        assertThat(result.surge().pressureHpa()).isEqualTo(985.0);
        assertThat(result.surge().windSpeedMs()).isEqualTo(18.0);
    }

    @Test
    @DisplayName("Surge calculation failure returns unchanged data")
    void surgeCalculationFailureReturnsUnchanged() {
        AtmosphericData base = baseDataWithTide();
        OpenMeteoForecastResponse response = buildForecastResponse(990, 15, 60);

        when(tideService.getTideStats(1L)).thenReturn(Optional.empty());
        when(weatherAugmentedTideService.augment(
                anyDouble(), anyDouble(), anyDouble(), any(), anyString(), anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("Calculation error"));

        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CRASTER_COASTAL, 1L, "Craster", response);

        assertThat(result.surge()).isNull();
    }

    @Test
    @DisplayName("Missing high tide height returns unchanged")
    void missingHighTideHeightReturnsUnchanged() {
        TideSnapshot tide = new TideSnapshot(
                TideState.HIGH, HIGH_TIDE_TIME, null,
                null, null, true, HIGH_TIDE_TIME, null,
                null, null, null, null);
        AtmosphericData base = TestAtmosphericData.builder()
                .locationName("Craster")
                .tide(tide)
                .build();

        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CRASTER_COASTAL, 1L, "Craster",
                buildForecastResponse(990, 15, 60));

        assertThat(result.surge()).isNull();
    }

    @Test
    @DisplayName("Spring tide detected when above threshold but below P95")
    void springTideDetected() {
        AtmosphericData base = baseDataWithTide();
        OpenMeteoForecastResponse response = buildForecastResponse(990, 15, 60);

        // p95HighMetres = 5.00 → tide height 4.80 does NOT exceed it
        // springTideThreshold = 4.60 → tide height 4.80 exceeds it → SPRING_TIDE
        TideStats stats = new TideStats(
                BigDecimal.valueOf(4.00), BigDecimal.valueOf(5.20),
                BigDecimal.valueOf(0.80), BigDecimal.valueOf(0.50),
                100L, BigDecimal.valueOf(3.20),
                BigDecimal.valueOf(4.30), BigDecimal.valueOf(4.50),
                BigDecimal.valueOf(5.00), 15L,
                BigDecimal.valueOf(0.15), BigDecimal.valueOf(4.60),
                BigDecimal.valueOf(4.70), 5L);

        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Spring tide surge");
        when(tideService.getTideStats(1L)).thenReturn(Optional.of(stats));
        when(weatherAugmentedTideService.augment(
                anyDouble(), anyDouble(), anyDouble(), any(),
                org.mockito.ArgumentMatchers.eq("SPRING_TIDE"),
                anyDouble(), anyDouble()))
                .thenReturn(new WeatherAugmentedTideService.AugmentedTideResult(
                        surge, 3.95, 5.15));

        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CRASTER_COASTAL, 1L, "Craster", response);

        assertThat(result.surge()).isNotNull();
        assertThat(result.surge().riskLevel()).isEqualTo(TideRiskLevel.MODERATE);
    }

    @Test
    @DisplayName("Regular tide when height below both thresholds")
    void regularTideWhenBelowThresholds() {
        AtmosphericData base = baseDataWithTide();
        OpenMeteoForecastResponse response = buildForecastResponse(990, 15, 60);

        // p95HighMetres = 5.50 → tide 4.80 below
        // springTideThreshold = 5.00 → tide 4.80 below → REGULAR_TIDE
        TideStats stats = new TideStats(
                BigDecimal.valueOf(4.00), BigDecimal.valueOf(5.50),
                BigDecimal.valueOf(0.80), BigDecimal.valueOf(0.50),
                100L, BigDecimal.valueOf(3.20),
                BigDecimal.valueOf(4.30), BigDecimal.valueOf(4.50),
                BigDecimal.valueOf(5.50), 15L,
                BigDecimal.valueOf(0.15), BigDecimal.valueOf(5.00),
                BigDecimal.valueOf(4.70), 5L);

        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.10, 0.05, 0.15, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.LOW, "Regular tide");
        when(tideService.getTideStats(1L)).thenReturn(Optional.of(stats));
        when(weatherAugmentedTideService.augment(
                anyDouble(), anyDouble(), anyDouble(), any(),
                org.mockito.ArgumentMatchers.eq("REGULAR_TIDE"),
                anyDouble(), anyDouble()))
                .thenReturn(new WeatherAugmentedTideService.AugmentedTideResult(
                        surge, 3.75, 5.00));

        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CRASTER_COASTAL, 1L, "Craster", response);

        assertThat(result.surge()).isNotNull();
        assertThat(result.surge().riskLevel()).isEqualTo(TideRiskLevel.LOW);
    }

    @Test
    @DisplayName("Null P95 threshold falls through to spring tide check")
    void nullP95FallsThroughToSpringCheck() {
        AtmosphericData base = baseDataWithTide();
        OpenMeteoForecastResponse response = buildForecastResponse(990, 15, 60);

        // p95HighMetres = null, springTideThreshold = 4.60 → SPRING_TIDE
        TideStats stats = new TideStats(
                BigDecimal.valueOf(4.00), BigDecimal.valueOf(5.20),
                BigDecimal.valueOf(0.80), BigDecimal.valueOf(0.50),
                100L, BigDecimal.valueOf(3.20),
                BigDecimal.valueOf(4.30), BigDecimal.valueOf(4.50),
                null, 15L,
                BigDecimal.valueOf(0.15), BigDecimal.valueOf(4.60),
                BigDecimal.valueOf(4.70), 5L);

        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Spring tide");
        when(tideService.getTideStats(1L)).thenReturn(Optional.of(stats));
        when(weatherAugmentedTideService.augment(
                anyDouble(), anyDouble(), anyDouble(), any(),
                org.mockito.ArgumentMatchers.eq("SPRING_TIDE"),
                anyDouble(), anyDouble()))
                .thenReturn(new WeatherAugmentedTideService.AugmentedTideResult(
                        surge, 3.95, 5.15));

        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CRASTER_COASTAL, 1L, "Craster", response);

        assertThat(result.surge()).isNotNull();
    }

    @Test
    @DisplayName("Missing weather data at high-tide time returns unchanged")
    void missingWeatherAtHighTideTimeReturnsUnchanged() {
        AtmosphericData base = baseDataWithTide();
        // Forecast response with null surface pressure list
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(List.of(
                "2026-03-30T18:00", "2026-03-30T19:00", "2026-03-30T20:00"));
        hourly.setSurfacePressure(null); // null pressure list
        hourly.setWindSpeed10m(List.of(15.0, 15.0, 15.0));
        hourly.setWindDirection10m(List.of(60, 60, 60));
        response.setHourly(hourly);

        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CRASTER_COASTAL, 1L, "Craster", response);

        assertThat(result.surge()).isNull();
    }

    @Test
    @DisplayName("Null low tide height defaults to zero for range calculation")
    void nullLowTideHeightDefaultsToZero() {
        TideSnapshot tide = new TideSnapshot(
                TideState.HIGH, HIGH_TIDE_TIME, BigDecimal.valueOf(4.80),
                LocalDateTime.of(2026, 3, 30, 13, 0), null,
                true, HIGH_TIDE_TIME, LocalDateTime.of(2026, 3, 30, 13, 0),
                null, null, null, null);
        AtmosphericData base = TestAtmosphericData.builder()
                .locationName("Craster")
                .tide(tide)
                .build();
        OpenMeteoForecastResponse response = buildForecastResponse(990, 15, 60);

        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Test surge");
        when(tideService.getTideStats(1L)).thenReturn(Optional.empty());
        when(weatherAugmentedTideService.augment(
                anyDouble(), anyDouble(), anyDouble(), any(), anyString(),
                // astronomicalRange should be 4.80 - 0.0 = 4.80
                org.mockito.ArgumentMatchers.eq(4.80),
                anyDouble()))
                .thenReturn(new WeatherAugmentedTideService.AugmentedTideResult(
                        surge, 5.15, 5.15));

        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CRASTER_COASTAL, 1L, "Craster", response);

        assertThat(result.surge()).isNotNull();
        assertThat(result.astronomicalRangeMetres()).isCloseTo(4.80,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("Null high tide height in deriveStatisticalSize returns null")
    void nullHighTideHeightReturnsNullStatisticalSize() {
        TideSnapshot tide = new TideSnapshot(
                TideState.HIGH, HIGH_TIDE_TIME, null,
                null, null, true, HIGH_TIDE_TIME, null,
                null, null, null, null);
        AtmosphericData base = TestAtmosphericData.builder()
                .locationName("Craster")
                .tide(tide)
                .build();

        // Should return unchanged because nextHighTideHeightMetres is null (guard clause)
        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CRASTER_COASTAL, 1L, "Craster",
                buildForecastResponse(990, 15, 60));

        assertThat(result.surge()).isNull();
    }

    @Test
    @DisplayName("Calibration logger is called for significant surge")
    void calibrationLoggerCalled() {
        AtmosphericData base = baseDataWithTide();
        OpenMeteoForecastResponse response = buildForecastResponse(990, 15, 60);

        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Test");
        when(tideService.getTideStats(1L)).thenReturn(Optional.empty());
        when(weatherAugmentedTideService.augment(
                anyDouble(), anyDouble(), anyDouble(), any(), anyString(), anyDouble(), anyDouble()))
                .thenReturn(new WeatherAugmentedTideService.AugmentedTideResult(
                        surge, 3.95, 5.15));

        augmentor.augmentWithStormSurge(
                base, CRASTER_COASTAL, 1L, "Craster", response);

        verify(surgeCalibrationLogger).logPrediction(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("Craster"),
                any(), any());
    }
}
