package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraTonightSummary;
import com.gregochr.goldenhour.model.AuroraTomorrowSummary;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.repository.DailyBriefingCacheRepository;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.evaluation.BriefingBestBetAdvisor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingService}.
 */
@ExtendWith(MockitoExtension.class)
class BriefingServiceTest {

    @Mock
    private LocationService locationService;
    @Mock
    private SolarService solarService;
    @Mock
    private OpenMeteoClient openMeteoClient;
    @Mock
    private TideService tideService;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private DailyBriefingCacheRepository briefingCacheRepository;
    @Mock
    private BriefingBestBetAdvisor bestBetAdvisor;
    @Mock
    private AuroraStateCache auroraStateCache;
    @Mock
    private NoaaSwpcClient noaaSwpcClient;

    private BriefingService briefingService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(bestBetAdvisor.advise(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        org.mockito.Mockito.lenient().when(auroraStateCache.isActive()).thenReturn(false);
        org.mockito.Mockito.lenient().when(noaaSwpcClient.fetchKpForecast())
                .thenReturn(java.util.List.of());
        briefingService = new BriefingService(
                locationService, solarService, openMeteoClient, tideService,
                jobRunService, briefingCacheRepository, new ObjectMapper().findAndRegisterModules(),
                new BriefingVerdictEvaluator(), new BriefingHeadlineGenerator(), bestBetAdvisor,
                auroraStateCache, noaaSwpcClient,
                Executors.newVirtualThreadPerTaskExecutor());
    }




    @Nested
    @DisplayName("Event summary grouping")
    class EventSummaryTests {

        @Test
        @DisplayName("Slots grouped by region with unregioned fallback")
        void groupsByRegion() {
            Map<String, String> locationToRegion = new LinkedHashMap<>();
            locationToRegion.put("Bamburgh", "Northumberland");
            locationToRegion.put("Seahouses", "Northumberland");
            locationToRegion.put("Durham", null);

            List<BriefingSlot> slots = List.of(
                    slot("Bamburgh", Verdict.GO),
                    slot("Seahouses", Verdict.MARGINAL),
                    slot("Durham", Verdict.GO));

            BriefingEventSummary summary = briefingService.buildEventSummary(
                    TargetType.SUNSET, slots, locationToRegion);

            assertThat(summary.targetType()).isEqualTo(TargetType.SUNSET);
            assertThat(summary.regions()).hasSize(1);
            assertThat(summary.regions().getFirst().regionName()).isEqualTo("Northumberland");
            assertThat(summary.regions().getFirst().slots()).hasSize(2);
            assertThat(summary.unregioned()).hasSize(1);
            assertThat(summary.unregioned().getFirst().locationName()).isEqualTo("Durham");
        }
    }

    

    @Nested
    @DisplayName("Day structure")
    class DayTests {

        @Test
        @DisplayName("4 event types across 2 days")
        void fourEventTypes() {
            LocalDate today = LocalDate.of(2026, 3, 25);
            LocalDate tomorrow = today.plusDays(1);

            LocationEntity loc = location("Bamburgh", "Northumberland");

            List<BriefingSlot> slots = List.of(
                    slotAt("Bamburgh", Verdict.GO, today.atTime(6, 0)),
                    slotAt("Bamburgh", Verdict.MARGINAL, today.atTime(18, 0)),
                    slotAt("Bamburgh", Verdict.GO, tomorrow.atTime(6, 0)),
                    slotAt("Bamburgh", Verdict.STANDDOWN, tomorrow.atTime(18, 0)));

            List<BriefingDay> days = briefingService.buildDays(
                    slots, List.of(loc), List.of(today, tomorrow));

            assertThat(days).hasSize(2);
            assertThat(days.get(0).eventSummaries()).hasSize(2);
            assertThat(days.get(1).eventSummaries()).hasSize(2);
        }
    }

    


    @Nested
    @DisplayName("Region comfort rollup")
    class RegionComfortTests {

        private BriefingSlot slotWithComfort(String name, Verdict verdict,
                double temp, double apparentTemp, double wind, int weatherCode) {
            return new BriefingSlot(name, LocalDateTime.of(2026, 3, 26, 18, 0), verdict,
                    20, BigDecimal.ZERO, 15000, 70, temp, apparentTemp, weatherCode,
                    BigDecimal.valueOf(wind), null, false, null, null, false, false, List.of());
        }

        @Test
        @DisplayName("averages temperature and wind across GO slots")
        void averagesGoSlots() {
            List<BriefingSlot> slots = List.of(
                    slotWithComfort("A", Verdict.GO, 10.0, 7.0, 5.0, 0),
                    slotWithComfort("B", Verdict.GO, 12.0, 9.0, 3.0, 1),
                    slotWithComfort("C", Verdict.STANDDOWN, 20.0, 18.0, 1.0, 95));
            BriefingRegion region = briefingService.buildRegion("Test", slots);

            assertThat(region.regionTemperatureCelsius()).isEqualTo(11.0);
            assertThat(region.regionApparentTemperatureCelsius()).isEqualTo(8.0);
            assertThat(region.regionWindSpeedMs()).isEqualTo(4.0);
        }

        @Test
        @DisplayName("falls back to all slots when no GO slots present")
        void fallsBackToAllSlots() {
            List<BriefingSlot> slots = List.of(
                    slotWithComfort("A", Verdict.STANDDOWN, 8.0, 5.0, 6.0, 61),
                    slotWithComfort("B", Verdict.STANDDOWN, 10.0, 7.0, 4.0, 63));
            BriefingRegion region = briefingService.buildRegion("Test", slots);

            assertThat(region.regionTemperatureCelsius()).isEqualTo(9.0);
            assertThat(region.regionWindSpeedMs()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("weather code taken from median-temperature GO slot")
        void weatherCodeFromMedianSlot() {
            List<BriefingSlot> slots = List.of(
                    slotWithComfort("A", Verdict.GO, 8.0, 5.0, 5.0, 0),
                    slotWithComfort("B", Verdict.GO, 10.0, 7.0, 5.0, 3),
                    slotWithComfort("C", Verdict.GO, 12.0, 9.0, 5.0, 80));
            BriefingRegion region = briefingService.buildRegion("Test", slots);

            // Sorted by temp: A(8), B(10), C(12) — middle index 1 → weatherCode=3
            assertThat(region.regionWeatherCode()).isEqualTo(3);
        }

        @Test
        @DisplayName("returns null comfort fields when all slots have null temperature")
        void nullTemperatureGraceful() {
            List<BriefingSlot> slots = List.of(
                    new BriefingSlot("A", LocalDateTime.of(2026, 3, 26, 18, 0), Verdict.GO,
                            20, BigDecimal.ZERO, 15000, 70, null, null, null,
                            BigDecimal.ONE, null, false, null, null, false, false, List.of()));
            BriefingRegion region = briefingService.buildRegion("Test", slots);

            assertThat(region.regionTemperatureCelsius()).isNull();
            assertThat(region.regionApparentTemperatureCelsius()).isNull();
            assertThat(region.regionWeatherCode()).isNull();
        }
    }




    @Nested
    @DisplayName("Colour location filter")
    class ColourFilterTests {

        @Test
        @DisplayName("LANDSCAPE location is colour")
        void landscape() {
            LocationEntity loc = LocationEntity.builder()
                    .name("Test").lat(55).lon(-1)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .build();
            assertThat(briefingService.isColourLocation(loc)).isTrue();
        }

        @Test
        @DisplayName("Pure WILDLIFE location is excluded")
        void wildlife() {
            LocationEntity loc = LocationEntity.builder()
                    .name("Test").lat(55).lon(-1)
                    .locationType(Set.of(LocationType.WILDLIFE))
                    .build();
            assertThat(briefingService.isColourLocation(loc)).isFalse();
        }

        @Test
        @DisplayName("Mixed WILDLIFE + SEASCAPE location is included")
        void mixed() {
            LocationEntity loc = LocationEntity.builder()
                    .name("Test").lat(55).lon(-1)
                    .locationType(Set.of(LocationType.WILDLIFE, LocationType.SEASCAPE))
                    .build();
            assertThat(briefingService.isColourLocation(loc)).isTrue();
        }

        @Test
        @DisplayName("Empty location type defaults to colour")
        void emptyType() {
            LocationEntity loc = LocationEntity.builder()
                    .name("Test").lat(55).lon(-1)
                    .locationType(Set.of())
                    .build();
            assertThat(briefingService.isColourLocation(loc)).isTrue();
        }
    }

    

    // ── Coastal tide demotion ──

    @Nested
    @DisplayName("Coastal tide demotion in buildSlot")
    class TideDemotionTests {

        private static final LocalDateTime SOLAR_TIME = LocalDateTime.of(2026, 3, 25, 18, 0);

        private LocationEntity coastalLoc() {
            return LocationEntity.builder()
                    .id(10L).name("Bamburgh").lat(55.6).lon(-1.7)
                    .locationType(Set.of(LocationType.SEASCAPE))
                    .tideType(Set.of(TideType.HIGH))
                    .solarEventType(Set.of())
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        private LocationEntity inlandLoc() {
            return LocationEntity.builder()
                    .id(11L).name("Durham").lat(54.8).lon(-1.6)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of())
                    .solarEventType(Set.of())
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        /** Minimal TideData with a given state and no extremes present. */
        private TideData tideData(TideState state) {
            return new TideData(state, false, null, null, null, null, null, null);
        }

        @Test
        @DisplayName("Coastal + weather GO + tide not aligned → STANDDOWN with 'Tide not aligned' flag")
        void coastal_weatherGo_tideNotAligned_demotedToStanddown() throws Exception {
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveTideData(eq(loc.getId()), eq(SOLAR_TIME)))
                    .thenReturn(Optional.of(tideData(TideState.LOW)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);

            BriefingService.LocationWeather lw =
                    new BriefingService.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = briefingService.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.STANDDOWN);
            assertThat(slot.flags()).contains("Tide not aligned");
        }

        @Test
        @DisplayName("Coastal + weather MARGINAL + tide not aligned → STANDDOWN")
        void coastal_weatherMarginal_tideNotAligned_demotedToStanddown() throws Exception {
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveTideData(eq(loc.getId()), eq(SOLAR_TIME)))
                    .thenReturn(Optional.of(tideData(TideState.LOW)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);

            OpenMeteoForecastResponse marginalForecast = buildForecastResponse();
            marginalForecast.getHourly().getCloudCoverLow().replaceAll(ignored -> 65);

            BriefingService.LocationWeather lw =
                    new BriefingService.LocationWeather(loc, marginalForecast);
            BriefingSlot slot = briefingService.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.STANDDOWN);
            assertThat(slot.flags()).contains("Tide not aligned");
        }

        @Test
        @DisplayName("Inland location + weather GO → GO, no tide demotion")
        void inland_weatherGo_notAffected() throws Exception {
            LocationEntity loc = inlandLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            BriefingService.LocationWeather lw =
                    new BriefingService.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = briefingService.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.GO);
            assertThat(slot.flags()).doesNotContain("Tide not aligned");
        }

        @Test
        @DisplayName("Coastal + no tide data in DB → weather GO verdict retained")
        void coastal_noTideData_weatherVerdictRetained() throws Exception {
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveTideData(eq(loc.getId()), eq(SOLAR_TIME)))
                    .thenReturn(Optional.empty());

            BriefingService.LocationWeather lw =
                    new BriefingService.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = briefingService.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.GO);
            assertThat(slot.flags()).doesNotContain("Tide not aligned");
        }

        @Test
        @DisplayName("Coastal + weather already STANDDOWN + tide not aligned → no 'Tide not aligned' flag")
        void coastal_weatherStanddown_noFlagAdded() throws Exception {
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveTideData(eq(loc.getId()), eq(SOLAR_TIME)))
                    .thenReturn(Optional.of(tideData(TideState.LOW)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);

            OpenMeteoForecastResponse standdownForecast = buildForecastResponse();
            standdownForecast.getHourly().getCloudCoverLow().replaceAll(ignored -> 90);

            BriefingService.LocationWeather lw =
                    new BriefingService.LocationWeather(loc, standdownForecast);
            BriefingSlot slot = briefingService.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.STANDDOWN);
            assertThat(slot.flags()).doesNotContain("Tide not aligned");
            assertThat(slot.flags()).contains("Sun blocked");
        }

        @Test
        @DisplayName("Coastal + tide aligned → GO retained, 'Tide aligned' flag present")
        void coastal_tideAligned_goRetained() throws Exception {
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveTideData(eq(loc.getId()), eq(SOLAR_TIME)))
                    .thenReturn(Optional.of(tideData(TideState.HIGH)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(true);

            BriefingService.LocationWeather lw =
                    new BriefingService.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = briefingService.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.GO);
            assertThat(slot.flags()).doesNotContain("Tide not aligned");
            assertThat(slot.flags()).contains("Tide aligned");
        }
    }

    @Nested
    @DisplayName("Aurora summary building")
    class BuildAuroraSummaryTests {

        @Test
        @DisplayName("buildAuroraTonight returns null when state machine is idle")
        void tonightNull_whenIdle() {
            when(auroraStateCache.isActive()).thenReturn(false);
            assertThat(briefingService.buildAuroraTonight()).isNull();
        }

        @Test
        @DisplayName("buildAuroraTonight returns summary with clear count when active")
        void tonightSummary_whenActive() {
            LocationEntity loc = location("Kielder", "Northumberland");
            AuroraForecastScore score = new AuroraForecastScore(
                    loc, 4, AlertLevel.MODERATE, 40, "Active aurora", "Clear skies");
            when(auroraStateCache.isActive()).thenReturn(true);
            when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
            when(auroraStateCache.getLastTriggerKp()).thenReturn(5.0);
            when(auroraStateCache.getCachedScores()).thenReturn(List.of(score));

            AuroraTonightSummary summary = briefingService.buildAuroraTonight();

            assertThat(summary).isNotNull();
            assertThat(summary.alertLevel()).isEqualTo(AlertLevel.MODERATE);
            assertThat(summary.kp()).isEqualTo(5.0);
            assertThat(summary.clearLocationCount()).isEqualTo(1);
            assertThat(summary.regions()).hasSize(1);
        }

        @Test
        @DisplayName("buildAuroraTomorrow returns Quiet label when Kp < 3")
        void tomorrowQuiet() {
            ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
            when(noaaSwpcClient.fetchKpForecast()).thenReturn(List.of(
                    new KpForecast(now.plusHours(20), now.plusHours(23), 1.5)));

            AuroraTomorrowSummary summary = briefingService.buildAuroraTomorrow();

            assertThat(summary).isNotNull();
            assertThat(summary.label()).isEqualTo("Quiet");
            assertThat(summary.peakKp()).isEqualTo(1.5);
        }

        @Test
        @DisplayName("buildAuroraTomorrow returns Worth watching label when Kp >= 4")
        void tomorrowWorthWatching() {
            ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
            when(noaaSwpcClient.fetchKpForecast()).thenReturn(List.of(
                    new KpForecast(now.plusHours(24), now.plusHours(27), 4.33)));

            AuroraTomorrowSummary summary = briefingService.buildAuroraTomorrow();

            assertThat(summary).isNotNull();
            assertThat(summary.label()).isEqualTo("Worth watching");
        }

        @Test
        @DisplayName("buildAuroraTomorrow returns Potentially strong label when Kp >= 6")
        void tomorrowPotentiallyStrong() {
            ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
            when(noaaSwpcClient.fetchKpForecast()).thenReturn(List.of(
                    new KpForecast(now.plusHours(30), now.plusHours(33), 6.67)));

            AuroraTomorrowSummary summary = briefingService.buildAuroraTomorrow();

            assertThat(summary).isNotNull();
            assertThat(summary.label()).isEqualTo("Potentially strong");
        }

        @Test
        @DisplayName("buildAuroraTomorrow returns null when forecast fetch throws")
        void tomorrowNull_onException() {
            when(noaaSwpcClient.fetchKpForecast())
                    .thenThrow(new RuntimeException("NOAA unavailable"));

            assertThat(briefingService.buildAuroraTomorrow()).isNull();
        }
    }

    @Test
    @DisplayName("getCachedBriefing returns null before first refresh")
    void cache_initiallyNull() {
        assertThat(briefingService.getCachedBriefing()).isNull();
    }

    @Test
    @DisplayName("Refresh populates the cache")
    void refresh_populatesCache() {
        LocationEntity loc = location("Durham", null);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
        when(solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
        when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));
        when(openMeteoClient.fetchForecast(loc.getLat(), loc.getLon()))
                .thenReturn(buildForecastResponse());

        briefingService.refreshBriefing();

        DailyBriefingResponse cached = briefingService.getCachedBriefing();
        assertThat(cached).isNotNull();
        assertThat(cached.days()).hasSize(4);
        assertThat(cached.headline()).isNotBlank();
    }

    

    private static BriefingSlot slot(String name, Verdict verdict) {
        return new BriefingSlot(name,
                LocalDateTime.of(2026, 3, 25, 18, 0), verdict,
                20, BigDecimal.ZERO, 15000, 70, 8.0, null, null, BigDecimal.ONE,
                null, false, null, null, false, false, List.of());
    }

    private static BriefingSlot slotAt(String name, Verdict verdict, LocalDateTime time) {
        return new BriefingSlot(name, time, verdict,
                20, BigDecimal.ZERO, 15000, 70, 8.0, null, null, BigDecimal.ONE,
                null, false, null, null, false, false, List.of());
    }

    private static LocationEntity location(String name, String regionName) {
        RegionEntity region = regionName != null
                ? RegionEntity.builder().name(regionName).build() : null;
        return LocationEntity.builder()
                .id(1L).name(name).lat(55.0).lon(-1.5)
                .locationType(Set.of(LocationType.LANDSCAPE))
                .tideType(Set.of())
                .solarEventType(Set.of())
                .region(region)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private static OpenMeteoForecastResponse buildForecastResponse() {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();

        // Generate 48 hours of data (today + tomorrow)
        List<String> times = new ArrayList<>();
        List<Integer> cloudLow = new ArrayList<>();
        List<Integer> cloudMid = new ArrayList<>();
        List<Integer> cloudHigh = new ArrayList<>();
        List<Double> visibility = new ArrayList<>();
        List<Double> windSpeed = new ArrayList<>();
        List<Integer> windDir = new ArrayList<>();
        List<Double> precip = new ArrayList<>();
        List<Integer> weatherCode = new ArrayList<>();
        List<Integer> humidity = new ArrayList<>();
        List<Double> pressure = new ArrayList<>();
        List<Double> radiation = new ArrayList<>();
        List<Double> blh = new ArrayList<>();
        List<Double> temp = new ArrayList<>();
        List<Double> feelsLike = new ArrayList<>();
        List<Integer> precipProb = new ArrayList<>();
        List<Double> dewPoint = new ArrayList<>();

        LocalDateTime start = LocalDate.now().atStartOfDay();
        for (int i = 0; i < 48; i++) {
            times.add(start.plusHours(i).toString());
            cloudLow.add(20);
            cloudMid.add(30);
            cloudHigh.add(40);
            visibility.add(15000.0);
            windSpeed.add(5.0);
            windDir.add(180);
            precip.add(0.0);
            weatherCode.add(0);
            humidity.add(70);
            pressure.add(1013.0);
            radiation.add(100.0);
            blh.add(500.0);
            temp.add(10.0);
            feelsLike.add(8.0);
            precipProb.add(5);
            dewPoint.add(5.0);
        }

        hourly.setTime(times);
        hourly.setCloudCoverLow(cloudLow);
        hourly.setCloudCoverMid(cloudMid);
        hourly.setCloudCoverHigh(cloudHigh);
        hourly.setVisibility(visibility);
        hourly.setWindSpeed10m(windSpeed);
        hourly.setWindDirection10m(windDir);
        hourly.setPrecipitation(precip);
        hourly.setWeatherCode(weatherCode);
        hourly.setRelativeHumidity2m(humidity);
        hourly.setSurfacePressure(pressure);
        hourly.setShortwaveRadiation(radiation);
        hourly.setBoundaryLayerHeight(blh);
        hourly.setTemperature2m(temp);
        hourly.setApparentTemperature(feelsLike);
        hourly.setPrecipitationProbability(precipProb);
        hourly.setDewPoint2m(dewPoint);

        response.setHourly(hourly);
        return response;
    }
}
