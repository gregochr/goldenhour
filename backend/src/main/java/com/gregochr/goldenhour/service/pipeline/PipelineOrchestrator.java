package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelinePhaseStatus;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.model.BestBetStatus;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastCycleJobs;
import com.gregochr.goldenhour.service.LocationFailureService;
import com.gregochr.goldenhour.service.ask.AskReadyPrecompute;
import com.gregochr.goldenhour.service.batch.BatchSubmissionSummary;
import com.gregochr.goldenhour.service.batch.CandidateCollectionStrategy;
import com.gregochr.goldenhour.service.batch.EligibilityPolicy;
import com.gregochr.goldenhour.service.batch.ForecastBatchSubmissionOutcome;
import com.gregochr.goldenhour.service.batch.EveningCandidateCollectionStrategy;
import com.gregochr.goldenhour.service.batch.EveningEligibilityPolicy;
import com.gregochr.goldenhour.service.batch.IntradayCandidateCollectionStrategy;
import com.gregochr.goldenhour.service.batch.IntradayEligibilityPolicy;
import com.gregochr.goldenhour.service.batch.BatchRetryService;
import com.gregochr.goldenhour.service.batch.NightlyCandidateCollectionStrategy;
import com.gregochr.goldenhour.service.batch.NightlyEligibilityPolicy;
import com.gregochr.goldenhour.service.batch.ReclassSummary;
import com.gregochr.goldenhour.service.batch.RetrySelection;
import com.gregochr.goldenhour.service.batch.RetrySubmitResult;
import com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * {@code photocast.pipeline.safety-timeout} (ISO-8601 duration, default PT3H30M)
 * because Anthropic batch latency is load-dependent — see
 * {@link #DEFAULT_SAFETY_TIMEOUT} for the empirical calibration.
 *
 * <p><b>One budget per cycle (solar-relative-refresh-plan D3).</b> WAIT and RETRY_FAILED share a
 * single deadline: the earliest {@code forecast_batch.submitted_at} of the run plus the safety
 * timeout, read from the database on every poll. A restart therefore cannot extend it, and the
 * retry does not get a fresh allowance, so a cycle can never stay RUNNING for more than the
 * timeout after its first submission (before this, WAIT then RETRY_FAILED could hold a run open
 * for twice the timeout, longer than the shortest gap between two cycles). A run with no batch
 * to measure from (none submitted, so the wait returns at once) falls back to "now + timeout".
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
     * <p>Calibrated empirically: nightly batches reach terminal in 2–5 min, but
     * afternoon (~14:00 UTC, peak Anthropic load) batches were observed taking up
     * to 181 min with every request succeeding. 3 h 30 min clears the slowest of
     * 49 observed cycles with margin while staying under the shortest gap between
     * two cycles (6.4 h) once WAIT and RETRY_FAILED share it (D3). Tunable without
     * a deploy via the {@code photocast.pipeline.safety-timeout} property.
     */
    public static final Duration DEFAULT_SAFETY_TIMEOUT = Duration.ofMinutes(210);

    /**
     * The least budget a retry batch is worth submitting with. A retry of a nightly cycle
     * typically lands within minutes; with less than this left it could not be awaited, and a
     * batch submitted only to be abandoned would fail an otherwise good run on the timeout.
     */
    static final Duration MIN_RETRY_BUDGET = Duration.ofMinutes(10);

    /** Least value {@code photocast.pipeline.evening-safety-timeout} may take (startup fails below). */
    static final Duration MIN_EVENING_SAFETY_TIMEOUT = Duration.ofMinutes(30);

    /** Greatest value {@code photocast.pipeline.evening-safety-timeout} may take (startup fails above). */
    static final Duration MAX_EVENING_SAFETY_TIMEOUT = DEFAULT_SAFETY_TIMEOUT;

    private final PipelineRunService pipelineRunService;
    private final ScheduledBatchEvaluationService scheduledBatchEvaluationService;
    private final BriefingService briefingService;
    private final ForecastBatchRepository forecastBatchRepository;
    private final Clock clock;
    private final Executor backgroundExecutor;
    private final Duration pollInterval;
    private final Duration safetyTimeout;
    private final Map<CycleType, Duration> cycleBudgets;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final PipelineRunPickService pipelineRunPickService;
    private final BatchRetryService batchRetryService;
    private final AdminAlertService adminAlertService;
    private final LocationFailureService locationFailureService;
    private final AskReadyPrecompute askReadyPrecompute;
    private final AlmanacService almanacService;

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
     * @param adminAlertService               emails enabled ADMINs when a cycle is marked DEGRADED
     * @param locationFailureService          settles each cycle's per-place failure counting
     * @param askReadyPrecompute                 precomputes Ask PhotoCast's Ready answers once the
     *                                        cycle's run is finished
     * @param almanacService                  rebuilds the "Coming up" feed's cache once the cycle's
     *                                        run is finished
     * @param eveningSafetyTimeout            wait budget of the evening cycle
     *                                        ({@code photocast.pipeline.evening-safety-timeout},
     *                                        default PT2H); must lie within
     *                                        {@code MIN_EVENING_SAFETY_TIMEOUT} and
     *                                        {@code MAX_EVENING_SAFETY_TIMEOUT} or startup fails
     */
    @Autowired
    public PipelineOrchestrator(PipelineRunService pipelineRunService,
            ScheduledBatchEvaluationService scheduledBatchEvaluationService,
            BriefingService briefingService,
            ForecastBatchRepository forecastBatchRepository,
            Clock clock,
            @Value("${photocast.pipeline.safety-timeout:PT3H30M}") Duration safetyTimeout,
            DynamicSchedulerService dynamicSchedulerService,
            PipelineRunPickService pipelineRunPickService,
            BatchRetryService batchRetryService,
            AdminAlertService adminAlertService,
            LocationFailureService locationFailureService,
            AskReadyPrecompute askReadyPrecompute,
            AlmanacService almanacService,
            @Value("${photocast.pipeline.evening-safety-timeout:PT2H}") Duration eveningSafetyTimeout) {
        this(pipelineRunService, scheduledBatchEvaluationService, briefingService,
                forecastBatchRepository, clock,
                Executors.newVirtualThreadPerTaskExecutor(),
                DEFAULT_POLL_INTERVAL, safetyTimeout,
                dynamicSchedulerService, pipelineRunPickService, batchRetryService,
                adminAlertService, locationFailureService, askReadyPrecompute, almanacService,
                budgetsFrom(safetyTimeout, eveningSafetyTimeout));
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
     * @param adminAlertService               emails enabled ADMINs when a cycle is marked DEGRADED;
     *                                        tests may pass {@code null} to skip alerting
     * @param locationFailureService          settles each cycle's per-place failure counting
     *                                        (required; there is no null guard for it)
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
            BatchRetryService batchRetryService,
            AdminAlertService adminAlertService,
            LocationFailureService locationFailureService) {
        this(pipelineRunService, scheduledBatchEvaluationService, briefingService,
                forecastBatchRepository, clock, backgroundExecutor, pollInterval, safetyTimeout,
                dynamicSchedulerService, pipelineRunPickService, batchRetryService,
                adminAlertService, locationFailureService, null);
    }

    /**
     * Full constructor with Ask PhotoCast's Ready precompute, for tests that exercise the dispatch
     * after the run is finished. The constructor above is this one with no precompute.
     *
     * @param pipelineRunService              pipeline run / phase persistence
     * @param scheduledBatchEvaluationService forecast batch submitter (cycle-aware variant)
     * @param briefingService                 briefing refresh entry point
     * @param forecastBatchRepository         queried for cycle completion
     * @param clock                           injectable clock
     * @param backgroundExecutor              where to run the wait+briefing tail and the precompute
     * @param pollInterval                    DB poll interval during FORECAST_BATCH_WAIT
     * @param safetyTimeout                   safety backstop for the wait phase
     * @param dynamicSchedulerService         scheduler the orchestrator registers itself with
     * @param pipelineRunPickService          persists each cycle's Plan A / Plan B picks
     * @param batchRetryService               selects + re-submits transient failures (RETRY_FAILED)
     * @param adminAlertService               emails enabled ADMINs when a cycle is marked DEGRADED
     * @param locationFailureService          settles each cycle's per-place failure counting
     * @param askReadyPrecompute                 precomputes the Ready answers after the run is
     *                                        finished; {@code null} skips it
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
            BatchRetryService batchRetryService,
            AdminAlertService adminAlertService,
            LocationFailureService locationFailureService,
            AskReadyPrecompute askReadyPrecompute) {
        this(pipelineRunService, scheduledBatchEvaluationService, briefingService,
                forecastBatchRepository, clock, backgroundExecutor, pollInterval, safetyTimeout,
                dynamicSchedulerService, pipelineRunPickService, batchRetryService,
                adminAlertService, locationFailureService, askReadyPrecompute, null);
    }

    /**
     * Full constructor with both post-run hand-overs: Ask PhotoCast's Ready precompute and the
     * "Coming up" feed's cache refresh. The constructor above is this one with no feed refresh.
     *
     * @param pipelineRunService              pipeline run / phase persistence
     * @param scheduledBatchEvaluationService forecast batch submitter (cycle-aware variant)
     * @param briefingService                 briefing refresh entry point
     * @param forecastBatchRepository         queried for cycle completion
     * @param clock                           injectable clock
     * @param backgroundExecutor              where to run the wait+briefing tail and both hand-overs
     * @param pollInterval                    DB poll interval during FORECAST_BATCH_WAIT
     * @param safetyTimeout                   safety backstop for the wait phase
     * @param dynamicSchedulerService         scheduler the orchestrator registers itself with
     * @param pipelineRunPickService          persists each cycle's Plan A / Plan B picks
     * @param batchRetryService               selects + re-submits transient failures (RETRY_FAILED)
     * @param adminAlertService               emails enabled ADMINs when a cycle is marked DEGRADED
     * @param locationFailureService          settles each cycle's per-place failure counting
     * @param askReadyPrecompute                 precomputes the Ready answers after the run is
     *                                        finished; {@code null} skips it
     * @param almanacService                  rebuilds the "Coming up" feed after the run is
     *                                        finished; {@code null} skips it
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
            BatchRetryService batchRetryService,
            AdminAlertService adminAlertService,
            LocationFailureService locationFailureService,
            AskReadyPrecompute askReadyPrecompute,
            AlmanacService almanacService) {
        this(pipelineRunService, scheduledBatchEvaluationService, briefingService,
                forecastBatchRepository, clock, backgroundExecutor, pollInterval, safetyTimeout,
                dynamicSchedulerService, pipelineRunPickService, batchRetryService,
                adminAlertService, locationFailureService, askReadyPrecompute, almanacService,
                budgetsFrom(safetyTimeout));
    }

    /**
     * Full constructor with a wait budget per cycle type, for tests that need a cycle type's budget
     * to differ from the others'. The constructor above gives every type {@code safetyTimeout}.
     *
     * @param pipelineRunService              pipeline run / phase persistence
     * @param scheduledBatchEvaluationService forecast batch submitter (cycle-aware variant)
     * @param briefingService                 briefing refresh entry point
     * @param forecastBatchRepository         queried for cycle completion
     * @param clock                           injectable clock
     * @param backgroundExecutor              where to run the wait+briefing tail and both hand-overs
     * @param pollInterval                    DB poll interval during FORECAST_BATCH_WAIT
     * @param safetyTimeout                   fallback budget for a run with no cycle type
     * @param dynamicSchedulerService         scheduler the orchestrator registers itself with
     * @param pipelineRunPickService          persists each cycle's Plan A / Plan B picks
     * @param batchRetryService               selects + re-submits transient failures (RETRY_FAILED)
     * @param adminAlertService               emails enabled ADMINs when a cycle is marked DEGRADED
     * @param locationFailureService          settles each cycle's per-place failure counting
     * @param askReadyPrecompute              precomputes the Ready answers; {@code null} skips it
     * @param almanacService                  rebuilds the "Coming up" feed; {@code null} skips it
     * @param cycleBudgets                    the wait budget of each cycle type
     */
    PipelineOrchestrator(PipelineRunService pipelineRunService,
            ScheduledBatchEvaluationService scheduledBatchEvaluationService,
            BriefingService briefingService,
            ForecastBatchRepository forecastBatchRepository,
            Clock clock,
            Executor backgroundExecutor,
            Duration pollInterval,
            Duration safetyTimeout,
            DynamicSchedulerService dynamicSchedulerService,
            PipelineRunPickService pipelineRunPickService,
            BatchRetryService batchRetryService,
            AdminAlertService adminAlertService,
            LocationFailureService locationFailureService,
            AskReadyPrecompute askReadyPrecompute,
            AlmanacService almanacService,
            Map<CycleType, Duration> cycleBudgets) {
        this.cycleBudgets = Map.copyOf(cycleBudgets);
        this.pipelineRunService = pipelineRunService;
        this.scheduledBatchEvaluationService = scheduledBatchEvaluationService;
        this.briefingService = briefingService;
        this.forecastBatchRepository = forecastBatchRepository;
        this.clock = clock;
        this.backgroundExecutor = backgroundExecutor;
        this.pollInterval = pollInterval;
        this.safetyTimeout = safetyTimeout;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.adminAlertService = adminAlertService;
        this.pipelineRunPickService = pipelineRunPickService;
        this.batchRetryService = batchRetryService;
        this.locationFailureService = locationFailureService;
        this.askReadyPrecompute = askReadyPrecompute;
        this.almanacService = almanacService;
    }

    /**
     * The per-type budgets with the evening's own, validated: an out-of-range value fails startup.
     */
    static Map<CycleType, Duration> budgetsFrom(Duration safetyTimeout, Duration eveningSafetyTimeout) {
        if (eveningSafetyTimeout == null
                || eveningSafetyTimeout.compareTo(MIN_EVENING_SAFETY_TIMEOUT) < 0
                || eveningSafetyTimeout.compareTo(MAX_EVENING_SAFETY_TIMEOUT) > 0) {
            throw new IllegalStateException("photocast.pipeline.evening-safety-timeout must be between "
                    + MIN_EVENING_SAFETY_TIMEOUT + " and " + MAX_EVENING_SAFETY_TIMEOUT
                    + " but was " + eveningSafetyTimeout);
        }
        Map<CycleType, Duration> budgets = budgetsFrom(safetyTimeout);
        budgets.put(CycleType.EVENING, eveningSafetyTimeout);
        return budgets;
    }

    private static Map<CycleType, Duration> budgetsFrom(Duration safetyTimeout) {
        Map<CycleType, Duration> budgets = new EnumMap<>(CycleType.class);
        for (CycleType type : CycleType.values()) {
            budgets.put(type, safetyTimeout);
        }
        return budgets;
    }

    /**
     * The wait budget of a cycle type; the configured safety timeout when the type is unknown.
     */
    private Duration budgetFor(CycleType cycleType) {
        return cycleType == null ? safetyTimeout : cycleBudgets.getOrDefault(cycleType, safetyTimeout);
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
            dynamicSchedulerService.registerJobTarget(
                    ForecastCycleJobs.EVENING_JOB, this::runEveningCycle);
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
     * <p>Whether tonight's evening refresh is due is resolved once here; if it is, an already-rated
     * T+1 sunrise is left for it (see {@link IntradayCandidateCollectionStrategy#defersRatedSlot}).
     *
     * <p>Bound to the {@code intraday_forecast_refresh} schedule (14:00 UTC by default) via
     * {@link #registerJobTarget()}. Because it goes through {@code runCycle} with
     * {@link CycleType#INTRADAY}, the submit phase runs ephemerally (the morning
     * snapshot is preserved) and records a {@code STABILITY_RECLASSIFY} phase
     * before {@code FORECAST_BATCH_SUBMIT}; wait, briefing and best-bet persistence
     * are identical to nightly.
     */
    public void runIntradayCycle() {
        boolean eveningDue = dynamicSchedulerService != null
                && dynamicSchedulerService.firesLaterTodayUk(ForecastCycleJobs.EVENING_JOB);
        runCycle(CycleType.INTRADAY,
                new IntradayCandidateCollectionStrategy(clock, eveningDue),
                IntradayEligibilityPolicy.INSTANCE);
    }

    /**
     * Evening refresh entry point: one look at tomorrow's sunrise at 21:00 UK time, so the bedtime
     * decision rests on a forecast hours fresher than the afternoon's. Bound to the
     * {@code evening_sunrise_refresh} schedule (seeded PAUSED). It differs from the other cycles in
     * its strategy (tomorrow's sunrise alone, bypassing the cache gate), its policy (every stability),
     * its own wait budget and in giving no failure evidence ({@link CycleType#EVENING}).
     */
    public void runEveningCycle() {
        runCycle(CycleType.EVENING,
                new EveningCandidateCollectionStrategy(clock),
                EveningEligibilityPolicy.INSTANCE);
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
     * @param cycleType         which cycle is being run — both are live; INTRADAY
     *                          enters via {@link #runIntradayCycle()} above
     * @param candidateStrategy filter deciding which event slots enter the
     *                          candidate set
     * @param eligibilityPolicy per-candidate include/skip decision function
     */
    public void runCycle(CycleType cycleType,
            CandidateCollectionStrategy candidateStrategy,
            EligibilityPolicy eligibilityPolicy) {
        PipelineRunEntity run = pipelineRunService.startRun(cycleType);
        boolean submitted;
        try {
            submitted = submitPhase(run.getId(), cycleType, candidateStrategy, eligibilityPolicy);
        } catch (Exception e) {
            LOG.error("Pipeline run {} ({}): submission phase failed — {}", run.getId(),
                    cycleType, e.getMessage(), e);
            pipelineRunService.failRun(run.getId(), "Submit phase failed: " + e.getMessage());
            return;
        }
        if (!submitted) {
            // submitPhase already failed the run — this cycle's submission was dropped
            // because another run held the guard. Do not dispatch the wait/brief tail:
            // there are no batches of this run's own to wait for, and briefing now would
            // brief from stale cache while the winning run is still in flight.
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
        boolean submitted;
        try {
            submitted = submitPhase(runId, CycleType.NIGHTLY,
                    NightlyCandidateCollectionStrategy.INSTANCE,
                    NightlyEligibilityPolicy.INSTANCE);
        } catch (Exception e) {
            pipelineRunService.failRun(runId, "Submit phase failed: " + e.getMessage());
            return;
        }
        if (!submitted) {
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
     *
     * <p>After the resume, and whether or not anything was running, sweeps the location-failure
     * settles that were never claimed ({@link LocationFailureService#sweepUnsettledRuns()}): a cycle
     * whose settle failed, or that was never settled, is already COMPLETED, FAILED or DEGRADED and so
     * is never resumed here, and this sweep is what settles it. Runs resumed above are still RUNNING
     * and are left to their own tail.
     *
     * <p>Deliberately NOT an event listener itself: {@code @Profile} is a {@code @Conditional}, which
     * Spring evaluates only on bean definitions, never on an {@code @EventListener} method, so a
     * profile guard on this method was a silent no-op. {@link PipelineOrchestratorStartup} listens for
     * {@code ApplicationReadyEvent} and carries the profile guard at class level, where it works.
     */
    public void resumeRunningCyclesOnStartup() {
        resumeRunning();
        sweepUnsettledLocationFailures();
    }

    private void sweepUnsettledLocationFailures() {
        try {
            int settled = locationFailureService.sweepUnsettledRuns();
            if (settled > 0) {
                LOG.info("Startup sweep settled the location failures of {} earlier pipeline "
                        + "run(s)", settled);
            }
        } catch (RuntimeException e) {
            LOG.error("Startup sweep of unsettled location failures raised an exception, the "
                    + "next tail settle or restart retries it: {}", e.getMessage(), e);
        }
    }

    private void resumeRunning() {
        List<PipelineRunEntity> running = pipelineRunService.findRunning();
        if (running.isEmpty()) {
            return;
        }
        LOG.info("Found {} RUNNING pipeline run(s) at startup — attempting resume",
                running.size());
        for (PipelineRunEntity run : running) {
            PipelinePhase phase = run.getCurrentPhase();
            if (PipelinePhase.isPreSubmission(phase)) {
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
     *
     * <p><b>A dropped submission fails the run outright.</b> The batch service's
     * submission guard is a single {@code AtomicBoolean} shared by every trigger
     * (scheduled and admin-fired); when this cycle loses that CAS, no batch of
     * its own was submitted. Completing {@code FORECAST_BATCH_SUBMIT} anyway
     * would record a phase that did nothing as successful, and letting the caller
     * proceed to the wait/briefing tail would brief from whatever cache the
     * winning run leaves behind — the exact overlap this method exists to
     * prevent. The winning run's own cycle self-corrects when its batches land.
     *
     * @return {@code true} if this run's submission actually ran (whether or not
     *         any batch ended up non-empty); {@code false} if the submission was
     *         dropped because another run already held the guard — the caller
     *         must not dispatch the wait/brief tail in that case
     */
    private boolean submitPhase(Long runId, CycleType cycleType,
            CandidateCollectionStrategy candidateStrategy,
            EligibilityPolicy eligibilityPolicy) {
        boolean reclassify = cycleType.recordsReclassifyPhase();
        PipelinePhase firstPhase = reclassify
                ? PipelinePhase.STABILITY_RECLASSIFY
                : PipelinePhase.FORECAST_BATCH_SUBMIT;
        pipelineRunService.startPhase(runId, firstPhase);

        // For intraday only, the hook closes STABILITY_RECLASSIFY (recording the
        // cost-gate summary) and opens FORECAST_BATCH_SUBMIT between the collect
        // and submit steps. For nightly the hook is a no-op and the single
        // FORECAST_BATCH_SUBMIT phase spans both steps.
        Consumer<ReclassSummary> betweenSteps = reclassify
                ? summary -> {
                    pipelineRunService.completePhase(runId,
                            PipelinePhase.STABILITY_RECLASSIFY, summary.detail());
                    pipelineRunService.startPhase(runId, PipelinePhase.FORECAST_BATCH_SUBMIT);
                }
                : summary -> { };

        try {
            ForecastBatchSubmissionOutcome outcome =
                    scheduledBatchEvaluationService.submitForecastBatchForPipelineRun(
                            runId, candidateStrategy, eligibilityPolicy,
                            !cycleType.publishesStabilitySnapshot(), betweenSteps);
            if (!outcome.submitted()) {
                String reason = "Forecast batch submission dropped — another pipeline run "
                        + "already holds the submission guard (overlapping cycle trigger)";
                LOG.warn("Pipeline run {} ({}): {}", runId, cycleType, reason);
                // Fail the phase in flight BEFORE failing the run, exactly as the catch
                // block below does. failRun touches only the parent row, so without this the
                // started phase stays RUNNING forever and the Operations timeline shows an
                // ever-growing "so far" duration against a run that is already terminal.
                // Read the current phase rather than assuming FORECAST_BATCH_SUBMIT: on the
                // intraday path the betweenSteps hook may not have fired yet, leaving
                // STABILITY_RECLASSIFY in flight.
                PipelinePhase inFlight = pipelineRunService.findById(runId)
                        .map(PipelineRunEntity::getCurrentPhase)
                        .orElse(firstPhase);
                pipelineRunService.failPhase(runId, inFlight, reason);
                pipelineRunService.failRun(runId, reason);
                return false;
            }
            // The submission ran, but not every bucket necessarily reached Anthropic. The phase
            // itself is marked FAILED (never the run — the run must still WAIT and BRIEF, briefing
            // from whatever cache exists, which is the correct fallback) so the failure is visible
            // in the phase timeline and so waitAndBriefPhase can read it back — including after a
            // process restart, since the phase row is the durable record — to decide DEGRADED vs
            // COMPLETED once the cycle finishes. See PipelineRunStatus#DEGRADED.
            BatchSubmissionSummary summary = outcome.summary();
            if (summary != null && !summary.allSucceeded()) {
                pipelineRunService.failPhase(runId, PipelinePhase.FORECAST_BATCH_SUBMIT,
                        summary.detail());
            } else {
                pipelineRunService.completePhase(runId, PipelinePhase.FORECAST_BATCH_SUBMIT, null);
            }
            return true;
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
            Duration budget = budgetFor(run.getCycleType());
            PipelinePhase phase = run.getCurrentPhase();
            boolean atOrPastBrief = phase == PipelinePhase.BRIEFING;
            boolean atOrPastRetry = atOrPastBrief || phase == PipelinePhase.RETRY_FAILED;

            if (!atOrPastRetry && phase != PipelinePhase.FORECAST_BATCH_WAIT) {
                pipelineRunService.startPhase(runId, PipelinePhase.FORECAST_BATCH_WAIT);
            }
            if (!atOrPastRetry) {
                BatchCompletionResult result = waitForBatchSetComplete(runId, budget);
                pipelineRunService.completePhase(runId, PipelinePhase.FORECAST_BATCH_WAIT,
                        result.summary());
            }

            // RETRY_FAILED — conditional phase. Runs only when the cycle's precursor
            // batches left retryable transient failures within the cap; a clean cycle
            // records no phase. Placed before BRIEFING so recovered locations are in
            // the data the briefing synthesises.
            if (!atOrPastBrief) {
                retryFailedPhase(runId, phase == PipelinePhase.RETRY_FAILED, budget);
            }

            // A run resumed mid-BRIEFING already has a BRIEFING phase row — reuse it
            // rather than starting a second one (V148 fixed the duplicate-pick fallout
            // of getting this wrong). refreshBriefing()/persistPicksForCycle() below
            // still re-run either way; that is deliberate (see class javadoc — a
            // republished briefing is harmless), and persistPicksForCycle upserts.
            if (!atOrPastBrief) {
                pipelineRunService.startPhase(runId, PipelinePhase.BRIEFING);
            }
            // Settled on EVERY path to the briefing, a run resumed at BRIEFING included: the claim
            // is durable (pipeline_run.failures_settled_at), so a run the process stopped after
            // startPhase(BRIEFING) committed but before the settle completed is settled here on
            // resume, and a run already settled is refused by the claim.
            settleLocationFailures(run);
            try {
                briefingService.refreshBriefing();
                persistPicksForCycle(runId);
                pipelineRunService.completePhase(runId, PipelinePhase.BRIEFING, null);
            } catch (RuntimeException e) {
                pipelineRunService.failPhase(runId, PipelinePhase.BRIEFING, e.getMessage());
                pipelineRunService.failRun(runId, "Briefing failed: " + e.getMessage());
                return;
            }

            finishRun(runId);
            dispatchAlmanacRefresh(runId);
            dispatchAskReady(runId);
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
     * Hands the "Coming up" feed's cache refresh to the background executor, <b>after</b>
     * {@link #finishRun} for the same reasons {@link #dispatchAskReady} gives: the run is terminal
     * first, the work is never on the run's thread, and nothing it does can reach the run.
     *
     * <p>Why here: the feed's chronology is rebuilt from the data this cycle has just written, and the
     * feed is cached for the whole UK day, so without a refresh it would stay as the day's first
     * request left it. The refresh builds the new feed completely
     * before replacing the old one, so no reader sees an empty cache while it runs. This method must
     * never throw, for the same reason as the Ask dispatch.
     *
     * @param runId the pipeline run that has just finished
     */
    private void dispatchAlmanacRefresh(Long runId) {
        if (almanacService == null) {
            return;
        }
        dispatchAfterRun(runId, "the Coming up feed refresh", almanacService::refresh);
    }

    /**
     * Hands Ask PhotoCast's Ready precompute to the background executor, <b>after</b>
     * {@link #finishRun} has marked the run terminal and never before.
     *
     * <p>Why after: a run held RUNNING makes every later tail settle {@code RESETS_ONLY} (see
     * {@link #settleLocationFailures}), and a precompute makes up to ~35 model calls under a
     * 5-minute deadline. So the run is COMPLETED (or DEGRADED) first, and nothing the precompute does
     * is on the run's thread, in its phases or in its status: the work is queued on the executor, and
     * a failure to queue it, an exception inside it or an overrun of its deadline is logged and
     * stops there. This method must never throw: it runs inside {@link #waitAndBriefPhase}'s try,
     * whose catch would otherwise mark an already-finished run FAILED.
     *
     * @param runId the pipeline run that has just finished
     */
    private void dispatchAskReady(Long runId) {
        if (askReadyPrecompute == null) {
            return;
        }
        dispatchAfterRun(runId, "the Ask Ready precompute", () -> askReadyPrecompute.precompute(runId));
    }

    /**
     * Queues one piece of after-the-run work on the background executor. Both catches are the
     * invariant: this must never throw, because it runs inside {@link #waitAndBriefPhase}'s try, whose
     * catch would otherwise mark an already-finished run FAILED. A failure to queue the work and an
     * exception inside it are each logged and stop there.
     *
     * @param runId the pipeline run that has just finished
     * @param what  what the work is, for the log line
     * @param work  the work to run off the run's thread
     */
    private void dispatchAfterRun(Long runId, String what, Runnable work) {
        try {
            backgroundExecutor.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    LOG.warn("Pipeline run {}: {} raised an exception — logged and ignored "
                            + "(the run is already finished): {}", runId, what, e.toString());
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("Pipeline run {}: {} could not be dispatched — logged and ignored "
                    + "(the run is already finished): {}", runId, what, e.toString());
        }
    }

    /**
     * Settles this cycle's per-place failure counting: the one place a scheduled cycle's outcomes
     * are turned into consecutive-failure counts and, eventually, auto-disables
     * ({@link LocationFailureService}).
     *
     * <p><b>Why here.</b> After the wait, so every batch result has landed or the safety timeout
     * has thrown (a timed-out cycle never reaches this line, so nothing is counted for a cycle whose
     * results are unknown); after RETRY_FAILED, so a request the retry recovered counts as a success;
     * and before {@code refreshBriefing()}, so a briefing failure, which returns early, cannot skip
     * it, and a place just disabled is already gone from the briefing built next. It runs on every
     * path to the briefing, including a run resumed after a restart at any phase, because the
     * service's claim ({@code pipeline_run.failures_settled_at}) is durable and atomic with the
     * counter writes: a cycle is settled exactly once, a cycle the process stopped before settling
     * is settled on resume, and a cycle already settled is refused. A result for this cycle arriving
     * after this point cannot count.
     *
     * <p>Best-effort: counting is housekeeping, never a reason to fail the briefing. A settle that
     * throws is logged at ERROR and the run carries on to completion; the settle's transaction has
     * rolled back, so the cycle is left unclaimed and the next sweep
     * ({@link LocationFailureService#sweepUnsettledRuns()}, run at startup and at the start of every
     * tail settle) settles it, in RESETS_ONLY mode. The forecast
     * buttons and map Run Forecast (hand-started runs) never reach this method: only
     * {@link #waitAndBriefPhase} calls it, and that runs only for a pipeline cycle. The scheduler's
     * Run now on the nightly or intraday job does run a pipeline cycle, so it settles like any other.
     *
     * @param run the pipeline run whose batches have just completed
     */
    private void settleLocationFailures(PipelineRunEntity run) {
        try {
            locationFailureService.settleCycle(run);
        } catch (RuntimeException e) {
            LOG.error("Pipeline run {}: location failure settle raised an exception, the cycle "
                    + "is left unsettled for the next sweep (the briefing continues): {}",
                    run.getId(), e.getMessage(), e);
        }
    }

    /**
     * Marks the run terminal on the success path — {@link PipelineRunStatus#COMPLETED} in the
     * ordinary case, or {@link PipelineRunStatus#DEGRADED} when EITHER this cycle's own
     * {@code FORECAST_BATCH_SUBMIT} phase (see {@code submitPhase}) OR its {@code RETRY_FAILED}
     * phase (see {@link #retryFailedPhase}) was left {@code FAILED}. The two are independent
     * facts — a cycle can lose a forecast batch, a retry batch, or both — and when both failed the
     * DEGRADED reason names both, not just the first one checked.
     *
     * <p>Reads both phase rows back from {@link PipelineRunService#findLatestPhase} rather than
     * carrying anything through in memory, so the decision is correct even when this call is
     * reached after a process restart resumed the cycle from {@code FORECAST_BATCH_WAIT},
     * {@code RETRY_FAILED} or {@code BRIEFING} — the phase rows, not an in-memory value, are the
     * durable record of whether submission fully succeeded. Called from exactly one place, the
     * success tail of {@link #waitAndBriefPhase}; every other exit from that method already marks
     * the run FAILED for an unrelated reason and returns before reaching here, so FAILED always
     * outranks DEGRADED by construction.
     *
     * <p>Fires the {@code AdminAlertService} email exactly on the DEGRADED branch, after the
     * status write — never on the COMPLETED branch and never for a FAILED run (those exit through
     * a different path entirely). The alert is best-effort: a null {@code adminAlertService}
     * (tests) or the call throwing synchronously before its {@code @Async} dispatch is caught and
     * logged, never allowed to undo the status write that already happened.
     *
     * @param runId pipeline run id
     */
    private void finishRun(Long runId) {
        String submitFailure = failureDetailIfFailed(runId, PipelinePhase.FORECAST_BATCH_SUBMIT,
                "One or more forecast batch submissions failed");
        String retryFailure = failureDetailIfFailed(runId, PipelinePhase.RETRY_FAILED,
                "Retry batch submission failed");
        if (submitFailure != null || retryFailure != null) {
            String reason = combineDegradeReasons(submitFailure, retryFailure);
            pipelineRunService.degradeRun(runId, reason);
            alertAdminsOfDegradedRun(runId, reason);
        } else {
            pipelineRunService.completeRun(runId);
        }
    }

    /**
     * @return the phase's own detail when its latest row is FAILED, {@code defaultDetail} when it
     *         is FAILED with no detail recorded, or {@code null} when the phase never ran or did
     *         not fail
     */
    private String failureDetailIfFailed(Long runId, PipelinePhase phase, String defaultDetail) {
        return pipelineRunService.findLatestPhase(runId, phase)
                .filter(row -> row.getStatus() == PipelinePhaseStatus.FAILED)
                .map(row -> row.getDetail() != null ? row.getDetail() : defaultDetail)
                .orElse(null);
    }

    /**
     * Builds the DEGRADED {@code failureReason} naming whichever of FORECAST_BATCH_SUBMIT /
     * RETRY_FAILED actually failed — either alone, or both concatenated when both did (a batch
     * submission failure this cycle and a retry submission failure are independent facts, and a
     * reader should see both rather than only the first one checked).
     */
    private static String combineDegradeReasons(String submitFailure, String retryFailure) {
        if (submitFailure != null && retryFailure != null) {
            return submitFailure + "; " + retryFailure;
        }
        return submitFailure != null ? submitFailure : retryFailure;
    }

    /**
     * Best-effort dispatch of the pipeline-degraded admin email. Never throws: a null service
     * (tests that pass {@code null} to skip alerting) is a no-op, and any exception the call
     * raises synchronously — before {@code AdminAlertService}'s own {@code @Async} boundary takes
     * over — is caught and logged rather than escaping into the orchestrator's success path.
     *
     * @param runId  the degraded run's id
     * @param reason the same detail recorded as the run's {@code failureReason}
     */
    private void alertAdminsOfDegradedRun(Long runId, String reason) {
        if (adminAlertService == null) {
            return;
        }
        try {
            PipelineRunEntity run = pipelineRunService.findById(runId).orElse(null);
            if (run == null) {
                LOG.warn("Pipeline run {}: degraded but could not be re-read for the admin "
                        + "alert — skipping", runId);
                return;
            }
            adminAlertService.sendPipelineDegradedAlert(
                    runId, run.getCycleType(), run.getTriggerTime(), reason);
        } catch (RuntimeException e) {
            LOG.warn("Pipeline run {}: admin alert dispatch raised an exception — logged and "
                    + "ignored (run stays DEGRADED): {}", runId, e.getMessage());
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
     * @param budget   the cycle type's wait budget
     */
    private void retryFailedPhase(Long runId, boolean resuming, Duration budget) {
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
                Duration remaining = remainingBudget(runId, budget);
                // Only a retry batch that already exists (a restart after submitRetry created it)
                // is exempt from the cutoff: it is paid for, and waiting on it is all that is left.
                // A resume that restarted BEFORE the batch was created submits a new one, so it
                // must observe the cutoff exactly as a first entry does.
                boolean retryAlreadySubmitted = resuming
                        && !forecastBatchRepository.findByPipelineRunIdAndRetryTrue(runId).isEmpty();
                if (!retryAlreadySubmitted && remaining.compareTo(MIN_RETRY_BUDGET) < 0) {
                    // WAIT used (nearly) all of the cycle's one budget. A retry batch submitted now
                    // could not be awaited and would fail the whole run on the timeout, discarding
                    // a cycle whose original batches all landed. Skip it instead, as the
                    // SYSTEMATIC case does: the run proceeds to BRIEFING with the locations that
                    // failed left failed.
                    String detail = selection.failureCount() + " failed — NOT retried: only "
                            + Math.max(0, remaining.toMinutes()) + " min of the "
                            + budget.toMinutes() + " min cycle budget left (minimum "
                            + MIN_RETRY_BUDGET.toMinutes() + ")";
                    LOG.info("Pipeline run {}: RETRY_FAILED — {}", runId, detail);
                    pipelineRunService.completePhase(runId, PipelinePhase.RETRY_FAILED, detail);
                    return;
                }
                RetrySubmitResult retryResult;
                try {
                    retryResult = batchRetryService.submitRetry(runId, selection);
                } catch (RuntimeException e) {
                    // The retry's evidence for this cycle may be incomplete (a retry-triage
                    // disposition could not be written): fail the phase and rethrow so
                    // waitAndBriefPhase fails the run BEFORE its tail settle. The cycle stays
                    // unclaimed and the sweep settles it later, RESETS_ONLY.
                    pipelineRunService.failPhase(runId, PipelinePhase.RETRY_FAILED,
                            "retry could not be completed: " + e.getMessage());
                    throw e;
                }
                waitForBatchSetComplete(runId, budget);
                if (retryResult.submissionFailed()) {
                    // A real, reconstructed retry batch never reached Anthropic — this is not
                    // "some failures stayed failed" (summariseRecovery's ordinary shape), it is
                    // an infrastructure failure of the retry attempt itself, so the phase is
                    // FAILED rather than completed with a recovery summary. finishRun reads this
                    // back, alongside FORECAST_BATCH_SUBMIT, to decide DEGRADED.
                    String reason = "retry batch submission failed ("
                            + retryResult.taskCount() + " requests)";
                    LOG.error("Pipeline run {}: RETRY_FAILED — {}", runId, reason);
                    pipelineRunService.failPhase(runId, PipelinePhase.RETRY_FAILED, reason);
                } else {
                    String detail = batchRetryService.summariseRecovery(
                            runId, selection.failureCount());
                    pipelineRunService.completePhase(runId, PipelinePhase.RETRY_FAILED, detail);
                    LOG.info("Pipeline run {}: RETRY_FAILED — {}", runId, detail);
                }
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
     * reaches a terminal status, or the cycle's safety budget is spent.
     *
     * <p>The budget is one per cycle: {@code min(submitted_at)} of the run's batches plus the
     * safety timeout, re-read from the database on every poll (see the class javadoc). When no
     * batch carries a submission instant the deadline is fixed at "now + timeout" on entry, which
     * is the pre-D3 behaviour; with no batches at all the loop returns on its first read anyway.
     *
     * @param runId  pipeline run id
     * @param budget the cycle type's wait budget
     * @return summary of the final state
     * @throws BatchSafetyTimeoutException if the safety budget is spent
     */
    private BatchCompletionResult waitForBatchSetComplete(Long runId, Duration budget) {
        Instant fallbackDeadline = clock.instant().plus(budget);

        while (true) {
            List<ForecastBatchEntity> batches = forecastBatchRepository.findByPipelineRunId(runId);
            BatchCompletionResult state = completionStateOf(batches);
            if (state.allTerminal()) {
                LOG.info("Pipeline run {}: batch set complete — {}", runId, state.summary());
                return state;
            }
            Instant deadline = budgetDeadline(batches, budget).orElse(fallbackDeadline);
            if (clock.instant().isAfter(deadline)) {
                throw new BatchSafetyTimeoutException(
                        "Batch set did not reach terminal status within " + budget
                                + " of the cycle's first submission — " + state.summary());
            }
            pipelineRunService.updateWaitingOn(runId, state.waitingOnText());
            sleepUninterruptibly(pollInterval);
        }
    }

    /**
     * The cycle's single deadline: its earliest batch submission plus the safety timeout.
     *
     * @param batches the run's batches
     * @param budget  the cycle type's wait budget
     * @return the deadline, empty when no batch carries a submission instant
     */
    private Optional<Instant> budgetDeadline(List<ForecastBatchEntity> batches, Duration budget) {
        return batches.stream()
                .map(ForecastBatchEntity::getSubmittedAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(first -> first.plus(budget));
    }

    /**
     * How much of the cycle's safety budget is left.
     *
     * @param runId  pipeline run id
     * @param budget the cycle type's wait budget
     * @return the remaining budget (negative once spent); the full timeout when no batch carries
     *         a submission instant
     */
    private Duration remainingBudget(Long runId, Duration budget) {
        Instant now = clock.instant();
        return budgetDeadline(forecastBatchRepository.findByPipelineRunId(runId), budget)
                .map(deadline -> Duration.between(now, deadline))
                .orElse(budget);
    }

    /**
     * Computes the current "is the cycle's batch set complete?" state from the DB.
     * Zero-batch case (no batches were submitted — e.g. empty briefing, fully
     * cached) counts as terminal so the orchestrator advances straight to
     * briefing rather than waiting forever for a set that doesn't exist.
     */
    BatchCompletionResult currentCompletionState(Long runId) {
        return completionStateOf(forecastBatchRepository.findByPipelineRunId(runId));
    }

    private static BatchCompletionResult completionStateOf(List<ForecastBatchEntity> batches) {
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
