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
import com.gregochr.goldenhour.service.evaluation.ClaudeEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * custom-id round-tripping, result→origin mapping, batch-discount persistence, run finalisation, and
 * the submission-failure path — without any real Claude calls or threads.
 */
class SkyRatingEvalBatchServiceTest {

    private SkyRatingEvalService evalService;
    private SkyRatingEvalBatchClient batchClient;
    private BatchRequestFactory batchRequestFactory;
    private DynamicSchedulerService dynamicSchedulerService;
    private ClaudeEvaluationStrategy parser;
    private ObjectMapper objectMapper;
    private SkyRatingEvalBatchService service;

    @BeforeEach
    void setUp() {
        evalService = mock(SkyRatingEvalService.class);
        batchClient = mock(SkyRatingEvalBatchClient.class);
        batchRequestFactory = mock(BatchRequestFactory.class);
        dynamicSchedulerService = mock(DynamicSchedulerService.class);
        parser = mock(ClaudeEvaluationStrategy.class);
        objectMapper = mock(ObjectMapper.class);
        Map<EvaluationModel, EvaluationStrategy> strategies = Map.of(EvaluationModel.HAIKU, parser);
        service = new SkyRatingEvalBatchService(evalService, batchClient, batchRequestFactory,
                dynamicSchedulerService, strategies, objectMapper, true, 60, 1);
    }

    @Test
    @DisplayName("registerJob registers the weekly job target with the scheduler")
    void registerJobRegistersTarget() {
        service.registerJob();
        verify(dynamicSchedulerService).registerJobTarget(eq(SkyRatingEvalService.JOB_KEY), any());
    }

    @Test
    @DisplayName("constructor rejects a non-Claude HAIKU strategy")
    void constructorRejectsNonClaudeParser() {
        Map<EvaluationModel, EvaluationStrategy> bad =
                Map.of(EvaluationModel.HAIKU, mock(EvaluationStrategy.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new SkyRatingEvalBatchService(evalService, batchClient, batchRequestFactory,
                        dynamicSchedulerService, bad, objectMapper, true, 60, 1))
                .isInstanceOf(IllegalStateException.class);
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
    @DisplayName("processBatch persists each success (batch cost) and finalises the run COMPLETED")
    void processBatchPersistsSuccessesAndFinalises() {
        SkyRatingEvalRunEntity run = runEntity(10L, EvaluationModel.SONNET);
        SkyRatingEvalBatchService.RunContext ctx =
                new SkyRatingEvalBatchService.RunContext(run, EvaluationModel.SONNET);
        Map<Long, SkyRatingEvalBatchService.RunContext> byRunId = Map.of(10L, ctx);

        TokenUsage usage = new TokenUsage(3_800, 180, 0, 0);
        when(batchClient.awaitEnded(any(), any(), any())).thenReturn(true);
        when(batchClient.collectResults("batch1")).thenReturn(List.of(
                ClaudeBatchOutcome.success("e_10_0_1", "{\"rating\":3}", usage, null),
                ClaudeBatchOutcome.success("e_10_0_2", "{\"rating\":3}", usage, null),
                ClaudeBatchOutcome.failure("e_10_1_1", "ERRORED", "batch_error", "nope"),
                ClaudeBatchOutcome.failure("junk", "ERRORED", "batch_error", "nope")));
        SunsetEvaluation eval = new SunsetEvaluation(3, 55, 60, "summary");
        when(parser.parseEvaluation(any(), any())).thenReturn(eval);

        service.processBatch("batch1", byRunId, 1_000L);

        // Two successful rows persisted with isBatch=true (50% cost) and no per-call duration.
        verify(evalService).persistResult(eq(run), eq(SkyRatingEvalFixtures.ALL.get(0)), eq(1),
                eq(eval), eq(usage), isNull(), eq(true), eq(ctx.agg()));
        verify(evalService).persistResult(eq(run), eq(SkyRatingEvalFixtures.ALL.get(0)), eq(2),
                eq(eval), eq(usage), isNull(), eq(true), eq(ctx.agg()));
        verify(evalService, times(2)).persistResult(any(), any(), anyInt(), any(), any(),
                isNull(), eq(true), any());
        verify(evalService).finalise(run, ctx.agg(), SkyRatingEvalStatus.COMPLETED, null, 1_000L);
    }

    @Test
    @DisplayName("processBatch finalises FAILED when the batch never ends")
    void processBatchTimesOutToFailed() {
        SkyRatingEvalRunEntity run = runEntity(11L, EvaluationModel.HAIKU);
        SkyRatingEvalBatchService.RunContext ctx =
                new SkyRatingEvalBatchService.RunContext(run, EvaluationModel.HAIKU);
        when(batchClient.awaitEnded(any(), any(), any())).thenReturn(false);

        service.processBatch("batch2", Map.of(11L, ctx), 2_000L);

        verify(evalService).finalise(eq(run), eq(ctx.agg()), eq(SkyRatingEvalStatus.FAILED),
                contains("did not end"), eq(2_000L));
        verify(evalService, never()).persistResult(any(), any(), anyInt(), any(), any(),
                any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
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
                        org.mockito.ArgumentMatchers.anyLong());
        verify(batchClient, never()).awaitEnded(any(), any(), any());
    }

    private static SkyRatingEvalRunEntity runEntity(long id, EvaluationModel model) {
        return SkyRatingEvalRunEntity.builder()
                .id(id)
                .model(model)
                .runsPerFixture(SkyRatingEvalService.DEFAULT_RUNS_PER_FIXTURE)
                .status(SkyRatingEvalStatus.RUNNING)
                .build();
    }
}
