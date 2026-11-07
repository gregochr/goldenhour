package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BluebellConditionScore;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.CoastalParameters;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideContext;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Edge cases of {@link ForecastDataAugmentor}: bluebell gating, the empty-cache fallbacks, tide snapshot
 * building, surge inputs that are missing or short, and the tide-size classification when the stored
 * thresholds are absent.
 */
@ExtendWith(MockitoExtension.class)
class ForecastDataAugmentorEdgeCasesTest {

    private static final LocalDate IN_SEASON = LocalDate.of(2026, 4, 25);
    private static final LocalDate OUT_OF_SEASON = LocalDate.of(2026, 6, 25);
    private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 6, 21, 20, 47);
    private static final LocalDateTime HIGH_TIDE_TIME = LocalDateTime.of(2026, 3, 30, 19, 15);
    private static final CoastalParameters CRASTER = new CoastalParameters(80, 250_000, 30, true);

    @Mock
    private OpenMeteoService openMeteoService;
    @Mock
    private SolarService solarService;
    @Mock
    private TideService tideService;
    @Mock
    private TideFactDeriver tideFactDeriver;
    @Mock
    private WeatherAugmentedTideService weatherAugmentedTideService;
    @Mock
    private SurgeCalibrationLogger surgeCalibrationLogger;
    @Mock
    private BluebellConditionService bluebellConditionService;

    private ForecastDataAugmentor augmentor;

    @BeforeEach
    void setUp() {
        augmentor = new ForecastDataAugmentor(openMeteoService, solarService, tideService, tideFactDeriver,
                weatherAugmentedTideService, surgeCalibrationLogger, bluebellConditionService,
                new SeasonalWindow(MonthDay.of(4, 18), MonthDay.of(5, 18), "BLUEBELL"));
    }

    // ── Bluebell ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Out of bluebell season nothing is scored")
    void bluebell_outOfSeason_returnsBase() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithBluebellConditions(
                base, Set.of(LocationType.BLUEBELL), BluebellExposure.WOODLAND, OUT_OF_SEASON);

        assertThat(result).isSameAs(base);
        verifyNoInteractions(bluebellConditionService);
    }

    @Test
    @DisplayName("In season, a site that is not a bluebell site, or has no location types, is not scored")
    void bluebell_notABluebellSite_returnsBase() {
        AtmosphericData base = TestAtmosphericData.defaults();

        assertThat(augmentor.augmentWithBluebellConditions(
                base, Set.of(LocationType.LANDSCAPE), BluebellExposure.WOODLAND, IN_SEASON)).isSameAs(base);
        assertThat(augmentor.augmentWithBluebellConditions(
                base, null, BluebellExposure.WOODLAND, IN_SEASON)).isSameAs(base);
        verifyNoInteractions(bluebellConditionService);
    }

    @Test
    @DisplayName("In season, a bluebell site with no exposure set is not scored")
    void bluebell_noExposure_returnsBase() {
        AtmosphericData base = TestAtmosphericData.defaults();

        assertThat(augmentor.augmentWithBluebellConditions(
                base, Set.of(LocationType.BLUEBELL), null, IN_SEASON)).isSameAs(base);
        verifyNoInteractions(bluebellConditionService);
    }

    @Test
    @DisplayName("In season, a bluebell site with an exposure carries the score the condition service gives it")
    void bluebell_inSeasonWithExposure_carriesTheScore() {
        AtmosphericData base = TestAtmosphericData.defaults();
        BluebellConditionScore score = new BluebellConditionScore(
                8, true, true, true, false, false, true, BluebellExposure.WOODLAND, "Misty and still");
        when(bluebellConditionService.score(base, BluebellExposure.WOODLAND)).thenReturn(score);

        AtmosphericData result = augmentor.augmentWithBluebellConditions(
                base, Set.of(LocationType.BLUEBELL), BluebellExposure.WOODLAND, IN_SEASON);

        assertThat(result.bluebellConditionScore()).isEqualTo(score);
    }

    // ── Cloud cache fallbacks ────────────────────────────────────────────────────

    @Test
    @DisplayName("An empty cloud cache is no cache: directional cloud is fetched from the API")
    void directionalCloud_emptyCache_callsTheApi() {
        AtmosphericData base = TestAtmosphericData.defaults();
        CloudPointCache empty = new CloudPointCache(Map.of());
        DirectionalCloudData directional = new DirectionalCloudData(10, 20, 30, 40, 50, 60, null);
        when(openMeteoService.fetchDirectionalCloudData(54.77, -1.58, 270, EVENT_TIME, base.targetType(), null))
                .thenReturn(directional);

        AtmosphericData result = augmentor.augmentWithDirectionalCloud(
                base, 54.77, -1.58, 270, EVENT_TIME, null, empty);

        assertThat(result.directionalCloud()).isEqualTo(directional);
        verify(openMeteoService, never()).fetchDirectionalCloudDataFromCache(
                54.77, -1.58, 270, EVENT_TIME, base.targetType(), empty);
    }

    @Test
    @DisplayName("An empty cloud cache is no cache: cloud approach is fetched from the API")
    void cloudApproach_emptyCache_callsTheApi() {
        AtmosphericData base = TestAtmosphericData.defaults();
        CloudPointCache empty = new CloudPointCache(Map.of());
        LocalDateTime now = LocalDateTime.of(2026, 6, 21, 12, 0);
        CloudApproachData approach = org.mockito.Mockito.mock(CloudApproachData.class);
        when(openMeteoService.fetchCloudApproachData(54.77, -1.58, 270, EVENT_TIME, now, base.targetType(),
                base.weather().windDirectionDegrees(), base.weather().windSpeedMs().doubleValue(), null))
                .thenReturn(approach);

        AtmosphericData result = augmentor.augmentWithCloudApproach(
                base, 54.77, -1.58, 270, EVENT_TIME, now, null, empty);

        assertThat(result.cloudApproach()).isSameAs(approach);
    }

    @Test
    @DisplayName("Cloud approach needs the weather block: without it the data is returned untouched")
    void cloudApproach_noWeather_returnsBase() {
        AtmosphericData withWeather = TestAtmosphericData.defaults();
        AtmosphericData base = new AtmosphericData(withWeather.locationName(), withWeather.solarEventTime(),
                withWeather.targetType(), withWeather.cloud(), null, withWeather.aerosol(),
                withWeather.comfort(), null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);

        AtmosphericData result = augmentor.augmentWithCloudApproach(
                base, 54.77, -1.58, 270, EVENT_TIME, LocalDateTime.of(2026, 6, 21, 12, 0), null, null);

        assertThat(result).isSameAs(base);
        verifyNoInteractions(openMeteoService);
    }

    // ── Tide snapshot and context ────────────────────────────────────────────────

    @Test
    @DisplayName("The tide snapshot is built for coastal locations only, once each, and ignores unsaved ones")
    void tideSnapshotFor_filtersToDistinctCoastalIds() {
        LocationEntity coastal = location(1L, Set.of(TideType.HIGH));
        LocationEntity sameCoastal = location(1L, Set.of(TideType.HIGH));
        LocationEntity inland = location(2L, Set.of());
        LocationEntity noTideSet = location(3L, null);
        LocationEntity unsaved = location(null, Set.of(TideType.LOW));
        List<LocalDate> dates = List.of(IN_SEASON);
        TideExtremeSnapshot snapshot = org.mockito.Mockito.mock(TideExtremeSnapshot.class);
        when(tideService.snapshotFor(List.of(1L), dates)).thenReturn(snapshot);

        TideExtremeSnapshot result = augmentor.tideSnapshotFor(
                List.of(coastal, sameCoastal, inland, noTideSet, unsaved), dates);

        assertThat(result).isSameAs(snapshot);
    }

    @Test
    @DisplayName("deriveTideContext for a sunrise slot reads the location's own sunrise")
    void deriveTideContext_sunrise_readsSunriseUtc() {
        LocationEntity coastal = location(1L, Set.of(TideType.HIGH));
        LocalDate date = LocalDate.of(2026, 3, 25);
        LocalDateTime sunrise = LocalDateTime.of(2026, 3, 25, 6, 5);
        when(solarService.sunriseUtc(55.0, -1.5, date)).thenReturn(sunrise);
        when(tideFactDeriver.derive(1L, sunrise, Set.of(TideType.HIGH), 55.0, -1.5, TargetType.SUNRISE))
                .thenReturn(Optional.empty());

        Optional<TideContext> result = augmentor.deriveTideContext(coastal, date, TargetType.SUNRISE);

        assertThat(result).isEmpty();
        verify(solarService, never()).sunsetUtc(55.0, -1.5, date);
    }

    @Test
    @DisplayName("deriveTideContext for a location with no tide types short-circuits without touching the tide data")
    void deriveTideContext_nullTideTypes_returnsEmpty() {
        LocationEntity noTideSet = location(3L, null);

        assertThat(augmentor.deriveTideContext(noTideSet, IN_SEASON, TargetType.SUNSET)).isEmpty();
        verifyNoInteractions(solarService, tideFactDeriver);
    }

    // ── Storm surge inputs ───────────────────────────────────────────────────────

    private static AtmosphericData baseWithTide(LocalDateTime nextHigh, BigDecimal nextHighHeight) {
        TideSnapshot tide = new TideSnapshot(TideState.HIGH, nextHigh, nextHighHeight,
                LocalDateTime.of(2026, 3, 30, 13, 0), BigDecimal.valueOf(1.20),
                true, nextHigh, LocalDateTime.of(2026, 3, 30, 13, 0), null, null, null, null);
        return TestAtmosphericData.builder().locationName("Craster").tide(tide).build();
    }

    private static OpenMeteoForecastResponse forecast(List<Double> pressure, List<Double> windSpeed,
            List<Integer> windDir) {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(List.of("2026-03-30T18:00", "2026-03-30T19:00", "2026-03-30T20:00"));
        hourly.setPressureMsl(pressure);
        hourly.setWindSpeed10m(windSpeed);
        hourly.setWindDirection10m(windDir);
        response.setHourly(hourly);
        return response;
    }

    @Test
    @DisplayName("A tide with no next high-tide time cannot have a surge computed")
    void surge_noNextHighTideTime_returnsBase() {
        AtmosphericData base = baseWithTide(null, BigDecimal.valueOf(4.8));

        AtmosphericData result = augmentor.augmentWithStormSurge(
                base, CRASTER, 1L, "Craster",
                forecast(List.of(990.0, 990.0, 990.0), List.of(15.0, 15.0, 15.0), List.of(60, 60, 60)));

        assertThat(result).isSameAs(base);
        verifyNoInteractions(weatherAugmentedTideService);
    }

    @Test
    @DisplayName("A missing or too-short pressure, wind speed or wind direction array skips the surge")
    void surge_missingOrShortWeatherArrays_skipTheSurge() {
        AtmosphericData base = baseWithTide(HIGH_TIDE_TIME, BigDecimal.valueOf(4.8));
        List<Double> pressure = List.of(990.0, 990.0, 990.0);
        List<Double> wind = List.of(15.0, 15.0, 15.0);
        List<Integer> dir = List.of(60, 60, 60);

        // The high tide is the second hour (index 1): an array of one entry is too short for it.
        assertThat(augmentor.augmentWithStormSurge(base, CRASTER, 1L, "Craster",
                forecast(null, wind, dir))).isSameAs(base);
        assertThat(augmentor.augmentWithStormSurge(base, CRASTER, 1L, "Craster",
                forecast(List.of(990.0), wind, dir))).isSameAs(base);
        assertThat(augmentor.augmentWithStormSurge(base, CRASTER, 1L, "Craster",
                forecast(pressure, null, dir))).isSameAs(base);
        assertThat(augmentor.augmentWithStormSurge(base, CRASTER, 1L, "Craster",
                forecast(pressure, List.of(15.0), dir))).isSameAs(base);
        assertThat(augmentor.augmentWithStormSurge(base, CRASTER, 1L, "Craster",
                forecast(pressure, wind, null))).isSameAs(base);
        assertThat(augmentor.augmentWithStormSurge(base, CRASTER, 1L, "Craster",
                forecast(pressure, wind, List.of(60)))).isSameAs(base);
        verifyNoInteractions(weatherAugmentedTideService);
    }

    @Test
    @DisplayName("Stored tide stats with no P95 and no spring threshold classify the tide as regular")
    void surge_statsWithoutThresholds_areRegularTide() {
        AtmosphericData base = baseWithTide(HIGH_TIDE_TIME, BigDecimal.valueOf(4.8));
        TideStats stats = new TideStats(null, null, null, null, 0, null, null, null, null, 0, null, null, null, 0);
        when(tideService.getTideStats(1L)).thenReturn(Optional.of(stats));
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85, TideRiskLevel.MODERATE, "Test surge");
        when(weatherAugmentedTideService.augment(990.0, 15.0, 60.0, CRASTER, "REGULAR_TIDE",
                4.8 - 1.2, 4.8)).thenReturn(new WeatherAugmentedTideService.AugmentedTideResult(surge, 3.95, 5.15));

        AtmosphericData result = augmentor.augmentWithStormSurge(base, CRASTER, 1L, "Craster",
                forecast(List.of(990.0, 990.0, 990.0), List.of(15.0, 15.0, 15.0), List.of(60, 60, 60)));

        assertThat(result.surge()).isEqualTo(surge);
        verify(surgeCalibrationLogger).logPrediction(1L, "Craster",
                HIGH_TIDE_TIME.toInstant(java.time.ZoneOffset.UTC), surge);
    }

    @Test
    @DisplayName("A P95 above the high tide and no spring threshold also classify as regular")
    void surge_p95AboveHighTideAndNoSpringThreshold_isRegularTide() {
        AtmosphericData base = baseWithTide(HIGH_TIDE_TIME, BigDecimal.valueOf(4.8));
        TideStats stats = new TideStats(null, null, null, null, 0, null, null, null,
                BigDecimal.valueOf(5.2), 0, null, null, null, 0);
        when(tideService.getTideStats(1L)).thenReturn(Optional.of(stats));
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85, TideRiskLevel.MODERATE, "Test surge");
        when(weatherAugmentedTideService.augment(990.0, 15.0, 60.0, CRASTER, "REGULAR_TIDE",
                4.8 - 1.2, 4.8)).thenReturn(new WeatherAugmentedTideService.AugmentedTideResult(surge, 3.95, 5.15));

        AtmosphericData result = augmentor.augmentWithStormSurge(base, CRASTER, 1L, "Craster",
                forecast(List.of(990.0, 990.0, 990.0), List.of(15.0, 15.0, 15.0), List.of(60, 60, 60)));

        assertThat(result.surge()).isEqualTo(surge);
    }

    private static LocationEntity location(Long id, Set<TideType> tideTypes) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName("Loc" + id);
        loc.setLat(55.0);
        loc.setLon(-1.5);
        loc.setTideType(tideTypes);
        return loc;
    }
}
