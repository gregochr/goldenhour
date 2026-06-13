package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.model.BestBetStatus;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.batch.CandidateCollectionStrategy;
import com.gregochr.goldenhour.service.batch.EligibilityPolicy;
import com.gregochr.goldenhour.service.batch.IntradayCandidateCollectionStrategy;
import com.gregochr.goldenhour.service.batch.IntradayEligibilityPolicy;
import com.gregochr.goldenhour.service.batch.BatchRetryService;
import com.gregochr.goldenhour.service.batch.NightlyCandidateCollectionStrategy;
import com.gregochr.goldenhour.service.batch.NightlyEligibilityPolicy;
import com.gregochr.goldenhour.service.batch.ReclassSummary;
import com.gregochr.goldenhour.service.batch.RetrySelection;
import com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.function.Consumer;

/**
 * Sequencer for the forecast pipeline — any cycle type, single code path.
 *
 * <p>Replaces the implicit time-buffer coupling between the forecast batch
 * and the daily briefing with an explicit, completion-gated sequence. The body
 * of {@link #runCycleSynchronously(PipelineRunEntity)} reads top-to-bottom as
 * the pipeline:
 *
 * <pre>
 *   1. start FORECAST_BATCH_SUBMIT phase
 *   2. submit forecast batches with the cycle's candidate strategy +
 *      eligibility policy (tagged with this pipeline_run id)
 *   3. start FORECAST_BATCH_WAIT phase; poll the DB until every tagged batch
 *      reaches a terminal status, or the safety timeout fires
 *   4. start BRIEFING phase; call BriefingService.refreshBriefing()
 *      (gloss + best-bet run synchronously inside that call); persist the
 *      cycle's Plan A / Plan B picks
 *   5. mark COMPLETED
 * </pre>
 *
 * <p><b>Cycle parameterisation.</b> The strategy + policy are the ONLY
 * difference between cycles. Everything else — submit, wait-for-batches,
 * briefing, pick persistence, complete — is shared single code path. The
 * intraday refresh is a different {@link CycleType} + different strategy +
 * different policy and reuses this sequencer end-to-end.
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
 * with the retired 3-hour cron buffer. The value is configurable via
 * {@code photocast.pipeline.safety-timeout} (ISO-8601 duration, default PT4H)
 * because Anthropic batch latency is load-dependent — see
 * {@link #DEFAULT_SAFETY_TIMEOUT} for the empirical calibration.
 *
 * <p><b>Aurora is outside this cycle.</b> The orchestrator never invokes aurora
 * polling or aurora batch logic. The briefing's read of {@code AuroraStateCache}
 * is a non-blocking volatile lookup unrelated to cycle ordering.
 */
@Service
public class PipelineOrchestrator {

    private static final Logger LOG =
            LoggerFactory.getLogger(PipelineOrchestrator.class);

    /** Default interval between DB polls while waiting for batches to complete. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(60);

    /**
     * Default safety-timeout backstop — the "something is broken" ceiling, NOT
     * the normal coordination mechanism (see class javadoc).
     *
     * <p>Calibrated empirically: nightly (01:00 UTC) batches reach terminal in
     * 2–5 min, but afternoon (~14:00 UTC, peak Anthropic load) batches were
     * observed taking 98–173 min with every request succeeding. 4 hours clears
     * the worst observed afternoon latency with margin while still bounding a
     * genuinely dead cycle. Tunable without a deploy via the
     * {@code photocast.pipeline.safety-timeout} property.
     */
    public static final Duration DEFAULT_SAFETY_TIMEOUT = Duration.ofHours(4);

    private final PipelineRunService pipelineRunService;
    private final ScheduledBatchEvaluationService scheduledBatchEvaluationService;
    private final BriefingService briefingService;
    private final ForecastBatchRepository forecastBatchRepository;
    private final Clock clock;
    private final Executor backgroundExecutor;
    private final Duration pollInterval;
    private final Duration safetyTimeout;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final PipelineRunPickService pipelineRunPickService;
    private final BatchRetryService batchRetryService;

    /**
     * Production constructor — uses a virtual-thread executor so the wait phase
     * does not occupy a scheduler thread, and the default poll interval.
     *
     * <p>Spring auto-wires this constructor; the package-private full-argument
     * constructor below exists only for tests.
     *
     * @param pipelineRunService              pipeline run / phase persistence
     * @param scheduledBatchEvaluationService forecast batch submitter (cycle-aware variant)
     * @param briefingService                 briefing refresh entry point
     * @param forecastBatchRepository         queried for cycle completion
     * @param clock                           injected clock for deterministic tests
     * @param safetyTimeout                   safety backstop for the wait phase
     *                                        ({@code photocast.pipeline.safety-timeout},
     *                                        default {@link #DEFAULT_SAFETY_TIMEOUT})
     * @param dynamicSchedulerService         scheduler this orchestrator registers itself with
     * @param pipelineRunPickService          persists each cycle's Plan A / Plan B picks
     * @param batchRetryService               selects + re-submits transient failures (RETRY_FAILED)
     */
    @Autowired
    public PipelineOrchestrator(PipelineRunService pipelineRunService,
            ScheduledBatchEvaluationService scheduledBatchEvaluationService,
            BriefingService briefingService,
            ForecastBatchRepository forecastBatchRepository,
            Clock clock,
            @Value("${photocast.pipeline.safety-timeout:PT4H}") Duration safetyTimeout,
            DynamicSchedulerService dynamicSchedulerService,
            PipelineRunPickService pipelineRunPickService,
            BatchRetryService batchRetryService) {
        this(pipelineRunService, scheduledBatchEvaluationService, briefingService,
                forecastBatchRepository, clock,
                Executors.newVirtualThreadPerTaskExecutor(),
                DEFAULT_POLL_INTERVAL, safetyTimeout,
                dynamicSchedulerService, pipelineRunPickService, batchRetryService);
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
     * @param dynamicSchedulerService         scheduler the orchestrator registers itself with;
     *                                        tests may pass {@code null} to skip registration
     * @param pipelineRunPickService          persists each cycle's Plan A / Plan B picks;
     *                                        tests may pass {@code null} to skip persistence
     * @param batchRetryService               selects + re-submits transient failures (RETRY_FAILED)
     */
    public PipelineOrchestrator(PipelineRunService pipelineRunService,
            ScheduledBatchEvaluationService scheduledBatchEvaluationService,
            BriefingService briefingService,
            ForecastBatchRepository forecastBatchRepository,
            Clock clock,
            Executor backgroundExecutor,
            Duration pollInterval,
            Duration safetyTimeout,
            DynamicSchedulerService dynamicSchedulerService,
            PipelineRunPickService pipelineRunPickService,
            BatchRetryService batchRetryService) {
        this.pipelineRunService = pipelineRunService;
        this.scheduledBatchEvaluationService = scheduledBatchEvaluationService;
        this.briefingService = briefingService;
        this.forecastBatchRepository = forecastBatchRepository;
        this.clock = clock;
        this.backgroundExecutor = backgroundExecutor;
        this.pollInterval = pollInterval;
        this.safetyTimeout = safetyTimeout;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.pipelineRunPickService = pipelineRunPickService;
        this.batchRetryService = batchRetryService;
    }

    /**
     * Registers the orchestrator as the runnable for the {@code near_term_batch_evaluation}
     * scheduled job. Previously this job invoked
     * {@code ScheduledBatchEvaluationService.submitForecastBatch()} directly; from V102 it
     * invokes {@link #runNightlyCycle()} so the briefing is gated on actual batch
     * completion rather than the legacy ~3h cron buffer.
     *
     * <p>Skipped for tests that pass a null scheduler.
     */
    @PostConstruct
    void registerJobTarget() {
        if (dynamicSchedulerService != null) {
            dynamicSchedulerService.registerJobTarget(
                    "near_term_batch_evaluation", this::runNightlyCycle);
            dynamicSchedulerService.registerJobTarget(
                    "intraday_forecast_refresh", this::runIntradayCycle);
        }
    }

    /**
     * Nightly cycle entry point — thin caller of
     * {@link #runCycle(CycleType, CandidateCollectionStrategy, EligibilityPolicy)}
     * with the nightly strategy + policy. Bound to the
     * {@code near_term_batch_evaluation} cron via
     * {@link #registerJobTarget()}.
     */
    public void runNightlyCycle() {
        runCycle(CycleType.NIGHTLY,
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE);
    }

    /**
     * Intraday refresh entry point — a peer of {@link #runNightlyCycle()} that
     * reuses the exact same {@link #runCycle(CycleType, CandidateCollectionStrategy,
     * EligibilityPolicy)} machinery. It differs only in the two cycle-specific
     * inputs:
     *
     * <ul>
     *   <li>{@link IntradayCandidateCollectionStrategy} — the decision window
     *       (T sunset, T+1 sunrise, T+1 sunset), built against this orchestrator's
     *       {@link Clock} so "today" is stable for the cycle;</li>
     *   <li>{@link IntradayEligibilityPolicy} — the skip-settled cost-gate.</li>
     * </ul>
     *
     * <p>Bound to the {@code intraday_forecast_refresh} cron (14:00 UTC) via
     * {@link #registerJobTarget()}. Because it goes through {@code runCycle} with
     * {@link CycleType#INTRADAY}, the submit phase runs ephemerally (the morning
     * snapshot is preserved) and records a {@code STABILITY_RECLASSIFY} phase
     * before {@code FORECAST_BATCH_SUBMIT}; wait, briefing and best-bet persistence
     * are identical to nightly.
     */
    public void runIntradayCycle() {
        runCycle(CycleType.INTRADAY,
                new IntradayCandidateCollectionStrategy(clock),
                IntradayEligibilityPolicy.INSTANCE);
    }

    /**
     * Generic cycle entry point. Creates the pipeline run with the given
     * {@link CycleType}, runs SUBMIT synchronously on the caller's scheduler
     * thread, then dispatches the WAIT+BRIEFING tail to a virtual thread so
     * the scheduler thread is freed for other jobs.
     *
     * <p>This is the single shared code path; the {@code candidateStrategy}
     * and {@code eligibilityPolicy} arguments are the only cycle-specific
     * inputs the sequencer needs.
     *
     * @param cycleType         which cycle is being run (NIGHTLY today;
     *                          INTRADAY reserved)
     * @param candidateStrategy filter deciding which event slots enter the
     *                          candidate set
     * @param eligibilityPolicy per-candidate include/skip decision function
     */
    public void runCycle(CycleType cycleType,
            CandidateCollectionStrategy candidateStrategy,
            EligibilityPolicy eligibilityPolicy) {
        PipelineRunEntity run = pipelineRunService.startRun(cycleType);
        try {
            submitPhase(run.getId(), cycleType, candidateStrategy, eligibilityPolicy);
        } catch (Exception e) {
            LOG.error("Pipeline run {} ({}): submission phase failed — {}", run.getId(),
                    cycleType, e.getMessage(), e);
            pipelineRunService.failRun(run.getId(), "Submit phase failed: " + e.getMessage());
            return;
        }
        backgroundExecutor.execute(() -> waitAndBriefPhase(run.getId()));
    }

    /**
     * Synchronous variant — runs the full cycle on the calling thread using the
     * nightly strategy + policy. Used in unit tests for deterministic ordering.
     * The production wiring uses {@link #runNightlyCycle()} /
     * {@link #runCycle(CycleType, CandidateCollectionStrategy, EligibilityPolicy)}.
     *
     * @param preCreatedRun a pipeline run previously created via
     *                      {@link PipelineRunService#startRun(CycleType)}
     */
    public void runCycleSynchronously(PipelineRunEntity preCreatedRun) {
        Long runId = preCreatedRun.getId();
        try {
            submitPhase(runId, CycleType.NIGHTLY,
                    NightlyCandidateCollectionStrategy.INSTANCE,
                    NightlyEligibilityPolicy.INSTANCE);
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
     * <p>Mid-WAIT, mid-RETRY_FAILED, or mid-BRIEFING: re-dispatches the
     * wait+retry+brief tail (which picks up where it left off — the wait loop's
     * progress is driven by the DB state, not in-memory, and the retry submission
     * is idempotent: it skips if a retry batch already exists for the cycle).
     * Mid-RECLASSIFY or mid-SUBMIT (the pre-submission
     * phases): marks FAILED because batches may have been submitted to Anthropic
     * in a partial state we can't safely resume — and resuming from RECLASSIFY
     * would jump straight to a zero-batch WAIT and brief on stale data; the next
     * scheduled cron creates a fresh cycle.
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
            if (phase == PipelinePhase.STABILITY_RECLASSIFY
                    || phase == PipelinePhase.FORECAST_BATCH_SUBMIT || phase == null) {
                pipelineRunService.failRun(run.getId(),
                        "Process restarted during pre-submission phase — cannot safely resume");
            } else {
                LOG.info("Resuming pipeline run {} from phase {}", run.getId(), phase);
                backgroundExecutor.execute(() -> waitAndBriefPhase(run.getId()));
            }
        }
    }

    /**
     * Runs the submission step, recording phases per cycle type.
     *
     * <p><b>Nightly</b> records a single {@link PipelinePhase#FORECAST_BATCH_SUBMIT}
     * phase spanning collection and submission (classification is published; not a
     * distinct phase). <b>Intraday</b> records two phases:
     * {@link PipelinePhase#STABILITY_RECLASSIFY} around the ephemeral
     * re-classification + cost-gate, then {@link PipelinePhase#FORECAST_BATCH_SUBMIT}
     * around the actual batch submission. The boundary is driven by the
     * between-collect-and-submit hook the batch service fires while still holding
     * its concurrency guard — so the two intraday phases have real, separate
     * durations rather than a synthetic near-simultaneous completion.
     *
     * <p>The collect + submit <em>logic</em> is one shared implementation in the
     * batch service; only this phase recording differs by cycle.
     */
    private void submitPhase(Long runId, CycleType cycleType,
            CandidateCollectionStrategy candidateStrategy,
            EligibilityPolicy eligibilityPolicy) {
        boolean intraday = cycleType == CycleType.INTRADAY;
        PipelinePhase firstPhase = intraday
                ? PipelinePhase.STABILITY_RECLASSIFY
                : PipelinePhase.FORECAST_BATCH_SUBMIT;
        pipelineRunService.startPhase(runId, firstPhase);

        // For intraday only, the hook closes STABILITY_RECLASSIFY (recording the
        // cost-gate summary) and opens FORECAST_BATCH_SUBMIT between the collect
        // and submit steps. For nightly the hook is a no-op and the single
        // FORECAST_BATCH_SUBMIT phase spans both steps.
        Consumer<ReclassSummary> betweenSteps = intraday
                ? summary -> {
                    pipelineRunService.completePhase(runId,
                            PipelinePhase.STABILITY_RECLASSIFY, summary.detail());
                    pipelineRunService.startPhase(runId, PipelinePhase.FORECAST_BATCH_SUBMIT);
                }
                : summary -> { };

        try {
            scheduledBatchEvaluationService.submitForecastBatchForPipelineRun(
                    runId, candidateStrategy, eligibilityPolicy, intraday, betweenSteps);
            pipelineRunService.completePhase(runId, PipelinePhase.FORECAST_BATCH_SUBMIT, null);
        } catch (RuntimeException e) {
            // Fail whichever phase was in flight when the exception fired — for
            // intraday that's STABILITY_RECLASSIFY if collection failed before the
            // hook, FORECAST_BATCH_SUBMIT if submission failed after it.
            PipelinePhase failed = pipelineRunService.findById(runId)
                    .map(PipelineRunEntity::getCurrentPhase)
                    .orElse(firstPhase);
            pipelineRunService.failPhase(runId, failed, e.getMessage());
            throw e;
        }
    }

    private void waitAndBriefPhase(Long runId) {
        try {
            // Idempotent re-entry: if we're resuming a run already mid-WAIT,
            // mid-RETRY_FAILED, or mid-BRIEFING, don't re-run earlier phases or
            // double-start their rows. The phases run in order WAIT → RETRY_FAILED
            // (conditional) → BRIEFING.
            PipelineRunEntity run = pipelineRunService.findById(runId).orElseThrow();
            PipelinePhase phase = run.getCurrentPhase();
            boolean atOrPastBrief = phase == PipelinePhase.BRIEFING;
            boolean atOrPastRetry = atOrPastBrief || phase == PipelinePhase.RETRY_FAILED;

            if (!atOrPastRetry && phase != PipelinePhase.FORECAST_BATCH_WAIT) {
                pipelineRunService.startPhase(runId, PipelinePhase.FORECAST_BATCH_WAIT);
            }
            if (!atOrPastRetry) {
                BatchCompletionResult result = waitForBatchSetComplete(runId);
                pipelineRunService.completePhase(runId, PipelinePhase.FORECAST_BATCH_WAIT,
                        result.summary());
            }

            // RETRY_FAILED — conditional phase. Runs only when the cycle's precursor
            // batches left retryable transient failures within the cap; a clean cycle
            // records no phase. Placed before BRIEFING so recovered locations are in
            // the data the briefing synthesises.
            if (!atOrPastBrief) {
                retryFailedPhase(runId, phase == PipelinePhase.RETRY_FAILED);
            }

            pipelineRunService.startPhase(runId, PipelinePhase.BRIEFING);
            try {
                briefingService.refreshBriefing();
                persistPicksForCycle(runId);
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
            // makes this distinguishable from a genuine phase failure. The retry
            // batch shares this same timeout, so the fired-in phase is whichever
            // wait was in flight (WAIT or RETRY_FAILED).
            PipelinePhase failedPhase = pipelineRunService.findById(runId)
                    .map(PipelineRunEntity::getCurrentPhase)
                    .orElse(PipelinePhase.FORECAST_BATCH_WAIT);
            LOG.error("Pipeline run {}: SAFETY TIMEOUT hit in phase {} — {}",
                    runId, failedPhase, e.getMessage());
            pipelineRunService.failPhase(runId, failedPhase, e.getMessage());
            pipelineRunService.failRun(runId, "Safety timeout: " + e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Pipeline run {}: wait/brief tail failed — {}", runId, e.getMessage(), e);
            pipelineRunService.failRun(runId, "Wait/brief tail failed: " + e.getMessage());
        }
    }

    /**
     * The conditional RETRY_FAILED phase: after the precursor batches are terminal,
     * re-submit the cycle's genuinely-failed forecast requests (parse failures / API
     * errors) once, capped, and wait for the recovered results to land.
     *
     * <ul>
     *   <li><b>NONE</b> (no failures): on a fresh run records no phase at all (keeps
     *       the timeline clean for the common clean cycle); on a resume records the
     *       no-op detail to close the already-started phase row.</li>
     *   <li><b>SYSTEMATIC</b> (over cap): records the phase with a "not retried —
     *       systematic failure" detail. Not retried — re-submitting en masse would be
     *       expensive and useless.</li>
     *   <li><b>RETRY</b> (within cap): submits one retry batch tagged with this cycle
     *       (idempotent — skipped if a retry batch already exists from a pre-restart
     *       attempt), waits for it to reach terminal status (sharing the safety
     *       timeout; recovered results merge into the cache via the normal polling
     *       path before the batch is terminal), then records the recovery summary.</li>
     * </ul>
     *
     * @param runId    pipeline run id
     * @param resuming {@code true} when re-entering an already-started RETRY_FAILED
     *                 phase after a restart (do not re-start the phase row)
     */
    private void retryFailedPhase(Long runId, boolean resuming) {
        RetrySelection selection = batchRetryService.selectFailures(runId);

        if (selection.decision() == RetrySelection.Decision.NONE && !resuming) {
            return;
        }
        if (!resuming) {
            pipelineRunService.startPhase(runId, PipelinePhase.RETRY_FAILED);
        }

        switch (selection.decision()) {
            case NONE -> pipelineRunService.completePhase(runId, PipelinePhase.RETRY_FAILED,
                    "0 failed, nothing to retry");
            case SYSTEMATIC -> pipelineRunService.completePhase(runId, PipelinePhase.RETRY_FAILED,
                    selection.failureCount() + " failed — exceeds cap " + selection.cap()
                            + ", NOT retried (systematic failure — investigate)");
            case RETRY -> {
                batchRetryService.submitRetry(runId, selection);
                waitForBatchSetComplete(runId);
                String detail = batchRetryService.summariseRecovery(
                        runId, selection.failureCount());
                pipelineRunService.completePhase(runId, PipelinePhase.RETRY_FAILED, detail);
                LOG.info("Pipeline run {}: RETRY_FAILED — {}", runId, detail);
            }
            default -> throw new IllegalStateException(
                    "Unhandled retry decision: " + selection.decision());
        }
    }

    /**
     * Reads the freshly-refreshed briefing's best-bet picks and persists them
     * against this cycle. Called after a successful
     * {@code briefingService.refreshBriefing()} and before
     * {@code completePhase(BRIEFING)} so the picks are committed within the
     * BRIEFING phase boundary.
     *
     * <p>Pick persistence is observability, not correctness — a failure here
     * must NOT fail the BRIEFING phase or the run. The primary defense is
     * inside {@link PipelineRunPickService#persist} (which swallows exceptions
     * by contract). The belt-and-braces try/catch here exists so even a
     * contract violation cannot fail a briefing that actually succeeded.
     * The orchestrator does not see anywhere on the success path where a
     * persist exception is the correct trigger for marking BRIEFING FAILED.
     *
     * <p>The {@code null} service guard exists for tests that pass a null
     * service via the package-private constructor.
     *
     * <p>A stale briefing is skipped: on a below-threshold run
     * {@code BriefingService.refreshBriefing()} serves the last-known-good
     * briefing, whose {@code bestBets} are the PREVIOUS cycle's picks carried
     * forward — not this run's. Persisting them would record the prior run's
     * picks against this {@code runId}, silently corrupting the cross-run
     * "did Plan A change?" comparison this table exists to power.
     */
    private void persistPicksForCycle(Long runId) {
        if (pipelineRunPickService == null) {
            return;
        }
        try {
            DailyBriefingResponse briefing = briefingService.getCachedBriefing();
            if (briefing == null) {
                LOG.info("Pipeline run {}: no cached briefing after refresh — "
                        + "skipping pick persistence", runId);
                return;
            }
            if (briefing.stale()) {
                LOG.info("Pipeline run {}: briefing served stale (below-threshold run) — "
                        + "its picks are carried-forward last-known-good, not this cycle's; "
                        + "skipping pick persistence", runId);
                return;
            }
            // Record this run's best-bet outcome regardless of whether picks exist, so the
            // run history distinguishes "good picks" / "honest decline" / "advisor failed".
            BestBetStatus status = briefing.bestBetStatus();
            pipelineRunService.recordBestBetStatus(runId, status);
            // Persist pick rows ONLY for a genuine SUCCESS_WITH_PICKS — a SUCCESS_NO_PICKS
            // (honest decline) or FAILED run has no picks of its own to record, and the
            // fallback reads pick rows expecting them to belong to a successful run.
            if (status == BestBetStatus.SUCCESS_WITH_PICKS) {
                pipelineRunPickService.persist(runId, briefing.bestBets());
            } else {
                LOG.info("Pipeline run {}: best-bet status {} — no own picks to persist",
                        runId, status);
            }
        } catch (RuntimeException e) {
            LOG.warn("Pipeline run {}: pick persistence raised an exception — "
                    + "logged and ignored (BRIEFING phase remains valid): {}",
                    runId, e.getMessage());
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
