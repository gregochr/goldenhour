package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BluebellConditionScore;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CandidateDisposition;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.BriefingVerdictEvaluator;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
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
    private StabilitySnapshotProvider stabilitySnapshotProvider;

    private ForecastTaskCollector collector;

    @BeforeEach
    void setUp() {
        // Force-eval disabled (cap 0) so these legacy-behaviour cases assert the
        // pure Gate 4 stability economics. Force-eval is covered separately in
        // ForecastTaskCollectorForceEvalTest.
        collector = new ForecastTaskCollector(
                locationService, briefingService, briefingEvaluationService,
                forecastService, stabilityClassifier, modelSelectionService,
                openMeteoService, solarService, freshnessResolver,
                stabilitySnapshotProvider, MIN_PREFETCH_RATIO, 0);
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
    @DisplayName("collectScheduledBatches: all TIDE_MISMATCH STANDDOWN slots → empty result")
    void collectScheduledBatches_allTideMismatchStanddown_returnsEmpty() {
        // Tide mismatch is the only hard-constraint STANDDOWN reason that still gates
        // after the Gate 2 redesign — a coastal slot whose tide is wrong is geometrically
        // unphotographable regardless of weather, so Claude is not asked.
        when(briefingService.getCachedBriefing())
                .thenReturn(buildBriefingWithStanddownReason(
                        TODAY, "Durham UK",
                        BriefingVerdictEvaluator.StanddownReason.TIDE_MISMATCH.label()));
        stubModels();

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.isEmpty()).isTrue();
        verifyNoInteractions(openMeteoService, forecastService);
    }

    @Test
    @DisplayName("collectScheduledBatches: weather-STANDDOWN slot is now eligible (Gate 2 redesign)")
    void collectScheduledBatches_weatherStanddown_reachesTriage() {
        // Under the Gate 2 redesign, weather-condition STANDDOWN slots (heavy cloud,
        // rain, clear sky, sun blocked at horizon, etc.) now reach Claude evaluation.
        // This is the central behaviour change of PR 1.
        LocationEntity loc = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        DailyBriefingResponse briefing = buildBriefingWithStanddownReason(
                TODAY, loc.getName(),
                BriefingVerdictEvaluator.StanddownReason.HEAVY_CLOUD.label());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY, 0));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        // The previously-filtered slot now reaches prefetch + triage and ends up in
        // the near-term inland bucket.
        assertThat(result.nearInland()).hasSize(1);
        verify(forecastService).fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("collectScheduledBatches: in-season WOODLAND → bluebell bucket only, no sky bucket")
    void collectScheduledBatches_inSeasonWoodland_bluebellBucketOnly() {
        LocationEntity loc = buildBluebellLocation(
                "Bluebell Wood", 54.5, -3.0, BluebellExposure.WOODLAND);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY, Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(bluebellPreEval(loc, TODAY, 0, BluebellExposure.WOODLAND, false));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.bluebell()).hasSize(1);
        assertThat(result.bluebell().get(0).promptKind())
                .isEqualTo(EvaluationTask.Forecast.PromptKind.BLUEBELL);
        assertThat(result.nearInland()).isEmpty();
        assertThat(result.nearCoastal()).isEmpty();
        assertThat(result.farInland()).isEmpty();
        assertThat(result.farCoastal()).isEmpty();
    }

    @Test
    @DisplayName("collectScheduledBatches: in-season WOODLAND stays in bluebell bucket even when "
            + "the colour triage stood it down")
    void collectScheduledBatches_inSeasonWoodlandTriaged_stillBluebell() {
        LocationEntity loc = buildBluebellLocation(
                "Bluebell Wood", 54.5, -3.0, BluebellExposure.WOODLAND);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY, Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);
        // Colour triage stands it down (overcast) — but woodland is bluebell-only, so it is
        // NOT dropped; the bluebell prompt scores the carpet light itself.
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(bluebellPreEval(loc, TODAY, 0, BluebellExposure.WOODLAND, true));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.bluebell()).hasSize(1);
        assertThat(result.totalSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("collectScheduledBatches: in-season OPEN_FELL → both a sky task and a bluebell task")
    void collectScheduledBatches_inSeasonOpenFell_bothBuckets() {
        LocationEntity loc = buildBluebellLocation(
                "Rannerdale", 54.55, -3.25, BluebellExposure.OPEN_FELL);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY, Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(bluebellPreEval(loc, TODAY, 0, BluebellExposure.OPEN_FELL, false));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        // Sky task in the near-term inland bucket...
        assertThat(result.nearInland()).hasSize(1);
        assertThat(result.nearInland().get(0).promptKind())
                .isEqualTo(EvaluationTask.Forecast.PromptKind.SKY);
        // ...plus a paired bluebell task.
        assertThat(result.bluebell()).hasSize(1);
        assertThat(result.bluebell().get(0).promptKind())
                .isEqualTo(EvaluationTask.Forecast.PromptKind.BLUEBELL);
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
        assertThat(task.model()).isEqualTo(EvaluationModel.SONNET);
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
        // Far-term tasks use the far-term model (BATCH_FAR_TERM → HAIKU in production)
        assertThat(result.farInland().get(0).model()).isEqualTo(EvaluationModel.HAIKU);
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

    @Test
    @DisplayName("collectScheduledBatches: T+2 TRANSITIONAL inland → far inland (Gate 4 unlock)")
    void collectScheduledBatches_t2TransitionalInland_included() {
        // Gate 4: TRANSITIONAL is newly eligible at T+2 (previously gated out by the
        // enum's evaluationWindowDays=1). Verifies the policy change directly.
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
                        ForecastStability.TRANSITIONAL, "frontal approach", 1));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.farInland()).hasSize(1);
        assertThat(result.farInland().get(0).model()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(result.nearInland()).isEmpty();
    }

    @Test
    @DisplayName("collectScheduledBatches: T+3 TRANSITIONAL → excluded (still triage-only at T+3)")
    void collectScheduledBatches_t3Transitional_excluded() {
        // Gate 4: TRANSITIONAL remains gated out at T+3 — only SETTLED reaches T+3.
        LocationEntity loc = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        loc.setGridLat(54.7500);
        loc.setGridLng(-1.6250);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY.plusDays(3),
                Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY.plusDays(3), 3));
        when(stabilityClassifier.classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), loc.getGridLat(), loc.getGridLng(),
                        ForecastStability.TRANSITIONAL, "frontal approach", 1));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.isEmpty()).isTrue();
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

    // ── Stability snapshot write ──────────────────────────────────────────────

    @Test
    @DisplayName("collectScheduledBatches: publishes stability snapshot for cells with grid cells")
    void collectScheduledBatches_publishesStabilitySnapshot() {
        LocationEntity loc = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        loc.setGridLat(54.7500);
        loc.setGridLng(-1.6250);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY.plusDays(1),
                Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY.plusDays(1), 1));
        when(stabilityClassifier.classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), loc.getGridLat(), loc.getGridLng(),
                        ForecastStability.SETTLED, "stable", 3));

        collector.collectScheduledBatches();

        ArgumentCaptor<StabilitySummaryResponse> captor =
                ArgumentCaptor.forClass(StabilitySummaryResponse.class);
        verify(stabilitySnapshotProvider).update(captor.capture());
        StabilitySummaryResponse summary = captor.getValue();
        assertThat(summary.totalGridCells()).isEqualTo(1);
        assertThat(summary.cells()).hasSize(1);
        StabilitySummaryResponse.GridCellDetail cell = summary.cells().get(0);
        assertThat(cell.gridCellKey()).isEqualTo(loc.gridCellKey());
        assertThat(cell.stability()).isEqualTo(ForecastStability.SETTLED);
        assertThat(cell.locationNames()).containsExactly("Durham UK");
        assertThat(summary.countsByStability())
                .containsEntry(ForecastStability.SETTLED, 1L);
    }

    @Test
    @DisplayName("collectScheduledBatches: ephemeral=true → classification drives gating "
            + "but snapshot is NOT published (morning snapshot preserved)")
    void collectScheduledBatches_ephemeralTrue_classifiesButDoesNotPublish() {
        LocationEntity loc = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        loc.setGridLat(54.7500);
        loc.setGridLng(-1.6250);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY.plusDays(1),
                Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY.plusDays(1), 1));
        when(stabilityClassifier.classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), loc.getGridLat(), loc.getGridLng(),
                        ForecastStability.SETTLED, "stable", 3));

        ScheduledBatchTasks result = collector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                true);

        // The classification still happened (the cell was consulted and the
        // near-term task is bucketed), but the authoritative snapshot was left
        // untouched — the whole point of the ephemeral intraday re-classify.
        assertThat(result.nearInland()).hasSize(1);
        verify(stabilityClassifier, org.mockito.Mockito.times(1))
                .classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(), any());
        verify(stabilitySnapshotProvider, never()).update(any());
    }

    @Test
    @DisplayName("collectScheduledBatches: ephemeral=false → snapshot IS published "
            + "(parameter respected both ways)")
    void collectScheduledBatches_ephemeralFalse_publishesSnapshot() {
        LocationEntity loc = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        loc.setGridLat(54.7500);
        loc.setGridLng(-1.6250);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY.plusDays(1),
                Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY.plusDays(1), 1));
        when(stabilityClassifier.classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), loc.getGridLat(), loc.getGridLng(),
                        ForecastStability.SETTLED, "stable", 3));

        collector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false);

        verify(stabilitySnapshotProvider).update(any());
    }

    @Test
    @DisplayName("collectScheduledBatches: multiple locations sharing one grid cell → "
            + "snapshot has single cell with both location names")
    void collectScheduledBatches_sharedGridCell_singleCellMultipleNames() {
        LocationEntity loc1 = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        loc1.setId(42L);
        loc1.setGridLat(54.7500);
        loc1.setGridLng(-1.6250);
        LocationEntity loc2 = buildInlandLocation("Durham Cathedral", 54.7740, -1.5760);
        loc2.setId(43L);
        loc2.setGridLat(54.7500);
        loc2.setGridLng(-1.6250);

        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY.plusDays(1),
                Verdict.GO, loc1.getName(), loc2.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
        stubModels();
        String key1 = OpenMeteoService.coordKey(loc1.getLat(), loc1.getLon());
        String key2 = OpenMeteoService.coordKey(loc2.getLat(), loc2.getLon());
        when(openMeteoService.prefetchWeatherBatchResilient(any()))
                .thenReturn(Map.of(key1, dummyExtraction(), key2, dummyExtraction()));
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc1, TODAY.plusDays(1), 1));
        when(stabilityClassifier.classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc1.gridCellKey(), loc1.getGridLat(), loc1.getGridLng(),
                        ForecastStability.TRANSITIONAL, "mixed signals", 2));

        collector.collectScheduledBatches();

        ArgumentCaptor<StabilitySummaryResponse> captor =
                ArgumentCaptor.forClass(StabilitySummaryResponse.class);
        verify(stabilitySnapshotProvider).update(captor.capture());
        StabilitySummaryResponse summary = captor.getValue();
        assertThat(summary.cells()).hasSize(1);
        assertThat(summary.cells().get(0).locationNames())
                .containsExactlyInAnyOrder("Durham UK", "Durham Cathedral");
    }

    @Test
    @DisplayName("collectScheduledBatches: classifier called once per unique grid cell, "
            + "not per location-task")
    void collectScheduledBatches_classifierCalledPerCellNotPerTask() {
        LocationEntity loc1 = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        loc1.setId(42L);
        loc1.setGridLat(54.7500);
        loc1.setGridLng(-1.6250);
        LocationEntity loc2 = buildInlandLocation("Durham Cathedral", 54.7740, -1.5760);
        loc2.setId(43L);
        loc2.setGridLat(54.7500);
        loc2.setGridLng(-1.6250);

        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY.plusDays(1),
                Verdict.GO, loc1.getName(), loc2.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
        stubModels();
        String key1 = OpenMeteoService.coordKey(loc1.getLat(), loc1.getLon());
        String key2 = OpenMeteoService.coordKey(loc2.getLat(), loc2.getLon());
        when(openMeteoService.prefetchWeatherBatchResilient(any()))
                .thenReturn(Map.of(key1, dummyExtraction(), key2, dummyExtraction()));
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc1, TODAY.plusDays(1), 1));
        when(stabilityClassifier.classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc1.gridCellKey(), loc1.getGridLat(), loc1.getGridLng(),
                        ForecastStability.SETTLED, "stable", 3));

        collector.collectScheduledBatches();

        verify(stabilityClassifier, org.mockito.Mockito.times(1))
                .classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(), any());
    }

    @Test
    @DisplayName("collectScheduledBatches: no candidates have grid cells → snapshot NOT written")
    void collectScheduledBatches_noGridCells_snapshotNotWritten() {
        LocationEntity loc = stubGoBriefingWithLocation(TODAY.plusDays(1), "Durham UK");
        // Deliberately no setGridLat/setGridLng — hasGridCell() returns false
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY.plusDays(1), 1));

        collector.collectScheduledBatches();

        verify(stabilitySnapshotProvider, never()).update(any());
    }

    @Test
    @DisplayName("collectScheduledBatches: empty briefing → snapshot NOT written")
    void collectScheduledBatches_emptyBriefing_snapshotNotWritten() {
        when(briefingService.getCachedBriefing()).thenReturn(null);

        collector.collectScheduledBatches();

        verify(stabilitySnapshotProvider, never()).update(any());
    }

    @Test
    @DisplayName("collectScheduledBatches: snapshot built BEFORE triage — includes cells "
            + "that get triaged out")
    void collectScheduledBatches_snapshotIncludesTriagedCells() {
        LocationEntity loc = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        loc.setGridLat(54.7500);
        loc.setGridLng(-1.6250);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY.plusDays(1),
                Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);
        // Triage rejects everything
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(triagedPreEval(loc));
        when(stabilityClassifier.classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), loc.getGridLat(), loc.getGridLng(),
                        ForecastStability.UNSETTLED, "thick cloud", 1));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        // Result is empty (triaged out)
        assertThat(result.isEmpty()).isTrue();
        // Snapshot still published — covering the triaged cell so the next run's
        // freshness lookup knows this region is UNSETTLED.
        ArgumentCaptor<StabilitySummaryResponse> captor =
                ArgumentCaptor.forClass(StabilitySummaryResponse.class);
        verify(stabilitySnapshotProvider).update(captor.capture());
        assertThat(captor.getValue().cells()).hasSize(1);
        assertThat(captor.getValue().cells().get(0).stability())
                .isEqualTo(ForecastStability.UNSETTLED);
    }

    @Test
    @DisplayName("collectRegionFilteredBatches: does NOT write snapshot (admin path)")
    void collectRegionFilteredBatches_doesNotWriteSnapshot() {
        LocationEntity loc = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        loc.setGridLat(54.7500);
        loc.setGridLng(-1.6250);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY,
                Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY, 0));

        collector.collectRegionFilteredBatches(List.of(1L));

        verify(stabilitySnapshotProvider, never()).update(any());
    }

    // ── Disposition recording (V101) ──────────────────────────────────────────

    @Test
    @DisplayName("dispositions: EVALUATED candidate recorded with location id + null detail")
    void dispositions_evaluatedCandidate_recordedWithLocationIdAndNullDetail() {
        LocationEntity loc = stubGoBriefingWithLocation(TODAY.plusDays(1), "Durham UK");
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY.plusDays(1), 1));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.dispositions()).hasSize(1);
        CandidateDisposition d = result.dispositions().get(0);
        assertThat(d.category()).isEqualTo(DispositionCategory.EVALUATED);
        assertThat(d.locationName()).isEqualTo("Durham UK");
        assertThat(d.locationId()).isEqualTo(42L);
        assertThat(d.evaluationDate()).isEqualTo(TODAY.plusDays(1));
        assertThat(d.eventType()).isEqualTo(TargetType.SUNRISE);
        assertThat(d.daysAhead()).isEqualTo(1);
        assertThat(d.detail()).isNull();
    }

    @Test
    @DisplayName("dispositions: TRIAGED candidate recorded with triage reason as detail")
    void dispositions_triagedCandidate_recordedWithReason() {
        LocationEntity loc = stubGoBriefingWithLocation("Durham UK");
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(triagedPreEval(loc));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.dispositions()).hasSize(1);
        CandidateDisposition d = result.dispositions().get(0);
        assertThat(d.category()).isEqualTo(DispositionCategory.SKIPPED_TRIAGED);
        assertThat(d.detail()).isEqualTo("cloud");
        assertThat(d.locationId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("dispositions: STABILITY-gated T+3 TRANSITIONAL recorded with skip reason")
    void dispositions_stabilityGated_recordedWithSkipReason() {
        LocationEntity loc = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        loc.setGridLat(54.7500);
        loc.setGridLng(-1.6250);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY.plusDays(3),
                Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY.plusDays(3), 3));
        when(stabilityClassifier.classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), loc.getGridLat(), loc.getGridLng(),
                        ForecastStability.TRANSITIONAL, "frontal approach", 1));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.dispositions()).hasSize(1);
        CandidateDisposition d = result.dispositions().get(0);
        assertThat(d.category()).isEqualTo(DispositionCategory.SKIPPED_STABILITY);
        assertThat(d.detail()).isEqualTo("T+3 TRANSITIONAL");
        assertThat(d.daysAhead()).isEqualTo(3);
    }

    @Test
    @DisplayName("dispositions: intraday SETTLED candidate skipped as SKIPPED_NO_REFRESH_NEEDED "
            + "(through the real collect path, IntradayEligibilityPolicy)")
    void dispositions_intradaySettled_recordedAsNoRefreshNeeded() {
        // Exercises the policy → disposition-category mapping through the real
        // collector, NOT a mocked seam: a SETTLED location under the intraday
        // cost-gate is skipped and recorded as SKIPPED_NO_REFRESH_NEEDED (the
        // category the disposition acceptance bar checks for), distinct from
        // nightly's SKIPPED_STABILITY.
        LocationEntity loc = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        loc.setGridLat(54.7500);
        loc.setGridLng(-1.6250);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY.plusDays(1),
                Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY.plusDays(1), 1));
        when(stabilityClassifier.classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), loc.getGridLat(), loc.getGridLng(),
                        ForecastStability.SETTLED, "stable", 3));

        ScheduledBatchTasks result = collector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                IntradayEligibilityPolicy.INSTANCE,
                true);

        // Settled → no Claude call this afternoon; not bucketed.
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.dispositions()).hasSize(1);
        CandidateDisposition d = result.dispositions().get(0);
        assertThat(d.category()).isEqualTo(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED);
        assertThat(d.detail()).contains("settled");
        assertThat(d.locationName()).isEqualTo("Durham UK");
    }

    @Test
    @DisplayName("dispositions: intraday TRANSITIONAL candidate is EVALUATED (refresh worth it)")
    void dispositions_intradayTransitional_recordedAsEvaluated() {
        LocationEntity loc = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        loc.setGridLat(54.7500);
        loc.setGridLng(-1.6250);
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY.plusDays(1),
                Verdict.GO, loc.getName());
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(loc, TODAY.plusDays(1), 1));
        when(stabilityClassifier.classify(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), loc.getGridLat(), loc.getGridLng(),
                        ForecastStability.TRANSITIONAL, "mixed signals", 1));

        ScheduledBatchTasks result = collector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                IntradayEligibilityPolicy.INSTANCE,
                true);

        assertThat(result.nearInland()).hasSize(1);
        assertThat(result.dispositions()).hasSize(1);
        assertThat(result.dispositions().get(0).category())
                .isEqualTo(DispositionCategory.EVALUATED);
        // Intraday evaluates on the near-term model.
        assertThat(result.nearInland().get(0).model()).isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("dispositions: HARD_CONSTRAINT (tide mismatch) recorded with standdown reason")
    void dispositions_hardConstraint_recordedWithStanddownReason() {
        when(briefingService.getCachedBriefing())
                .thenReturn(buildBriefingWithStanddownReason(
                        TODAY, "Durham UK",
                        BriefingVerdictEvaluator.StanddownReason.TIDE_MISMATCH.label()));
        stubModels();

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.dispositions()).hasSize(1);
        CandidateDisposition d = result.dispositions().get(0);
        assertThat(d.category()).isEqualTo(DispositionCategory.SKIPPED_HARD_CONSTRAINT);
        // Hard-constraint skips do not perform a location lookup, so location_id stays null
        assertThat(d.locationId()).isNull();
        assertThat(d.locationName()).isEqualTo("Durham UK");
        assertThat(d.detail()).isEqualTo(
                BriefingVerdictEvaluator.StanddownReason.TIDE_MISMATCH.label());
    }

    @Test
    @DisplayName("dispositions: CACHED region records one SKIPPED_CACHED per slot")
    void dispositions_cachedRegion_recordsOnePerSlot() {
        // Two-slot region, fresh cache → both slots get SKIPPED_CACHED
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY, Verdict.GO,
                "Durham UK", "Newcastle");
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        stubModels();
        when(briefingEvaluationService.hasFreshEvaluation(any(), any())).thenReturn(true);

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.dispositions()).hasSize(2);
        assertThat(result.dispositions())
                .extracting(CandidateDisposition::category)
                .containsOnly(DispositionCategory.SKIPPED_CACHED);
        assertThat(result.dispositions())
                .extracting(CandidateDisposition::locationName)
                .containsExactlyInAnyOrder("Durham UK", "Newcastle");
        assertThat(result.dispositions())
                .allMatch(d -> d.detail() != null && d.detail().startsWith("Fresh cached"));
    }

    @Test
    @DisplayName("dispositions: PAST_DATE day records one entry per slot in that day")
    void dispositions_pastDateDay_recordsOnePerSlot() {
        DailyBriefingResponse briefing = buildBriefingWithSlots(TODAY.minusDays(2),
                Verdict.GO, "Durham UK");
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        stubModels();

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.dispositions()).hasSize(1);
        CandidateDisposition d = result.dispositions().get(0);
        assertThat(d.category()).isEqualTo(DispositionCategory.SKIPPED_PAST_DATE);
        assertThat(d.daysAhead()).isEqualTo(-2);
        assertThat(d.locationId()).isNull();
    }

    @Test
    @DisplayName("dispositions: UNKNOWN_LOCATION recorded when slot name has no LocationEntity")
    void dispositions_unknownLocation_recorded() {
        when(briefingService.getCachedBriefing())
                .thenReturn(buildBriefingForVerdict(TODAY, Verdict.GO));
        stubModels();
        when(locationService.findAllEnabled()).thenReturn(List.of()); // no locations registered

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.dispositions()).hasSize(1);
        CandidateDisposition d = result.dispositions().get(0);
        assertThat(d.category()).isEqualTo(DispositionCategory.SKIPPED_UNKNOWN_LOCATION);
        assertThat(d.locationId()).isNull();
        assertThat(d.locationName()).isEqualTo("Durham UK");
    }

    @Test
    @DisplayName("dispositions: per-candidate exception recorded as SKIPPED_ERROR with message")
    void dispositions_perCandidateException_recordedAsError() {
        LocationEntity loc = stubGoBriefingWithLocation("Durham UK");
        stubModels();
        stubPrefetchSuccess(loc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        assertThat(result.dispositions()).hasSize(1);
        CandidateDisposition d = result.dispositions().get(0);
        assertThat(d.category()).isEqualTo(DispositionCategory.SKIPPED_ERROR);
        assertThat(d.detail()).isEqualTo("boom");
    }

    @Test
    @DisplayName("dispositions: reconciliation — every slot the briefing contained "
            + "produces exactly one disposition row")
    void dispositions_reconciliation_eachSlotProducesOneRow() {
        // The "the totals add up" guarantee. The briefing has six slots split across:
        //   • 1 past-date slot
        //   • 2 cached slots (one region)
        //   • 1 hard-constraint (tide mismatch) STANDDOWN slot
        //   • 1 unknown-location slot
        //   • 1 valid GO slot reaching triage → EVALUATED
        // Total dispositions MUST equal 6 — the UI arithmetic depends on this.
        LocationEntity goLoc = buildInlandLocation("Durham UK", 54.7753, -1.5849);
        goLoc.setId(42L);

        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);

        // Day 1 (past) — 1 slot
        BriefingDay pastDay = buildSingleSlotDay(TODAY.minusDays(1), "Past Loc",
                Verdict.GO, weather, null);

        // Day 2 (today) — assembled from three regions:
        //   region A: 2 slots, cached
        //   region B: 1 slot, STANDDOWN tide mismatch
        //   region C: 2 slots — one unknown location, one valid GO
        BriefingSlot cached1 = new BriefingSlot("Cached1", TODAY.atTime(5, 30),
                Verdict.GO, weather, BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingSlot cached2 = new BriefingSlot("Cached2", TODAY.atTime(5, 30),
                Verdict.GO, weather, BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion regionA = new BriefingRegion(
                "Cached Region", Verdict.GO, "Cached summary", List.of(),
                List.of(cached1, cached2), null, null, null, null, null, null);

        BriefingSlot tideStanddown = new BriefingSlot("Tide Standdown", TODAY.atTime(5, 30),
                Verdict.STANDDOWN, weather, BriefingSlot.TideInfo.NONE, List.of(),
                BriefingVerdictEvaluator.StanddownReason.TIDE_MISMATCH.label());
        BriefingRegion regionB = new BriefingRegion(
                "Tide Region", Verdict.STANDDOWN, "Tide summary", List.of(),
                List.of(tideStanddown), null, null, null, null, null, null);

        BriefingSlot unknownLoc = new BriefingSlot("Unknown Loc", TODAY.atTime(5, 30),
                Verdict.GO, weather, BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingSlot goSlot = new BriefingSlot(goLoc.getName(), TODAY.atTime(5, 30),
                Verdict.GO, weather, BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion regionC = new BriefingRegion(
                "Mixed Region", Verdict.GO, "Mixed summary", List.of(),
                List.of(unknownLoc, goSlot), null, null, null, null, null, null);

        BriefingEventSummary todaySummary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(regionA, regionB, regionC), List.of());
        BriefingDay today = new BriefingDay(TODAY, List.of(todaySummary));

        DailyBriefingResponse briefing = new DailyBriefingResponse(
                null, null, List.of(pastDay, today), null, null, null,
                false, false, 0, null, List.of(), List.of());

        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(goLoc));
        stubModels();
        // Cached only for regionA — exact key match
        String cachedKey = com.gregochr.goldenhour.service.evaluation.CacheKeyFactory
                .build(regionA.regionName(), TODAY, TargetType.SUNRISE);
        when(briefingEvaluationService.hasFreshEvaluation(org.mockito.ArgumentMatchers.eq(cachedKey),
                any())).thenReturn(true);
        stubPrefetchSuccess(goLoc);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(inlandPreEval(goLoc, TODAY, 0));

        ScheduledBatchTasks result = collector.collectScheduledBatches();

        // Six slots considered → six dispositions, exactly one per slot.
        assertThat(result.dispositions()).hasSize(6);
        Map<DispositionCategory, Long> counts = result.dispositions().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        CandidateDisposition::category,
                        java.util.stream.Collectors.counting()));
        assertThat(counts).containsEntry(DispositionCategory.SKIPPED_PAST_DATE, 1L);
        assertThat(counts).containsEntry(DispositionCategory.SKIPPED_CACHED, 2L);
        assertThat(counts).containsEntry(DispositionCategory.SKIPPED_HARD_CONSTRAINT, 1L);
        assertThat(counts).containsEntry(DispositionCategory.SKIPPED_UNKNOWN_LOCATION, 1L);
        assertThat(counts).containsEntry(DispositionCategory.EVALUATED, 1L);
    }

    private BriefingDay buildSingleSlotDay(LocalDate date, String name, Verdict verdict,
            BriefingSlot.WeatherConditions weather, String standdownReason) {
        BriefingSlot slot = new BriefingSlot(name, date.atTime(5, 30), verdict, weather,
                BriefingSlot.TideInfo.NONE, List.of(), standdownReason);
        BriefingRegion region = new BriefingRegion(
                "Region " + name, verdict, "Summary", List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        return new BriefingDay(date, List.of(eventSummary));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubModels() {
        // Match production (V92): BATCH_NEAR_TERM = SONNET, BATCH_FAR_TERM = HAIKU.
        // Fixture was inverted prior to Gate 4 — the test only cared about which
        // tier a task landed in, not which model. Asserting against the real
        // tier→model mapping catches accidental tier swaps.
        lenient().when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        lenient().when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
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

    /**
     * Builds a briefing with a single STANDDOWN slot carrying the supplied reason label.
     * Used to verify how the gating policy treats specific {@link
     * BriefingVerdictEvaluator.StanddownReason} values.
     */
    private DailyBriefingResponse buildBriefingWithStanddownReason(LocalDate date,
            String locationName, String standdownReason) {
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);
        BriefingSlot slot = new BriefingSlot(locationName,
                date.atTime(5, 30),
                Verdict.STANDDOWN, weather, BriefingSlot.TideInfo.NONE, List.of(),
                standdownReason);
        BriefingRegion region = new BriefingRegion(
                "North East", Verdict.STANDDOWN, "Summary", List.of(), List.of(slot),
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

    private LocationEntity buildBluebellLocation(String name, double lat, double lon,
            BluebellExposure exposure) {
        LocationEntity location = buildInlandLocation(name, lat, lon);
        location.setLocationType(Set.of(LocationType.BLUEBELL));
        location.setBluebellExposure(exposure);
        return location;
    }

    /**
     * Builds an in-season bluebell pre-eval: an inland atmospheric payload carrying a non-null
     * bluebell condition score (the augmentor's in-season-bluebell signal). {@code triaged}
     * controls whether the colour triage stood the slot down.
     */
    private ForecastPreEvalResult bluebellPreEval(LocationEntity loc, LocalDate date,
            int daysAhead, BluebellExposure exposure, boolean triaged) {
        BluebellConditionScore conditions = new BluebellConditionScore(
                7, true, true, true, false, false, true, exposure,
                "Bright still light under the canopy.");
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName(loc.getName())
                .solarEventTime(EVENT_TIME)
                .targetType(TargetType.SUNRISE)
                .bluebellConditionScore(conditions)
                .build();
        return new ForecastPreEvalResult(triaged, triaged ? "cloud" : null, null, data, loc, date,
                TargetType.SUNRISE, EVENT_TIME, 60, daysAhead,
                EvaluationModel.HAIKU, Set.of(), "k", new OpenMeteoForecastResponse());
    }
}
