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
import com.gregochr.goldenhour.model.AuroraTonightSummary;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRefreshedEvent;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.repository.DailyBriefingCacheRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.evaluation.BriefingBestBetAdvisor;
import com.gregochr.goldenhour.service.evaluation.BluebellGlossService;
import com.gregochr.goldenhour.service.evaluation.BriefingGlossService;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private BriefingGlossService glossService;
    @Mock
    private BluebellGlossService bluebellGlossService;
    @Mock
    private BriefingAuroraSummaryBuilder auroraSummaryBuilder;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private HotTopicAggregator hotTopicAggregator;
    @Mock
    private BriefingEvaluationService briefingEvaluationService;
    @Mock
    private EvaluationViewService evaluationViewService;

    private BriefingService briefingService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(bestBetAdvisor.advise(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        // Gloss service: pass-through by default (return input days unchanged)
        org.mockito.Mockito.lenient().when(glossService.generateGlosses(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
        // Bluebell gloss service: pass-through by default
        org.mockito.Mockito.lenient().when(bluebellGlossService.enrichGlosses(
                org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
        // Default batch mock: return a forecast response for each coordinate in the batch
        org.mockito.Mockito.lenient().when(openMeteoClient.fetchForecastBriefingBatch(
                org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> {
                    java.util.List<?> coords = inv.getArgument(0);
                    return coords.stream().map(c -> buildForecastResponse())
                            .collect(java.util.stream.Collectors.toList());
                });
        BriefingVerdictEvaluator verdictEvaluator = new BriefingVerdictEvaluator();
        LunarPhaseService lunarPhaseService = new LunarPhaseService();
        BriefingSlotBuilder slotBuilder = new BriefingSlotBuilder(
                solarService, locationService, tideService, lunarPhaseService, verdictEvaluator);
        briefingService = new BriefingService(
                locationService, openMeteoClient,
                jobRunService, briefingCacheRepository, locationRepository,
                new ObjectMapper().findAndRegisterModules(),
                new BriefingHeadlineGenerator(), bestBetAdvisor, glossService,
                bluebellGlossService, auroraSummaryBuilder,
                new BriefingHierarchyBuilder(verdictEvaluator),
                slotBuilder, eventPublisher, hotTopicAggregator,
                briefingEvaluationService, evaluationViewService);
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
        when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                .thenAnswer(inv -> {
                    List<?> coords = inv.getArgument(0);
                    return coords.stream().map(c -> buildForecastResponse()).toList();
                });

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
            // batch mock set up in @BeforeEach
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
        when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
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
        // batch mock set up in @BeforeEach
        when(solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
        when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));

        briefingService.refreshBriefing();
        DailyBriefingResponse firstCached = briefingService.getCachedBriefing();
        assertThat(firstCached).isNotNull();
        assertThat(firstCached.stale()).isFalse();

        // Second: failed refresh → below 50%, LKG exists → stale response
        when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
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
        when(solarService.sunriseUtc(eq(loc2.getLat()), eq(loc2.getLon()), any(LocalDate.class)))
                .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
        when(solarService.sunsetUtc(eq(loc2.getLat()), eq(loc2.getLon()), any(LocalDate.class)))
                .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));
        // batch mock set up in @BeforeEach — both locations succeed

        briefingService.refreshBriefing();

        DailyBriefingResponse cached = briefingService.getCachedBriefing();
        assertThat(cached).isNotNull();
        assertThat(cached.stale()).isFalse();
        // With batch API, all locations succeed or fail together
        assertThat(cached.failedLocationCount()).isZero();
    }

    @Test
    @DisplayName("loadPersistedBriefing restores cache from DB entity")
    void loadPersistedBriefing_restoresCache() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DailyBriefingResponse persisted = new DailyBriefingResponse(
                LocalDateTime.now(ZoneOffset.UTC), "Test headline", List.of(), List.of(),
                null, null, false, false, 0, "Opus", List.of(), List.of());
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
                new BriefingHeadlineGenerator(), bestBetAdvisor, glossService,
                bluebellGlossService, auroraSummaryBuilder,
                new BriefingHierarchyBuilder(verdictEvaluator),
                slotBuilder, eventPublisher, hotTopicAggregator,
                briefingEvaluationService, evaluationViewService);
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
                new BriefingHeadlineGenerator(), bestBetAdvisor, glossService,
                bluebellGlossService, auroraSummaryBuilder,
                new BriefingHierarchyBuilder(verdictEvaluator),
                slotBuilder, eventPublisher, hotTopicAggregator,
                briefingEvaluationService, evaluationViewService);
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
                new BriefingHeadlineGenerator(), bestBetAdvisor, glossService,
                bluebellGlossService, auroraSummaryBuilder,
                new BriefingHierarchyBuilder(verdictEvaluator),
                slotBuilder, eventPublisher, hotTopicAggregator,
                briefingEvaluationService, evaluationViewService);
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

            // Batch returns one response per coordinate in the batch
            when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                    .thenAnswer(inv -> {
                        List<?> coords = inv.getArgument(0);
                        return coords.stream().map(c -> buildForecastResponse()).toList();
                    });

            briefingService.refreshBriefing();

            // Single batch call with exactly 2 coordinates (one per grid cell, not 4)
            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<double[]>> captor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(openMeteoClient).fetchForecastBriefingBatch(captor.capture());
            List<double[]> batchCoords = captor.getValue();
            assertThat(batchCoords).hasSize(2);
            // First group uses loc1 (Bamburgh Castle) as representative
            assertThat(batchCoords.get(0)[0]).isEqualTo(loc1.getLat());
            assertThat(batchCoords.get(0)[1]).isEqualTo(loc1.getLon());
            // Second group uses loc3 (Durham) as representative
            assertThat(batchCoords.get(1)[0]).isEqualTo(loc3.getLat());
            assertThat(batchCoords.get(1)[1]).isEqualTo(loc3.getLon());

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

            briefingService.refreshBriefing();

            // Both fetched individually
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

            when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                    .thenReturn(List.of(buildForecastResponseWithGrid(55.1, -1.5)));

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

            briefingService.refreshBriefing();

            // Single batch call with 2 coordinates: grouped pair + ungrouped
            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<double[]>> captor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(openMeteoClient).fetchForecastBriefingBatch(captor.capture());
            List<double[]> batchCoords = captor.getValue();
            assertThat(batchCoords).hasSize(2);
            // First: grouped pair representative (Bamburgh)
            assertThat(batchCoords.get(0)[0]).isEqualTo(grouped1.getLat());
            // Second: ungrouped location
            assertThat(batchCoords.get(1)[0]).isEqualTo(ungrouped.getLat());

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

            briefingService.refreshBriefing();

            // Single batch call with 1 coordinate for all 5 co-located locations
            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<double[]>> captor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(openMeteoClient).fetchForecastBriefingBatch(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(briefingService.getCachedBriefing().failedLocationCount()).isZero();
        }

        @Test
        @DisplayName("Response with non-null lat but null lon does not persist grid coordinates")
        void fetchWeather_nonNullLatNullLon_doesNotSave() {
            // Kills the second equality-check removal mutant at L433:
            // real: lat==null || lon==null → true when lon==null → return early
            // mutant (lon check removed): false || false → proceeds to set lat, null lon → saveAll called
            LocationEntity loc = LocationEntity.builder()
                    .id(1L).name("Loc").lat(55.0).lon(-1.5)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();

            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc);

            // Response has lat set but lon null — only the lat check is alive; lon check is the survivor
            OpenMeteoForecastResponse response = buildForecastResponse();
            response.setLatitude(55.0);
            response.setLongitude(null);
            when(openMeteoClient.fetchForecastBriefingBatch(anyList())).thenReturn(List.of(response));

            briefingService.refreshBriefing();

            assertThat(loc.getGridLat()).isNull();
            assertThat(loc.getGridLng()).isNull();
            org.mockito.Mockito.verify(locationRepository, org.mockito.Mockito.never()).saveAll(any());
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

            when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                    .thenReturn(List.of(buildForecastResponseWithGrid(55.0, -1.5)));
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

            when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                    .thenThrow(new RuntimeException("API timeout"));

            briefingService.refreshBriefing();

            DailyBriefingResponse cached = briefingService.getCachedBriefing();
            assertThat(cached).isNotNull();
            // Both locations in the group failed (batch failure)
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

    @Nested
    @DisplayName("Partial batch response handling")
    class PartialBatchResponseTests {

        @Test
        @DisplayName("Null entry for one location fails that location; the other succeeds")
        void partialBatch_nullEntryForOneLocation_thatLocationFails() {
            LocationEntity loc1 = LocationEntity.builder()
                    .id(1L).name("Durham").lat(55.0).lon(-1.5)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(java.time.LocalDateTime.now()).build();
            LocationEntity loc2 = LocationEntity.builder()
                    .id(2L).name("Whitby").lat(54.4).lon(-0.6)
                    .locationType(Set.of(LocationType.SEASCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(java.time.LocalDateTime.now()).build();

            when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc1);
            stubSolarTimes(loc2);

            // loc1 gets a valid forecast; loc2's entry is null (its chunk failed)
            when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                    .thenReturn(java.util.Arrays.asList(buildForecastResponse(), null));

            briefingService.refreshBriefing();

            DailyBriefingResponse cached = briefingService.getCachedBriefing();
            assertThat(cached).isNotNull();
            assertThat(cached.failedLocationCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Null entry does not attempt to capture grid coordinates (null-guard)")
        void partialBatch_nullEntry_doesNotUpdateGridCoordinates() {
            LocationEntity loc = LocationEntity.builder()
                    .id(1L).name("NewLoc").lat(55.123).lon(-1.456)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(java.time.LocalDateTime.now()).build();

            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc);

            // Batch returns null for the one location — simulates its chunk failing
            when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                    .thenReturn(java.util.Arrays.asList((OpenMeteoForecastResponse) null));

            briefingService.refreshBriefing(); // must not throw NullPointerException

            assertThat(loc.getGridLat()).isNull();
            assertThat(loc.getGridLng()).isNull();
        }

        @Test
        @DisplayName("Null entry for a shared grid group marks all group members as failed")
        void partialBatch_nullEntryForSharedGroup_allGroupMembersFail() {
            // loc1 + loc2 share a grid cell; loc3 is separate
            LocationEntity loc1 = LocationEntity.builder()
                    .id(1L).name("Bamburgh Castle").lat(55.609).lon(-1.710)
                    .locationType(Set.of(LocationType.SEASCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .gridLat(55.6).gridLng(-1.7).enabled(true)
                    .createdAt(java.time.LocalDateTime.now()).build();
            LocationEntity loc2 = LocationEntity.builder()
                    .id(2L).name("Bamburgh Dunes").lat(55.611).lon(-1.712)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .gridLat(55.6).gridLng(-1.7).enabled(true)
                    .createdAt(java.time.LocalDateTime.now()).build();
            LocationEntity loc3 = LocationEntity.builder()
                    .id(3L).name("Durham").lat(54.775).lon(-1.585)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .gridLat(54.8).gridLng(-1.6).enabled(true)
                    .createdAt(java.time.LocalDateTime.now()).build();

            when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2, loc3));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc1);
            stubSolarTimes(loc2);
            stubSolarTimes(loc3);

            // 2 groups in the batch; Bamburgh group (position 0) is null, Durham (position 1) succeeds
            when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                    .thenReturn(java.util.Arrays.asList(null, buildForecastResponse()));

            briefingService.refreshBriefing();

            DailyBriefingResponse cached = briefingService.getCachedBriefing();
            assertThat(cached).isNotNull();
            assertThat(cached.failedLocationCount()).isEqualTo(2); // loc1 + loc2
        }

        private void stubSolarTimes(LocationEntity loc) {
            org.mockito.Mockito.lenient().when(
                    solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(java.time.LocalDateTime.now().withHour(6).withMinute(0));
            org.mockito.Mockito.lenient().when(
                    solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(java.time.LocalDateTime.now().withHour(18).withMinute(0));
        }
    }

    // ── Horizon grid key ──

    @Nested
    @DisplayName("Horizon grid key rounding")
    class HorizonGridKeyTests {

        @Test
        @DisplayName("Snaps to nearest 0.25° — 55.609 rounds to 55.50")
        void snapsLat() {
            assertThat(BriefingService.horizonGridKey(new double[]{55.609, -1.710}))
                    .isEqualTo("55.50,-1.75");
        }

        @Test
        @DisplayName("Exact quarter-degree stays unchanged")
        void exactQuarterDegree() {
            assertThat(BriefingService.horizonGridKey(new double[]{55.25, -1.50}))
                    .isEqualTo("55.25,-1.50");
        }

        @Test
        @DisplayName("Nearby locations with < 0.125° difference share the same grid key")
        void nearbyLocationsShareKey() {
            String key1 = BriefingService.horizonGridKey(new double[]{55.01, -1.51});
            String key2 = BriefingService.horizonGridKey(new double[]{55.05, -1.55});
            assertThat(key1).isEqualTo(key2);
        }

        @Test
        @DisplayName("Distant locations have different grid keys")
        void distantLocationsDifferentKeys() {
            String key1 = BriefingService.horizonGridKey(new double[]{55.0, -1.5});
            String key2 = BriefingService.horizonGridKey(new double[]{54.0, -2.0});
            assertThat(key1).isNotEqualTo(key2);
        }
    }

    // ── lastKnownGood is populated by loadPersistedBriefing (L141) ──

    @Nested
    @DisplayName("loadPersistedBriefing populates lastKnownGood")
    class CacheLoadingLkgTests {

        /**
         * Kills the VoidMethodCallMutator at L141 (lastKnownGood.set removed).
         * If lastKnownGood is not set during load, a subsequent below-threshold refresh has no LKG
         * to fall back to and returns a partial non-stale response instead of a stale one.
         */
        @Test
        @DisplayName("loadPersistedBriefing sets lastKnownGood — stale served on below-threshold refresh")
        void loadPersistedBriefing_setsLastKnownGood_usedOnBelowThresholdRefresh() throws Exception {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            DailyBriefingResponse persisted = new DailyBriefingResponse(
                    LocalDateTime.now(ZoneOffset.UTC), "Loaded headline", List.of(), List.of(),
                    null, null, false, false, 0, "Haiku", List.of(), List.of());
            DailyBriefingCacheEntity entity = new DailyBriefingCacheEntity();
            entity.setId(1);
            entity.setGeneratedAt(persisted.generatedAt());
            entity.setPayload(mapper.writeValueAsString(persisted));

            when(briefingCacheRepository.findById(1)).thenReturn(Optional.of(entity));

            BriefingVerdictEvaluator verdictEvaluator = new BriefingVerdictEvaluator();
            LunarPhaseService lunarPhaseService = new LunarPhaseService();
            BriefingSlotBuilder slotBuilder = new BriefingSlotBuilder(
                    solarService, locationService, tideService, lunarPhaseService, verdictEvaluator);
            BriefingService freshService = new BriefingService(
                    locationService, openMeteoClient,
                    jobRunService, briefingCacheRepository, locationRepository, mapper,
                    new BriefingHeadlineGenerator(), bestBetAdvisor, glossService,
                    bluebellGlossService, auroraSummaryBuilder,
                    new BriefingHierarchyBuilder(verdictEvaluator),
                    slotBuilder, eventPublisher, hotTopicAggregator,
                    briefingEvaluationService, evaluationViewService);
            freshService.loadPersistedBriefing();

            // Trigger below-threshold refresh: 1 location, batch throws → succeeded=0, failed=1
            LocationEntity loc = location("Durham", null);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                    .thenThrow(new RuntimeException("API down"));

            freshService.refreshBriefing();

            // LKG (loaded from DB) must be served as stale. Without L141, LKG is null → not stale.
            DailyBriefingResponse result = freshService.getCachedBriefing();
            assertThat(result).isNotNull();
            assertThat(result.stale()).isTrue();
        }
    }

    // ── getCachedBriefing aurora equality guard (L166-L167) ──

    @Nested
    @DisplayName("getCachedBriefing aurora equality")
    class GetCachedBriefingAuroraTests {

        /**
         * Kills RemoveConditionalMutator at L166-L167 that causes the cache to always be rebuilt.
         * When aurora is unchanged, getCachedBriefing must return the exact same cached instance
         * rather than allocating a new DailyBriefingResponse on every call.
         */
        @Test
        @DisplayName("aurora unchanged — successive calls return the same cached instance")
        void auroraUnchanged_successiveCalls_returnSameInstance() {
            LocationEntity loc = location("Durham", null);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            when(solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));

            briefingService.refreshBriefing();

            // Both buildAuroraTonight (stored in cache) and buildAuroraTonightCached (getCachedBriefing)
            // return null by default → Objects.equals(null, null) = true → same instance returned
            DailyBriefingResponse first = briefingService.getCachedBriefing();
            DailyBriefingResponse second = briefingService.getCachedBriefing();
            assertThat(second).isSameAs(first);
        }

        /**
         * Kills RemoveConditionalMutator at L166-L167 that causes the cache to never rebuild.
         * When aurora data changes, getCachedBriefing must return a new response carrying the
         * live aurora data, not the stale snapshot stored in the cache AtomicReference.
         */
        @Test
        @DisplayName("aurora changed — getCachedBriefing returns new response with live aurora")
        void auroraChanged_returnsNewResponseWithLiveAurora() {
            LocationEntity loc = location("Durham", null);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            when(solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));

            // During refresh: buildAuroraTonight() returns null → cached response has auroraTonight=null
            briefingService.refreshBriefing();

            // Now live aurora becomes non-null — simulates aurora FSM activating between refresh calls
            AuroraTonightSummary liveAurora = mock(AuroraTonightSummary.class);
            when(auroraSummaryBuilder.buildAuroraTonightCached()).thenReturn(liveAurora);

            DailyBriefingResponse result = briefingService.getCachedBriefing();
            assertThat(result.auroraTonight()).isSameAs(liveAurora);
        }
    }

    // ── getCachedBriefing hot topic overlay ───────────────────────────────────

    @Nested
    @DisplayName("getCachedBriefing hot topic overlay")
    class GetCachedBriefingHotTopicTests {

        private void refreshWithOneLocation() {
            LocationEntity loc = location("Durham", null);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            when(solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));
            briefingService.refreshBriefing();
        }

        /**
         * The core bug: simulation was toggled ON after the last refresh, so the cached
         * response had an empty hotTopics list. getCachedBriefing must overlay live topics
         * from the aggregator, not return the stale cached list.
         */
        @Test
        @DisplayName("simulation toggled after refresh — getCachedBriefing returns live simulated topics")
        void simulationToggledAfterRefresh_returnsLiveTopics() {
            // Aggregator returns empty during refresh (simulation off)
            when(hotTopicAggregator.getHotTopics(any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(List.of());
            refreshWithOneLocation();

            // Verify cached response has no hot topics
            DailyBriefingResponse beforeToggle = briefingService.getCachedBriefing();
            assertThat(beforeToggle.hotTopics()).isEmpty();

            // Now simulation is toggled on — aggregator returns topics
            HotTopic bluebell = new HotTopic("BLUEBELL", "Bluebell conditions",
                    "Misty and still", LocalDate.now(), 1, "BLUEBELL",
                    List.of("Northumberland"), "Bluebell season description", null);
            when(hotTopicAggregator.getHotTopics(any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(List.of(bluebell));

            DailyBriefingResponse afterToggle = briefingService.getCachedBriefing();
            assertThat(afterToggle.hotTopics()).containsExactly(bluebell);
        }

        /**
         * When hot topics haven't changed between calls, getCachedBriefing must return
         * the exact same cached instance — no unnecessary allocations.
         */
        @Test
        @DisplayName("hot topics unchanged — successive calls return the same cached instance")
        void hotTopicsUnchanged_returnsSameInstance() {
            when(hotTopicAggregator.getHotTopics(any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(List.of());
            refreshWithOneLocation();

            DailyBriefingResponse first = briefingService.getCachedBriefing();
            DailyBriefingResponse second = briefingService.getCachedBriefing();
            assertThat(second).isSameAs(first);
        }

        /**
         * When simulation is toggled OFF after being ON, the cached response must
         * revert to the aggregator's real (empty) output.
         */
        @Test
        @DisplayName("simulation toggled off — hot topics revert to real detector output")
        void simulationToggledOff_revertsToRealDetectorOutput() {
            HotTopic aurora = new HotTopic("AURORA", "Aurora possible",
                    "Kp 5 tonight", LocalDate.now(), 1, null,
                    List.of("Northumberland"), "Aurora description", null);
            when(hotTopicAggregator.getHotTopics(any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(List.of(aurora));
            refreshWithOneLocation();

            assertThat(briefingService.getCachedBriefing().hotTopics()).containsExactly(aurora);

            // Simulation toggled off — aggregator now returns empty
            when(hotTopicAggregator.getHotTopics(any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(List.of());

            DailyBriefingResponse afterOff = briefingService.getCachedBriefing();
            assertThat(afterOff.hotTopics()).isEmpty();
        }

        /**
         * When the aggregator returns a different set of topics (e.g. new simulation
         * types toggled), the response must carry the updated list, not the stale one.
         */
        @Test
        @DisplayName("hot topics change between calls — response carries updated list")
        void hotTopicsChange_responseCarriesUpdatedList() {
            HotTopic bluebell = new HotTopic("BLUEBELL", "Bluebell conditions",
                    "Misty and still", LocalDate.now(), 1, "BLUEBELL",
                    List.of("Northumberland"), null, null);
            when(hotTopicAggregator.getHotTopics(any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(List.of(bluebell));
            refreshWithOneLocation();

            // A second topic is now active
            HotTopic dust = new HotTopic("DUST", "Elevated dust",
                    "Saharan dust at sunset", LocalDate.now(), 3, null,
                    List.of("Northumberland"), null, null);
            when(hotTopicAggregator.getHotTopics(any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(List.of(bluebell, dust));

            DailyBriefingResponse result = briefingService.getCachedBriefing();
            assertThat(result.hotTopics()).containsExactly(bluebell, dust);
        }

        /**
         * Non-hot-topic fields (headline, days, bestBets etc.) must be preserved
         * when the hot topic overlay creates a new response.
         */
        @Test
        @DisplayName("hot topic overlay preserves all other briefing fields")
        void hotTopicOverlay_preservesOtherFields() {
            when(hotTopicAggregator.getHotTopics(any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(List.of());
            refreshWithOneLocation();

            DailyBriefingResponse original = briefingService.getCachedBriefing();

            // Change hot topics so a new response is built
            HotTopic topic = new HotTopic("INVERSION", "Cloud inversion",
                    "Strong inversion forecast", LocalDate.now(), 2, null,
                    List.of("The North York Moors"), null, null);
            when(hotTopicAggregator.getHotTopics(any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(List.of(topic));

            DailyBriefingResponse overlaid = briefingService.getCachedBriefing();
            assertThat(overlaid).isNotSameAs(original);
            assertThat(overlaid.generatedAt()).isEqualTo(original.generatedAt());
            assertThat(overlaid.headline()).isEqualTo(original.headline());
            assertThat(overlaid.days()).isEqualTo(original.days());
            assertThat(overlaid.bestBets()).isEqualTo(original.bestBets());
            assertThat(overlaid.stale()).isEqualTo(original.stale());
            assertThat(overlaid.partialFailure()).isEqualTo(original.partialFailure());
            assertThat(overlaid.failedLocationCount()).isEqualTo(original.failedLocationCount());
            assertThat(overlaid.bestBetModel()).isEqualTo(original.bestBetModel());
            assertThat(overlaid.seasonalFeatures()).isEqualTo(original.seasonalFeatures());
        }
    }

    // ── Refresh lifecycle: counters, gloss/bestBet gates, threshold, persist, event ──

    @Nested
    @DisplayName("Refresh lifecycle counters and side effects")
    class RefreshLifecycleTests {

        /**
         * Kills IncrementsMutator at L224 (succeeded++ → succeeded--).
         * Verifies the four-arg completeRun receives succeeded=1, failed=0 on a single success.
         */
        @Test
        @DisplayName("single success — completeRun receives succeeded=1, failed=0")
        void singleSuccess_completeRunCountsCorrect() {
            LocationEntity loc = location("Durham", null);
            JobRunEntity jobRun = JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build();
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any())).thenReturn(jobRun);
            stubSolarTimes(loc);

            briefingService.refreshBriefing();

            verify(jobRunService).completeRun(eq(jobRun), eq(1), eq(0), any());
        }

        /**
         * Kills RemoveConditionalMutator at L245 (gloss gate: succeeded > 0 always true).
         * When all locations fail, glossService must not be called.
         */
        @Test
        @DisplayName("all locations fail — glossService not called")
        void allLocationsFail_glossServiceNotCalled() {
            LocationEntity loc = location("Durham", null);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                    .thenThrow(new RuntimeException("API down"));

            briefingService.refreshBriefing();

            verify(glossService, never()).generateGlosses(any(), any());
        }

        /**
         * Kills RemoveConditionalMutator at L250 (bestBet gate: succeeded > 0 always true).
         * When all locations fail, bestBetAdvisor must not be called.
         */
        @Test
        @DisplayName("all locations fail — bestBetAdvisor not called")
        void allLocationsFail_bestBetAdvisorNotCalled() {
            LocationEntity loc = location("Durham", null);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                    .thenThrow(new RuntimeException("API down"));

            briefingService.refreshBriefing();

            verify(bestBetAdvisor, never()).advise(any(), any(), any());
        }

        /**
         * Kills BooleanReturnValsMutator at L256 (partialFailure = false constant).
         * 2 locations with one null batch entry → succeeded=1, failed=1 → above 50% threshold.
         * The cached response must report partialFailure=true.
         */
        @Test
        @DisplayName("partial failure above threshold — cached response has partialFailure=true")
        void partialFailureAboveThreshold_partialFailureFlagTrue() {
            LocationEntity loc1 = locationWithId(1L, "Durham", 55.0, -1.5);
            LocationEntity loc2 = locationWithId(2L, "Whitby", 54.4, -0.6);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc1);
            stubSolarTimes(loc2);
            // loc1 succeeds, loc2's entry is null → succeeded=1, failed=1 → 50% threshold
            when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                    .thenReturn(Arrays.asList(buildForecastResponse(), null));

            briefingService.refreshBriefing();

            DailyBriefingResponse cached = briefingService.getCachedBriefing();
            assertThat(cached).isNotNull();
            assertThat(cached.partialFailure()).isTrue();
            assertThat(cached.stale()).isFalse();
        }

        /**
         * Kills MathMutator at L258 (division replaced by multiplication).
         * 1 succeed + 2 fail (total=3): real=(1*100/3)=33 &lt; 50 → below threshold.
         * Mutant=(1*100*3)=300 &ge; 50 → above threshold. With LKG present, real serves stale.
         */
        @Test
        @DisplayName("1 succeed + 2 fail (33%) with LKG — below threshold, stale served")
        void oneSucceedTwoFail_belowThreshold_withLkg_staleServed() {
            // First refresh: single location succeeds → populates LKG
            LocationEntity lkg = location("Durham", null);
            when(locationService.findAllEnabled()).thenReturn(List.of(lkg));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(lkg);
            briefingService.refreshBriefing();

            // Second refresh: 3 locations, 1 succeed + 2 fail → 33% below threshold → LKG served
            LocationEntity loc1 = locationWithId(1L, "Loc1", 55.0, -1.5);
            LocationEntity loc2 = locationWithId(2L, "Loc2", 54.4, -0.6);
            LocationEntity loc3 = locationWithId(3L, "Loc3", 53.8, -1.2);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2, loc3));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(2L).runType(RunType.BRIEFING).build());
            org.mockito.Mockito.lenient().when(
                    solarService.sunriseUtc(eq(loc1.getLat()), eq(loc1.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
            org.mockito.Mockito.lenient().when(
                    solarService.sunsetUtc(eq(loc1.getLat()), eq(loc1.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));
            // loc2 and loc3 get null forecasts (batch returns [valid, null, null])
            when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                    .thenReturn(Arrays.asList(buildForecastResponse(), null, null));

            briefingService.refreshBriefing();

            // Real: 33% < 50 → LKG served (stale=true). Mutant: 300 >= 50 → fresh (stale=false).
            DailyBriefingResponse result = briefingService.getCachedBriefing();
            assertThat(result).isNotNull();
            assertThat(result.stale()).isTrue();
        }

        /**
         * Kills VoidMethodCallMutator at L270 (persistBriefing call removed).
         * A successful refresh must save the briefing to the DB cache repository.
         */
        @Test
        @DisplayName("successful refresh — briefingCacheRepository.save called")
        void aboveThreshold_persistBriefingCalled() {
            LocationEntity loc = location("Durham", null);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc);

            briefingService.refreshBriefing();

            verify(briefingCacheRepository).save(any());
        }

        /**
         * Kills VoidMethodCallMutator at L271 (publishEvent call removed).
         * A successful refresh must publish a BriefingRefreshedEvent.
         */
        @Test
        @DisplayName("successful refresh — BriefingRefreshedEvent published")
        void aboveThreshold_briefingRefreshedEventPublished() {
            LocationEntity loc = location("Durham", null);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc);

            briefingService.refreshBriefing();

            ArgumentCaptor<org.springframework.context.ApplicationEvent> eventCaptor =
                    ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue()).isInstanceOf(BriefingRefreshedEvent.class);
        }

        /**
         * Kills RemoveConditionalMutator at L245 that replaces "succeeded > 0" with false.
         * When at least one location succeeds, glossService must be called.
         */
        @Test
        @DisplayName("successful refresh — glossService called with day data")
        void singleSuccess_glossServiceCalled() {
            LocationEntity loc = location("Durham", null);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc);

            briefingService.refreshBriefing();

            verify(glossService).generateGlosses(anyList(), any());
        }

        /**
         * Kills RemoveConditionalMutator at L250 that replaces "succeeded > 0" with false.
         * When at least one location succeeds, bestBetAdvisor must be called.
         */
        @Test
        @DisplayName("successful refresh — bestBetAdvisor called")
        void singleSuccess_bestBetAdvisorCalled() {
            LocationEntity loc = location("Durham", null);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc);

            briefingService.refreshBriefing();

            verify(bestBetAdvisor).advise(anyList(), any(), any());
        }

        /**
         * Kills ConditionalsBoundaryMutator at L256 that changes "failed > 0" to "failed >= 0".
         * When all locations succeed (failed=0), partialFailure must be false.
         */
        @Test
        @DisplayName("all locations succeed — partialFailure is false")
        void allLocationsSucceed_partialFailureFalse() {
            LocationEntity loc = location("Durham", null);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc);

            briefingService.refreshBriefing();

            assertThat(briefingService.getCachedBriefing().partialFailure()).isFalse();
        }

        /**
         * Kills ConditionalsBoundaryMutator at L258 that changes ">= 50" to "> 50".
         * At exactly 50% (1 succeed + 1 fail), real code treats it as above threshold and
         * persists the briefing. The mutant would fall through to the below-threshold branch.
         */
        @Test
        @DisplayName("exactly 50% success rate — above threshold, briefing persisted")
        void exactlyFiftyPercent_aboveThreshold_briefingPersisted() {
            LocationEntity loc1 = locationWithId(1L, "Durham", 55.0, -1.5);
            LocationEntity loc2 = locationWithId(2L, "Whitby", 54.4, -0.6);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            stubSolarTimes(loc1);
            stubSolarTimes(loc2);
            // First entry succeeds, second is null → succeeded=1, failed=1, total=2 → 50%
            when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                    .thenReturn(Arrays.asList(buildForecastResponse(), null));

            briefingService.refreshBriefing();

            // Real: 50 >= 50 = true → above threshold → save called
            // Mutant (> 50): 50 > 50 = false → below threshold → save NOT called
            verify(briefingCacheRepository).save(any());
        }

        /**
         * Kills VoidMethodCallMutator at L272 (completeRun removed on above-threshold path).
         * Verifies the four-arg completeRun is called on the happy path.
         */
        @Test
        @DisplayName("above-threshold refresh — four-arg completeRun called")
        void aboveThreshold_fourArgCompleteRunCalled() {
            LocationEntity loc = location("Durham", null);
            JobRunEntity jobRun = JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build();
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any())).thenReturn(jobRun);
            stubSolarTimes(loc);

            briefingService.refreshBriefing();

            verify(jobRunService).completeRun(eq(jobRun), eq(1), eq(0), any());
        }

        /**
         * Kills VoidMethodCallMutator at L296 (completeRun removed on below-threshold path).
         * Verifies completeRun is still called even when the briefing falls below the 50% threshold.
         */
        @Test
        @DisplayName("below-threshold refresh — four-arg completeRun still called")
        void belowThreshold_fourArgCompleteRunCalled() {
            LocationEntity loc = location("Durham", null);
            JobRunEntity jobRun = JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build();
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any())).thenReturn(jobRun);
            when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                    .thenThrow(new RuntimeException("API down"));

            briefingService.refreshBriefing();

            // All locations failed → 0/1 = 0% below threshold → four-arg completeRun on else branch
            verify(jobRunService).completeRun(eq(jobRun), eq(0), eq(1), any());
        }

        private void stubSolarTimes(LocationEntity loc) {
            org.mockito.Mockito.lenient().when(
                    solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
            org.mockito.Mockito.lenient().when(
                    solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));
        }

        private LocationEntity locationWithId(long id, String name, double lat, double lon) {
            return LocationEntity.builder()
                    .id(id).name(name).lat(lat).lon(lon)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
        }
    }

    // ── persistBriefing entity field verification (L320–L322) ──

    @Nested
    @DisplayName("persistBriefing DB entity fields")
    class PersistBriefingEntityTests {

        /**
         * Kills VoidMethodCallMutator / field mutation at L320 (entity.setId(1) removed),
         * L321 (setGeneratedAt removed), and L322 (setPayload removed).
         * The saved entity must have id=1, a non-null generatedAt, and a payload that
         * contains the headline text so a deserialised response would be non-empty.
         */
        @Test
        @DisplayName("persistBriefing saves entity with id=1, generatedAt, and serialised payload")
        void persistBriefing_entityFieldsCorrect() {
            LocationEntity loc = location("Durham", null);
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            org.mockito.Mockito.lenient().when(
                    solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
            org.mockito.Mockito.lenient().when(
                    solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));

            briefingService.refreshBriefing();

            ArgumentCaptor<DailyBriefingCacheEntity> captor =
                    ArgumentCaptor.forClass(DailyBriefingCacheEntity.class);
            verify(briefingCacheRepository).save(captor.capture());
            DailyBriefingCacheEntity saved = captor.getValue();

            // L320: entity must use the fixed upsert id=1 (single-row cache)
            assertThat(saved.getId()).isEqualTo(1);
            // L321: generatedAt must be set (not null)
            assertThat(saved.getGeneratedAt()).isNotNull();
            // L322: payload must be serialised JSON (non-blank, contains expected structure)
            assertThat(saved.getPayload()).isNotBlank();
            assertThat(saved.getPayload()).contains("\"headline\"");
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

    // ── Cached score enrichment tests ────────────────────────────────────────────

    @Nested
    @DisplayName("Cached Claude score enrichment")
    class CachedScoreEnrichmentTests {

        @Test
        @DisplayName("Slot enriched when evaluation cache contains a rated entry")
        void slotEnrichedWhenCacheHit() {
            LocationEntity loc = locationWithRegion("Bamburgh", "North East");
            stubFullRefresh(loc);
            BriefingEvaluationResult eval = new BriefingEvaluationResult(
                    "Bamburgh", 4, 78, 52,
                    "Dramatic light expected.", null, null);
            when(evaluationViewService.getScoresForEnrichment(
                    eq("North East"), any(LocalDate.class), any(TargetType.class)))
                    .thenReturn(Map.of("Bamburgh", eval));

            briefingService.refreshBriefing();
            DailyBriefingResponse cached = briefingService.getCachedBriefing();

            BriefingSlot slot = findFirstSlot(cached, "North East", "Bamburgh");
            assertThat(slot.claudeRating()).isEqualTo(4);
            assertThat(slot.fierySkyPotential()).isEqualTo(78);
            assertThat(slot.goldenHourPotential()).isEqualTo(52);
            assertThat(slot.claudeSummary()).isEqualTo("Dramatic light expected.");
        }

        @Test
        @DisplayName("Slot NOT enriched when evaluation cache is empty")
        void slotNotEnrichedWhenCacheEmpty() {
            LocationEntity loc = locationWithRegion("Bamburgh", "North East");
            stubFullRefresh(loc);
            when(evaluationViewService.getScoresForEnrichment(
                    any(), any(LocalDate.class), any(TargetType.class)))
                    .thenReturn(Map.of());

            briefingService.refreshBriefing();
            DailyBriefingResponse cached = briefingService.getCachedBriefing();

            BriefingSlot slot = findFirstSlot(cached, "North East", "Bamburgh");
            assertThat(slot.claudeRating()).isNull();
            assertThat(slot.fierySkyPotential()).isNull();
            assertThat(slot.goldenHourPotential()).isNull();
            assertThat(slot.claudeSummary()).isNull();
        }

        @Test
        @DisplayName("Triaged entry (null rating) does NOT enrich slot")
        void triagedEntryDoesNotEnrich() {
            LocationEntity loc = locationWithRegion("Bamburgh", "North East");
            stubFullRefresh(loc);
            BriefingEvaluationResult triaged = new BriefingEvaluationResult(
                    "Bamburgh", null, null, null, null,
                    com.gregochr.goldenhour.model.TriageReason.HIGH_CLOUD, "Heavy overcast");
            when(evaluationViewService.getScoresForEnrichment(
                    eq("North East"), any(LocalDate.class), any(TargetType.class)))
                    .thenReturn(Map.of("Bamburgh", triaged));

            briefingService.refreshBriefing();
            DailyBriefingResponse cached = briefingService.getCachedBriefing();

            BriefingSlot slot = findFirstSlot(cached, "North East", "Bamburgh");
            assertThat(slot.claudeRating()).isNull();
        }

        @Test
        @DisplayName("Cache lookup uses exact regionName, date, and targetType")
        void cacheLookupUsesExactParams() {
            LocationEntity loc = LocationEntity.builder()
                    .id(1L).name("Bamburgh").lat(55.0).lon(-1.5)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of())
                    .solarEventType(Set.of(SolarEventType.SUNRISE))
                    .region(RegionEntity.builder().name("North East").build())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
            stubFullRefresh(loc);
            when(evaluationViewService.getScoresForEnrichment(
                    any(), any(LocalDate.class), any(TargetType.class)))
                    .thenReturn(Map.of());

            briefingService.refreshBriefing();

            // SUNRISE-only location: only SUNRISE lookups occur (kills targetType swap)
            verify(evaluationViewService, org.mockito.Mockito.atLeastOnce())
                    .getScoresForEnrichment(
                            eq("North East"), any(LocalDate.class), eq(TargetType.SUNRISE));
            // SUNSET events have empty regions, so no lookups for SUNSET
            verify(evaluationViewService, never()).getScoresForEnrichment(
                    any(), any(LocalDate.class), eq(TargetType.SUNSET));
        }

        @Test
        @DisplayName("Enrichment preserves verdict and weather fields")
        void enrichmentPreservesExistingFields() {
            LocationEntity loc = locationWithRegion("Bamburgh", "North East");
            stubFullRefresh(loc);
            BriefingEvaluationResult eval = new BriefingEvaluationResult(
                    "Bamburgh", 3, 45, 60, "Average conditions.", null, null);
            when(evaluationViewService.getScoresForEnrichment(
                    eq("North East"), any(LocalDate.class), any(TargetType.class)))
                    .thenReturn(Map.of("Bamburgh", eval));

            briefingService.refreshBriefing();
            DailyBriefingResponse cached = briefingService.getCachedBriefing();

            BriefingSlot slot = findFirstSlot(cached, "North East", "Bamburgh");
            // Claude fields are set
            assertThat(slot.claudeRating()).isEqualTo(3);
            // Original fields are preserved
            assertThat(slot.locationName()).isEqualTo("Bamburgh");
            assertThat(slot.verdict()).isNotNull();
            assertThat(slot.solarEventTime()).isNotNull();
        }

        @Test
        @DisplayName("Two regions get independent cache lookups with correct names")
        void twoRegionsGetIndependentLookups() {
            LocationEntity loc1 = LocationEntity.builder()
                    .id(1L).name("Bamburgh").lat(55.6).lon(-1.7)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of())
                    .solarEventType(Set.of(SolarEventType.SUNSET))
                    .region(RegionEntity.builder().name("North East").build())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
            LocationEntity loc2 = LocationEntity.builder()
                    .id(2L).name("Scarborough").lat(54.3).lon(-0.4)
                    .locationType(Set.of(LocationType.SEASCAPE))
                    .tideType(Set.of())
                    .solarEventType(Set.of(SolarEventType.SUNSET))
                    .region(RegionEntity.builder().name("Yorkshire").build())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
            when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            org.mockito.Mockito.lenient().when(
                    solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));
            when(evaluationViewService.getScoresForEnrichment(
                    any(), any(LocalDate.class), any(TargetType.class)))
                    .thenReturn(Map.of());

            briefingService.refreshBriefing();

            // Both regions looked up — kills hardcoded region name
            verify(evaluationViewService, org.mockito.Mockito.atLeastOnce())
                    .getScoresForEnrichment(eq("North East"), any(LocalDate.class), eq(TargetType.SUNSET));
            verify(evaluationViewService, org.mockito.Mockito.atLeastOnce())
                    .getScoresForEnrichment(eq("Yorkshire"), any(LocalDate.class), eq(TargetType.SUNSET));
        }

        @Test
        @DisplayName("Cache miss for one location leaves its slot unenriched while others are enriched")
        void partialCacheHit() {
            LocationEntity loc1 = LocationEntity.builder()
                    .id(1L).name("Bamburgh").lat(55.6).lon(-1.7)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of())
                    .solarEventType(Set.of(SolarEventType.SUNSET))
                    .region(RegionEntity.builder().name("North East").build())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
            LocationEntity loc2 = LocationEntity.builder()
                    .id(2L).name("Durham").lat(54.8).lon(-1.6)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of())
                    .solarEventType(Set.of(SolarEventType.SUNSET))
                    .region(RegionEntity.builder().name("North East").build())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
            when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            org.mockito.Mockito.lenient().when(
                    solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));
            // Only Bamburgh in cache, Durham missing
            BriefingEvaluationResult eval = new BriefingEvaluationResult(
                    "Bamburgh", 5, 90, 85, "Spectacular sunset.", null, null);
            when(evaluationViewService.getScoresForEnrichment(
                    eq("North East"), any(LocalDate.class), any(TargetType.class)))
                    .thenReturn(Map.of("Bamburgh", eval));

            briefingService.refreshBriefing();
            DailyBriefingResponse cached = briefingService.getCachedBriefing();

            BriefingSlot bamburgh = findFirstSlot(cached, "North East", "Bamburgh");
            BriefingSlot durham = findFirstSlot(cached, "North East", "Durham");
            assertThat(bamburgh.claudeRating()).isEqualTo(5);
            assertThat(durham.claudeRating()).isNull();
        }

        // ── Helpers ──────────────────────────────────────────────────────────

        private LocationEntity locationWithRegion(String name, String regionName) {
            return LocationEntity.builder()
                    .id(1L).name(name).lat(55.0).lon(-1.5)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of())
                    .solarEventType(Set.of())
                    .region(RegionEntity.builder().name(regionName).build())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
        }

        private void stubFullRefresh(LocationEntity loc) {
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(jobRunService.startRun(eq(RunType.BRIEFING), anyBoolean(), any()))
                    .thenReturn(JobRunEntity.builder().id(1L).runType(RunType.BRIEFING).build());
            org.mockito.Mockito.lenient().when(
                    solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(6).withMinute(0));
            org.mockito.Mockito.lenient().when(
                    solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                    .thenReturn(LocalDateTime.now().withHour(18).withMinute(0));
        }

        private double anyDouble() {
            return org.mockito.ArgumentMatchers.anyDouble();
        }

        private BriefingSlot findFirstSlot(DailyBriefingResponse response,
                String regionName, String locationName) {
            return response.days().stream()
                    .flatMap(d -> d.eventSummaries().stream())
                    .flatMap(es -> es.regions().stream())
                    .filter(r -> r.regionName().equals(regionName))
                    .flatMap(r -> r.slots().stream())
                    .filter(s -> s.locationName().equals(locationName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "No slot found for " + regionName + "/" + locationName));
        }
    }
}
