package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anthropic.models.messages.batches.BatchCreateParams;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixture;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixtures;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.evaluation.BatchRequestFactory;
import com.gregochr.goldenhour.service.evaluation.ClaudeBatchOutcome;
import com.gregochr.goldenhour.service.evaluation.SunsetEvaluationParser;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Branch tests for {@link SkyRatingEvalBatchService}: the synchronous-path registration, a single-model
 * submission, blank and failing orphan reclaims, results that point at no live run or fixture, and a batch that
 * answers every evaluation.
 */
class SkyRatingEvalBatchServiceBranchTest {

    private static final long POLL_TIMEOUT_SECONDS = 60;
    private static final int FIXTURES = SkyRatingEvalFixtures.ALL.size();
    private static final int RUNS = SkyRatingEvalService.DEFAULT_RUNS_PER_FIXTURE;

    private SkyRatingEvalService evalService;
    private SkyRatingEvalBatchClient batchClient;
    private BatchRequestFactory batchRequestFactory;
    private DynamicSchedulerService scheduler;
    private SunsetEvaluationParser parser;
    private ObjectMapper objectMapper;
    private SkyRatingEvalBatchService service;

    @BeforeEach
    void setUp() {
        evalService = mock(SkyRatingEvalService.class);
        batchClient = mock(SkyRatingEvalBatchClient.class);
        batchRequestFactory = mock(BatchRequestFactory.class);
        scheduler = mock(DynamicSchedulerService.class);
        parser = mock(SunsetEvaluationParser.class);
        objectMapper = mock(ObjectMapper.class);
        service = new SkyRatingEvalBatchService(evalService, batchClient, batchRequestFactory, scheduler, parser,
                objectMapper, true, POLL_TIMEOUT_SECONDS);
    }

    private static SkyRatingEvalRunEntity run(long id, String batchId, LocalDateTime startedAt) {
        return SkyRatingEvalRunEntity.builder().id(id).model(EvaluationModel.HAIKU).runsPerFixture(RUNS)
                .status(SkyRatingEvalStatus.RUNNING).batchId(batchId).startedAt(startedAt).build();
    }

    private static LocalDateTime staleStart() {
        return LocalDateTime.now(ZoneOffset.UTC).minusSeconds(POLL_TIMEOUT_SECONDS * 4);
    }

    private static long millis(LocalDateTime startedAt) {
        return startedAt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    @Test
    @DisplayName("with batching off the weekly job target is the synchronous eval run")
    void registerJob_batchingOff_registersTheSynchronousRun() {
        SkyRatingEvalBatchService synchronous = new SkyRatingEvalBatchService(evalService, batchClient,
                batchRequestFactory, scheduler, parser, objectMapper, false, POLL_TIMEOUT_SECONDS);

        synchronous.registerJob();

        ArgumentCaptor<Runnable> target = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).registerJobTarget(eq(SkyRatingEvalService.JOB_KEY), target.capture());
        target.getValue().run();
        verify(evalService).runScheduled();
    }

    @Test
    @DisplayName("a single-model batch is reported as '1 model' and submits one request per fixture run")
    @SuppressWarnings("unchecked")
    void runScheduledBatched_singleModel_reportsOneModel() {
        SkyRatingEvalRunEntity started = run(100L, null, null);
        BatchCreateParams.Request request = mock(BatchCreateParams.Request.class);
        when(evalService.scheduledModels()).thenReturn(List.of(EvaluationModel.HAIKU));
        when(evalService.startRun(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, RUNS)).thenReturn(started);
        for (int f = 0; f < FIXTURES; f++) {
            AtmosphericData data = SkyRatingEvalFixtures.load(SkyRatingEvalFixtures.ALL.get(f));
            for (int r = 1; r <= RUNS; r++) {
                when(batchRequestFactory.buildForecastRequest(SkyRatingEvalBatchService.customId(100L, f, r),
                        EvaluationModel.HAIKU, data, EvaluationModel.HAIKU.getMaxTokens())).thenReturn(request);
            }
        }
        ArgumentCaptor<List<BatchCreateParams.Request>> submitted = ArgumentCaptor.forClass(List.class);
        when(batchClient.submit(submitted.capture())).thenReturn("batch-1");
        AtomicReference<List<SkyRatingEvalRunEntity>> runs = new AtomicReference<>();

        SchedulerFireOutcome.Report report = SchedulerFireOutcome.capture(
                () -> runs.set(service.runScheduledBatched()));

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).isEqualTo("Batch submitted for 1 model");
        assertThat(runs.get()).containsExactly(started);
        assertThat(submitted.getValue()).hasSize(FIXTURES * RUNS).containsOnly(request);
        verify(evalService).attachBatchId(started, "batch-1");
    }

    @Test
    @DisplayName("a RUNNING run with a blank batch id and an old start is failed as an orphan")
    void reconcile_blankBatchId_orphanFailed() {
        LocalDateTime started = staleStart();
        SkyRatingEvalRunEntity orphan = run(5L, "  ", started);
        when(evalService.findRunning()).thenReturn(List.of(orphan));

        SchedulerFireOutcome.Report report = SchedulerFireOutcome.capture(service::reconcilePendingBatches);

        ArgumentCaptor<SkyRatingEvalService.Aggregate> agg =
                ArgumentCaptor.forClass(SkyRatingEvalService.Aggregate.class);
        verify(evalService).finalise(eq(orphan), agg.capture(), eq(SkyRatingEvalStatus.FAILED),
                eq("Orphaned RUNNING run with no batch id (submission likely died before the id was persisted)"),
                eq(millis(started)));
        assertThat(agg.getValue().failedCount()).isZero();
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).isEqualTo("0 batches checked");
    }

    @Test
    @DisplayName("an orphan whose reclaim throws is counted as a failure and the tick reports FAILED")
    void reconcile_orphanReclaimThrows_reportsFailed() {
        LocalDateTime started = staleStart();
        SkyRatingEvalRunEntity orphan = run(5L, null, started);
        when(evalService.findRunning()).thenReturn(List.of(orphan));
        ArgumentCaptor<SkyRatingEvalService.Aggregate> agg =
                ArgumentCaptor.forClass(SkyRatingEvalService.Aggregate.class);
        doThrow(new IllegalStateException("db down")).when(evalService).finalise(eq(orphan), agg.capture(),
                eq(SkyRatingEvalStatus.FAILED),
                eq("Orphaned RUNNING run with no batch id (submission likely died before the id was persisted)"),
                eq(millis(started)));

        SchedulerFireOutcome.Report report = SchedulerFireOutcome.capture(service::reconcilePendingBatches);

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(report.summary()).isEqualTo("0 batches checked · 1 failed");
    }

    @Test
    @DisplayName("results for an unknown run or a fixture index past the list are skipped; unanswered slots fail it")
    void processResults_unmatchedResults_runFailsAllMissing() {
        LocalDateTime started = LocalDateTime.now(ZoneOffset.UTC);
        SkyRatingEvalRunEntity live = run(100L, "batch-1", started);
        when(batchClient.collectResults("batch-1")).thenReturn(List.of(
                new ClaudeBatchOutcome("e_999_0_1", true, "succeeded", null, null, "{}", null, null),
                new ClaudeBatchOutcome("e_100_" + FIXTURES + "_1", true, "succeeded", null, null, "{}", null, null),
                new ClaudeBatchOutcome("x_100_0_1", true, "succeeded", null, null, "{}", null, null)));

        service.processResults("batch-1", List.of(live));

        verify(evalService).deleteResultsForRun(100L);
        ArgumentCaptor<SkyRatingEvalService.Aggregate> agg =
                ArgumentCaptor.forClass(SkyRatingEvalService.Aggregate.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(evalService).finalise(eq(live), agg.capture(), eq(SkyRatingEvalStatus.FAILED), message.capture(),
                eq(millis(started)));
        assertThat(agg.getValue().failedCount()).isEqualTo(FIXTURES * RUNS);
        assertThat(message.getValue()).startsWith("Every evaluation failed: " + (FIXTURES * RUNS) + " of "
                + (FIXTURES * RUNS));
        for (int f = 0; f < FIXTURES; f++) {
            verify(evalService, times(1)).persistFailureRow(live, SkyRatingEvalFixtures.ALL.get(f), 1, "MISSING",
                    null);
        }
    }

    @Test
    @DisplayName("a batch that answers every evaluation finalises its run COMPLETED with no error message")
    void processResults_everyEvaluationAnswered_completed() {
        LocalDateTime started = LocalDateTime.now(ZoneOffset.UTC);
        SkyRatingEvalRunEntity live = run(100L, "batch-1", started);
        SunsetEvaluation evaluation = mock(SunsetEvaluation.class);
        List<ClaudeBatchOutcome> outcomes = new ArrayList<>();
        for (int f = 0; f < FIXTURES; f++) {
            for (int r = 1; r <= RUNS; r++) {
                outcomes.add(new ClaudeBatchOutcome(SkyRatingEvalBatchService.customId(100L, f, r), true,
                        "succeeded", null, null, "raw-" + f + "-" + r, null, null));
                when(parser.parseEvaluation("raw-" + f + "-" + r, objectMapper)).thenReturn(evaluation);
            }
        }
        when(batchClient.collectResults("batch-1")).thenReturn(outcomes);

        service.processResults("batch-1", List.of(live));

        ArgumentCaptor<SkyRatingEvalService.Aggregate> agg =
                ArgumentCaptor.forClass(SkyRatingEvalService.Aggregate.class);
        for (int f = 0; f < FIXTURES; f++) {
            SkyRatingEvalFixture fixture = SkyRatingEvalFixtures.ALL.get(f);
            for (int r = 1; r <= RUNS; r++) {
                verify(evalService).persistResult(eq(live), eq(fixture), eq(r), eq(evaluation), isNull(),
                        isNull(), eq(true), agg.capture());
            }
        }
        verify(evalService).finalise(eq(live), agg.capture(), eq(SkyRatingEvalStatus.COMPLETED), isNull(),
                eq(millis(started)));
        assertThat(agg.getAllValues()).allSatisfy(a -> assertThat(a.failedCount()).isZero());
    }

    @Test
    @DisplayName("a four-part custom id without the eval prefix does not parse")
    void parseCustomId_wrongPrefix_null() {
        assertThat(SkyRatingEvalBatchService.parseCustomId("x_1_2_3")).isNull();
    }
}
