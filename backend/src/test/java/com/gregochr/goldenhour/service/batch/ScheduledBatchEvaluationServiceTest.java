package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CandidateDisposition;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.evaluation.EvaluationHandle;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.time.LocalDate.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduledBatchEvaluationService} after Pass 3.2.
 *
 * <p>The service no longer builds Anthropic batch requests directly — it collects
 * tasks (briefing → triage → stability) and hands them to
 * {@link EvaluationService#submit}. These tests verify:
 * <ul>
 *   <li>Pre-collection short-circuits (no briefing, all STANDDOWN, past dates)</li>
 *   <li>The triage / stability gates still skip the right tasks</li>
 *   <li>Successful task lists reach {@code evaluationService.submit} with the right
 *       trigger source</li>
 *   <li>Concurrency guards (forecastBatchRunning / auroraBatchRunning)</li>
 * </ul>
 *
 * <p>Customid format, prompt building, and batch submission mechanics live in their
 * own (much smaller) test classes ({@code CustomIdFactoryTest}, {@code
 * BatchRequestFactoryTest}, {@code BatchSubmissionServiceTest}, {@code
 * EvaluationServiceImplTest}). The end-to-end byte-identical contract is held by the
 * integration test pyramid.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledBatchEvaluationServiceTest {

    private static final LocalDate TEST_DATE = now();
    private static final LocalDateTime TEST_EVENT_TIME = TEST_DATE.atTime(5, 30);

    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private NoaaSwpcClient noaaSwpcClient;
    @Mock
    private WeatherTriageService weatherTriageService;
    @Mock
    private AuroraOrchestrator auroraOrchestrator;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private AuroraProperties auroraProperties;
    @Mock
    private DynamicSchedulerService dynamicSchedulerService;
    @Mock
    private EvaluationService evaluationService;
    @Mock
    private ForecastTaskCollector forecastTaskCollector;
    @Mock
    private ForecastDispositionService dispositionService;
    @Mock
    private com.gregochr.goldenhour.service.JobRunService jobRunService;

    private ScheduledBatchEvaluationService service;

    @BeforeEach
    void setUp() {
        // The aurora batch self-gates on this flag; these tests exercise the enabled path.
        // The disabled path is pinned by submitAuroraBatch_auroraDisabled_* below.
        lenient().when(auroraProperties.isEnabled()).thenReturn(true);
        service = new ScheduledBatchEvaluationService(
                modelSelectionService, noaaSwpcClient,
                weatherTriageService, auroraOrchestrator,
                locationRepository, auroraProperties, dynamicSchedulerService,
                evaluationService, forecastTaskCollector, dispositionService,
                jobRunService);
    }

    // ── registerJobTargets ───────────────────────────────────────────────────

    @Test
    @DisplayName("registerJobTargets registers aurora_batch_evaluation only "
            + "(near_term_batch_evaluation moved to PipelineOrchestrator in V102)")
    void registerJobTargets_registersExpectedKeys() {
        service.registerJobTargets();

        verify(dynamicSchedulerService).registerJobTarget(
                eq("aurora_batch_evaluation"), any(Runnable.class));
        // Near-term is now owned by PipelineOrchestrator — verify the legacy
        // self-registration was removed.
        verify(dynamicSchedulerService, org.mockito.Mockito.never()).registerJobTarget(
                eq("near_term_batch_evaluation"), any(Runnable.class));
    }

    // ── Forecast: routes through ForecastTaskCollector ───────────────────────

    @Test
    @DisplayName("submitForecastBatch: empty collector result → no submission")
    void submitForecastBatch_collectorReturnsEmpty_noSubmission() {
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(ScheduledBatchTasks.empty());

        service.submitForecastBatch();

        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitForecastBatch: collector returns near-inland tasks → one submit per bucket")
    void submitForecastBatch_collectorReturnsTasks_submitsEachNonEmptyBucket() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast nearInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast nearCoastalTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(nearInlandTask), List.of(nearCoastalTask),
                        List.of(), List.of(), List.of(), List.of()));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED),
                ArgumentMatchers.isNull()))
                .thenReturn(new EvaluationHandle(null, "msgbatch_x", 1));

        service.submitForecastBatch();

        // One submit per non-empty bucket — exactly two here
        verify(evaluationService, org.mockito.Mockito.times(2))
                .submit(any(List.class), eq(BatchTriggerSource.SCHEDULED),
                        ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("submitForecastBatch: non-empty bluebell bucket → submitted as its own batch")
    void submitForecastBatch_bluebellBucket_submitted() {
        LocationEntity location = buildLocation("Bluebell Wood");
        EvaluationTask.Forecast bluebellTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE,
                EvaluationTask.Forecast.PromptKind.BLUEBELL);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(), List.of(), List.of(), List.of(),
                        List.of(bluebellTask), List.of()));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED),
                ArgumentMatchers.isNull()))
                .thenReturn(new EvaluationHandle(null, "msgbatch_bb", 1));

        service.submitForecastBatch();

        // The bluebell bucket is the only non-empty bucket → exactly one submit, carrying it.
        ArgumentCaptor<List<EvaluationTask.Forecast>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(evaluationService).submit(captor.capture(), eq(BatchTriggerSource.SCHEDULED),
                ArgumentMatchers.isNull());
        assertThat(captor.getValue()).containsExactly(bluebellTask);
    }

    @Test
    @DisplayName("submitForecastBatch: persists dispositions tied to the cycle's "
            + "first non-null jobRunId")
    void submitForecastBatch_persistsDispositionsAgainstFirstJobRunId() {
        // Two buckets submitted; the FIRST handle returned has jobRunId 100, the
        // second has 101. Dispositions must be persisted against 100 — the cycle's
        // representative job_run — and persist must be invoked exactly once with
        // the full dispositions list from the collector.
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast nearInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast nearCoastalTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        CandidateDisposition evaluatedDispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.EVALUATED, null);
        CandidateDisposition triagedDispo = new CandidateDisposition(
                43L, "Newcastle", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.SKIPPED_TRIAGED, "cloud");
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(nearInlandTask), List.of(nearCoastalTask),
                        List.of(), List.of(), List.of(),
                        List.of(evaluatedDispo, triagedDispo)));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED),
                ArgumentMatchers.isNull()))
                .thenReturn(new EvaluationHandle(100L, "msgbatch_1", 1))
                .thenReturn(new EvaluationHandle(101L, "msgbatch_2", 1));

        service.submitForecastBatch();

        verify(dispositionService).persist(eq(100L),
                eq(List.of(evaluatedDispo, triagedDispo)));
    }

    @Test
    @DisplayName("submitForecastBatch: empty buckets AND empty dispositions → "
            + "no persist, no anchor run")
    void submitForecastBatch_emptyBucketsEmptyDispositions_noPersist() {
        // ScheduledBatchTasks.empty() has empty dispositions too (e.g. no cached
        // briefing). Nothing to account for, so neither persist nor anchor fire.
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(ScheduledBatchTasks.empty());

        service.submitForecastBatch();

        verifyNoInteractions(dispositionService);
        verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("submitForecastBatch: empty buckets but NON-empty dispositions "
            + "(all-cached cycle) → anchor run created + dispositions persisted")
    void submitForecastBatch_emptyBucketsWithDispositions_persistsAgainstAnchorRun() {
        // This is the live bug the [DISPOSITION] log never firing pointed to: a
        // cycle where every candidate is cached/skipped submits no bucket, so the
        // old `if (tasks.isEmpty()) return;` dropped the dispositions. Now an
        // anchor run is created and the dispositions persist against it.
        CandidateDisposition cached1 = new CandidateDisposition(
                null, "Cached A", TEST_DATE, TargetType.SUNRISE, 1,
                DispositionCategory.SKIPPED_CACHED, "Fresh cached evaluation");
        CandidateDisposition cached2 = new CandidateDisposition(
                null, "Cached B", TEST_DATE, TargetType.SUNSET, 1,
                DispositionCategory.SKIPPED_CACHED, "Fresh cached evaluation");
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(cached1, cached2)));
        when(jobRunService.startDispositionAnchorRun(2)).thenReturn(555L);

        service.submitForecastBatch();

        // No batch submitted...
        verifyNoInteractions(evaluationService);
        // ...but the anchor run was created and dispositions persisted against it.
        verify(jobRunService).startDispositionAnchorRun(2);
        verify(dispositionService).persist(eq(555L), eq(List.of(cached1, cached2)));
    }

    @Test
    @DisplayName("submitForecastBatch: buckets submitted but all handles return null "
            + "jobRunId → anchor run created so dispositions still land")
    void submitForecastBatch_bucketsSubmittedButNullJobRunIds_anchorsDispositions() {
        // Defensive: if every bucket's submit() returns EvaluationHandle.empty()
        // (Anthropic submission failed after the collector produced work), there
        // is no batch job_run to anchor to — but the dispositions are still real
        // and must not be dropped. An anchor run catches them.
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast nearInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        CandidateDisposition dispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.EVALUATED, null);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(nearInlandTask), List.of(), List.of(), List.of(), List.of(),
                        List.of(dispo)));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED),
                ArgumentMatchers.isNull()))
                .thenReturn(EvaluationHandle.empty());
        when(jobRunService.startDispositionAnchorRun(1)).thenReturn(999L);

        service.submitForecastBatch();

        verify(jobRunService).startDispositionAnchorRun(1);
        verify(dispositionService).persist(eq(999L), eq(List.of(dispo)));
    }

    @Test
    @DisplayName("submitScheduledBatchForRegions: empty collector result → returns null")
    void submitScheduledBatchForRegions_collectorEmpty_returnsNull() {
        when(forecastTaskCollector.collectRegionFilteredBatches(any()))
                .thenReturn(RegionFilteredBatchTasks.empty());

        BatchSubmitResult result = service.submitScheduledBatchForRegions(List.of(1L));

        assertThat(result).isNull();
        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitScheduledBatchForRegions: collector returns tasks → submits via ADMIN trigger")
    void submitScheduledBatchForRegions_collectorReturnsTasks_submitsAdmin() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast inlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        when(forecastTaskCollector.collectRegionFilteredBatches(any()))
                .thenReturn(new RegionFilteredBatchTasks(
                        List.of(inlandTask), List.of()));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.ADMIN)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_admin", 1));

        BatchSubmitResult result = service.submitScheduledBatchForRegions(List.of(1L));

        assertThat(result).isNotNull();
        assertThat(result.batchId()).isEqualTo("msgbatch_admin");
        verify(evaluationService).submit(any(List.class), eq(BatchTriggerSource.ADMIN));
    }

    // ── Aurora ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("submitAuroraBatch skips when NOAA fetch fails")
    void submitAuroraBatch_noaaFails_skips() {
        when(noaaSwpcClient.fetchAll()).thenThrow(new RuntimeException("network"));

        service.submitAuroraBatch();

        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("aurora batch does nothing when aurora.enabled=false — no NOAA fetch, no spend")
    void submitAuroraBatch_auroraDisabled_fetchesNothingAndSubmitsNothing() {
        when(auroraProperties.isEnabled()).thenReturn(false);

        service.submitAuroraBatch();

        // The gate must fire before the NOAA call: resuming or triggering this job from the
        // Scheduler UI with the feature off previously fetched NOAA and submitted a batch.
        verifyNoInteractions(noaaSwpcClient);
        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitAuroraBatch skips when alert level is QUIET")
    void submitAuroraBatch_quietLevel_skips() {
        SpaceWeatherData wx = new SpaceWeatherData(
                List.of(), List.of(), null, List.of(), List.of());
        when(noaaSwpcClient.fetchAll()).thenReturn(wx);
        when(auroraOrchestrator.deriveAlertLevel(wx)).thenReturn(AlertLevel.QUIET);

        service.submitAuroraBatch();

        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitAuroraBatch dispatches Aurora task to evaluationService.submit on viable triage")
    void submitAuroraBatch_viableLocations_dispatchesToEvaluationService() {
        SpaceWeatherData wx = new SpaceWeatherData(
                List.of(), List.of(), null, List.of(), List.of());
        when(noaaSwpcClient.fetchAll()).thenReturn(wx);
        when(auroraOrchestrator.deriveAlertLevel(wx)).thenReturn(AlertLevel.MODERATE);
        AuroraProperties.BortleThreshold threshold = new AuroraProperties.BortleThreshold();
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        LocationEntity loc = buildLocation("Northumberland Coast");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(loc));
        WeatherTriageService.TriageResult triage =
                new WeatherTriageService.TriageResult(
                        List.of(loc), List.of(), Map.of(loc, 30));
        when(weatherTriageService.triage(any())).thenReturn(triage);
        when(modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION))
                .thenReturn(EvaluationModel.HAIKU);
        // Aurora batch uses the cycle-unaware 2-arg overload (aurora is parallel
        // to, not inside, the orchestrated forecast cycle).
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_aurora", 1));

        service.submitAuroraBatch();

        ArgumentCaptor<List<EvaluationTask.Aurora>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(evaluationService).submit(captor.capture(),
                eq(BatchTriggerSource.SCHEDULED));
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).alertLevel()).isEqualTo(AlertLevel.MODERATE);
        assertThat(captor.getValue().get(0).viableLocations()).containsExactly(loc);
    }

    @Test
    @DisplayName("submitAuroraBatch skips when no viable locations after triage")
    void submitAuroraBatch_noViableLocations_skips() {
        SpaceWeatherData wx = new SpaceWeatherData(
                List.of(), List.of(), null, List.of(), List.of());
        when(noaaSwpcClient.fetchAll()).thenReturn(wx);
        when(auroraOrchestrator.deriveAlertLevel(wx)).thenReturn(AlertLevel.MODERATE);
        AuroraProperties.BortleThreshold threshold = new AuroraProperties.BortleThreshold();
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        LocationEntity loc = buildLocation("Northumberland Coast");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(loc));
        WeatherTriageService.TriageResult triage =
                new WeatherTriageService.TriageResult(
                        List.of(), List.of(loc), Map.of(loc, 100));
        when(weatherTriageService.triage(any())).thenReturn(triage);

        service.submitAuroraBatch();

        verifyNoInteractions(evaluationService);
    }

    // ── Concurrency guards ───────────────────────────────────────────────────

    @Test
    @DisplayName("resetBatchGuards clears forecast guard")
    void resetBatchGuards_clearsForecastGuard() throws Exception {
        Field forecastField = ScheduledBatchEvaluationService.class
                .getDeclaredField("forecastBatchRunning");
        forecastField.setAccessible(true);
        ((AtomicBoolean) forecastField.get(service)).set(true);

        service.resetBatchGuards();

        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(ScheduledBatchTasks.empty());
        service.submitForecastBatch();
        verify(forecastTaskCollector).collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false);
    }

    @Test
    @DisplayName("resetBatchGuards clears aurora guard")
    void resetBatchGuards_clearsAuroraGuard() throws Exception {
        Field auroraField = ScheduledBatchEvaluationService.class
                .getDeclaredField("auroraBatchRunning");
        auroraField.setAccessible(true);
        ((AtomicBoolean) auroraField.get(service)).set(true);

        service.resetBatchGuards();

        when(noaaSwpcClient.fetchAll()).thenThrow(new RuntimeException("test"));
        service.submitAuroraBatch();
        verify(noaaSwpcClient).fetchAll();
    }

    @Test
    @DisplayName("submitForecastBatch skips when guard already held")
    void submitForecastBatch_guardAlreadyHeld_skips() throws Exception {
        Field forecastField = ScheduledBatchEvaluationService.class
                .getDeclaredField("forecastBatchRunning");
        forecastField.setAccessible(true);
        ((AtomicBoolean) forecastField.get(service)).set(true);

        service.submitForecastBatch();

        verifyNoInteractions(forecastTaskCollector);
    }

    @Test
    @DisplayName("submitAuroraBatch skips when guard already held")
    void submitAuroraBatch_guardAlreadyHeld_skips() throws Exception {
        Field auroraField = ScheduledBatchEvaluationService.class
                .getDeclaredField("auroraBatchRunning");
        auroraField.setAccessible(true);
        ((AtomicBoolean) auroraField.get(service)).set(true);

        service.submitAuroraBatch();

        verifyNoInteractions(noaaSwpcClient);
    }

    @Test
    @DisplayName("submitForecastBatch clears guard even when collector throws")
    void submitForecastBatch_exceptionInCollector_clearsGuard() {
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(ScheduledBatchTasks.empty());

        try {
            service.submitForecastBatch();
        } catch (RuntimeException ignored) {
            // expected — guard must still be cleared
        }

        service.submitForecastBatch();
        verify(forecastTaskCollector, org.mockito.Mockito.times(2))
                .collectScheduledBatches(
                        NightlyCandidateCollectionStrategy.INSTANCE,
                        NightlyEligibilityPolicy.INSTANCE,
                        false);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private LocationEntity buildLocation(String name) {
        LocationEntity location = new LocationEntity();
        location.setId(42L);
        location.setName(name);
        location.setLat(54.7753);
        location.setLon(-1.5849);
        RegionEntity region = new RegionEntity();
        region.setName("North East");
        location.setRegion(region);
        location.setTideType(Set.of());
        return location;
    }

    private AtmosphericData buildAtmospheric() {
        return com.gregochr.goldenhour.TestAtmosphericData.builder()
                .locationName("Durham UK")
                .solarEventTime(TEST_EVENT_TIME)
                .targetType(TargetType.SUNRISE)
                .build();
    }
}
