package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelinePhaseStatus;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunPhaseEntity;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.LocationFailureService;
import com.gregochr.goldenhour.service.ask.AskReadyPrecompute;
import com.gregochr.goldenhour.service.batch.BatchRetryService;
import com.gregochr.goldenhour.service.batch.BatchSubmissionSummary;
import com.gregochr.goldenhour.service.batch.ForecastBatchSubmissionOutcome;
import com.gregochr.goldenhour.service.batch.RetrySelection;
import com.gregochr.goldenhour.service.batch.RetrySubmitResult;
import com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Where the pipeline hands over to Ask PhotoCast's Ready precompute (plan §2.4, D-12): <b>after</b>
 * {@code finishRun}, on the background executor, and never able to reach the run. The run is a
 * pipeline cycle; a precompute that held it RUNNING would force every later tail settle to
 * {@code RESETS_ONLY} (CLAUDE.md, Job metrics).
 */
@ExtendWith(MockitoExtension.class)
class PipelineOrchestratorAskReadyTest {

    private static final Instant T0 = Instant.parse("2026-10-05T01:00:00Z");
    private static final Long RUN_ID = 42L;

    @Mock
    private PipelineRunService pipelineRunService;
    @Mock
    private ScheduledBatchEvaluationService scheduledBatchEvaluationService;
    @Mock
    private BriefingService briefingService;
    @Mock
    private ForecastBatchRepository forecastBatchRepository;
    @Mock
    private PipelineRunPickService pipelineRunPickService;
    @Mock
    private BatchRetryService batchRetryService;
    @Mock
    private AdminAlertService adminAlertService;
    @Mock
    private LocationFailureService locationFailureService;
    @Mock
    private AskReadyPrecompute askReadyPrecompute;

    @BeforeEach
    void setUp() {
        PipelineRunEntity run = run();
        lenient().when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(run);
        lenient().when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(run()));
        lenient().when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of());
        lenient().when(batchRetryService.selectFailures(any())).thenReturn(RetrySelection.none(5));
        lenient().when(batchRetryService.submitRetry(any(), any())).thenReturn(RetrySubmitResult.none());
        lenient().when(scheduledBatchEvaluationService.submitForecastBatchForPipelineRun(
                any(), any(), any(), anyBoolean(), any()))
                .thenReturn(ForecastBatchSubmissionOutcome.ran(BatchSubmissionSummary.allEmpty()));
    }

    private static PipelineRunEntity run() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0);
        run.setId(RUN_ID);
        return run;
    }

    private PipelineOrchestrator orchestrator(Executor executor, AskReadyPrecompute ask) {
        return new PipelineOrchestrator(pipelineRunService, scheduledBatchEvaluationService, briefingService,
                forecastBatchRepository, Clock.fixed(T0, ZoneOffset.UTC), executor, Duration.ofMillis(1),
                Duration.ofSeconds(10), null, pipelineRunPickService, batchRetryService, adminAlertService,
                locationFailureService, ask);
    }

    @Test
    @DisplayName("the run is COMPLETED before the precompute starts, and the precompute is handed this run's id")
    void completedBeforePrecomputeStarts() {
        orchestrator(Runnable::run, askReadyPrecompute).runNightlyCycle();

        InOrder order = inOrder(pipelineRunService, askReadyPrecompute);
        order.verify(pipelineRunService).completePhase(eq(RUN_ID), eq(PipelinePhase.BRIEFING), any());
        order.verify(pipelineRunService).completeRun(RUN_ID);
        order.verify(askReadyPrecompute).precompute(RUN_ID);
    }

    @Test
    @DisplayName("a DEGRADED run is finished too, and the precompute still follows it")
    void degradedRunIsFollowedByPrecompute() {
        PipelineRunPhaseEntity failed = new PipelineRunPhaseEntity(RUN_ID, PipelinePhase.FORECAST_BATCH_SUBMIT, 1, T0);
        failed.setStatus(PipelinePhaseStatus.FAILED);
        failed.setDetail("one bucket failed");
        when(pipelineRunService.findLatestPhase(RUN_ID, PipelinePhase.FORECAST_BATCH_SUBMIT))
                .thenReturn(Optional.of(failed));

        orchestrator(Runnable::run, askReadyPrecompute).runNightlyCycle();

        InOrder order = inOrder(pipelineRunService, askReadyPrecompute);
        order.verify(pipelineRunService).degradeRun(RUN_ID, "one bucket failed");
        order.verify(askReadyPrecompute).precompute(RUN_ID);
    }

    @Test
    @DisplayName("a precompute that throws cannot reach the run: it stays COMPLETED and is never failed")
    void precomputeThrowingLeavesTheRunCompleted() {
        when(askReadyPrecompute.precompute(RUN_ID)).thenThrow(new IllegalStateException("precompute broke"));

        orchestrator(Runnable::run, askReadyPrecompute).runNightlyCycle();

        verify(askReadyPrecompute).precompute(RUN_ID);
        verify(pipelineRunService).completeRun(RUN_ID);
        verify(pipelineRunService, never()).failRun(any(), any());
        verify(pipelineRunService, never()).degradeRun(any(), any());
    }

    @Test
    @DisplayName("an executor that refuses the precompute cannot reach the run either")
    void refusedDispatchLeavesTheRunCompleted() {
        Executor refusesAfterTheTail = new Executor() {
            private boolean first = true;

            @Override
            public void execute(Runnable command) {
                if (first) {
                    first = false;
                    command.run();
                    return;
                }
                throw new RejectedExecutionException("shut down");
            }
        };

        orchestrator(refusesAfterTheTail, askReadyPrecompute).runNightlyCycle();

        verify(pipelineRunService).completeRun(RUN_ID);
        verify(pipelineRunService, never()).failRun(any(), any());
        verifyNoInteractions(askReadyPrecompute);
    }

    @Test
    @DisplayName("a precompute that overruns does not hold the run open: the run is COMPLETED while the "
            + "precompute is still going, on another thread")
    void overrunningPrecomputeDoesNotHoldTheRun() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        doAnswer(inv -> {
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            finished.set(true);
            return new AskReadyPrecompute.Result(0, 0, 0, null);
        }).when(askReadyPrecompute).precompute(RUN_ID);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            orchestrator(executor, askReadyPrecompute).runNightlyCycle();

            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            verify(pipelineRunService, timeout(10_000)).completeRun(RUN_ID);
            assertThat(finished.get()).isFalse();
            verify(pipelineRunService, never()).failRun(any(), any());
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("no precompute follows a run that did not finish: a failed briefing marks the run FAILED and "
            + "dispatches nothing")
    void failedRunDispatchesNothing() {
        doThrow(new RuntimeException("briefing broke")).when(briefingService).refreshBriefing();

        orchestrator(Runnable::run, askReadyPrecompute).runNightlyCycle();

        verify(pipelineRunService).failRun(eq(RUN_ID), any());
        verify(pipelineRunService, never()).completeRun(RUN_ID);
        verifyNoInteractions(askReadyPrecompute);
    }

    @Test
    @DisplayName("with no precompute wired (tests, and the 13-argument constructor) the run finishes as before")
    void noPrecomputeWired() {
        orchestrator(Runnable::run, null).runNightlyCycle();

        verify(pipelineRunService).completeRun(RUN_ID);
    }
}
