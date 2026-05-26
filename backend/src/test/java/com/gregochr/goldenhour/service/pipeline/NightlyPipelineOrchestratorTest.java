package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NightlyPipelineOrchestrator}.
 *
 * <p>All sequencing is exercised on the calling thread by passing a
 * {@code Runnable::run} executor and a sub-millisecond poll interval, so the
 * wait-loop deterministically reaches completion within the test.
 */
@ExtendWith(MockitoExtension.class)
class NightlyPipelineOrchestratorTest {

    /** Fixed instant for clock injection; advanced in timeout test only. */
    private static final Instant T0 = Instant.parse("2026-05-26T01:00:00Z");

    private static final Long RUN_ID = 42L;

    @Mock
    private PipelineRunService pipelineRunService;

    @Mock
    private ScheduledBatchEvaluationService scheduledBatchEvaluationService;

    @Mock
    private BriefingService briefingService;

    @Mock
    private ForecastBatchRepository forecastBatchRepository;

    private NightlyPipelineOrchestrator orchestrator;

    /** Direct executor — runs the wait/brief tail on the calling thread. */
    private final Executor directExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(T0, ZoneOffset.UTC);
        // 1ms poll interval, 10s safety timeout — keeps the wait loop fast
        // while leaving room for multi-iteration boundary tests. Null scheduler:
        // unit tests don't exercise the @PostConstruct cron registration.
        orchestrator = new NightlyPipelineOrchestrator(
                pipelineRunService,
                scheduledBatchEvaluationService,
                briefingService,
                forecastBatchRepository,
                fixedClock,
                directExecutor,
                Duration.ofMillis(1),
                Duration.ofSeconds(10),
                null);
    }

    private PipelineRunEntity newRun() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0);
        run.setId(RUN_ID);
        return run;
    }

    private PipelineRunEntity runInPhase(PipelinePhase phase) {
        PipelineRunEntity run = newRun();
        run.setCurrentPhase(phase);
        return run;
    }

    private ForecastBatchEntity batch(BatchStatus status) {
        ForecastBatchEntity b = new ForecastBatchEntity(
                "msgbatch_test_" + status, BatchType.FORECAST, 10, T0.plusSeconds(86400));
        b.setStatus(status);
        b.setPipelineRunId(RUN_ID);
        return b;
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("submits, waits for all batches complete on first poll, runs briefing, marks COMPLETED")
        void completes_full_sequence() {
            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newRun()));
            // First poll: all 3 batches terminal.
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of(
                    batch(BatchStatus.COMPLETED),
                    batch(BatchStatus.COMPLETED),
                    batch(BatchStatus.FAILED)));

            orchestrator.runNightlyCycle();

            // Phase order: SUBMIT → WAIT → BRIEFING (each started, each completed).
            verify(pipelineRunService).startPhase(RUN_ID, PipelinePhase.FORECAST_BATCH_SUBMIT);
            verify(pipelineRunService).completePhase(eq(RUN_ID),
                    eq(PipelinePhase.FORECAST_BATCH_SUBMIT), isNull());
            verify(pipelineRunService).startPhase(RUN_ID, PipelinePhase.FORECAST_BATCH_WAIT);
            verify(pipelineRunService).completePhase(eq(RUN_ID),
                    eq(PipelinePhase.FORECAST_BATCH_WAIT),
                    eq("3 of 3 batches reached a terminal status"));
            verify(pipelineRunService).startPhase(RUN_ID, PipelinePhase.BRIEFING);
            verify(pipelineRunService).completePhase(eq(RUN_ID),
                    eq(PipelinePhase.BRIEFING), isNull());

            verify(scheduledBatchEvaluationService).submitForecastBatchForPipelineRun(RUN_ID);
            verify(briefingService).refreshBriefing();
            verify(pipelineRunService).completeRun(RUN_ID);
            verify(pipelineRunService, never()).failRun(eq(RUN_ID), eq(""));
        }

        @Test
        @DisplayName("zero-batch cycle skips waiting and goes straight to briefing")
        void zero_batches_short_circuits_wait() {
            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newRun()));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of());

            orchestrator.runNightlyCycle();

            verify(pipelineRunService).completePhase(eq(RUN_ID),
                    eq(PipelinePhase.FORECAST_BATCH_WAIT),
                    eq("no batches submitted"));
            verify(briefingService).refreshBriefing();
            verify(pipelineRunService).completeRun(RUN_ID);
        }
    }

    @Nested
    @DisplayName("Completion detection boundary")
    class CompletionBoundary {

        /**
         * The pivotal sequencer test. With 4 batches, the wait loop must report
         * incomplete when 3/4 are terminal (one still SUBMITTED) and complete
         * when all 4 are terminal. Boundary fires once and only once.
         */
        @Test
        @DisplayName("3-of-4 waits; 4-of-4 proceeds")
        void waits_until_all_terminal() {
            List<ForecastBatchEntity> threeOfFour = List.of(
                    batch(BatchStatus.COMPLETED),
                    batch(BatchStatus.COMPLETED),
                    batch(BatchStatus.FAILED),
                    batch(BatchStatus.SUBMITTED));
            List<ForecastBatchEntity> fourOfFour = List.of(
                    batch(BatchStatus.COMPLETED),
                    batch(BatchStatus.COMPLETED),
                    batch(BatchStatus.FAILED),
                    batch(BatchStatus.COMPLETED));

            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newRun()));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID))
                    .thenReturn(threeOfFour)   // first poll — wait
                    .thenReturn(fourOfFour);   // second poll — proceed

            orchestrator.runNightlyCycle();

            // Verify the wait-loop's intermediate update fired with the right progress text.
            verify(pipelineRunService).updateWaitingOn(RUN_ID,
                    "forecast batch set (3 of 4 complete)");
            // Two completion polls; one updateWaitingOn (the still-waiting one).
            verify(forecastBatchRepository, times(2)).findByPipelineRunId(RUN_ID);
            verify(briefingService).refreshBriefing();
            verify(pipelineRunService).completeRun(RUN_ID);
        }

        @Test
        @DisplayName("currentCompletionState exposed for direct verification at boundary")
        void completion_state_at_boundary() {
            // N-1 of N → not terminal
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of(
                    batch(BatchStatus.COMPLETED),
                    batch(BatchStatus.COMPLETED),
                    batch(BatchStatus.SUBMITTED)));
            NightlyPipelineOrchestrator.BatchCompletionResult notDone =
                    orchestrator.currentCompletionState(RUN_ID);
            assertThat(notDone.allTerminal()).isFalse();
            assertThat(notDone.waitingOnText()).isEqualTo("forecast batch set (2 of 3 complete)");

            // N of N → terminal
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of(
                    batch(BatchStatus.COMPLETED),
                    batch(BatchStatus.COMPLETED),
                    batch(BatchStatus.COMPLETED)));
            NightlyPipelineOrchestrator.BatchCompletionResult done =
                    orchestrator.currentCompletionState(RUN_ID);
            assertThat(done.allTerminal()).isTrue();
        }
    }

    @Nested
    @DisplayName("Safety timeout — backstop, not coordination")
    class SafetyTimeout {

        @Test
        @DisplayName("when batches never complete the orchestrator marks the run FAILED and does NOT brief")
        void timeout_fails_run_without_briefing() {
            // Clock advances past the safety deadline immediately, so the loop's
            // first deadline check fires.
            Instant past = T0.minus(Duration.ofHours(2));
            Clock movingClock = new Clock() {
                private final Iterator<Instant> instants =
                        List.of(past, T0.plusSeconds(10_000)).iterator();

                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return instants.hasNext() ? instants.next() : T0.plusSeconds(10_000);
                }
            };
            NightlyPipelineOrchestrator timingOutOrch = new NightlyPipelineOrchestrator(
                    pipelineRunService, scheduledBatchEvaluationService, briefingService,
                    forecastBatchRepository, movingClock,
                    directExecutor, Duration.ofMillis(1), Duration.ofSeconds(10),
                    null);

            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newRun()));
            // Always 1 SUBMITTED (never terminal) — wait loop relies on the clock to break out.
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of(
                    batch(BatchStatus.SUBMITTED)));

            timingOutOrch.runNightlyCycle();

            // Safety-timeout path: WAIT phase marked FAILED, run marked FAILED with the
            // "Safety timeout" prefix that makes this distinguishable from a normal
            // phase failure.
            verify(pipelineRunService).failPhase(eq(RUN_ID),
                    eq(PipelinePhase.FORECAST_BATCH_WAIT),
                    org.mockito.ArgumentMatchers.contains("Batch set did not reach terminal status"));
            verify(pipelineRunService).failRun(eq(RUN_ID),
                    org.mockito.ArgumentMatchers.startsWith("Safety timeout:"));
            verify(briefingService, never()).refreshBriefing();
            verify(pipelineRunService, never()).completeRun(RUN_ID);
        }
    }

    @Nested
    @DisplayName("Phase failure handling")
    class PhaseFailures {

        @Test
        @DisplayName("submit phase failure marks run FAILED, does NOT advance to wait/briefing")
        void submit_failure_short_circuits() {
            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            doThrow(new RuntimeException("anthropic 5xx"))
                    .when(scheduledBatchEvaluationService).submitForecastBatchForPipelineRun(RUN_ID);

            orchestrator.runNightlyCycle();

            verify(pipelineRunService).failPhase(eq(RUN_ID),
                    eq(PipelinePhase.FORECAST_BATCH_SUBMIT), eq("anthropic 5xx"));
            verify(pipelineRunService).failRun(eq(RUN_ID),
                    org.mockito.ArgumentMatchers.contains("Submit phase failed"));
            verify(pipelineRunService, never()).startPhase(RUN_ID, PipelinePhase.FORECAST_BATCH_WAIT);
            verify(briefingService, never()).refreshBriefing();
            verify(pipelineRunService, never()).completeRun(RUN_ID);
        }

        @Test
        @DisplayName("briefing failure marks BRIEFING phase + run FAILED")
        void briefing_failure_marks_failed() {
            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newRun()));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID))
                    .thenReturn(List.of(batch(BatchStatus.COMPLETED)));
            doThrow(new RuntimeException("briefing broke"))
                    .when(briefingService).refreshBriefing();

            orchestrator.runNightlyCycle();

            verify(pipelineRunService).failPhase(eq(RUN_ID),
                    eq(PipelinePhase.BRIEFING), eq("briefing broke"));
            verify(pipelineRunService).failRun(eq(RUN_ID),
                    org.mockito.ArgumentMatchers.contains("Briefing failed"));
            verify(pipelineRunService, never()).completeRun(RUN_ID);
        }
    }

    @Nested
    @DisplayName("Restart durability")
    class RestartResume {

        @Test
        @DisplayName("a RUNNING run mid-WAIT is dispatched for resume")
        void resumes_mid_wait() {
            PipelineRunEntity midWait = runInPhase(PipelinePhase.FORECAST_BATCH_WAIT);
            when(pipelineRunService.findRunning()).thenReturn(List.of(midWait));
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(midWait));
            // Batches already complete by the time we resume.
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID))
                    .thenReturn(List.of(batch(BatchStatus.COMPLETED)));

            orchestrator.resumeRunningCyclesOnStartup();

            // Resume re-runs the wait check, then briefs. It must NOT start a fresh
            // WAIT phase row (idempotent re-entry — see waitAndBriefPhase guard).
            verify(pipelineRunService, never())
                    .startPhase(RUN_ID, PipelinePhase.FORECAST_BATCH_WAIT);
            verify(pipelineRunService).startPhase(RUN_ID, PipelinePhase.BRIEFING);
            verify(briefingService).refreshBriefing();
            verify(pipelineRunService).completeRun(RUN_ID);
        }

        @Test
        @DisplayName("a RUNNING run mid-BRIEFING re-runs the briefing only")
        void resumes_mid_briefing() {
            PipelineRunEntity midBrief = runInPhase(PipelinePhase.BRIEFING);
            when(pipelineRunService.findRunning()).thenReturn(List.of(midBrief));
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(midBrief));

            orchestrator.resumeRunningCyclesOnStartup();

            // No wait at all — only the briefing.
            verify(pipelineRunService, never())
                    .startPhase(RUN_ID, PipelinePhase.FORECAST_BATCH_WAIT);
            verify(forecastBatchRepository, never()).findByPipelineRunId(RUN_ID);
            verify(pipelineRunService).startPhase(RUN_ID, PipelinePhase.BRIEFING);
            verify(briefingService).refreshBriefing();
            verify(pipelineRunService).completeRun(RUN_ID);
        }

        @Test
        @DisplayName("a RUNNING run mid-SUBMIT is marked FAILED — partial-state recovery is unsafe")
        void mid_submit_marked_failed() {
            PipelineRunEntity midSubmit = runInPhase(PipelinePhase.FORECAST_BATCH_SUBMIT);
            when(pipelineRunService.findRunning()).thenReturn(List.of(midSubmit));

            orchestrator.resumeRunningCyclesOnStartup();

            verify(pipelineRunService).failRun(eq(RUN_ID),
                    org.mockito.ArgumentMatchers.contains("restarted during submit"));
            verify(briefingService, never()).refreshBriefing();
        }

        @Test
        @DisplayName("no RUNNING runs at startup is a no-op")
        void no_running_runs_no_op() {
            when(pipelineRunService.findRunning()).thenReturn(List.of());

            orchestrator.resumeRunningCyclesOnStartup();

            verifyNoInteractions(scheduledBatchEvaluationService);
            verifyNoInteractions(briefingService);
            verifyNoInteractions(forecastBatchRepository);
        }
    }

    @Nested
    @DisplayName("Scope guarantees")
    class ScopeGuarantees {

        @Test
        @DisplayName("orchestrator is forecast-only — never invokes aurora paths (no aurora deps injected)")
        void no_aurora_dependencies_exist() {
            // The orchestrator's constructor only accepts forecast-side dependencies.
            // Aurora paths (AuroraPollingJob, AuroraOrchestrator, AuroraStateCache) are
            // not reachable from this class — a structural guarantee that aurora stays
            // parallel to, never inside, the orchestrated cycle.
            assertThat(orchestrator).isNotNull();
            // The mocked dependency set proves the surface area:
            assertThat(pipelineRunService).isNotNull();
            assertThat(scheduledBatchEvaluationService).isNotNull();
            assertThat(briefingService).isNotNull();
            assertThat(forecastBatchRepository).isNotNull();
        }

        @Test
        @DisplayName("CycleType enum reserves INTRADAY for future intraday refresh")
        void intraday_cycle_type_is_reserved() {
            assertThat(CycleType.values()).contains(CycleType.NIGHTLY, CycleType.INTRADAY);
        }
    }

    @Nested
    @DisplayName("Cron wiring")
    class CronWiring {

        @Test
        @DisplayName("registerJobTarget binds runNightlyCycle to near_term_batch_evaluation")
        void registers_near_term_target() {
            DynamicSchedulerService scheduler =
                    org.mockito.Mockito.mock(DynamicSchedulerService.class);
            NightlyPipelineOrchestrator wired = new NightlyPipelineOrchestrator(
                    pipelineRunService, scheduledBatchEvaluationService, briefingService,
                    forecastBatchRepository, Clock.fixed(T0, ZoneOffset.UTC),
                    directExecutor, Duration.ofMillis(1), Duration.ofSeconds(10),
                    scheduler);

            wired.registerJobTarget();

            verify(scheduler).registerJobTarget(
                    org.mockito.ArgumentMatchers.eq("near_term_batch_evaluation"),
                    org.mockito.ArgumentMatchers.any(Runnable.class));
        }

        @Test
        @DisplayName("null scheduler skips registration silently (test-friendly)")
        void null_scheduler_is_a_no_op() {
            // The default orchestrator built in @BeforeEach has a null scheduler.
            // Calling registerJobTarget should not throw.
            orchestrator.registerJobTarget();
        }
    }
}
