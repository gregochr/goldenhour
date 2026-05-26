package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Sequencer for the nightly forecast pipeline.
 *
 * <p>Replaces the implicit time-buffer coupling between the forecast batch (01:00,
 * completes ~10 min) and the daily briefing (04:00) with an explicit, completion-
 * gated sequence. The body of {@link #runCycleSynchronously(PipelineRunEntity)}
 * reads top-to-bottom as the pipeline:
 *
 * <pre>
 *   1. start FORECAST_BATCH_SUBMIT phase
 *   2. submit forecast batches (tagged with this pipeline_run id)
 *   3. start FORECAST_BATCH_WAIT phase; poll the DB until every tagged batch
 *      reaches a terminal status, or the safety timeout fires
 *   4. start BRIEFING phase; call BriefingService.refreshBriefing()
 *      (gloss + best-bet run synchronously inside that call)
 *   5. mark COMPLETED
 * </pre>
 *
 * <p><b>The DB poll in step 3 is deliberately simple and synchronous.</b> It is
 * the one and only "wait" in this sequencer. We chose DB polling over callbacks
 * or futures because (a) state is durable across a process restart and (b) the
 * waiting_on string is a queryable fact that powers the observability UX.
 *
 * <p><b>The safety timeout is a failure backstop, not the coordination
 * mechanism.</b> It exists only so a genuinely broken cycle does not hang
 * forever. The normal path is completion-gated by step 3's loop; the timeout
 * only fires when batches never transition out of SUBMITTED. Do not confuse it
 * with the retired 3-hour cron buffer.
 *
 * <p><b>Aurora is outside this cycle.</b> The orchestrator never invokes aurora
 * polling or aurora batch logic. The briefing's read of {@code AuroraStateCache}
 * is a non-blocking volatile lookup unrelated to cycle ordering.
 *
 * <p>Intraday reuse: this orchestrator is forecast-only today, but parameterising
 * on {@code cycleType} and accepting different phase sets is the future path —
 * the intraday refresh will run its own cycle through this same sequencer.
 */
@Service
public class NightlyPipelineOrchestrator {

    private static final Logger LOG =
            LoggerFactory.getLogger(NightlyPipelineOrchestrator.class);

    /** Default interval between DB polls while waiting for batches to complete. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(60);

    /**
     * Default safety-timeout backstop. Genuine production batches complete in
     * ~10 min; this is the "something is broken" ceiling. NOT the normal
     * coordination mechanism — see class javadoc.
     */
    public static final Duration DEFAULT_SAFETY_TIMEOUT = Duration.ofMinutes(90);

    private final PipelineRunService pipelineRunService;
    private final ScheduledBatchEvaluationService scheduledBatchEvaluationService;
    private final BriefingService briefingService;
    private final ForecastBatchRepository forecastBatchRepository;
    private final Clock clock;
    private final Executor backgroundExecutor;
    private final Duration pollInterval;
    private final Duration safetyTimeout;

    /**
     * Production constructor — uses a virtual-thread executor so the wait phase
     * does not occupy a scheduler thread, and the default poll/timeout values.
     *
     * <p>Spring auto-wires this constructor; the package-private full-argument
     * constructor below exists only for tests.
     *
     * @param pipelineRunService              pipeline run / phase persistence
     * @param scheduledBatchEvaluationService forecast batch submitter (cycle-aware variant)
     * @param briefingService                 briefing refresh entry point
     * @param forecastBatchRepository         queried for cycle completion
     * @param clock                           injected clock for deterministic tests
     */
    @Autowired
    public NightlyPipelineOrchestrator(PipelineRunService pipelineRunService,
            ScheduledBatchEvaluationService scheduledBatchEvaluationService,
            BriefingService briefingService,
            ForecastBatchRepository forecastBatchRepository,
            Clock clock) {
        this(pipelineRunService, scheduledBatchEvaluationService, briefingService,
                forecastBatchRepository, clock,
                Executors.newVirtualThreadPerTaskExecutor(),
                DEFAULT_POLL_INTERVAL, DEFAULT_SAFETY_TIMEOUT);
    }

    /**
     * Full constructor — exposed for tests that need a deterministic executor
     * (e.g. {@code Runnable::run}) or shorter poll/timeout values.
     *
     * @param pipelineRunService              pipeline run / phase persistence
     * @param scheduledBatchEvaluationService forecast batch submitter (cycle-aware variant)
     * @param briefingService                 briefing refresh entry point
     * @param forecastBatchRepository         queried for cycle completion
     * @param clock                           injectable clock
     * @param backgroundExecutor              where to run the wait+briefing tail
     * @param pollInterval                    DB poll interval during FORECAST_BATCH_WAIT
     * @param safetyTimeout                   safety backstop for the wait phase
     */
    public NightlyPipelineOrchestrator(PipelineRunService pipelineRunService,
            ScheduledBatchEvaluationService scheduledBatchEvaluationService,
            BriefingService briefingService,
            ForecastBatchRepository forecastBatchRepository,
            Clock clock,
            Executor backgroundExecutor,
            Duration pollInterval,
            Duration safetyTimeout) {
        this.pipelineRunService = pipelineRunService;
        this.scheduledBatchEvaluationService = scheduledBatchEvaluationService;
        this.briefingService = briefingService;
        this.forecastBatchRepository = forecastBatchRepository;
        this.clock = clock;
        this.backgroundExecutor = backgroundExecutor;
        this.pollInterval = pollInterval;
        this.safetyTimeout = safetyTimeout;
    }

    /**
     * Entry point invoked by the scheduler (commit 2 wires this to
     * {@code near_term_batch_evaluation}). Creates the pipeline run, runs the
     * SUBMIT phase synchronously on the caller's scheduler thread, then
     * dispatches the WAIT+BRIEFING tail to a virtual thread so the scheduler
     * thread is freed for other jobs.
     */
    public void runNightlyCycle() {
        PipelineRunEntity run = pipelineRunService.startRun(CycleType.NIGHTLY);
        try {
            submitPhase(run.getId());
        } catch (Exception e) {
            LOG.error("Pipeline run {}: submission phase failed — {}", run.getId(),
                    e.getMessage(), e);
            pipelineRunService.failRun(run.getId(), "Submit phase failed: " + e.getMessage());
            return;
        }
        backgroundExecutor.execute(() -> waitAndBriefPhase(run.getId()));
    }

    /**
     * Synchronous variant — runs the full cycle on the calling thread. Used in
     * unit tests for deterministic ordering. The production wiring uses
     * {@link #runNightlyCycle()}.
     *
     * @param preCreatedRun a pipeline run previously created via
     *                      {@link PipelineRunService#startRun(CycleType)}
     */
    public void runCycleSynchronously(PipelineRunEntity preCreatedRun) {
        Long runId = preCreatedRun.getId();
        try {
            submitPhase(runId);
        } catch (Exception e) {
            pipelineRunService.failRun(runId, "Submit phase failed: " + e.getMessage());
            return;
        }
        waitAndBriefPhase(runId);
    }

    /**
     * Resumes any pipeline runs left in {@code RUNNING} status when the
     * application starts — covers the mid-cycle restart case.
     *
     * <p>Mid-WAIT or mid-BRIEFING: re-dispatches the wait+brief tail (which
     * picks up where it left off — the wait loop's progress is driven by the
     * DB state, not in-memory). Mid-SUBMIT: marks FAILED because batches may
     * have been submitted to Anthropic in a partial state we can't safely
     * resume; the next scheduled cron creates a fresh cycle.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Profile("!integration-test")
    public void resumeRunningCyclesOnStartup() {
        List<PipelineRunEntity> running = pipelineRunService.findRunning();
        if (running.isEmpty()) {
            return;
        }
        LOG.info("Found {} RUNNING pipeline run(s) at startup — attempting resume",
                running.size());
        for (PipelineRunEntity run : running) {
            PipelinePhase phase = run.getCurrentPhase();
            if (phase == PipelinePhase.FORECAST_BATCH_SUBMIT || phase == null) {
                pipelineRunService.failRun(run.getId(),
                        "Process restarted during submit phase — cannot safely resume");
            } else {
                LOG.info("Resuming pipeline run {} from phase {}", run.getId(), phase);
                backgroundExecutor.execute(() -> waitAndBriefPhase(run.getId()));
            }
        }
    }

    private void submitPhase(Long runId) {
        pipelineRunService.startPhase(runId, PipelinePhase.FORECAST_BATCH_SUBMIT);
        try {
            scheduledBatchEvaluationService.submitForecastBatchForPipelineRun(runId);
            pipelineRunService.completePhase(runId, PipelinePhase.FORECAST_BATCH_SUBMIT, null);
        } catch (RuntimeException e) {
            pipelineRunService.failPhase(runId, PipelinePhase.FORECAST_BATCH_SUBMIT,
                    e.getMessage());
            throw e;
        }
    }

    private void waitAndBriefPhase(Long runId) {
        try {
            // Idempotent re-entry: if we're resuming a run already mid-WAIT or
            // mid-BRIEFING, don't double-start the phase row.
            PipelineRunEntity run = pipelineRunService.findById(runId).orElseThrow();
            PipelinePhase phase = run.getCurrentPhase();

            if (phase != PipelinePhase.FORECAST_BATCH_WAIT
                    && phase != PipelinePhase.BRIEFING) {
                pipelineRunService.startPhase(runId, PipelinePhase.FORECAST_BATCH_WAIT);
            }
            if (phase != PipelinePhase.BRIEFING) {
                BatchCompletionResult result = waitForBatchSetComplete(runId);
                pipelineRunService.completePhase(runId, PipelinePhase.FORECAST_BATCH_WAIT,
                        result.summary());
            }

            pipelineRunService.startPhase(runId, PipelinePhase.BRIEFING);
            try {
                briefingService.refreshBriefing();
                pipelineRunService.completePhase(runId, PipelinePhase.BRIEFING, null);
            } catch (RuntimeException e) {
                pipelineRunService.failPhase(runId, PipelinePhase.BRIEFING, e.getMessage());
                pipelineRunService.failRun(runId, "Briefing failed: " + e.getMessage());
                return;
            }

            pipelineRunService.completeRun(runId);
        } catch (BatchSafetyTimeoutException e) {
            // Safety backstop fired — log loudly and mark the run failed so the
            // next cron schedules a fresh cycle. The user-visible failureReason
            // makes this distinguishable from a genuine phase failure.
            LOG.error("Pipeline run {}: SAFETY TIMEOUT hit — {}", runId, e.getMessage());
            pipelineRunService.failPhase(runId, PipelinePhase.FORECAST_BATCH_WAIT, e.getMessage());
            pipelineRunService.failRun(runId, "Safety timeout: " + e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Pipeline run {}: wait/brief tail failed — {}", runId, e.getMessage(), e);
            pipelineRunService.failRun(runId, "Wait/brief tail failed: " + e.getMessage());
        }
    }

    /**
     * Polls {@code forecast_batch} until every row tagged with this cycle id
     * reaches a terminal status, or the safety timeout elapses.
     *
     * @return summary of the final state
     * @throws BatchSafetyTimeoutException if the safety timeout fires
     */
    private BatchCompletionResult waitForBatchSetComplete(Long runId) {
        Instant deadline = clock.instant().plus(safetyTimeout);

        while (true) {
            BatchCompletionResult state = currentCompletionState(runId);
            if (state.allTerminal()) {
                LOG.info("Pipeline run {}: batch set complete — {}", runId, state.summary());
                return state;
            }
            if (clock.instant().isAfter(deadline)) {
                throw new BatchSafetyTimeoutException(
                        "Batch set did not reach terminal status within " + safetyTimeout
                                + " — " + state.summary());
            }
            pipelineRunService.updateWaitingOn(runId, state.waitingOnText());
            sleepUninterruptibly(pollInterval);
        }
    }

    /**
     * Computes the current "is the cycle's batch set complete?" state from the DB.
     * Zero-batch case (no batches were submitted — e.g. empty briefing, fully
     * cached) counts as terminal so the orchestrator advances straight to
     * briefing rather than waiting forever for a set that doesn't exist.
     */
    BatchCompletionResult currentCompletionState(Long runId) {
        List<ForecastBatchEntity> batches = forecastBatchRepository.findByPipelineRunId(runId);
        int total = batches.size();
        int terminal = (int) batches.stream()
                .filter(b -> b.getStatus() != BatchStatus.SUBMITTED)
                .count();
        return new BatchCompletionResult(total, terminal);
    }

    private void sleepUninterruptibly(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during batch wait", ie);
        }
    }

    /**
     * Snapshot of cycle completion progress, also producing the human strings
     * surfaced as {@code waiting_on} and the phase's terminal detail.
     *
     * @param total    batches tagged with this cycle
     * @param terminal batches in a terminal status
     */
    public record BatchCompletionResult(int total, int terminal) {
        /**
         * @return {@code true} when every batch in the set has reached a terminal
         *         status, or when no batches were submitted (zero-batch cycle).
         */
        public boolean allTerminal() {
            return total == 0 || terminal == total;
        }

        /**
         * @return short text for the live {@code waiting_on} field
         */
        public String waitingOnText() {
            if (total == 0) {
                return "no batches submitted — proceeding to briefing";
            }
            return "forecast batch set (" + terminal + " of " + total + " complete)";
        }

        /**
         * @return concluding summary recorded on the WAIT phase row
         */
        public String summary() {
            if (total == 0) {
                return "no batches submitted";
            }
            return terminal + " of " + total + " batches reached a terminal status";
        }
    }

    /**
     * Internal marker — raised when the wait phase's safety backstop fires.
     * Distinguished from a normal RuntimeException so the caller can mark the
     * run failure reason specifically.
     */
    static class BatchSafetyTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BatchSafetyTimeoutException(String message) {
            super(message);
        }
    }
}
