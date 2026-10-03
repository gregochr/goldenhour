package com.gregochr.goldenhour.service.batch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.time.LocalDate.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

    /**
     * The real production detail string {@code ForecastTaskCollector#includeDisposition} writes
     * for every FORCE_EVALUATED disposition — never null, unlike a plain EVALUATED row's. Fixtures
     * use this rather than {@code null} so a rewrite test can prove the force-eval reason is
     * preserved (both-failed: kept as-is inside the SUBMISSION_FAILED detail; partial: appended
     * to, never replacing it).
     */
    private static final String FORCE_EVAL_DETAIL = "Force-evaluated best-bet headline candidate";

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

    /** Fixed so the batch-breakdown log line never depends on wall-clock time. */
    private static final Clock CLOCK = Clock.fixed(
            java.time.Instant.parse("2026-04-14T12:00:00Z"), java.time.ZoneOffset.UTC);

    private ScheduledBatchEvaluationService service;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

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
                jobRunService, CLOCK);

        serviceLogger = (Logger) LoggerFactory.getLogger(ScheduledBatchEvaluationService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        serviceLogger.detachAppender(logAppender);
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
                        List.of(), List.of(), List.of(), List.of(), List.of()));
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
                        List.of(), List.of(), List.of(),
                        List.of(), List.of(),
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
                        List.of(), List.of(), List.of(), List.of(),
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
                        List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(),
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
    @DisplayName("a pipeline cycle that submits no batch persists its dispositions AND the link to "
            + "its anchor job run in one call (one transaction), so the location auto-disable "
            + "settle can find them even after a restart")
    void pipelineCycle_noBatch_recordsAnchorRunAgainstPipelineRun() {
        CandidateDisposition triaged = new CandidateDisposition(
                7L, "Triaged A", TEST_DATE, TargetType.SUNRISE, 1,
                DispositionCategory.SKIPPED_TRIAGED, "heavy cloud");
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(),
                        List.of(triaged)));
        when(jobRunService.startDispositionAnchorRun(1)).thenReturn(555L);

        service.submitForecastBatchForPipelineRun(
                99L, NightlyCandidateCollectionStrategy.INSTANCE, NightlyEligibilityPolicy.INSTANCE,
                false, summary -> { });

        verify(dispositionService).persist(eq(99L), eq(555L), eq(List.of(triaged)));
        verifyNoMoreInteractions(dispositionService);
    }

    @Test
    @DisplayName("a failed atomic persist of dispositions and link is NOT swallowed: it propagates "
            + "so the cycle is not left with dispositions and no link")
    void pipelineCycle_linkWriteFails_propagates() {
        CandidateDisposition triaged = new CandidateDisposition(
                7L, "Triaged A", TEST_DATE, TargetType.SUNRISE, 1,
                DispositionCategory.SKIPPED_TRIAGED, "heavy cloud");
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(),
                        List.of(triaged)));
        when(jobRunService.startDispositionAnchorRun(1)).thenReturn(555L);
        org.mockito.Mockito.doThrow(new IllegalStateException("link write failed"))
                .when(dispositionService).persist(99L, 555L, List.of(triaged));

        assertThatThrownBy(() -> service.submitForecastBatchForPipelineRun(
                99L, NightlyCandidateCollectionStrategy.INSTANCE, NightlyEligibilityPolicy.INSTANCE,
                false, summary -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("link write failed");
    }

    @Test
    @DisplayName("the legacy cron-direct entry (no pipeline run) records no link: there is no "
            + "cycle to settle")
    void legacyEntry_noPipelineRun_recordsNoLink() {
        CandidateDisposition cached = new CandidateDisposition(
                null, "Cached A", TEST_DATE, TargetType.SUNRISE, 1,
                DispositionCategory.SKIPPED_CACHED, "Fresh cached evaluation");
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(),
                        List.of(cached)));
        when(jobRunService.startDispositionAnchorRun(1)).thenReturn(555L);

        service.submitForecastBatch();

        verify(dispositionService).persist(eq(555L), eq(List.of(cached)));
        verifyNoMoreInteractions(dispositionService);
    }

    @Test
    @DisplayName("submitForecastBatch: buckets submitted but all handles return null "
            + "jobRunId → anchor run created so dispositions still land, rewritten "
            + "SUBMISSION_FAILED since the one bucket carrying them failed")
    void submitForecastBatch_bucketsSubmittedButNullJobRunIds_anchorsDispositions() {
        // Defensive: if every bucket's submit() returns EvaluationHandle.empty()
        // (Anthropic submission failed after the collector produced work), there
        // is no batch job_run to anchor to — but the dispositions are still real
        // and must not be dropped. An anchor run catches them. And because the
        // ONLY bucket carrying this candidate's task failed, its EVALUATED
        // disposition is rewritten to SUBMISSION_FAILED before persistence — see
        // applySubmissionFailures_* below for the full rewrite-rule coverage.
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
                        List.of(nearInlandTask), List.of(), List.of(),
                        List.of(), List.of(), List.of(),
                        List.of(dispo)));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED),
                ArgumentMatchers.isNull()))
                .thenReturn(EvaluationHandle.empty());
        when(jobRunService.startDispositionAnchorRun(1)).thenReturn(999L);

        service.submitForecastBatch();

        verify(jobRunService).startDispositionAnchorRun(1);
        CandidateDisposition expected = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.SUBMISSION_FAILED,
                "near-term inland batch submission failed");
        verify(dispositionService).persist(eq(999L), eq(List.of(expected)));
    }

    @Test
    @DisplayName("submitForecastBatch: an OPEN_FELL candidate whose sky AND bluebell buckets "
            + "BOTH fail is rewritten to SUBMISSION_FAILED naming both buckets — no request of "
            + "either kind reached Claude for it")
    void submitForecastBatch_pairedCandidateBothBucketsFail_rewritesSubmissionFailed() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast skyTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast bluebellTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE,
                EvaluationTask.Forecast.PromptKind.BLUEBELL);
        CandidateDisposition dispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.EVALUATED, null);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(skyTask), List.of(), List.of(), List.of(),
                        List.of(bluebellTask), List.of(),
                        List.of(dispo)));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED),
                ArgumentMatchers.isNull()))
                .thenReturn(EvaluationHandle.empty())
                .thenReturn(EvaluationHandle.empty());
        when(jobRunService.startDispositionAnchorRun(1)).thenReturn(999L);

        service.submitForecastBatch();

        CandidateDisposition expected = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.SUBMISSION_FAILED,
                "near-term inland+bluebell batch submission failed");
        verify(dispositionService).persist(eq(999L), eq(List.of(expected)));
    }

    @Test
    @DisplayName("submitForecastBatch: an OPEN_FELL candidate whose sky bucket succeeds but "
            + "bluebell bucket fails stays EVALUATED (a real request DID reach Claude) with the "
            + "partial loss annotated in detail")
    void submitForecastBatch_pairedCandidatePartialFailure_staysEvaluatedWithAnnotation() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast skyTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast bluebellTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE,
                EvaluationTask.Forecast.PromptKind.BLUEBELL);
        CandidateDisposition dispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.EVALUATED, null);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(skyTask), List.of(), List.of(), List.of(),
                        List.of(bluebellTask), List.of(),
                        List.of(dispo)));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED),
                ArgumentMatchers.isNull()))
                .thenReturn(new EvaluationHandle(7L, "msgbatch_sky_ok", 1))
                .thenReturn(EvaluationHandle.empty());

        service.submitForecastBatch();

        CandidateDisposition expected = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.EVALUATED,
                "bluebell batch submission failed for part of this candidate's pairing — "
                        + "the rest was evaluated normally");
        verify(dispositionService).persist(eq(7L), eq(List.of(expected)));
    }

    @Test
    @DisplayName("submitForecastBatch: a FORCE_EVALUATED OPEN_FELL candidate with a partial "
            + "pairing failure APPENDS the partial-loss note to the existing force-eval detail — "
            + "never replacing ForecastTaskCollector's own reason")
    void submitForecastBatch_forceEvaluatedPairedCandidatePartialFailure_appendsToExistingDetail() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast skyTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast bluebellTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE,
                EvaluationTask.Forecast.PromptKind.BLUEBELL);
        CandidateDisposition dispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 5,
                DispositionCategory.FORCE_EVALUATED, FORCE_EVAL_DETAIL);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(skyTask), List.of(), List.of(), List.of(),
                        List.of(bluebellTask), List.of(),
                        List.of(dispo)));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED),
                ArgumentMatchers.isNull()))
                .thenReturn(new EvaluationHandle(7L, "msgbatch_sky_ok", 1))
                .thenReturn(EvaluationHandle.empty());

        service.submitForecastBatch();

        CandidateDisposition expected = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 5,
                DispositionCategory.FORCE_EVALUATED,
                FORCE_EVAL_DETAIL + "; bluebell batch submission failed for part of this "
                        + "candidate's pairing — the rest was evaluated normally");
        verify(dispositionService).persist(eq(7L), eq(List.of(expected)));
    }

    @Test
    @DisplayName("submitForecastBatch: a FORCE_EVALUATED candidate whose only bucket fails is "
            + "rewritten to SUBMISSION_FAILED with the forced fact preserved in detail")
    void submitForecastBatch_forceEvaluatedCandidateBucketFails_rewritesWithForcedNote() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast farInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        CandidateDisposition dispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 5,
                DispositionCategory.FORCE_EVALUATED, FORCE_EVAL_DETAIL);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(), List.of(), List.of(farInlandTask), List.of(),
                        List.of(), List.of(),
                        List.of(dispo)));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED),
                ArgumentMatchers.isNull()))
                .thenReturn(EvaluationHandle.empty());
        when(jobRunService.startDispositionAnchorRun(1)).thenReturn(999L);

        service.submitForecastBatch();

        CandidateDisposition expected = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 5,
                DispositionCategory.SUBMISSION_FAILED,
                "far-term inland batch submission failed (forced)");
        verify(dispositionService).persist(eq(999L), eq(List.of(expected)));
    }

    @Test
    @DisplayName("submitForecastBatch: a SKIPPED_TRIAGED disposition is never rewritten, even "
            + "when another candidate's bucket fails in the same cycle")
    void submitForecastBatch_skippedDispositionUntouchedByBucketFailure() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast nearInlandTask = new EvaluationTask.Forecast(
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
                        List.of(nearInlandTask), List.of(), List.of(), List.of(),
                        List.of(), List.of(),
                        List.of(evaluatedDispo, triagedDispo)));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED),
                ArgumentMatchers.isNull()))
                .thenReturn(EvaluationHandle.empty());
        when(jobRunService.startDispositionAnchorRun(2)).thenReturn(999L);

        service.submitForecastBatch();

        CandidateDisposition expectedEvaluated = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.SUBMISSION_FAILED,
                "near-term inland batch submission failed");
        verify(dispositionService).persist(eq(999L),
                eq(List.of(expectedEvaluated, triagedDispo)));
    }

    @Test
    @DisplayName("submitForecastBatch: a plain RuntimeException from one bucket's submit (e.g. "
            + "request building failing, outside BatchSubmissionService's own try/catch) is "
            + "treated as a failed bucket and the cycle CONTINUES — the next bucket still submits "
            + "and the whole cycle's dispositions are still persisted, rather than the exception "
            + "aborting submitBuckets before persistCycleDispositions ever runs")
    void submitForecastBatch_bucketThrowsPlainException_treatedAsFailedAndCycleContinues() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast nearInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast nearCoastalTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNSET,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        CandidateDisposition inlandDispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.EVALUATED, null);
        CandidateDisposition coastalDispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNSET, 0,
                DispositionCategory.EVALUATED, null);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(nearInlandTask), List.of(nearCoastalTask), List.of(), List.of(),
                        List.of(), List.of(),
                        List.of(inlandDispo, coastalDispo)));
        // near-term inland (first bucket attempted) throws building its request; near-term
        // coastal (second) succeeds normally.
        when(evaluationService.submit(eq(List.of(nearInlandTask)),
                eq(BatchTriggerSource.SCHEDULED), ArgumentMatchers.isNull()))
                .thenThrow(new IllegalStateException("request building failed"));
        when(evaluationService.submit(eq(List.of(nearCoastalTask)),
                eq(BatchTriggerSource.SCHEDULED), ArgumentMatchers.isNull()))
                .thenReturn(new EvaluationHandle(13L, "msgbatch_coastal_ok", 1));

        service.submitForecastBatch();

        CandidateDisposition expectedInland = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.SUBMISSION_FAILED,
                "near-term inland batch submission failed");
        // The coastal bucket succeeded, so its own disposition is untouched, and the cycle's
        // dispositions anchor to ITS real job_run rather than an anchor run — proving the
        // second bucket really was attempted after the first one threw.
        verify(dispositionService).persist(eq(13L), eq(List.of(expectedInland, coastalDispo)));
    }

    @Test
    @DisplayName("submitForecastBatch: an OrphanedBatchException from one bucket persists the "
            + "cycle's dispositions collected so far — with the orphaned bucket's OWN candidate "
            + "left EVALUATED, since a real request reached Claude for it — then rethrows so the "
            + "run still fails; a bucket after the orphaned one is never attempted")
    void submitForecastBatch_orphanedBatchException_persistsThenRethrows() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast nearInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast nearCoastalTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNSET,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        CandidateDisposition inlandDispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.EVALUATED, null);
        CandidateDisposition coastalDispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNSET, 0,
                DispositionCategory.FORCE_EVALUATED, FORCE_EVAL_DETAIL);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(nearInlandTask), List.of(nearCoastalTask), List.of(), List.of(),
                        List.of(), List.of(),
                        List.of(inlandDispo, coastalDispo)));
        // near-term inland (first bucket attempted) succeeds for real; near-term coastal
        // (second) is accepted by Anthropic but its tracking row fails to persist.
        when(evaluationService.submit(eq(List.of(nearInlandTask)),
                eq(BatchTriggerSource.SCHEDULED), ArgumentMatchers.isNull()))
                .thenReturn(new EvaluationHandle(21L, "msgbatch_inland_ok", 1));
        OrphanedBatchException orphaned = new OrphanedBatchException(
                "msgbatch_coastal_orphan", new RuntimeException("row persist failed"));
        when(evaluationService.submit(eq(List.of(nearCoastalTask)),
                eq(BatchTriggerSource.SCHEDULED), ArgumentMatchers.isNull()))
                .thenThrow(orphaned);

        assertThatThrownBy(() -> service.submitForecastBatch())
                .isSameAs(orphaned);

        // Both candidates stay exactly as the collector wrote them: the inland one because its
        // bucket genuinely succeeded, the coastal one because a real request reached Claude for
        // it even though the tracking row was lost — and the cycle anchors to the inland bucket's
        // own real job_run, which was already known before the orphan was thrown.
        verify(dispositionService).persist(eq(21L), eq(List.of(inlandDispo, coastalDispo)));
    }

    @Test
    @DisplayName("submitForecastBatch: an OrphanedBatchException in the FIRST of three non-empty "
            + "buckets rewrites BOTH later, never-attempted buckets' candidates to "
            + "SUBMISSION_FAILED — not just left as the misleading EVALUATED/FORCE_EVALUATED the "
            + "collector wrote, which was the review-found gap in the first cut of this feature "
            + "(2026-09-30): a bucket queued after the orphaned one never reaches "
            + "evaluationService.submit at all, so its candidates had no matching outcome for "
            + "applySubmissionFailures to rewrite and were persisted as though a request had "
            + "reached Claude for them")
    void submitForecastBatch_orphanInFirstOfThreeBuckets_laterUnattemptedBucketsBecomeSubmissionFailed() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast nearInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast nearCoastalTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNSET,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast farInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE.plusDays(1), TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        // Bucket 1 (near-term inland) is the one that orphans — its own candidate stays
        // EVALUATED, since a real request DID reach Claude for it.
        CandidateDisposition inlandDispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.EVALUATED, null);
        // Bucket 2 (near-term coastal) — an ordinary EVALUATED candidate, never attempted at all.
        CandidateDisposition coastalDispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNSET, 0,
                DispositionCategory.EVALUATED, null);
        // Bucket 3 (far-term inland) — a FORCE_EVALUATED candidate, never attempted at all; its
        // force-eval provenance must survive as the " (forced)" suffix, the same convention item
        // B's ordinary both-failed branch already uses.
        CandidateDisposition farInlandDispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE.plusDays(1), TargetType.SUNRISE, 1,
                DispositionCategory.FORCE_EVALUATED, FORCE_EVAL_DETAIL);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(nearInlandTask), List.of(nearCoastalTask), List.of(farInlandTask),
                        List.of(), List.of(), List.of(),
                        List.of(inlandDispo, coastalDispo, farInlandDispo)));
        OrphanedBatchException orphaned = new OrphanedBatchException(
                "msgbatch_inland_orphan", new RuntimeException("row persist failed"));
        when(evaluationService.submit(eq(List.of(nearInlandTask)),
                eq(BatchTriggerSource.SCHEDULED), ArgumentMatchers.isNull()))
                .thenThrow(orphaned);
        // No stub for the near-coastal or far-inland buckets at all — submitBuckets must never
        // call evaluationService.submit for them, since the orphan aborts the cycle before their
        // turn. The orphan is on the FIRST bucket, so no earlier bucket set cycleJobRunId; the
        // cycle falls back to a disposition-only anchor run.
        when(jobRunService.startDispositionAnchorRun(3)).thenReturn(777L);

        assertThatThrownBy(() -> service.submitForecastBatch())
                .isSameAs(orphaned);

        CandidateDisposition expectedCoastalFailed = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNSET, 0,
                DispositionCategory.SUBMISSION_FAILED,
                "near-term coastal batch not submitted: an earlier bucket's batch was orphaned");
        CandidateDisposition expectedFarInlandFailed = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE.plusDays(1), TargetType.SUNRISE, 1,
                DispositionCategory.SUBMISSION_FAILED,
                "far-term inland batch not submitted: an earlier bucket's batch was orphaned "
                        + "(forced)");
        verify(dispositionService).persist(eq(777L),
                eq(List.of(inlandDispo, expectedCoastalFailed, expectedFarInlandFailed)));
        verifyNoMoreInteractions(dispositionService);

        // Proves the later buckets were never attempted at all — not attempted-and-failed.
        verify(evaluationService).submit(eq(List.of(nearInlandTask)),
                eq(BatchTriggerSource.SCHEDULED), ArgumentMatchers.isNull());
        verifyNoMoreInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitForecastBatch: an OrphanedBatchException on an OPEN_FELL candidate's sky "
            + "bucket (the FIRST bucket) counts that bucket as a SUCCESS — a real request reached "
            + "Claude for the sky rating — while the paired bluebell bucket, queued after it, is "
            + "never attempted at all; the candidate's one disposition row stays EVALUATED with the "
            + "\"not submitted\" pairing note appended, never rewritten to SUBMISSION_FAILED")
    void submitForecastBatch_orphanOnPairedSkyBucket_staysEvaluatedWithNotSubmittedNote() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast skyTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast bluebellTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE,
                EvaluationTask.Forecast.PromptKind.BLUEBELL);
        CandidateDisposition dispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.EVALUATED, null);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(skyTask), List.of(), List.of(), List.of(),
                        List.of(bluebellTask), List.of(),
                        List.of(dispo)));
        OrphanedBatchException orphaned = new OrphanedBatchException(
                "msgbatch_sky_orphan", new RuntimeException("row persist failed"));
        when(evaluationService.submit(eq(List.of(skyTask)),
                eq(BatchTriggerSource.SCHEDULED), ArgumentMatchers.isNull()))
                .thenThrow(orphaned);
        // No stub for the bluebell bucket at all — submitBuckets must never call
        // evaluationService.submit for it, since the orphan on the first (and only preceding)
        // bucket aborts the cycle before the bluebell bucket's turn. The orphan is on the FIRST
        // bucket, so no earlier bucket set cycleJobRunId; the cycle falls back to a
        // disposition-only anchor run over the collector's one disposition row.
        when(jobRunService.startDispositionAnchorRun(1)).thenReturn(778L);

        assertThatThrownBy(() -> service.submitForecastBatch())
                .isSameAs(orphaned);

        CandidateDisposition expected = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.EVALUATED,
                "bluebell batch not submitted for part of this candidate's pairing — an earlier "
                        + "bucket's batch was orphaned; the rest was evaluated normally");
        verify(dispositionService).persist(eq(778L), eq(List.of(expected)));
        verifyNoMoreInteractions(dispositionService);

        verify(jobRunService).startDispositionAnchorRun(1);

        // Proves the bluebell bucket was never attempted at all — not attempted-and-failed.
        verify(evaluationService).submit(eq(List.of(skyTask)),
                eq(BatchTriggerSource.SCHEDULED), ArgumentMatchers.isNull());
        verifyNoMoreInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitForecastBatch: the same orphaned-sky-bucket/unattempted-bluebell-bucket "
            + "pairing on a FORCE_EVALUATED OPEN_FELL candidate APPENDS the \"not submitted\" note "
            + "to the existing force-eval detail — the row stays FORCE_EVALUATED, never "
            + "SUBMISSION_FAILED, and ForecastTaskCollector's own forced-reason text is preserved "
            + "rather than replaced")
    void submitForecastBatch_orphanOnPairedSkyBucketForceEvaluated_appendsNotSubmittedNote() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast skyTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast bluebellTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE,
                EvaluationTask.Forecast.PromptKind.BLUEBELL);
        CandidateDisposition dispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 1,
                DispositionCategory.FORCE_EVALUATED, FORCE_EVAL_DETAIL);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(skyTask), List.of(), List.of(), List.of(),
                        List.of(bluebellTask), List.of(),
                        List.of(dispo)));
        OrphanedBatchException orphaned = new OrphanedBatchException(
                "msgbatch_sky_orphan", new RuntimeException("row persist failed"));
        when(evaluationService.submit(eq(List.of(skyTask)),
                eq(BatchTriggerSource.SCHEDULED), ArgumentMatchers.isNull()))
                .thenThrow(orphaned);
        when(jobRunService.startDispositionAnchorRun(1)).thenReturn(778L);

        assertThatThrownBy(() -> service.submitForecastBatch())
                .isSameAs(orphaned);

        CandidateDisposition expected = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 1,
                DispositionCategory.FORCE_EVALUATED,
                FORCE_EVAL_DETAIL + "; bluebell batch not submitted for part of this candidate's "
                        + "pairing — an earlier bucket's batch was orphaned; the rest was "
                        + "evaluated normally");
        verify(dispositionService).persist(eq(778L), eq(List.of(expected)));
        verifyNoMoreInteractions(dispositionService);

        verify(jobRunService).startDispositionAnchorRun(1);

        verify(evaluationService).submit(eq(List.of(skyTask)),
                eq(BatchTriggerSource.SCHEDULED), ArgumentMatchers.isNull());
        verifyNoMoreInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitForecastBatch: a successful bucket logs [BATCH DIAG] Submitted at WARN "
            + "with the batch id, and the trailing summary reports 1/1 buckets submitted")
    void submitForecastBatch_successfulBucket_logsSubmittedWithBatchId() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast nearInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(nearInlandTask), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of()));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED),
                ArgumentMatchers.isNull()))
                .thenReturn(new EvaluationHandle(7L, "msgbatch_honest_ok", 1));

        service.submitForecastBatch();

        assertThat(logAppender.list)
                .filteredOn(event -> event.getFormattedMessage().startsWith("[BATCH DIAG]"))
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .startsWith("[BATCH DIAG] Submitted 1 near-term inland requests "
                                    + "(batchId=msgbatch_honest_ok)");
                })
                .noneSatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("NOT submitted"));

        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("submitted 1/1 buckets");
        });
    }

    @Test
    @DisplayName("submitForecastBatch: a bucket whose submission failed logs [BATCH DIAG] NOT "
            + "submitted at ERROR rather than the WARN \"Submitted\" line — the 2026-09-29 "
            + "incident's dishonest log")
    void submitForecastBatch_failedBucket_logsNotSubmittedAtError() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast nearInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(nearInlandTask), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of()));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED),
                ArgumentMatchers.isNull()))
                .thenReturn(EvaluationHandle.empty());

        service.submitForecastBatch();

        assertThat(logAppender.list)
                .filteredOn(event -> event.getFormattedMessage().startsWith("[BATCH DIAG]"))
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage())
                            .startsWith("[BATCH DIAG] NOT submitted 1 near-term inland requests "
                                    + "(submission failed)");
                })
                .noneSatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("Submitted 1 near-term inland"));

        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("submitted 0/1 buckets");
        });
    }

    @Test
    @DisplayName("submitForecastBatch: a partial failure (one bucket ok, one fails) reports "
            + "1/2 buckets submitted in the trailing summary")
    void submitForecastBatch_partialFailure_reportsPartialBucketCount() {
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
                        List.of(), List.of(), List.of(), List.of(), List.of()));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED),
                ArgumentMatchers.isNull()))
                .thenReturn(new EvaluationHandle(7L, "msgbatch_partial_ok", 1))
                .thenReturn(EvaluationHandle.empty());

        service.submitForecastBatch();

        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .contains("total 2 requests")
                    .contains("submitted 1/2 buckets");
        });
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

    @Test
    @DisplayName("submitScheduledBatchForRegions: a bucket that HAD tasks but failed to submit "
            + "logs \"(failed)\", never \"(empty)\" — that reading was indistinguishable from a "
            + "bucket with no tasks at all")
    void submitScheduledBatchForRegions_oneBucketFails_logsFailedNotEmpty() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast inlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast coastalTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        when(forecastTaskCollector.collectRegionFilteredBatches(any()))
                .thenReturn(new RegionFilteredBatchTasks(
                        List.of(inlandTask), List.of(coastalTask)));
        // Inland is submitted first in the production code, and fails; coastal succeeds.
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.ADMIN)))
                .thenReturn(EvaluationHandle.empty())
                .thenReturn(new EvaluationHandle(null, "msgbatch_coastal_ok", 1));

        service.submitScheduledBatchForRegions(List.of(1L));

        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo(
                    "[BATCH DIAG] Admin batch split: 1 inland in (failed), "
                            + "1 coastal in msgbatch_coastal_ok");
        });
    }

    @Test
    @DisplayName("submitScheduledBatchForRegions: inland and coastal both submit ok → "
            + "returns the INLAND result")
    void submitScheduledBatchForRegions_bothOk_prefersInland() {
        LocationEntity inlandLocation = buildLocation("Durham UK");
        LocationEntity coastalLocation = buildLocation("Bamburgh");
        EvaluationTask.Forecast inlandTask = new EvaluationTask.Forecast(
                inlandLocation, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast coastalTask = new EvaluationTask.Forecast(
                coastalLocation, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        when(forecastTaskCollector.collectRegionFilteredBatches(any()))
                .thenReturn(new RegionFilteredBatchTasks(
                        List.of(inlandTask), List.of(coastalTask)));
        when(evaluationService.submit(eq(List.of(inlandTask)), eq(BatchTriggerSource.ADMIN)))
                .thenReturn(new EvaluationHandle(200L, "msgbatch_inland", 1));
        when(evaluationService.submit(eq(List.of(coastalTask)), eq(BatchTriggerSource.ADMIN)))
                .thenReturn(new EvaluationHandle(201L, "msgbatch_coastal", 1));

        BatchSubmitResult result = service.submitScheduledBatchForRegions(List.of(1L));

        assertThat(result).isNotNull();
        assertThat(result.jobRunId()).isEqualTo(200L);
        assertThat(result.batchId()).isEqualTo("msgbatch_inland");
        assertThat(result.requestCount()).isEqualTo(1);
        verify(evaluationService).submit(eq(List.of(inlandTask)), eq(BatchTriggerSource.ADMIN));
        verify(evaluationService).submit(eq(List.of(coastalTask)), eq(BatchTriggerSource.ADMIN));
    }

    @Test
    @DisplayName("submitScheduledBatchForRegions: inland submission fails, coastal ok → "
            + "returns the COASTAL result, not null (regression for the hidden-batch bug)")
    void submitScheduledBatchForRegions_inlandFailedCoastalOk_returnsCoastal() {
        LocationEntity inlandLocation = buildLocation("Durham UK");
        LocationEntity coastalLocation = buildLocation("Bamburgh");
        EvaluationTask.Forecast inlandTask = new EvaluationTask.Forecast(
                inlandLocation, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast coastalTask = new EvaluationTask.Forecast(
                coastalLocation, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        when(forecastTaskCollector.collectRegionFilteredBatches(any()))
                .thenReturn(new RegionFilteredBatchTasks(
                        List.of(inlandTask), List.of(coastalTask)));
        when(evaluationService.submit(eq(List.of(inlandTask)), eq(BatchTriggerSource.ADMIN)))
                .thenReturn(EvaluationHandle.empty());
        when(evaluationService.submit(eq(List.of(coastalTask)), eq(BatchTriggerSource.ADMIN)))
                .thenReturn(new EvaluationHandle(301L, "msgbatch_coastal_only", 1));

        BatchSubmitResult result = service.submitScheduledBatchForRegions(List.of(1L));

        assertThat(result).isNotNull();
        assertThat(result.jobRunId()).isEqualTo(301L);
        assertThat(result.batchId()).isEqualTo("msgbatch_coastal_only");
        assertThat(result.requestCount()).isEqualTo(1);
        verify(evaluationService).submit(eq(List.of(inlandTask)), eq(BatchTriggerSource.ADMIN));
        verify(evaluationService).submit(eq(List.of(coastalTask)), eq(BatchTriggerSource.ADMIN));
    }

    @Test
    @DisplayName("submitScheduledBatchForRegions: both inland and coastal submissions fail → "
            + "returns null, but both buckets were still submitted")
    void submitScheduledBatchForRegions_bothFailed_returnsNull() {
        LocationEntity inlandLocation = buildLocation("Durham UK");
        LocationEntity coastalLocation = buildLocation("Bamburgh");
        EvaluationTask.Forecast inlandTask = new EvaluationTask.Forecast(
                inlandLocation, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast coastalTask = new EvaluationTask.Forecast(
                coastalLocation, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        when(forecastTaskCollector.collectRegionFilteredBatches(any()))
                .thenReturn(new RegionFilteredBatchTasks(
                        List.of(inlandTask), List.of(coastalTask)));
        when(evaluationService.submit(eq(List.of(inlandTask)), eq(BatchTriggerSource.ADMIN)))
                .thenReturn(EvaluationHandle.empty());
        when(evaluationService.submit(eq(List.of(coastalTask)), eq(BatchTriggerSource.ADMIN)))
                .thenReturn(EvaluationHandle.empty());

        BatchSubmitResult result = service.submitScheduledBatchForRegions(List.of(1L));

        assertThat(result).isNull();
        verify(evaluationService).submit(eq(List.of(inlandTask)), eq(BatchTriggerSource.ADMIN));
        verify(evaluationService).submit(eq(List.of(coastalTask)), eq(BatchTriggerSource.ADMIN));
    }

    @Test
    @DisplayName("submitScheduledBatchForRegions: no inland tasks, coastal ok → "
            + "returns coastal, submit called exactly once")
    void submitScheduledBatchForRegions_inlandEmptyCoastalOk_submitsOnce() {
        LocationEntity coastalLocation = buildLocation("Bamburgh");
        EvaluationTask.Forecast coastalTask = new EvaluationTask.Forecast(
                coastalLocation, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        when(forecastTaskCollector.collectRegionFilteredBatches(any()))
                .thenReturn(new RegionFilteredBatchTasks(List.of(), List.of(coastalTask)));
        when(evaluationService.submit(eq(List.of(coastalTask)), eq(BatchTriggerSource.ADMIN)))
                .thenReturn(new EvaluationHandle(401L, "msgbatch_coastal_solo", 1));

        BatchSubmitResult result = service.submitScheduledBatchForRegions(List.of(1L));

        assertThat(result).isNotNull();
        assertThat(result.jobRunId()).isEqualTo(401L);
        assertThat(result.batchId()).isEqualTo("msgbatch_coastal_solo");
        assertThat(result.requestCount()).isEqualTo(1);
        verify(evaluationService).submit(eq(List.of(coastalTask)), eq(BatchTriggerSource.ADMIN));
        verifyNoMoreInteractions(evaluationService);
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
        // The task's label date comes from the injected clock, not the JVM's wall clock. It was a
        // bare LocalDate.now() — no zone at all — and nothing pins TZ in the Dockerfile, so it was
        // UTC by Alpine's default rather than by contract. Reverting it reads the real system
        // date, which cannot be CLOCK's. Note the limit: at midday UTC and London agree, so this
        // pins "uses the clock" and not "in Europe/London" — proportionate for a field that, on
        // this path, only names a custom ID.
        assertThat(captor.getValue().get(0).date()).isEqualTo(LocalDate.of(2026, 4, 14));
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

    // ── BatchSubmissionSummary — end to end via the 5-arg orchestrator entry point ─────────────

    @Test
    @DisplayName("submitForecastBatchForPipelineRun (5-arg, the orchestrator's own entry point) "
            + "returns a BatchSubmissionSummary whose bucketsAttempted/bucketsSubmitted/"
            + "failedBuckets/detail()/lostRequestCount()/allSucceeded() all match a real 3-bucket, "
            + "2-failure cycle — proven on the returned object itself, not only via the trailing "
            + "log line other tests already cover")
    void submitForecastBatchForPipelineRun_returnsAccurateBatchSubmissionSummary() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast nearInlandTaskA = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast nearInlandTaskB = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNSET,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast nearCoastalTask = new EvaluationTask.Forecast(
                location, TEST_DATE.plusDays(1), TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast farInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE.plusDays(2), TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        List<EvaluationTask.Forecast> nearInlandBucket = List.of(nearInlandTaskA, nearInlandTaskB);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        nearInlandBucket, List.of(nearCoastalTask), List.of(farInlandTask),
                        List.of(), List.of(), List.of(), List.of()));
        when(evaluationService.submit(eq(nearInlandBucket), eq(BatchTriggerSource.SCHEDULED),
                eq(99L)))
                .thenReturn(new EvaluationHandle(11L, "msgbatch_near_inland_ok", 2));
        when(evaluationService.submit(eq(List.of(nearCoastalTask)),
                eq(BatchTriggerSource.SCHEDULED), eq(99L)))
                .thenReturn(EvaluationHandle.empty());
        when(evaluationService.submit(eq(List.of(farInlandTask)),
                eq(BatchTriggerSource.SCHEDULED), eq(99L)))
                .thenReturn(EvaluationHandle.empty());
        Consumer<ReclassSummary> noOpHook = s -> { };

        ForecastBatchSubmissionOutcome outcome = service.submitForecastBatchForPipelineRun(
                99L, NightlyCandidateCollectionStrategy.INSTANCE, NightlyEligibilityPolicy.INSTANCE,
                false, noOpHook);

        assertThat(outcome.submitted()).isTrue();
        BatchSubmissionSummary summary = outcome.summary();
        assertThat(summary.bucketsAttempted()).isEqualTo(3);
        assertThat(summary.bucketsSubmitted()).isEqualTo(1);
        assertThat(summary.allSucceeded()).isFalse();
        assertThat(summary.lostRequestCount()).isEqualTo(2);
        assertThat(summary.failedBuckets()).containsExactlyInAnyOrder(
                new BatchSubmissionSummary.FailedBucket("near-term coastal", 1),
                new BatchSubmissionSummary.FailedBucket("far-term inland", 1));
        assertThat(summary.detail()).isEqualTo(
                "2 of 3 forecast batch submissions failed (2 requests not submitted — "
                        + "near-term coastal, far-term inland)");
    }

    @Test
    @DisplayName("submitForecastBatchForPipelineRun: a clean cycle (every bucket submitted) "
            + "returns a summary with an empty failedBuckets list, allSucceeded() true, "
            + "lostRequestCount() zero, and detail() null")
    void submitForecastBatchForPipelineRun_cleanCycle_summaryReportsAllSucceeded() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast nearInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(nearInlandTask), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of()));
        when(evaluationService.submit(eq(List.of(nearInlandTask)),
                eq(BatchTriggerSource.SCHEDULED), eq(100L)))
                .thenReturn(new EvaluationHandle(12L, "msgbatch_clean_ok", 1));

        ForecastBatchSubmissionOutcome outcome = service.submitForecastBatchForPipelineRun(
                100L, NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE, false, s -> { });

        assertThat(outcome.submitted()).isTrue();
        BatchSubmissionSummary summary = outcome.summary();
        assertThat(summary.bucketsAttempted()).isEqualTo(1);
        assertThat(summary.bucketsSubmitted()).isEqualTo(1);
        assertThat(summary.failedBuckets()).isEmpty();
        assertThat(summary.allSucceeded()).isTrue();
        assertThat(summary.lostRequestCount()).isZero();
        assertThat(summary.detail()).isNull();
    }

    // ── Same-cycle mix: one successful candidate, one failed candidate, same location ──────────

    @Test
    @DisplayName("a cycle with an EVALUATED candidate in a successful bucket (Durham SUNRISE, "
            + "near-term inland) next to a DIFFERENT EVALUATED candidate for the SAME location in "
            + "a failed bucket (Durham SUNSET, far-term inland) rewrites only the failed one — "
            + "proving the rewrite keys on the full (location, date, event) slot identity, not "
            + "merely on location")
    void submitForecastBatch_mixedSuccessAndFailureSameLocation_rewritesOnlyTheFailedSlot() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast nearInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        EvaluationTask.Forecast farInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE.plusDays(3), TargetType.SUNSET,
                EvaluationModel.HAIKU, buildAtmospheric(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        CandidateDisposition okDispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE, TargetType.SUNRISE, 0,
                DispositionCategory.EVALUATED, null);
        CandidateDisposition failDispo = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE.plusDays(3), TargetType.SUNSET, 3,
                DispositionCategory.EVALUATED, null);
        when(forecastTaskCollector.collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE,
                false))
                .thenReturn(new ScheduledBatchTasks(
                        List.of(nearInlandTask), List.of(), List.of(farInlandTask), List.of(),
                        List.of(), List.of(),
                        List.of(okDispo, failDispo)));
        when(evaluationService.submit(eq(List.of(nearInlandTask)),
                eq(BatchTriggerSource.SCHEDULED), ArgumentMatchers.isNull()))
                .thenReturn(new EvaluationHandle(15L, "msgbatch_mixed_ok", 1));
        when(evaluationService.submit(eq(List.of(farInlandTask)),
                eq(BatchTriggerSource.SCHEDULED), ArgumentMatchers.isNull()))
                .thenReturn(EvaluationHandle.empty());

        service.submitForecastBatch();

        CandidateDisposition expectedFail = new CandidateDisposition(
                42L, "Durham UK", TEST_DATE.plusDays(3), TargetType.SUNSET, 3,
                DispositionCategory.SUBMISSION_FAILED,
                "far-term inland batch submission failed");
        // Anchored to the SUCCESSFUL bucket's real job_run — proving the failed bucket did not
        // prevent the successful one from being attempted or from anchoring the cycle.
        verify(dispositionService).persist(eq(15L), eq(List.of(okDispo, expectedFail)));
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
