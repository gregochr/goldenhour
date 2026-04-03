package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.DailyBriefingCacheEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.repository.DailyBriefingCacheRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.evaluation.BriefingBestBetAdvisor;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
    private LocationRepository locationRepository;
    @Mock
    private BriefingBestBetAdvisor bestBetAdvisor;
    @Mock
    private BriefingAuroraSummaryBuilder auroraSummaryBuilder;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private BriefingService briefingService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(bestBetAdvisor.advise(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        BriefingVerdictEvaluator verdictEvaluator = new BriefingVerdictEvaluator();
        LunarPhaseService lunarPhaseService = new LunarPhaseService();
        BriefingSlotBuilder slotBuilder = new BriefingSlotBuilder(
                solarService, locationService, tideService, lunarPhaseService, verdictEvaluator);
        briefingService = new BriefingService(
                locationService, openMeteoClient,
                jobRunService, briefingCacheRepository, locationRepository,
                new ObjectMapper().findAndRegisterModules(),
                new BriefingHeadlineGenerator(), bestBetAdvisor,
                auroraSummaryBuilder,
                new BriefingHierarchyBuilder(verdictEvaluator),
                slotBuilder, eventPublisher);
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
        when(openMeteoClient.fetchForecastBriefing(loc.getLat(), loc.getLon()))
                .thenReturn(buildForecastResponse());

        briefingService.refreshBriefing();

        DailyBriefingResponse cached = briefingService.getCachedBriefing();
        assertThat(cached).isNotNull();
        assertThat(cached.days()).hasSize(4);
        assertThat(cached.headline()).isNotBlank();
    }

    @Nested
    @DisplayName("Solar event type filtering in briefing triage")
    class SolarEventFilterTests {

        @Test
        @DisplayName("SUNRISE-only location produces only sunrise event summaries")
        void sunriseOnly_producesSunriseOnly() {
            LocationEntity loc = LocationEntity.builder()
                    .id(1L).name("East Facing").lat(55.0).lon(-1.5)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of())
                    .solarEventType(Set.of(SolarEventType.SUNRISE))
                    .region(RegionEntity.builder().name("North East").build())
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
            stubForLocation(loc);

            briefingService.refreshBriefing();

            DailyBriefingResponse cached = briefingService.getCachedBriefing();
            assertThat(cached).isNotNull();
            // Check first day (within 48h forecast window)
            BriefingDay day = cached.days().getFirst();
            BriefingEventSummary sunrise = findEvent(day, TargetType.SUNRISE);
            BriefingEventSummary sunset = findEvent(day, TargetType.SUNSET);
            assertThat(sunrise.regions()).isNotEmpty();
            assertThat(sunset.regions()).isEmpty();
            assertThat(sunset.unregioned()).isEmpty();
        }

        @Test
        @DisplayName("SUNSET-only location produces only sunset slots")
        void sunsetOnly_producesSunsetOnly() {
            LocationEntity loc = LocationEntity.builder()
                    .id(2L).name("West Facing").lat(55.0).lon(-1.5)
                    .locationType(Set.of(LocationType.SEASCAPE))
                    .tideType(Set.of())
                    .solarEventType(Set.of(SolarEventType.SUNSET))
                    .region(RegionEntity.builder().name("North East").build())
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
            stubForLocation(loc);

            briefingService.refreshBriefing();

            DailyBriefingResponse cached = briefingService.getCachedBriefing();
            assertThat(cached).isNotNull();
            BriefingDay day = cached.days().getFirst();
            BriefingEventSummary sunrise = findEvent(day, TargetType.SUNRISE);
            BriefingEventSummary sunset = findEvent(day, TargetType.SUNSET);
            assertThat(sunset.regions()).isNotEmpty();
            assertThat(sunrise.regions()).isEmpty();
            assertThat(sunrise.unregioned()).isEmpty();
        }

        @Test
        @DisplayName("Empty solarEventType produces both sunrise and sunset slots")
        void emptySolarEventType_producesBoth() {
            LocationEntity loc = LocationEntity.builder()
                    .id(3L).name("Both Facing").lat(55.0).lon(-1.5)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of())
                    .solarEventType(Set.of())
                    .region(RegionEntity.builder().name("North East").build())
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
            stubForLocation(loc);

            briefingService.refreshBriefing();

            DailyBriefingResponse cached = briefingService.getCachedBriefing();
            assertThat(cached).isNotNull();
            BriefingDay day = cached.days().getFirst();
            BriefingEventSummary sunrise = findEvent(day, TargetType.SUNRISE);
            BriefingEventSummary sunset = findEvent(day, TargetType.SUNSET);
            assertThat(sunrise.regions()).isNotEmpty();
            assertThat(sunset.regions()).isNotEmpty();
        }

        private BriefingEventSummary findEvent(BriefingDay day, TargetType type) {
            return day.eventSummaries().stream()
                    .filter(e -> e.targetType() == type)
                    .findFirst()
                    .orElseThrow();
        }

        private void stubForLocation(LocationEntity loc) {
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            org.mockito.Mockito.lenient().when(
                    solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
            org.mockito.Mockito.lenient().when(
                    solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));
            when(openMeteoClient.fetchForecastBriefing(loc.getLat(), loc.getLon()))
                    .thenReturn(buildForecastResponse());
        }
    }

    @Test
    @DisplayName("Refresh with no colour locations skips and completes run")
    void refresh_noColourLocations_skips() {
        LocationEntity wildlife = LocationEntity.builder()
                .id(2L).name("Wildlife Hide").lat(55).lon(-1)
                .locationType(Set.of(LocationType.WILDLIFE))
                .tideType(Set.of()).solarEventType(Set.of())
                .enabled(true).createdAt(LocalDateTime.now()).build();

        when(locationService.findAllEnabled()).thenReturn(List.of(wildlife));
        when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());

        briefingService.refreshBriefing();

        assertThat(briefingService.getCachedBriefing()).isNull();
        verify(jobRunService).completeRun(any(), eq(0), eq(0));
    }

    @Test
    @DisplayName("Refresh with weather fetch failure marks location as failed")
    void refresh_weatherFetchFailure_partialResult() {
        LocationEntity loc = location("Durham", null);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
        when(openMeteoClient.fetchForecastBriefing(loc.getLat(), loc.getLon()))
                .thenThrow(new RuntimeException("API timeout"));

        briefingService.refreshBriefing();

        // All locations failed → below 50% threshold, no LKG → partial result served
        DailyBriefingResponse cached = briefingService.getCachedBriefing();
        assertThat(cached).isNotNull();
        assertThat(cached.failedLocationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Below 50% threshold with LKG serves stale response")
    void refresh_belowThresholdWithLkg_servesStale() {
        LocationEntity loc = location("Durham", null);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());

        // First: successful refresh to populate LKG
        when(openMeteoClient.fetchForecastBriefing(loc.getLat(), loc.getLon()))
                .thenReturn(buildForecastResponse());
        when(solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
        when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));

        briefingService.refreshBriefing();
        DailyBriefingResponse firstCached = briefingService.getCachedBriefing();
        assertThat(firstCached).isNotNull();
        assertThat(firstCached.stale()).isFalse();

        // Second: failed refresh → below 50%, LKG exists → stale response
        when(openMeteoClient.fetchForecastBriefing(loc.getLat(), loc.getLon()))
                .thenThrow(new RuntimeException("API down"));

        briefingService.refreshBriefing();

        DailyBriefingResponse staleCached = briefingService.getCachedBriefing();
        assertThat(staleCached).isNotNull();
        assertThat(staleCached.stale()).isTrue();
        assertThat(staleCached.partialFailure()).isTrue();
    }

    @Test
    @DisplayName("Refresh with multiple locations where some fail — partial success above threshold")
    void refresh_partialFailureAboveThreshold() {
        LocationEntity loc1 = location("Durham", null);
        LocationEntity loc2 = LocationEntity.builder()
                .id(2L).name("Bamburgh").lat(55.6).lon(-1.7)
                .locationType(Set.of(LocationType.SEASCAPE))
                .tideType(Set.of()).solarEventType(Set.of())
                .region(null).enabled(true).createdAt(LocalDateTime.now()).build();

        when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
        when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
        when(solarService.sunriseUtc(eq(loc1.getLat()), eq(loc1.getLon()), any(LocalDate.class)))
                .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
        when(solarService.sunsetUtc(eq(loc1.getLat()), eq(loc1.getLon()), any(LocalDate.class)))
                .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));
        // loc1 succeeds
        when(openMeteoClient.fetchForecastBriefing(loc1.getLat(), loc1.getLon()))
                .thenReturn(buildForecastResponse());
        // loc2 fails
        when(openMeteoClient.fetchForecastBriefing(loc2.getLat(), loc2.getLon()))
                .thenThrow(new RuntimeException("Timeout"));

        briefingService.refreshBriefing();

        DailyBriefingResponse cached = briefingService.getCachedBriefing();
        assertThat(cached).isNotNull();
        assertThat(cached.stale()).isFalse();
        assertThat(cached.partialFailure()).isTrue();
        assertThat(cached.failedLocationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("loadPersistedBriefing restores cache from DB entity")
    void loadPersistedBriefing_restoresCache() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DailyBriefingResponse persisted = new DailyBriefingResponse(
                LocalDateTime.now(ZoneOffset.UTC), "Test headline", List.of(), List.of(),
                null, null, false, false, 0, "Opus");
        DailyBriefingCacheEntity entity = new DailyBriefingCacheEntity();
        entity.setId(1);
        entity.setGeneratedAt(persisted.generatedAt());
        entity.setPayload(mapper.writeValueAsString(persisted));

        when(briefingCacheRepository.findById(1)).thenReturn(Optional.of(entity));

        // Re-create service to trigger @PostConstruct
        BriefingVerdictEvaluator verdictEvaluator = new BriefingVerdictEvaluator();
        LunarPhaseService lunarPhaseService = new LunarPhaseService();
        BriefingSlotBuilder slotBuilder = new BriefingSlotBuilder(
                solarService, locationService, tideService, lunarPhaseService, verdictEvaluator);
        BriefingService freshService = new BriefingService(
                locationService, openMeteoClient,
                jobRunService, briefingCacheRepository, locationRepository, mapper,
                new BriefingHeadlineGenerator(), bestBetAdvisor,
                auroraSummaryBuilder,
                new BriefingHierarchyBuilder(verdictEvaluator),
                slotBuilder, eventPublisher);
        freshService.loadPersistedBriefing();

        DailyBriefingResponse cached = freshService.getCachedBriefing();
        assertThat(cached).isNotNull();
        assertThat(cached.headline()).isEqualTo("Test headline");
    }

    @Test
    @DisplayName("loadPersistedBriefing handles corrupt JSON gracefully")
    void loadPersistedBriefing_corruptJson_doesNotThrow() {
        DailyBriefingCacheEntity entity = new DailyBriefingCacheEntity();
        entity.setId(1);
        entity.setGeneratedAt(LocalDateTime.now());
        entity.setPayload("{invalid json!!!");

        when(briefingCacheRepository.findById(1)).thenReturn(Optional.of(entity));

        BriefingVerdictEvaluator verdictEvaluator = new BriefingVerdictEvaluator();
        LunarPhaseService lunarPhaseService = new LunarPhaseService();
        BriefingSlotBuilder slotBuilder = new BriefingSlotBuilder(
                solarService, locationService, tideService, lunarPhaseService, verdictEvaluator);
        BriefingService freshService = new BriefingService(
                locationService, openMeteoClient,
                jobRunService, briefingCacheRepository, locationRepository,
                new ObjectMapper().findAndRegisterModules(),
                new BriefingHeadlineGenerator(), bestBetAdvisor,
                auroraSummaryBuilder,
                new BriefingHierarchyBuilder(verdictEvaluator),
                slotBuilder, eventPublisher);
        freshService.loadPersistedBriefing();

        assertThat(freshService.getCachedBriefing()).isNull();
    }

    @Test
    @DisplayName("loadPersistedBriefing with empty repository leaves cache null")
    void loadPersistedBriefing_emptyRepo_cachNull() {
        when(briefingCacheRepository.findById(1)).thenReturn(Optional.empty());

        BriefingVerdictEvaluator verdictEvaluator = new BriefingVerdictEvaluator();
        LunarPhaseService lunarPhaseService = new LunarPhaseService();
        BriefingSlotBuilder slotBuilder = new BriefingSlotBuilder(
                solarService, locationService, tideService, lunarPhaseService, verdictEvaluator);
        BriefingService freshService = new BriefingService(
                locationService, openMeteoClient,
                jobRunService, briefingCacheRepository, locationRepository,
                new ObjectMapper().findAndRegisterModules(),
                new BriefingHeadlineGenerator(), bestBetAdvisor,
                auroraSummaryBuilder,
                new BriefingHierarchyBuilder(verdictEvaluator),
                slotBuilder, eventPublisher);
        freshService.loadPersistedBriefing();

        assertThat(freshService.getCachedBriefing()).isNull();
    }

    @Test
    @DisplayName("Refresh with all locations returning empty (no enabled) completes run")
    void refresh_emptyEnabledLocations_skips() {
        when(locationService.findAllEnabled()).thenReturn(List.of());
        when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());

        briefingService.refreshBriefing();

        assertThat(briefingService.getCachedBriefing()).isNull();
        verify(jobRunService).completeRun(any(), eq(0), eq(0));
    }

    @Nested
    @DisplayName("Grid cell deduplication")
    class GridDeduplicationTests {

        @Test
        @DisplayName("Locations sharing a grid cell are fetched once and all receive weather")
        void fetchWeather_groupsByGridCell_fetchesOncePerGroup() {
            LocationEntity loc1 = LocationEntity.builder()
                    .id(1L).name("Bamburgh Castle").lat(55.609).lon(-1.710)
                    .locationType(Set.of(LocationType.SEASCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .gridLat(55.6).gridLng(-1.7).enabled(true)
                    .createdAt(LocalDateTime.now()).build();
            LocationEntity loc2 = LocationEntity.builder()
                    .id(2L).name("Bamburgh Dunes").lat(55.611).lon(-1.712)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .gridLat(55.6).gridLng(-1.7).enabled(true)
                    .createdAt(LocalDateTime.now()).build();
            LocationEntity loc3 = LocationEntity.builder()
                    .id(3L).name("Durham").lat(54.775).lon(-1.585)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .gridLat(54.8).gridLng(-1.6).enabled(true)
                    .createdAt(LocalDateTime.now()).build();
            LocationEntity loc4 = LocationEntity.builder()
                    .id(4L).name("Durham Cathedral").lat(54.774).lon(-1.576)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .gridLat(54.8).gridLng(-1.6).enabled(true)
                    .createdAt(LocalDateTime.now()).build();

            when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2, loc3, loc4));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc1);
            stubSolarTimes(loc2);
            stubSolarTimes(loc3);
            stubSolarTimes(loc4);

            OpenMeteoForecastResponse bamburghForecast = buildForecastResponse();
            OpenMeteoForecastResponse durhamForecast = buildForecastResponse();
            // First group (Bamburgh) — fetched using loc1's coordinates
            when(openMeteoClient.fetchForecastBriefing(loc1.getLat(), loc1.getLon()))
                    .thenReturn(bamburghForecast);
            // Second group (Durham) — fetched using loc3's coordinates
            when(openMeteoClient.fetchForecastBriefing(loc3.getLat(), loc3.getLon()))
                    .thenReturn(durhamForecast);

            briefingService.refreshBriefing();

            // Only 2 API calls, not 4
            org.mockito.Mockito.verify(openMeteoClient, org.mockito.Mockito.times(1))
                    .fetchForecastBriefing(loc1.getLat(), loc1.getLon());
            org.mockito.Mockito.verify(openMeteoClient, org.mockito.Mockito.times(1))
                    .fetchForecastBriefing(loc3.getLat(), loc3.getLon());
            org.mockito.Mockito.verify(openMeteoClient, org.mockito.Mockito.never())
                    .fetchForecastBriefing(loc2.getLat(), loc2.getLon());
            org.mockito.Mockito.verify(openMeteoClient, org.mockito.Mockito.never())
                    .fetchForecastBriefing(loc4.getLat(), loc4.getLon());

            DailyBriefingResponse cached = briefingService.getCachedBriefing();
            assertThat(cached).isNotNull();
            assertThat(cached.failedLocationCount()).isZero();
        }

        @Test
        @DisplayName("Ungrouped locations (no grid cell) are fetched individually")
        void fetchWeather_ungroupedLocations_fetchedIndividually() {
            LocationEntity loc1 = LocationEntity.builder()
                    .id(1L).name("NewLoc1").lat(55.0).lon(-1.5)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
            LocationEntity loc2 = LocationEntity.builder()
                    .id(2L).name("NewLoc2").lat(54.0).lon(-2.0)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();

            when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc1);
            stubSolarTimes(loc2);

            OpenMeteoForecastResponse resp1 = buildForecastResponseWithGrid(55.0, -1.5);
            OpenMeteoForecastResponse resp2 = buildForecastResponseWithGrid(54.0, -2.0);
            when(openMeteoClient.fetchForecastBriefing(loc1.getLat(), loc1.getLon()))
                    .thenReturn(resp1);
            when(openMeteoClient.fetchForecastBriefing(loc2.getLat(), loc2.getLon()))
                    .thenReturn(resp2);

            briefingService.refreshBriefing();

            // Both fetched individually
            org.mockito.Mockito.verify(openMeteoClient).fetchForecastBriefing(loc1.getLat(), loc1.getLon());
            org.mockito.Mockito.verify(openMeteoClient).fetchForecastBriefing(loc2.getLat(), loc2.getLon());
        }

        @Test
        @DisplayName("Grid coordinates are captured from response and saved")
        void fetchWeather_capturesGridCoordinatesOnFirstFetch() {
            LocationEntity loc = LocationEntity.builder()
                    .id(1L).name("NewLoc").lat(55.123).lon(-1.456)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();

            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc);

            OpenMeteoForecastResponse resp = buildForecastResponseWithGrid(55.1, -1.5);
            when(openMeteoClient.fetchForecastBriefing(loc.getLat(), loc.getLon()))
                    .thenReturn(resp);

            briefingService.refreshBriefing();

            assertThat(loc.getGridLat()).isEqualTo(55.1);
            assertThat(loc.getGridLng()).isEqualTo(-1.5);
            org.mockito.Mockito.verify(locationRepository).saveAll(any());
        }

        @Test
        @DisplayName("Mixed grouped and ungrouped locations: 2 grouped + 1 ungrouped = 2 fetches")
        void fetchWeather_mixedGroupedAndUngrouped_correctFetchCount() {
            LocationEntity grouped1 = LocationEntity.builder()
                    .id(1L).name("Bamburgh").lat(55.609).lon(-1.710)
                    .locationType(Set.of(LocationType.SEASCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .gridLat(55.6).gridLng(-1.7).enabled(true)
                    .createdAt(LocalDateTime.now()).build();
            LocationEntity grouped2 = LocationEntity.builder()
                    .id(2L).name("Bamburgh Dunes").lat(55.611).lon(-1.712)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .gridLat(55.6).gridLng(-1.7).enabled(true)
                    .createdAt(LocalDateTime.now()).build();
            LocationEntity ungrouped = LocationEntity.builder()
                    .id(3L).name("NewLoc").lat(53.0).lon(-2.0)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();

            when(locationService.findAllEnabled())
                    .thenReturn(List.of(grouped1, grouped2, ungrouped));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(grouped1);
            stubSolarTimes(grouped2);
            stubSolarTimes(ungrouped);

            when(openMeteoClient.fetchForecastBriefing(grouped1.getLat(), grouped1.getLon()))
                    .thenReturn(buildForecastResponse());
            when(openMeteoClient.fetchForecastBriefing(ungrouped.getLat(), ungrouped.getLon()))
                    .thenReturn(buildForecastResponseWithGrid(53.0, -2.0));

            briefingService.refreshBriefing();

            // 2 fetches: one for grouped pair, one for ungrouped
            org.mockito.Mockito.verify(openMeteoClient, org.mockito.Mockito.times(1))
                    .fetchForecastBriefing(grouped1.getLat(), grouped1.getLon());
            org.mockito.Mockito.verify(openMeteoClient, org.mockito.Mockito.times(1))
                    .fetchForecastBriefing(ungrouped.getLat(), ungrouped.getLon());
            org.mockito.Mockito.verify(openMeteoClient, org.mockito.Mockito.never())
                    .fetchForecastBriefing(grouped2.getLat(), grouped2.getLon());

            assertThat(briefingService.getCachedBriefing().failedLocationCount()).isZero();
        }

        @Test
        @DisplayName("Response with null lat/lon does not persist grid coordinates")
        void fetchWeather_nullResponseCoords_doesNotSave() {
            LocationEntity loc = LocationEntity.builder()
                    .id(1L).name("Loc").lat(55.0).lon(-1.5)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();

            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc);

            // Response with null latitude/longitude
            OpenMeteoForecastResponse resp = buildForecastResponse();
            when(openMeteoClient.fetchForecastBriefing(loc.getLat(), loc.getLon()))
                    .thenReturn(resp);

            briefingService.refreshBriefing();

            assertThat(loc.getGridLat()).isNull();
            assertThat(loc.getGridLng()).isNull();
            org.mockito.Mockito.verify(locationRepository, org.mockito.Mockito.never())
                    .saveAll(any());
        }

        @Test
        @DisplayName("Already-populated grid cells are not re-saved")
        void fetchWeather_alreadyPopulated_notResaved() {
            LocationEntity loc = LocationEntity.builder()
                    .id(1L).name("Loc").lat(55.0).lon(-1.5)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .gridLat(55.0).gridLng(-1.5).enabled(true)
                    .createdAt(LocalDateTime.now()).build();

            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc);

            when(openMeteoClient.fetchForecastBriefing(loc.getLat(), loc.getLon()))
                    .thenReturn(buildForecastResponseWithGrid(55.0, -1.5));

            briefingService.refreshBriefing();

            // Grid cell was already set — no save needed
            org.mockito.Mockito.verify(locationRepository, org.mockito.Mockito.never())
                    .saveAll(any());
        }

        @Test
        @DisplayName("All locations in one grid cell — single API call")
        void fetchWeather_allSameGridCell_singleFetch() {
            List<LocationEntity> locs = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                LocationEntity loc = LocationEntity.builder()
                        .id((long) i).name("Loc" + i).lat(55.0 + i * 0.001).lon(-1.5)
                        .locationType(Set.of(LocationType.LANDSCAPE))
                        .tideType(Set.of()).solarEventType(Set.of())
                        .gridLat(55.0).gridLng(-1.5).enabled(true)
                        .createdAt(LocalDateTime.now()).build();
                locs.add(loc);
                stubSolarTimes(loc);
            }

            when(locationService.findAllEnabled()).thenReturn(locs);
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            when(openMeteoClient.fetchForecastBriefing(locs.getFirst().getLat(),
                    locs.getFirst().getLon())).thenReturn(buildForecastResponse());

            briefingService.refreshBriefing();

            // 1 API call for 5 locations
            org.mockito.Mockito.verify(openMeteoClient, org.mockito.Mockito.times(1))
                    .fetchForecastBriefing(org.mockito.ArgumentMatchers.anyDouble(),
                            org.mockito.ArgumentMatchers.anyDouble());
            assertThat(briefingService.getCachedBriefing().failedLocationCount()).isZero();
        }

        @Test
        @DisplayName("saveAll failure during grid capture is logged but does not break briefing")
        void fetchWeather_saveAllFailure_briefingStillCompletes() {
            LocationEntity loc = LocationEntity.builder()
                    .id(1L).name("Loc").lat(55.0).lon(-1.5)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();

            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc);

            when(openMeteoClient.fetchForecastBriefing(loc.getLat(), loc.getLon()))
                    .thenReturn(buildForecastResponseWithGrid(55.0, -1.5));
            when(locationRepository.saveAll(any()))
                    .thenThrow(new RuntimeException("DB write failed"));

            briefingService.refreshBriefing();

            // Briefing still completes despite save failure
            DailyBriefingResponse cached = briefingService.getCachedBriefing();
            assertThat(cached).isNotNull();
            assertThat(cached.failedLocationCount()).isZero();
        }

        @Test
        @DisplayName("Failed fetch marks all group members as failed")
        void fetchWeather_failedFetch_allGroupMembersGetNull() {
            LocationEntity loc1 = LocationEntity.builder()
                    .id(1L).name("Loc1").lat(55.0).lon(-1.5)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .gridLat(55.0).gridLng(-1.5).enabled(true)
                    .createdAt(LocalDateTime.now()).build();
            LocationEntity loc2 = LocationEntity.builder()
                    .id(2L).name("Loc2").lat(55.001).lon(-1.501)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .gridLat(55.0).gridLng(-1.5).enabled(true)
                    .createdAt(LocalDateTime.now()).build();

            when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());

            when(openMeteoClient.fetchForecastBriefing(loc1.getLat(), loc1.getLon()))
                    .thenThrow(new RuntimeException("API timeout"));

            briefingService.refreshBriefing();

            DailyBriefingResponse cached = briefingService.getCachedBriefing();
            assertThat(cached).isNotNull();
            // Both locations in the group failed
            assertThat(cached.failedLocationCount()).isEqualTo(2);
        }

        private void stubSolarTimes(LocationEntity loc) {
            org.mockito.Mockito.lenient().when(
                    solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
            org.mockito.Mockito.lenient().when(
                    solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));
        }
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

    private static OpenMeteoForecastResponse buildForecastResponseWithGrid(
            Double gridLat, Double gridLng) {
        OpenMeteoForecastResponse response = buildForecastResponse();
        response.setLatitude(gridLat);
        response.setLongitude(gridLng);
        return response;
    }

    private static OpenMeteoForecastResponse buildForecastResponse() {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();

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
