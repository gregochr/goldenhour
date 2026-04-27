package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.ForecastCommandExecutor;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForecastTaskCollector} introduced in v2.12.5 (Pass 3.2.1).
 *
 * <p>The collector encapsulates briefing read, weather pre-fetch, cloud-point
 * pre-fetch, per-task triage, stability gating, and near/far × inland/coastal
 * bucketing — i.e. everything that used to live inline inside
 * {@code ScheduledBatchEvaluationService.doSubmitForecastBatch}. These tests
 * verify the legacy behaviour is preserved byte-for-byte.
 *
 * <p>Concurrency guards and submission orchestration remain in the scheduled
 * service and are tested in {@code ScheduledBatchEvaluationServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class ForecastTaskCollectorTest {

    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDateTime EVENT_TIME = TODAY.atTime(5, 30);
    private static final double MIN_PREFETCH_RATIO = 0.5;

    @Mock
    private LocationService locationService;
    @Mock
    private BriefingService briefingService;
    @Mock
    private BriefingEvaluationService briefingEvaluationService;
    @Mock
    private ForecastService forecastService;
    @Mock
    private ForecastStabilityClassifier stabilityClassifier;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private OpenMeteoService openMeteoService;
    @Mock
    private SolarService solarService;
    @Mock
    private FreshnessResolver freshnessResolver;
    @Mock
    private ForecastCommandExecutor forecastCommandExecutor;

    private ForecastTaskCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ForecastTaskCollector(
                locationService, briefingService, briefingEvaluationService,
                forecastService, stabilityClassifier, modelSelectionService,
                openMeteoService, solarService, freshnessResolver,
                forecastCommandExecutor, MIN_PREFETCH_RATIO);
        // Default freshness threshold (matches UNSETTLED-equivalent default in legacy code)
        lenient().when(freshnessResolver.maxAgeFor(any())).thenReturn(Duration.ofHours(6));
    }

    // ── Collection short-circuits ─────────────────────────────────────────────

    @Test
    @DisplayName("collectScheduledBatches: no briefing → empty result, no work attempted")
    void collectScheduledBatches_noBriefing_returnsEmpty() {
        when(briefingService.getCachedBriefing()).thenReturn(null);

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.isEmpty()).isTrue();
        verifyNoInteractions(forecastService, openMeteoService);
    }

    @Test
    @DisplayName("collectScheduledBatches: all STANDDOWN slots → empty result, no prefetch")
    void collectScheduledBatches_allStanddown_returnsEmpty() {
        when(briefingService.getCachedBriefing())
                .thenReturn(buildBriefingForVerdict(TODAY, Verdict.STANDDOWN));
        stubModels();

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.isEmpty()).isTrue();
        verifyNoInteractions(openMeteoService, forecastService);
    }

    @Test
    @DisplayName("collectScheduledBatches: past dates only → empty result, no prefetch")
    void collectScheduledBatches_pastDatesOnly_returnsEmpty() {
        when(briefingService.getCachedBriefing())
                .thenReturn(buildBriefingForVerdict(TODAY.minusDays(1), Verdict.GO));
        stubModels();

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.isEmpty()).isTrue();
        verifyNoInteractions(openMeteoService, forecastService);
    }

    @Test
    @DisplayName("collectScheduledBatches: cached region skipped → no slots collected")
    void collectScheduledBatches_cachedRegion_skipsRegion() {
        when(briefingService.getCachedBriefing())
                .thenReturn(buildBriefingForVerdict(TODAY, Verdict.GO));
        stubModels();
        when(briefingEvaluationService.hasFreshEvaluation(any(), any())).thenReturn(true);

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.isEmpty()).isTrue();
        verifyNoInteractions(openMeteoService, forecastService);
    }

    @Test
    @DisplayName("collectScheduledBatches: unknown location → skipped, not in result")
    void collectScheduledBatches_unknownLocation_skipsTask() {
        when(briefingService.getCachedBriefing())
                .thenReturn(buildBriefingForVerdict(TODAY, Verdict.GO));
        stubModels();
        // No location returned by locationService.findAllEnabled() — slot will be UNKNOWN_LOCATION
        when(locationService.findAllEnabled()).thenReturn(List.of());

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.isEmpty()).isTrue();
        verifyNoInteractions(openMeteoService, forecastService);
    }

    // ── Prefetch gate ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("collectScheduledBatches: zero prefetch → empty result, no triage attempted")
    void collectScheduledBatches_prefetchYieldsZero_returnsEmpty() {
        stubGoBriefingWithLocation("Durham UK");
        stubModels();
        when(openMeteoService.prefetchWeatherBatchResilient(any())).thenReturn(Map.of());

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.isEmpty()).isTrue();
        verifyNoInteractions(forecastService);
    }

    @Test
    @DisplayName("collectScheduledBatches: prefetch ratio below threshold → empty, no triage")
    void collectScheduledBatches_prefetchBelowRatio_returnsEmpty() {
        // Two unique locations seeded; only one comes back from prefetch (ratio 0.5 → equal to
        // threshold, NOT strictly below; we lower threshold by adding a third location)
        LocationEntity loc1 = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        LocationEntity loc2 = buildInlandLocation("Newcastle", 54.9783, -1.6178);
        LocationEntity loc3 = buildInlandLocation("Whitby", 54.4858, -0.6206);

        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY, Verdict.GO,
                loc1.getName(), loc2.getName(), loc3.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        stubModels();
        when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2, loc3));

        // Only one location's coords come back — 1/3 = 0.33, below 0.5 threshold
        String key1 = OpenMeteoService.coordKey(loc1.getLat(), loc1.getLon());
        when(openMeteoService.prefetchWeatherBatchResilient(any()))
                .thenReturn(Map.of(key1, dummyExtraction()));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.isEmpty()).isTrue();
        verifyNoInteractions(forecastService);
    }

    @Test
    @DisplayName("collectScheduledBatches: partial-but-above-ratio prefetch → continues to triage")
    void collectScheduledBatches_partialPrefetchAboveRatio_continuesToTriage() {
        LocationEntity loc1 = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        LocationEntity loc2 = buildInlandLocation("Newcastle", 54.9783, -1.6178);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY, Verdict.GO,
                loc1.getName(), loc2.getName());

        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        stubModels();
        when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));

        // 1 of 2 locations prefetched — ratio 0.5, equal to threshold → continues
        String key1 = OpenMeteoService.coordKey(loc1.getLat(), loc1.getLon());
        when(openMeteoService.prefetchWeatherBatchResilient(any()))
                .thenReturn(Map.of(key1, dummyExtraction()));
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(triagedPreEval(loc1));

        collector.collectScheduledBatches();

        // fetchWeatherAndTriage IS called → triage proceeded
        verify(forecastService, org.mockito.Mockito.atLeastOnce()).fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
    }

    // ── Triage / stability gates ──────────────────────────────────────────────

    @Test
    @DisplayName("collectScheduledBatches: triaged location excluded from result")
    void collectScheduledBatches_triagedLocation_excluded() {
        LocationEntity loc = stubGoBriefingWithLocation("Durham UK");
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(triagedPreEval(loc));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("collectScheduledBatches: task beyond stability window excluded")
    void collectScheduledBatches_beyondStabilityWindow_excluded() {
        // Location with a grid cell so stability classifier IS consulted
        LocationEntity loc = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        loc.setGridLat(54.7500);
        loc.setGridLng(-1.6250);

        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY.plusDays(3),
                Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);

        ForecastPreEvalResult farPreEval = inlandPreEval(loc, TODAY.plusDays(3), 3);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(farPreEval);
        when(stabilityClassifier.classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), loc.getGridLat(), loc.getGridLng(),
                        ForecastStability.UNSETTLED, "low confidence", 1));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("collectScheduledBatches: per-task exception swallowed, other tasks continue")
    void collectScheduledBatches_perTaskException_swallowed() {
        LocationEntity loc = stubGoBriefingWithLocation("Durham UK");
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.isEmpty()).isTrue();
    }

    // ── Bucketing ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("collectScheduledBatches: T+1 inland → near-term inland bucket")
    void collectScheduledBatches_nearTermInland_bucketed() {
        LocationEntity loc = stubGoBriefingWithLocation(TODAY.plusDays(1), "Durham UK");
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY.plusDays(1), 1));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.nearInland()).hasSize(1);
        assertThat(result.nearCoastal()).isEmpty();
        assertThat(result.farInland()).isEmpty();
        assertThat(result.farCoastal()).isEmpty();
        EvaluationTask.Forecast task = result.nearInland().get(0);
        assertThat(task.location().getName()).isEqualTo("Durham UK");
        assertThat(task.model()).isEqualTo(EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("collectScheduledBatches: T+1 coastal → near-term coastal bucket")
    void collectScheduledBatches_nearTermCoastal_bucketed() {
        LocationEntity loc = stubGoBriefingWithLocation(TODAY.plusDays(1), "Whitby");
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(coastalPreEval(loc, TODAY.plusDays(1), 1));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.nearCoastal()).hasSize(1);
        assertThat(result.nearInland()).isEmpty();
        assertThat(result.farInland()).isEmpty();
        assertThat(result.farCoastal()).isEmpty();
    }

    @Test
    @DisplayName("collectScheduledBatches: T+2 inland with permissive stability → far inland")
    void collectScheduledBatches_farTermInland_bucketed() {
        LocationEntity loc = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        loc.setGridLat(54.7500);
        loc.setGridLng(-1.6250);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY.plusDays(2),
                Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY.plusDays(2), 2));
        when(stabilityClassifier.classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), loc.getGridLat(), loc.getGridLng(),
                        ForecastStability.SETTLED, "stable", 3));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.farInland()).hasSize(1);
        assertThat(result.nearInland()).isEmpty();
        assertThat(result.nearCoastal()).isEmpty();
        assertThat(result.farCoastal()).isEmpty();
        // Far-term tasks use the far-term model
        assertThat(result.farInland().get(0).model()).isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("collectScheduledBatches: T+2 coastal with permissive stability → far coastal")
    void collectScheduledBatches_farTermCoastal_bucketed() {
        LocationEntity loc = buildInlandLocation("Whitby", 54.4858, -0.6206);
        loc.setGridLat(54.5000);
        loc.setGridLng(-0.6250);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY.plusDays(2),
                Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(coastalPreEval(loc, TODAY.plusDays(2), 2));
        when(stabilityClassifier.classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), loc.getGridLat(), loc.getGridLng(),
                        ForecastStability.SETTLED, "stable", 3));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.farCoastal()).hasSize(1);
        assertThat(result.farInland()).isEmpty();
    }

    // ── Region-filtered ───────────────────────────────────────────────────────

    @Test
    @DisplayName("collectRegionFilteredBatches: no briefing → empty")
    void collectRegionFilteredBatches_noBriefing_returnsEmpty() {
        when(briefingService.getCachedBriefing()).thenReturn(null);

        RegionFilteredBatchTasks result = collector.collectRegionFilteredBatches(List.of(7L));

        assertThat(result.isEmpty()).isTrue();
        verifyNoInteractions(openMeteoService, forecastService);
    }

    @Test
    @DisplayName("collectRegionFilteredBatches: filters out non-matching regions")
    void collectRegionFilteredBatches_filtersByRegion() {
        LocationEntity loc = stubGoBriefingWithLocation("Durham UK");
        // Region id is 1L (set in buildInlandLocation), passing regionIds=[99L] filters it out
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        RegionFilteredBatchTasks result = collector.collectRegionFilteredBatches(List.of(99L));

        assertThat(result.isEmpty()).isTrue();
        verifyNoInteractions(openMeteoService, forecastService);
    }

    @Test
    @DisplayName("collectRegionFilteredBatches: matching region uses near-term model for all tasks")
    void collectRegionFilteredBatches_matchingRegion_usesNearTermModel() {
        LocationEntity loc = stubGoBriefingWithLocation(TODAY, "Durham UK");
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY, 0));

        // Region id matches (loc has region id 1L, see buildInlandLocation)
        RegionFilteredBatchTasks result = collector.collectRegionFilteredBatches(List.of(1L));

        assertThat(result.inland()).hasSize(1);
        assertThat(result.coastal()).isEmpty();
        // Region path uses near-term model — even far-term tasks (not asserted here;
        // far-term in this path is exercised separately).
        assertThat(result.inland().get(0).model()).isEqualTo(EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("collectRegionFilteredBatches: zero prefetch → empty (no ratio threshold)")
    void collectRegionFilteredBatches_zeroPrefetch_returnsEmpty() {
        LocationEntity loc = stubGoBriefingWithLocation("Durham UK");
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        when(openMeteoService.prefetchWeatherBatchResilient(any())).thenReturn(Map.of());

        RegionFilteredBatchTasks result = collector.collectRegionFilteredBatches(null);

        assertThat(result.isEmpty()).isTrue();
        verifyNoInteractions(forecastService);
    }

    @Test
    @DisplayName("collectRegionFilteredBatches: null/empty regionIds → all regions included")
    void collectRegionFilteredBatches_nullRegionIds_allIncluded() {
        LocationEntity loc = stubGoBriefingWithLocation("Durham UK");
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY, 0));

        RegionFilteredBatchTasks result = collector.collectRegionFilteredBatches(null);

        assertThat(result.inland()).hasSize(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubModels() {
        lenient().when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        lenient().when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
    }

    /**
     * Stubs a single-slot GO briefing for TODAY pointing at a single inland location,
     * registers that location with locationService, and returns it.
     */
    private LocationEntity stubGoBriefingWithLocation(String name) {
        return stubGoBriefingWithLocation(TODAY, name);
    }

    private LocationEntity stubGoBriefingWithLocation(LocalDate date, String name) {
        LocationEntity loc = buildInlandLocation(name, 54.7753, -1.5849);
        DailyBriefingResponse briefing = buildBriefingWithSlots(date, Verdict.GO, name);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        return loc;
    }

    private void stubPrefetchSuccess(LocationEntity loc) {
        String key = OpenMeteoService.coordKey(loc.getLat(), loc.getLon());
        when(openMeteoService.prefetchWeatherBatchResilient(any()))
                .thenReturn(Map.of(key, dummyExtraction()));
    }

    private WeatherExtractionResult dummyExtraction() {
        return new WeatherExtractionResult(
                null, new OpenMeteoForecastResponse(), new OpenMeteoAirQualityResponse());
    }

    private DailyBriefingResponse buildBriefingForVerdict(LocalDate date, Verdict verdict) {
        return buildBriefingWithSlots(date, verdict, "Durham UK");
    }

    private DailyBriefingResponse buildBriefingWithSlots(LocalDate date,
            Verdict verdict, String... locationNames) {
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);
        java.util.List<BriefingSlot> slots = new java.util.ArrayList<>();
        for (String name : locationNames) {
            slots.add(new BriefingSlot(name,
                    date.atTime(5, 30),
                    verdict, weather, BriefingSlot.TideInfo.NONE, List.of(), null));
        }
        BriefingRegion region = new BriefingRegion(
                "North East", verdict, "Summary", List.of(), slots,
                null, null, null, null, null, null);
        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        BriefingDay day = new BriefingDay(date, List.of(eventSummary));
        return new DailyBriefingResponse(null, null, List.of(day), null, null, null,
                false, false, 0, null, List.of(), List.of());
    }

    private LocationEntity buildInlandLocation(String name, double lat, double lon) {
        LocationEntity location = new LocationEntity();
        location.setId(42L);
        location.setName(name);
        location.setLat(lat);
        location.setLon(lon);
        RegionEntity region = new RegionEntity();
        region.setId(1L);
        region.setName("North East");
        location.setRegion(region);
        location.setTideType(Set.of());
        return location;
    }

    private ForecastPreEvalResult inlandPreEval(LocationEntity loc, LocalDate date,
            int daysAhead) {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName(loc.getName())
                .solarEventTime(EVENT_TIME)
                .targetType(TargetType.SUNRISE)
                .build();
        // Non-null forecastResponse — required so getStabilityWindowDays() consults
        // the classifier instead of short-circuiting to 1 day.
        return new ForecastPreEvalResult(false, null, null, data, loc, date,
                TargetType.SUNRISE, EVENT_TIME, 60, daysAhead,
                EvaluationModel.HAIKU, Set.of(), "k", new OpenMeteoForecastResponse());
    }

    private ForecastPreEvalResult coastalPreEval(LocationEntity loc, LocalDate date,
            int daysAhead) {
        TideSnapshot tide = new TideSnapshot(
                TideState.HIGH, EVENT_TIME.plusHours(2), BigDecimal.valueOf(5.5),
                EVENT_TIME.plusHours(8), BigDecimal.valueOf(0.5), false,
                EVENT_TIME.plusHours(2), EVENT_TIME.plusHours(8),
                null, null, null, null);
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName(loc.getName())
                .solarEventTime(EVENT_TIME)
                .targetType(TargetType.SUNRISE)
                .tide(tide)
                .build();
        return new ForecastPreEvalResult(false, null, null, data, loc, date,
                TargetType.SUNRISE, EVENT_TIME, 60, daysAhead,
                EvaluationModel.HAIKU, Set.of(), "k", new OpenMeteoForecastResponse());
    }

    private ForecastPreEvalResult triagedPreEval(LocationEntity loc) {
        return new ForecastPreEvalResult(true, "cloud", null, null, loc, TODAY,
                TargetType.SUNRISE, EVENT_TIME, 60, 0,
                EvaluationModel.HAIKU, Set.of(), "k", null);
    }
}
