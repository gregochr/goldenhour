package com.gregochr.goldenhour.service;

import com.anthropic.models.messages.batches.BatchCreateParams;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixtures;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.evaluation.BatchRequestFactory;
import com.gregochr.goldenhour.service.evaluation.ClaudeBatchOutcome;
import com.gregochr.goldenhour.service.evaluation.SunsetEvaluationParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SkyRatingEvalBatchService} — the Batch API orchestration around the eval.
 * The batch client, request factory, and persistence service are mocked, so the tests verify
 * custom-id round-tripping, batch-id persistence on submit, the restart-safe reconciler's branches
 * (ended → finalise COMPLETED, in-flight → leave, past-deadline → FAIL, orphan-without-batch → FAIL),
 * result→origin mapping, batch-discount persistence, and the submission-failure path — without any
 * real Claude calls or threads.
 */
class SkyRatingEvalBatchServiceTest {

    private static final long POLL_TIMEOUT_SECONDS = 60;

    private SkyRatingEvalService evalService;
    private SkyRatingEvalBatchClient batchClient;
    private BatchRequestFactory batchRequestFactory;
    private DynamicSchedulerService dynamicSchedulerService;
    private SunsetEvaluationParser parser;
    private ObjectMapper objectMapper;
    private SkyRatingEvalBatchService service;

    @BeforeEach
    void setUp() {
        evalService = mock(SkyRatingEvalService.class);
        batchClient = mock(SkyRatingEvalBatchClient.class);
        batchRequestFactory = mock(BatchRequestFactory.class);
        dynamicSchedulerService = mock(DynamicSchedulerService.class);
        parser = mock(SunsetEvaluationParser.class);
        objectMapper = mock(ObjectMapper.class);
        service = new SkyRatingEvalBatchService(evalService, batchClient, batchRequestFactory,
                dynamicSchedulerService, parser, objectMapper, true, POLL_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("registerJob registers both the weekly job and the reconciler targets")
    void registerJobRegistersBothTargets() {
        service.registerJob();
        verify(dynamicSchedulerService).registerJobTarget(eq(SkyRatingEvalService.JOB_KEY), any());
        verify(dynamicSchedulerService).registerJobTarget(
                eq(SkyRatingEvalBatchService.POLL_JOB_KEY), any());
    }

    @Test
    @DisplayName("customId and parseCustomId round-trip; malformed ids parse to null")
    void customIdRoundTrips() {
        assertThat(SkyRatingEvalBatchService.customId(7, 2, 5)).isEqualTo("e_7_2_5");

        SkyRatingEvalBatchService.ResultRef ref =
                SkyRatingEvalBatchService.parseCustomId("e_7_2_5");
        assertThat(ref).isNotNull();
        assertThat(ref.runId()).isEqualTo(7L);
        assertThat(ref.fixtureIdx()).isEqualTo(2);
        assertThat(ref.runIndex()).isEqualTo(5);

        assertThat(SkyRatingEvalBatchService.parseCustomId("garbage")).isNull();
        assertThat(SkyRatingEvalBatchService.parseCustomId("e_x_2_5")).isNull();
        assertThat(SkyRatingEvalBatchService.parseCustomId(null)).isNull();
    }

    @Test
    @DisplayName("runScheduledBatched submits once, persists the batch id on every run, and does not block")
    void runScheduledBatchedPersistsBatchIdWithoutBlocking() {
        AtomicLong ids = new AtomicLong(100);
        when(evalService.startRun(any(), eq(SkyRatingEvalTrigger.SCHEDULED), anyInt()))
                .thenAnswer(inv -> runEntity(ids.getAndIncrement(), inv.getArgument(0)));
        when(batchRequestFactory.buildForecastRequest(any(), any(), any(), anyInt()))
                .thenReturn(mock(BatchCreateParams.Request.class));
        when(batchClient.submit(any())).thenReturn("batchX");

        List<SkyRatingEvalRunEntity> runs = service.runScheduledBatched();

        assertThat(runs).hasSize(SkyRatingEvalService.SCHEDULED_MODELS.size());
        verify(batchClient).submit(any());
        verify(evalService, times(SkyRatingEvalService.SCHEDULED_MODELS.size()))
                .attachBatchId(any(), eq("batchX"));
        // No synchronous await/collect — the reconciler owns completion.
        verify(batchClient, never()).isEnded(any());
        verify(batchClient, never()).collectResults(any());
        verify(evalService, never()).finalise(any(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("runScheduledBatched marks all runs FAILED synchronously when submission throws")
    void runScheduledBatchedSubmissionFailureFinalisesFailed() {
        AtomicLong ids = new AtomicLong(100);
        when(evalService.startRun(any(), eq(SkyRatingEvalTrigger.SCHEDULED), anyInt()))
                .thenAnswer(inv -> runEntity(ids.getAndIncrement(), inv.getArgument(0)));
        when(batchRequestFactory.buildForecastRequest(any(), any(), any(), anyInt()))
                .thenReturn(mock(BatchCreateParams.Request.class));
        when(batchClient.submit(any())).thenThrow(new RuntimeException("submit boom"));

        List<SkyRatingEvalRunEntity> runs = service.runScheduledBatched();

        assertThat(runs).hasSize(SkyRatingEvalService.SCHEDULED_MODELS.size());
        verify(evalService, times(SkyRatingEvalService.SCHEDULED_MODELS.size()))
                .finalise(any(), any(), eq(SkyRatingEvalStatus.FAILED), contains("submit boom"),
                        anyLong());
        verify(evalService, never()).attachBatchId(any(), any());
        verify(batchClient, never()).isEnded(any());
    }

    @Test
    @DisplayName("processResults clears prior rows, persists each success (batch cost), finalises COMPLETED")
    void processResultsPersistsSuccessesAndFinalises() {
        SkyRatingEvalRunEntity run = runningRun(10L, EvaluationModel.SONNET, "batch1", recentStart());

        TokenUsage usage = new TokenUsage(3_800, 180, 0, 0);
        when(batchClient.collectResults("batch1")).thenReturn(List.of(
                ClaudeBatchOutcome.success("e_10_0_1", "{\"rating\":3}", usage, null),
                ClaudeBatchOutcome.success("e_10_0_2", "{\"rating\":3}", usage, null),
                ClaudeBatchOutcome.failure("e_10_1_1", "ERRORED", "batch_error", "nope"),
                ClaudeBatchOutcome.failure("junk", "ERRORED", "batch_error", "nope")));
        SunsetEvaluation eval = new SunsetEvaluation(3, 55, 60, "summary");
        when(parser.parseEvaluation(any(), any())).thenReturn(eval);

        service.processResults("batch1", List.of(run));

        // Prior rows cleared for idempotency, then two successes persisted with isBatch=true and no
        // per-call duration; the two failures and the unparseable id are skipped.
        verify(evalService).deleteResultsForRun(10L);
        verify(evalService).persistResult(eq(run), eq(SkyRatingEvalFixtures.ALL.get(0)), eq(1),
                eq(eval), eq(usage), isNull(), eq(true), any());
        verify(evalService).persistResult(eq(run), eq(SkyRatingEvalFixtures.ALL.get(0)), eq(2),
                eq(eval), eq(usage), isNull(), eq(true), any());
        verify(evalService, times(2)).persistResult(any(), any(), anyInt(), any(), any(),
                isNull(), eq(true), any());
        verify(evalService).finalise(eq(run), any(), eq(SkyRatingEvalStatus.COMPLETED), isNull(),
                anyLong());
    }

    @Test
    @DisplayName("reconcile finalises an ENDED batch's run COMPLETED via processResults")
    void reconcileProcessesEndedBatch() {
        SkyRatingEvalRunEntity run = runningRun(20L, EvaluationModel.HAIKU, "b-ended", recentStart());
        when(evalService.findRunning()).thenReturn(List.of(run));
        when(batchClient.isEnded("b-ended")).thenReturn(true);
        when(batchClient.collectResults("b-ended")).thenReturn(List.of(
                ClaudeBatchOutcome.success("e_20_0_1", "{\"rating\":4}",
                        new TokenUsage(3_800, 180, 0, 0), null)));
        when(parser.parseEvaluation(any(), any())).thenReturn(new SunsetEvaluation(4, 55, 60, "s"));

        service.reconcilePendingBatches();

        verify(batchClient).collectResults("b-ended");
        verify(evalService).persistResult(eq(run), any(), eq(1), any(), any(), isNull(), eq(true),
                any());
        verify(evalService).finalise(eq(run), any(), eq(SkyRatingEvalStatus.COMPLETED), isNull(),
                anyLong());
    }

    @Test
    @DisplayName("reconcile leaves an in-flight batch RUNNING when it has not ended within the deadline")
    void reconcileLeavesInFlightBatchRunning() {
        SkyRatingEvalRunEntity run = runningRun(30L, EvaluationModel.OPUS, "b-slow", recentStart());
        when(evalService.findRunning()).thenReturn(List.of(run));
        when(batchClient.isEnded("b-slow")).thenReturn(false);

        service.reconcilePendingBatches();

        verify(batchClient, never()).collectResults(any());
        verify(evalService, never()).finalise(any(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("reconcile fails a batch that has not ended once its runs are past the deadline")
    void reconcileFailsBatchPastDeadline() {
        SkyRatingEvalRunEntity run = runningRun(40L, EvaluationModel.SONNET, "b-stuck", staleStart());
        when(evalService.findRunning()).thenReturn(List.of(run));
        when(batchClient.isEnded("b-stuck")).thenReturn(false);

        service.reconcilePendingBatches();

        verify(evalService).finalise(eq(run), any(), eq(SkyRatingEvalStatus.FAILED),
                contains("did not end"), anyLong());
        verify(batchClient, never()).collectResults(any());
    }

    @Test
    @DisplayName("reconcile reclaims a past-deadline RUNNING run that never recorded a batch id")
    void reconcileReclaimsOrphanWithoutBatchId() {
        SkyRatingEvalRunEntity orphan = runningRun(50L, EvaluationModel.HAIKU, null, staleStart());
        when(evalService.findRunning()).thenReturn(List.of(orphan));

        service.reconcilePendingBatches();

        verify(evalService).finalise(eq(orphan), any(), eq(SkyRatingEvalStatus.FAILED),
                contains("no batch id"), anyLong());
        verify(batchClient, never()).isEnded(any());
    }

    @Test
    @DisplayName("reconcile leaves a fresh RUNNING run without a batch id alone (id lands momentarily)")
    void reconcileLeavesFreshOrphanWithoutBatchId() {
        SkyRatingEvalRunEntity fresh = runningRun(60L, EvaluationModel.HAIKU, null, recentStart());
        when(evalService.findRunning()).thenReturn(List.of(fresh));

        service.reconcilePendingBatches();

        verify(evalService, never()).finalise(any(), any(), any(), any(), anyLong());
        verify(batchClient, never()).isEnded(any());
    }

    @Test
    @DisplayName("reconcile swallows a status-check error and retries (no finalise) while within deadline")
    void reconcileSwallowsStatusCheckErrorWithinDeadline() {
        SkyRatingEvalRunEntity run = runningRun(70L, EvaluationModel.OPUS, "b-err", recentStart());
        when(evalService.findRunning()).thenReturn(List.of(run));
        when(batchClient.isEnded("b-err")).thenThrow(new RuntimeException("api down"));

        service.reconcilePendingBatches();

        verify(evalService, never()).finalise(any(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("reconcile fails a batch whose status check keeps erroring past the deadline")
    void reconcileFailsBatchWhenStatusCheckErrorsPastDeadline() {
        SkyRatingEvalRunEntity run = runningRun(80L, EvaluationModel.OPUS, "b-err", staleStart());
        when(evalService.findRunning()).thenReturn(List.of(run));
        when(batchClient.isEnded("b-err")).thenThrow(new RuntimeException("api down"));

        service.reconcilePendingBatches();

        verify(evalService).finalise(eq(run), any(), eq(SkyRatingEvalStatus.FAILED),
                contains("status check failed"), anyLong());
    }

    @Test
    @DisplayName("reconcile is a no-op when nothing is RUNNING")
    void reconcileNoOpWhenNothingRunning() {
        when(evalService.findRunning()).thenReturn(List.of());

        service.reconcilePendingBatches();

        verify(batchClient, never()).isEnded(any());
        verify(evalService, never()).finalise(any(), any(), any(), any(), anyLong());
        verify(evalService, never()).persistResult(any(), any(), anyInt(), any(), any(), any(),
                anyBoolean(), any());
    }

    private static LocalDateTime recentStart() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** A start well beyond the {@value #POLL_TIMEOUT_SECONDS}s test deadline. */
    private static LocalDateTime staleStart() {
        return LocalDateTime.now(ZoneOffset.UTC).minusSeconds(POLL_TIMEOUT_SECONDS * 4);
    }

    private static SkyRatingEvalRunEntity runEntity(long id, EvaluationModel model) {
        return SkyRatingEvalRunEntity.builder()
                .id(id)
                .model(model)
                .runsPerFixture(SkyRatingEvalService.DEFAULT_RUNS_PER_FIXTURE)
                .status(SkyRatingEvalStatus.RUNNING)
                .build();
    }

    private static SkyRatingEvalRunEntity runningRun(long id, EvaluationModel model, String batchId,
            LocalDateTime startedAt) {
        return SkyRatingEvalRunEntity.builder()
                .id(id)
                .model(model)
                .runsPerFixture(SkyRatingEvalService.DEFAULT_RUNS_PER_FIXTURE)
                .status(SkyRatingEvalStatus.RUNNING)
                .batchId(batchId)
                .startedAt(startedAt)
                .build();
    }
}
