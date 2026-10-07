package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelinePhaseStatus;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunPhaseEntity;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.LocationFailureService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Where the pipeline hands over to the "Coming up" feed's cache refresh: <b>after</b> {@code finishRun}, on
 * the background executor, and never able to reach the run — the same contract Ask PhotoCast's Ready
 * precompute has, and for the same reason (a RUNNING run forces every later tail settle to
 * {@code RESETS_ONLY}).
 */
@ExtendWith(MockitoExtension.class)
class PipelineOrchestratorAlmanacRefreshTest {

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
    private AlmanacService almanacService;

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

    private PipelineOrchestrator orchestrator(Executor executor, AlmanacService almanac) {
        return new PipelineOrchestrator(pipelineRunService, scheduledBatchEvaluationService, briefingService,
                forecastBatchRepository, Clock.fixed(T0, ZoneOffset.UTC), executor, Duration.ofMillis(1),
                Duration.ofSeconds(10), null, pipelineRunPickService, batchRetryService, adminAlertService,
                locationFailureService, null, almanac);
    }

    @Test
    @DisplayName("the run is COMPLETED before the feed refresh starts")
    void completedBeforeTheRefreshStarts() {
        orchestrator(Runnable::run, almanacService).runNightlyCycle();

        InOrder order = inOrder(pipelineRunService, almanacService);
        order.verify(pipelineRunService).completePhase(eq(RUN_ID), eq(PipelinePhase.BRIEFING), any());
        order.verify(pipelineRunService).completeRun(RUN_ID);
        order.verify(almanacService).refresh();
    }

    @Test
    @DisplayName("the refresh is queued on the executor, not run on the pipeline's own thread")
    void refreshIsHandedToTheExecutor() {
        List<Runnable> queued = new ArrayList<>();
        // The tail itself is queued by runNightlyCycle; run it, and keep what it queues afterwards.
        Executor firstRunsThenQueues = new Executor() {
            private boolean first = true;

            @Override
            public void execute(Runnable command) {
                if (first) {
                    first = false;
                    command.run();
                } else {
                    queued.add(command);
                }
            }
        };

        orchestrator(firstRunsThenQueues, almanacService).runNightlyCycle();

        verify(pipelineRunService).completeRun(RUN_ID);
        verifyNoInteractions(almanacService);
        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        verify(almanacService).refresh();
    }

    @Test
    @DisplayName("a DEGRADED run is finished too, and the refresh still follows it")
    void degradedRunIsFollowedByARefresh() {
        PipelineRunPhaseEntity failed = new PipelineRunPhaseEntity(RUN_ID, PipelinePhase.FORECAST_BATCH_SUBMIT, 1, T0);
        failed.setStatus(PipelinePhaseStatus.FAILED);
        failed.setDetail("one bucket failed");
        when(pipelineRunService.findLatestPhase(RUN_ID, PipelinePhase.FORECAST_BATCH_SUBMIT))
                .thenReturn(Optional.of(failed));

        orchestrator(Runnable::run, almanacService).runNightlyCycle();

        InOrder order = inOrder(pipelineRunService, almanacService);
        order.verify(pipelineRunService).degradeRun(RUN_ID, "one bucket failed");
        order.verify(almanacService).refresh();
    }

    @Test
    @DisplayName("a refresh that throws cannot reach the run: it stays COMPLETED and is never failed")
    void refreshThrowingLeavesTheRunCompleted() {
        when(almanacService.refresh()).thenThrow(new IllegalStateException("build broke"));

        orchestrator(Runnable::run, almanacService).runNightlyCycle();

        verify(almanacService).refresh();
        verify(pipelineRunService).completeRun(RUN_ID);
        verify(pipelineRunService, never()).failRun(any(), any());
        verify(pipelineRunService, never()).degradeRun(any(), any());
    }

    @Test
    @DisplayName("an executor that refuses the refresh cannot reach the run either")
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

        orchestrator(refusesAfterTheTail, almanacService).runNightlyCycle();

        verify(pipelineRunService).completeRun(RUN_ID);
        verify(pipelineRunService, never()).failRun(any(), any());
        verifyNoInteractions(almanacService);
    }

    @Test
    @DisplayName("no refresh follows a run that did not finish: a failed briefing marks the run FAILED and "
            + "refreshes nothing")
    void failedRunRefreshesNothing() {
        doThrow(new RuntimeException("briefing broke")).when(briefingService).refreshBriefing();

        orchestrator(Runnable::run, almanacService).runNightlyCycle();

        verify(pipelineRunService).failRun(eq(RUN_ID), any());
        verify(pipelineRunService, never()).completeRun(RUN_ID);
        verifyNoInteractions(almanacService);
    }

    @Test
    @DisplayName("with no feed service wired (the shorter constructors) the run finishes as before")
    void noAlmanacWired() {
        orchestrator(Runnable::run, null).runNightlyCycle();

        verify(pipelineRunService).completeRun(RUN_ID);
    }

}
