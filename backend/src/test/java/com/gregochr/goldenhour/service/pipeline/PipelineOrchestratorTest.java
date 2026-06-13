package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.batch.IntradayCandidateCollectionStrategy;
import com.gregochr.goldenhour.service.batch.IntradayEligibilityPolicy;
import com.gregochr.goldenhour.service.batch.BatchRetryService;
import com.gregochr.goldenhour.service.batch.NightlyCandidateCollectionStrategy;
import com.gregochr.goldenhour.service.batch.NightlyEligibilityPolicy;
import com.gregochr.goldenhour.service.batch.ReclassSummary;
import com.gregochr.goldenhour.service.batch.RetrySelection;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PipelineOrchestrator}.
 *
 * <p>All sequencing is exercised on the calling thread by passing a
 * {@code Runnable::run} executor and a sub-millisecond poll interval, so the
 * wait-loop deterministically reaches completion within the test.
 */
@ExtendWith(MockitoExtension.class)
class PipelineOrchestratorTest {

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

    @Mock
    private PipelineRunPickService pipelineRunPickService;

    @Mock
    private BatchRetryService batchRetryService;

    private PipelineOrchestrator orchestrator;

    /** Direct executor — runs the wait/brief tail on the calling thread. */
    private final Executor directExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(T0, ZoneOffset.UTC);
        // 1ms poll interval, 10s safety timeout — keeps the wait loop fast
        // while leaving room for multi-iteration boundary tests. Null scheduler:
        // unit tests don't exercise the @PostConstruct cron registration.
        orchestrator = new PipelineOrchestrator(
                pipelineRunService,
                scheduledBatchEvaluationService,
                briefingService,
                forecastBatchRepository,
                fixedClock,
                directExecutor,
                Duration.ofMillis(1),
                Duration.ofSeconds(10),
                null,
                pipelineRunPickService,
                batchRetryService);
        // Default: clean cycle (no transient failures), so RETRY_FAILED is a silent
        // no-op and the existing sequence assertions are unaffected. Lenient because
        // the pre-submission-failure and timeout tests never reach the retry phase.
        lenient().when(batchRetryService.selectFailures(any()))
                .thenReturn(RetrySelection.none(5));
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

            // Orchestrator passes nightly's strategy + policy explicitly through the
            // submit chain, with ephemeral=false (publish the snapshot) — pin all of
            // these for nightly so a regression that drops them or swaps in different
            // ones (intraday's, say, or ephemeral=true) is loud.
            verify(scheduledBatchEvaluationService).submitForecastBatchForPipelineRun(
                    eq(RUN_ID),
                    eq(NightlyCandidateCollectionStrategy.INSTANCE),
                    eq(NightlyEligibilityPolicy.INSTANCE),
                    eq(false),
                    any());
            // Nightly records no STABILITY_RECLASSIFY phase — that's intraday-only.
            verify(pipelineRunService, never())
                    .startPhase(RUN_ID, PipelinePhase.STABILITY_RECLASSIFY);
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
            PipelineOrchestrator.BatchCompletionResult notDone =
                    orchestrator.currentCompletionState(RUN_ID);
            assertThat(notDone.allTerminal()).isFalse();
            assertThat(notDone.waitingOnText()).isEqualTo("forecast batch set (2 of 3 complete)");

            // N of N → terminal
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of(
                    batch(BatchStatus.COMPLETED),
                    batch(BatchStatus.COMPLETED),
                    batch(BatchStatus.COMPLETED)));
            PipelineOrchestrator.BatchCompletionResult done =
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
            PipelineOrchestrator timingOutOrch = new PipelineOrchestrator(
                    pipelineRunService, scheduledBatchEvaluationService, briefingService,
                    forecastBatchRepository, movingClock,
                    directExecutor, Duration.ofMillis(1), Duration.ofSeconds(10),
                    null,
                    pipelineRunPickService,
                    batchRetryService);

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
                    .when(scheduledBatchEvaluationService).submitForecastBatchForPipelineRun(
                            eq(RUN_ID),
                            eq(NightlyCandidateCollectionStrategy.INSTANCE),
                            eq(NightlyEligibilityPolicy.INSTANCE),
                            eq(false),
                            any());

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
                    org.mockito.ArgumentMatchers.contains("restarted during pre-submission"));
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
    @DisplayName("Pick persistence")
    class PickPersistence {

        private DailyBriefingResponse briefingWithPicks(List<BestBet> picks) {
            return briefing(picks, false, com.gregochr.goldenhour.model.BestBetStatus.SUCCESS_WITH_PICKS);
        }

        private DailyBriefingResponse briefingWithPicks(List<BestBet> picks, boolean stale) {
            return briefing(picks, stale, com.gregochr.goldenhour.model.BestBetStatus.SUCCESS_WITH_PICKS);
        }

        private DailyBriefingResponse briefing(List<BestBet> picks, boolean stale,
                com.gregochr.goldenhour.model.BestBetStatus status) {
            return new DailyBriefingResponse(
                    java.time.LocalDateTime.of(2026, 5, 26, 4, 0),
                    "test headline",
                    List.of(),
                    picks,
                    null,
                    null,
                    stale,
                    false,
                    0,
                    "Haiku",
                    List.<HotTopic>of(),
                    List.of(),
                    status);
        }

        private BestBet pick(int rank) {
            return new BestBet(rank, "h" + rank, "d" + rank,
                    "2026-05-26_sunset", "Northumberland", Confidence.HIGH,
                    null, "Today", "sunset", "20:50");
        }

        @Test
        @DisplayName("happy path: briefing returns 2 picks → pickService.persist called with both")
        void persists_picks_after_successful_briefing() {
            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newRun()));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID))
                    .thenReturn(List.of(batch(BatchStatus.COMPLETED)));
            List<BestBet> picks = List.of(pick(1), pick(2));
            when(briefingService.getCachedBriefing()).thenReturn(briefingWithPicks(picks));

            orchestrator.runNightlyCycle();

            verify(pipelineRunService).recordBestBetStatus(RUN_ID,
                    com.gregochr.goldenhour.model.BestBetStatus.SUCCESS_WITH_PICKS);
            verify(pipelineRunPickService).persist(RUN_ID, picks);
            verify(pipelineRunService).completePhase(eq(RUN_ID),
                    eq(PipelinePhase.BRIEFING), isNull());
            verify(pipelineRunService).completeRun(RUN_ID);
        }

        @Test
        @DisplayName("FAILED advisor → status recorded, NO pick rows persisted")
        void records_status_but_no_picks_when_advisor_failed() {
            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newRun()));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID))
                    .thenReturn(List.of(batch(BatchStatus.COMPLETED)));
            when(briefingService.getCachedBriefing()).thenReturn(
                    briefing(List.of(), false, com.gregochr.goldenhour.model.BestBetStatus.FAILED));

            orchestrator.runNightlyCycle();

            // A failed advisor records its outcome but must not write pick rows — absence of
            // rows now means "no successful picks", which the fallback relies on.
            verify(pipelineRunService).recordBestBetStatus(RUN_ID,
                    com.gregochr.goldenhour.model.BestBetStatus.FAILED);
            verify(pipelineRunPickService, never()).persist(eq(RUN_ID),
                    org.mockito.ArgumentMatchers.anyList());
            verify(pipelineRunService).completeRun(RUN_ID);
        }

        @Test
        @DisplayName("honest decline (SUCCESS_NO_PICKS) → status recorded, NO pick rows persisted")
        void records_status_but_no_picks_when_honest_decline() {
            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newRun()));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID))
                    .thenReturn(List.of(batch(BatchStatus.COMPLETED)));
            when(briefingService.getCachedBriefing()).thenReturn(
                    briefing(List.of(), false,
                            com.gregochr.goldenhour.model.BestBetStatus.SUCCESS_NO_PICKS));

            orchestrator.runNightlyCycle();

            verify(pipelineRunService).recordBestBetStatus(RUN_ID,
                    com.gregochr.goldenhour.model.BestBetStatus.SUCCESS_NO_PICKS);
            verify(pipelineRunPickService, never()).persist(eq(RUN_ID),
                    org.mockito.ArgumentMatchers.anyList());
            verify(pipelineRunService).completeRun(RUN_ID);
        }

        @Test
        @DisplayName("stale briefing (below-threshold run, LKG picks) → persist NOT called")
        void skips_persist_when_briefing_stale() {
            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newRun()));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID))
                    .thenReturn(List.of(batch(BatchStatus.COMPLETED)));
            // Below-threshold run: the cache holds the last-known-good briefing whose
            // bestBets are the PREVIOUS cycle's picks. Recording them against this
            // runId would corrupt the cross-run comparison, so persistence is skipped.
            when(briefingService.getCachedBriefing())
                    .thenReturn(briefingWithPicks(List.of(pick(1)), true));

            orchestrator.runNightlyCycle();

            verifyNoInteractions(pipelineRunPickService);
            verify(pipelineRunService).completePhase(eq(RUN_ID),
                    eq(PipelinePhase.BRIEFING), isNull());
            verify(pipelineRunService).completeRun(RUN_ID);
        }

        @Test
        @DisplayName("getCachedBriefing returns null → persist NOT called, run still COMPLETED")
        void skips_persist_when_no_cached_briefing() {
            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newRun()));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID))
                    .thenReturn(List.of(batch(BatchStatus.COMPLETED)));
            when(briefingService.getCachedBriefing()).thenReturn(null);

            orchestrator.runNightlyCycle();

            verifyNoInteractions(pipelineRunPickService);
            verify(pipelineRunService).completeRun(RUN_ID);
        }

        @Test
        @DisplayName("pickService.persist throwing does NOT fail the BRIEFING phase or the run")
        void persist_failure_does_not_fail_briefing_phase() {
            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newRun()));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID))
                    .thenReturn(List.of(batch(BatchStatus.COMPLETED)));
            when(briefingService.getCachedBriefing())
                    .thenReturn(briefingWithPicks(List.of(pick(1))));
            // Service contract is "swallows internally and never throws", BUT the
            // orchestrator also has a belt-and-braces try/catch so a contract violation
            // still cannot fail a briefing that actually succeeded. This test exercises
            // that defensive wrapper by forcing the service to throw and asserting the
            // BRIEFING phase + run still complete cleanly.
            doThrow(new RuntimeException("simulated pick persist failure — should never escape"))
                    .when(pipelineRunPickService).persist(eq(RUN_ID),
                            org.mockito.ArgumentMatchers.anyList());

            orchestrator.runNightlyCycle();

            // The BRIEFING phase completed normally and the run completed despite the
            // pick-persist failure — proving pick persistence is observability, not
            // correctness, and exceptions from it never silently corrupt the phase.
            verify(pipelineRunService).completePhase(eq(RUN_ID),
                    eq(PipelinePhase.BRIEFING), isNull());
            verify(pipelineRunService, never()).failPhase(eq(RUN_ID),
                    eq(PipelinePhase.BRIEFING), org.mockito.ArgumentMatchers.anyString());
            verify(pipelineRunService).completeRun(RUN_ID);
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
            PipelineOrchestrator wired = new PipelineOrchestrator(
                    pipelineRunService, scheduledBatchEvaluationService, briefingService,
                    forecastBatchRepository, Clock.fixed(T0, ZoneOffset.UTC),
                    directExecutor, Duration.ofMillis(1), Duration.ofSeconds(10),
                    scheduler,
                    pipelineRunPickService,
                    batchRetryService);

            wired.registerJobTarget();

            verify(scheduler).registerJobTarget(
                    org.mockito.ArgumentMatchers.eq("near_term_batch_evaluation"),
                    org.mockito.ArgumentMatchers.any(Runnable.class));
            verify(scheduler).registerJobTarget(
                    org.mockito.ArgumentMatchers.eq("intraday_forecast_refresh"),
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

    @Nested
    @DisplayName("Intraday cycle")
    class IntradayCycle {

        private PipelineRunEntity newIntradayRun() {
            PipelineRunEntity run = new PipelineRunEntity(CycleType.INTRADAY, T0);
            run.setId(RUN_ID);
            // After submit, the orchestrator's wait/brief tail reads this — set it to a
            // post-submit phase so the tail proceeds rather than restarting submit.
            run.setCurrentPhase(PipelinePhase.FORECAST_BATCH_SUBMIT);
            return run;
        }

        @Test
        @DisplayName("runIntradayCycle goes through the SAME runCycle path with INTRADAY "
                + "inputs + ephemeral=true (no parallel orchestrator method)")
        void intraday_uses_shared_runCycle_with_intraday_inputs() {
            when(pipelineRunService.startRun(CycleType.INTRADAY)).thenReturn(newIntradayRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newIntradayRun()));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of());
            when(briefingService.getCachedBriefing()).thenReturn(null);

            orchestrator.runIntradayCycle();

            // Same code path as nightly: a run is started with INTRADAY, then the
            // shared submit method is invoked — with intraday's strategy + policy and
            // ephemeral=true (don't overwrite the morning snapshot).
            verify(pipelineRunService).startRun(CycleType.INTRADAY);
            verify(scheduledBatchEvaluationService).submitForecastBatchForPipelineRun(
                    eq(RUN_ID),
                    isA(IntradayCandidateCollectionStrategy.class),
                    eq(IntradayEligibilityPolicy.INSTANCE),
                    eq(true),
                    any());
            // And it reaches briefing through the identical tail.
            verify(briefingService).refreshBriefing();
            verify(pipelineRunService).completeRun(RUN_ID);
        }

        @Test
        @DisplayName("records STABILITY_RECLASSIFY then FORECAST_BATCH_SUBMIT as distinct "
                + "phases, with the cost-gate summary as the reclassify detail")
        void intraday_records_reclassify_then_submit_phases() {
            when(pipelineRunService.startRun(CycleType.INTRADAY)).thenReturn(newIntradayRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newIntradayRun()));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of());
            when(briefingService.getCachedBriefing()).thenReturn(null);
            // Simulate the real batch service firing the between-collect-and-submit hook
            // with a cost-gate summary, which is what drives the phase boundary.
            doAnswer(inv -> {
                @SuppressWarnings("unchecked")
                java.util.function.Consumer<ReclassSummary> hook = inv.getArgument(4);
                hook.accept(new ReclassSummary(3, 1, 2));
                return null;
            }).when(scheduledBatchEvaluationService).submitForecastBatchForPipelineRun(
                    eq(RUN_ID), any(), any(), eq(true), any());

            orchestrator.runIntradayCycle();

            // RECLASSIFY opens first, completes with the cost-gate summary; SUBMIT then
            // opens (from the hook) and completes (after the call). Real, separate phases.
            verify(pipelineRunService).startPhase(RUN_ID, PipelinePhase.STABILITY_RECLASSIFY);
            verify(pipelineRunService).completePhase(eq(RUN_ID),
                    eq(PipelinePhase.STABILITY_RECLASSIFY),
                    eq("3 considered, 1 settled-skipped, 2 unsettled-evaluated"));
            verify(pipelineRunService).startPhase(RUN_ID, PipelinePhase.FORECAST_BATCH_SUBMIT);
            verify(pipelineRunService).completePhase(eq(RUN_ID),
                    eq(PipelinePhase.FORECAST_BATCH_SUBMIT), isNull());
        }

        @Test
        @DisplayName("a RUNNING intraday run mid-RECLASSIFY is marked FAILED — pre-submission, "
                + "unsafe to resume")
        void mid_reclassify_marked_failed() {
            PipelineRunEntity midReclassify = new PipelineRunEntity(CycleType.INTRADAY, T0);
            midReclassify.setId(RUN_ID);
            midReclassify.setCurrentPhase(PipelinePhase.STABILITY_RECLASSIFY);
            when(pipelineRunService.findRunning()).thenReturn(List.of(midReclassify));

            orchestrator.resumeRunningCyclesOnStartup();

            verify(pipelineRunService).failRun(eq(RUN_ID),
                    org.mockito.ArgumentMatchers.contains("restarted during pre-submission"));
            verify(briefingService, never()).refreshBriefing();
        }
    }

    @Nested
    @DisplayName("RETRY_FAILED phase")
    class RetryFailedPhase {

        @Test
        @DisplayName("clean cycle (no transient failures) records NO RETRY_FAILED phase and "
                + "never submits a retry")
        void clean_cycle_no_retry_phase() {
            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newRun()));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID))
                    .thenReturn(List.of(batch(BatchStatus.COMPLETED)));
            // setUp's lenient default already returns RetrySelection.none(5).

            orchestrator.runNightlyCycle();

            verify(pipelineRunService, never())
                    .startPhase(RUN_ID, PipelinePhase.RETRY_FAILED);
            verify(batchRetryService, never()).submitRetry(eq(RUN_ID), any());
            verify(briefingService).refreshBriefing();
            verify(pipelineRunService).completeRun(RUN_ID);
        }

        @Test
        @DisplayName("within-cap failures → RETRY_FAILED phase submits a retry, waits, "
                + "records the recovery summary, then briefs")
        void within_cap_retries_and_records_recovery() {
            RetrySelection selection = RetrySelection.retry(List.of(
                    new RetrySelection.RetryFailure("fc-42-2026-05-26-SUNSET", 42L,
                            java.time.LocalDate.of(2026, 5, 26),
                            com.gregochr.goldenhour.entity.TargetType.SUNSET)), 5);
            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newRun()));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID))
                    .thenReturn(List.of(batch(BatchStatus.COMPLETED)));
            when(batchRetryService.selectFailures(RUN_ID)).thenReturn(selection);
            when(batchRetryService.summariseRecovery(RUN_ID, 1))
                    .thenReturn("1 failed, 1 retried, 1 recovered, 0 still-failed");

            orchestrator.runNightlyCycle();

            verify(pipelineRunService).startPhase(RUN_ID, PipelinePhase.RETRY_FAILED);
            verify(batchRetryService).submitRetry(RUN_ID, selection);
            verify(pipelineRunService).completePhase(RUN_ID, PipelinePhase.RETRY_FAILED,
                    "1 failed, 1 retried, 1 recovered, 0 still-failed");
            // RETRY_FAILED sits between WAIT and BRIEFING.
            verify(pipelineRunService).completePhase(eq(RUN_ID),
                    eq(PipelinePhase.FORECAST_BATCH_WAIT), any());
            verify(pipelineRunService).startPhase(RUN_ID, PipelinePhase.BRIEFING);
            verify(briefingService).refreshBriefing();
            verify(pipelineRunService).completeRun(RUN_ID);
        }

        @Test
        @DisplayName("over-cap failures → RETRY_FAILED phase records systematic failure, "
                + "does NOT submit a retry, still briefs")
        void over_cap_records_systematic_no_retry() {
            when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(newRun());
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(newRun()));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID))
                    .thenReturn(List.of(batch(BatchStatus.COMPLETED)));
            when(batchRetryService.selectFailures(RUN_ID))
                    .thenReturn(RetrySelection.systematic(7, 5));

            orchestrator.runNightlyCycle();

            verify(pipelineRunService).startPhase(RUN_ID, PipelinePhase.RETRY_FAILED);
            verify(batchRetryService, never()).submitRetry(eq(RUN_ID), any());
            verify(pipelineRunService).completePhase(eq(RUN_ID),
                    eq(PipelinePhase.RETRY_FAILED),
                    org.mockito.ArgumentMatchers.contains("exceeds cap"));
            verify(briefingService).refreshBriefing();
            verify(pipelineRunService).completeRun(RUN_ID);
        }

        @Test
        @DisplayName("INTRADAY cycle gets RETRY_FAILED through the SAME shared tail "
                + "(single code path)")
        void intraday_uses_same_retry_path() {
            PipelineRunEntity intraday = new PipelineRunEntity(CycleType.INTRADAY, T0);
            intraday.setId(RUN_ID);
            intraday.setCurrentPhase(PipelinePhase.FORECAST_BATCH_SUBMIT);
            RetrySelection selection = RetrySelection.retry(List.of(
                    new RetrySelection.RetryFailure("fc-42-2026-05-26-SUNSET", 42L,
                            java.time.LocalDate.of(2026, 5, 26),
                            com.gregochr.goldenhour.entity.TargetType.SUNSET)), 5);
            when(pipelineRunService.startRun(CycleType.INTRADAY)).thenReturn(intraday);
            when(pipelineRunService.findById(RUN_ID)).thenReturn(Optional.of(intraday));
            when(forecastBatchRepository.findByPipelineRunId(RUN_ID))
                    .thenReturn(List.of(batch(BatchStatus.COMPLETED)));
            when(briefingService.getCachedBriefing()).thenReturn(null);
            when(batchRetryService.selectFailures(RUN_ID)).thenReturn(selection);
            when(batchRetryService.summariseRecovery(RUN_ID, 1))
                    .thenReturn("1 failed, 1 retried, 1 recovered, 0 still-failed");

            orchestrator.runIntradayCycle();

            verify(pipelineRunService).startPhase(RUN_ID, PipelinePhase.RETRY_FAILED);
            verify(batchRetryService).submitRetry(RUN_ID, selection);
            verify(briefingService).refreshBriefing();
            verify(pipelineRunService).completeRun(RUN_ID);
        }
    }
}
