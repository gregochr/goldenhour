package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CandidateDisposition;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.evaluation.EvaluationHandle;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.util.ForecastHorizon;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Submits forecast and aurora evaluations to the Anthropic Batch API for cost-efficient
 * asynchronous processing.
 *
 * <p>FORECAST batch: one request per GO/MARGINAL location in the current daily briefing.
 * The {@code customId} uses the safe format {@code "fc-{locationId}-{date}-{targetType}"}
 * (e.g. {@code "fc-42-2026-04-16-SUNRISE"}) so {@link BatchResultProcessor} can look up
 * the location by ID and route results to the correct evaluation cache entry.
 *
 * <p>AURORA batch: a single request containing the full multi-location aurora prompt
 * (identical structure to the real-time path). The {@code customId} uses the format
 * {@code "au-{alertLevel}-{date}"} (e.g. {@code "au-MODERATE-2026-04-16"}).
 *
 * <p>Both jobs are registered with {@link DynamicSchedulerService} and controlled via
 * the Scheduler admin UI.
 */
@Service
public class ScheduledBatchEvaluationService {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledBatchEvaluationService.class);

    private final ModelSelectionService modelSelectionService;
    private final NoaaSwpcClient noaaSwpcClient;
    private final WeatherTriageService weatherTriageService;
    private final AuroraOrchestrator auroraOrchestrator;
    private final LocationRepository locationRepository;
    private final AuroraProperties auroraProperties;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final EvaluationService evaluationService;
    private final ForecastTaskCollector forecastTaskCollector;
    private final ForecastDispositionService dispositionService;
    private final JobRunService jobRunService;
    private final java.time.Clock clock;

    /**
     * No-op between-steps hook for paths that do not split the submit into
     * distinct {@code STABILITY_RECLASSIFY} + {@code FORECAST_BATCH_SUBMIT}
     * phases (legacy admin submit, nightly's single SUBMIT phase).
     */
    private static final Consumer<ReclassSummary> NO_OP_BETWEEN_STEPS = s -> { };

    /** Prevents concurrent forecast batch submissions. */
    private final AtomicBoolean forecastBatchRunning = new AtomicBoolean(false);

    /** Prevents concurrent aurora batch submissions. */
    private final AtomicBoolean auroraBatchRunning = new AtomicBoolean(false);

    /**
     * Constructs the batch evaluation service.
     *
     * @param modelSelectionService       resolves the active Claude model for aurora
     * @param noaaSwpcClient              NOAA SWPC space weather data client
     * @param weatherTriageService        aurora weather triage
     * @param auroraOrchestrator          derives alert level from space weather data
     * @param locationRepository          location JPA repository for Bortle-filtered candidates
     * @param auroraProperties            aurora configuration (Bortle thresholds)
     * @param dynamicSchedulerService     scheduler to register job targets
     * @param evaluationService           Pass 3.2 engine — builds requests + submits + processes
     * @param forecastTaskCollector       Pass 3.2.1 collector — task construction + triage + bucketing
     * @param dispositionService          persists per-candidate disposition rows tied to the cycle's first job_run
     * @param jobRunService               creates the disposition-anchor run for zero-batch cycles
     * @param clock                       supplies "today" for the batch-breakdown log line,
     *                                    resolved in {@code Europe/London} by {@link ForecastHorizon}
     */
    public ScheduledBatchEvaluationService(
            ModelSelectionService modelSelectionService,
            NoaaSwpcClient noaaSwpcClient,
            WeatherTriageService weatherTriageService,
            AuroraOrchestrator auroraOrchestrator,
            LocationRepository locationRepository,
            AuroraProperties auroraProperties,
            DynamicSchedulerService dynamicSchedulerService,
            EvaluationService evaluationService,
            ForecastTaskCollector forecastTaskCollector,
            ForecastDispositionService dispositionService,
            JobRunService jobRunService,
            java.time.Clock clock) {
        this.modelSelectionService = modelSelectionService;
        this.noaaSwpcClient = noaaSwpcClient;
        this.weatherTriageService = weatherTriageService;
        this.auroraOrchestrator = auroraOrchestrator;
        this.locationRepository = locationRepository;
        this.auroraProperties = auroraProperties;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.evaluationService = evaluationService;
        this.forecastTaskCollector = forecastTaskCollector;
        this.dispositionService = dispositionService;
        this.jobRunService = jobRunService;
        this.clock = clock;
    }

    /**
     * Registers the aurora batch job target with the dynamic scheduler.
     *
     * <p>The {@code near_term_batch_evaluation} target is registered by
     * {@code PipelineOrchestrator} since V102 — the cron now invokes
     * the orchestrator's {@code runNightlyCycle()} rather than this service's
     * {@code submitForecastBatch()} directly. The orchestrator calls
     * {@link #submitForecastBatchForPipelineRun(Long)} as its first phase so
     * batches are tagged with the cycle id. The legacy {@link #submitForecastBatch()}
     * entry point is retained for admin invocations / tests.
     */
    @PostConstruct
    public void registerJobTargets() {
        dynamicSchedulerService.registerJobTarget(
                "aurora_batch_evaluation", this::submitAuroraBatch);
    }

    /**
     * Forcibly resets both batch-running guards to {@code false}.
     *
     * <p>Under normal operation the {@code finally} blocks in {@link #submitForecastBatch()}
     * and {@link #submitAuroraBatch()} always clear the guards, so this method should never
     * need to be called. It exists as an admin escape hatch in case a guard somehow becomes
     * stuck (e.g. during in-process debugging or an unrecoverable JVM-level failure that
     * bypassed the finally block).
     */
    public void resetBatchGuards() {
        LOG.warn("Batch guards manually reset by admin");
        forecastBatchRunning.set(false);
        auroraBatchRunning.set(false);
    }

    /**
     * Builds and submits a forecast evaluation batch to the Anthropic Batch API.
     *
     * <p>Guards against concurrent submissions with an {@link AtomicBoolean}. If a batch
     * submission is already in progress (e.g. triggered simultaneously by two scheduler
     * threads), the second call is silently dropped. The {@code finally} block guarantees
     * the guard is always cleared, even if {@link #doSubmitForecastBatch(Long)} throws.
     *
     * <p>Weather data for all candidate locations is pre-fetched in bulk before the
     * per-location triage loop, avoiding per-location Open-Meteo calls that would trip
     * the minutely rate limit when processing 200+ locations.
     */
    public void submitForecastBatch() {
        if (!forecastBatchRunning.compareAndSet(false, true)) {
            LOG.warn("Forecast batch already running — skipping concurrent trigger");
            return;
        }
        try {
            doSubmitForecastBatch(null,
                    NightlyCandidateCollectionStrategy.INSTANCE,
                    NightlyEligibilityPolicy.INSTANCE,
                    false, NO_OP_BETWEEN_STEPS);
        } finally {
            forecastBatchRunning.set(false);
        }
    }

    /**
     * Cycle-aware variant called by {@code PipelineOrchestrator}. Identical to
     * {@link #submitForecastBatch()} except every {@code forecast_batch} row it produces
     * is tagged with the given {@code pipelineRunId} so the orchestrator can detect
     * cycle completion later via a single DB query.
     *
     * <p>Defaults to the nightly cycle's candidate strategy + eligibility policy.
     * Callers that need a different cycle's behaviour use
     * {@link #submitForecastBatchForPipelineRun(Long, CandidateCollectionStrategy,
     * EligibilityPolicy)} directly.
     *
     * @param pipelineRunId orchestrated cycle id
     * @return {@code true} if this call submitted the cycle's batches; {@code false}
     *         if the submission guard was already held and this call was dropped
     */
    public boolean submitForecastBatchForPipelineRun(Long pipelineRunId) {
        return submitForecastBatchForPipelineRun(pipelineRunId,
                NightlyCandidateCollectionStrategy.INSTANCE,
                NightlyEligibilityPolicy.INSTANCE);
    }

    /**
     * Cycle-aware variant with default (non-ephemeral, single-phase) semantics.
     * Delegates to {@link #submitForecastBatchForPipelineRun(Long,
     * CandidateCollectionStrategy, EligibilityPolicy, boolean, Consumer)} with
     * {@code ephemeral=false} and a no-op between-steps hook — i.e. the nightly
     * behaviour: classify-and-publish, one combined submit step.
     *
     * @param pipelineRunId        orchestrated cycle id
     * @param candidateStrategy    filter deciding which event slots enter the candidate set
     * @param eligibilityPolicy    per-candidate include/skip decision function
     * @return {@code true} if this call submitted the cycle's batches; {@code false}
     *         if the submission guard was already held and this call was dropped
     */
    public boolean submitForecastBatchForPipelineRun(Long pipelineRunId,
            CandidateCollectionStrategy candidateStrategy,
            EligibilityPolicy eligibilityPolicy) {
        return submitForecastBatchForPipelineRun(pipelineRunId, candidateStrategy, eligibilityPolicy,
                false, NO_OP_BETWEEN_STEPS)
                .submitted();
    }

    /**
     * Fully-parameterised cycle-aware variant. The strategy + policy are the only
     * difference between cycles; the rest of the submit pipeline (briefing read,
     * weather pre-fetch, cloud pre-fetch, triage, bucketing, submission, disposition
     * persistence) is one shared code path. Used by the orchestrator for any cycle
     * type.
     *
     * <p><b>The collect→submit seam is one guarded critical section.</b> The
     * {@code betweenCollectAndSubmit} hook fires after collection (which includes
     * the stability re-classification + cost-gate) and before the batches are
     * submitted, while the concurrency guard is still held — so a concurrent
     * trigger cannot interleave between the two steps. This is how the orchestrator
     * records intraday's {@code STABILITY_RECLASSIFY} phase around collection and
     * {@code FORECAST_BATCH_SUBMIT} around submission without splitting the logic
     * into two divergent paths: nightly passes a no-op hook (one SUBMIT phase),
     * intraday passes a hook that closes RECLASSIFY and opens SUBMIT.
     *
     * @param pipelineRunId          orchestrated cycle id
     * @param candidateStrategy      filter deciding which event slots enter the candidate set
     * @param eligibilityPolicy      per-candidate include/skip decision function
     * @param ephemeral              when {@code true}, the stability re-classification
     *                               is computed for gating but the snapshot is NOT
     *                               published (intraday); nightly passes {@code false}
     * @param betweenCollectAndSubmit hook invoked with the cost-gate summary after
     *                               collection and before submission (used for
     *                               cycle-specific phase recording)
     * @return {@link ForecastBatchSubmissionOutcome#submitted()} is {@code true} if this call
     *         acquired the submission guard and ran the submission; {@code false} if another
     *         submission was already in progress and this call was dropped without submitting
     *         anything. A caller that creates a pipeline run for this call (the orchestrator)
     *         MUST NOT treat {@code false} as success — the cycle submitted no
     *         batches of its own and must not proceed to wait-for-completion or
     *         briefing, which would otherwise brief from the other run's
     *         in-flight (or stale cached) state. When {@code submitted()} is {@code true}, the
     *         outcome's {@link ForecastBatchSubmissionOutcome#summary() summary} tells the
     *         caller whether every bucket this call attempted actually reached Anthropic —
     *         see {@link BatchSubmissionSummary#allSucceeded()}.
     */
    public ForecastBatchSubmissionOutcome submitForecastBatchForPipelineRun(Long pipelineRunId,
            CandidateCollectionStrategy candidateStrategy,
            EligibilityPolicy eligibilityPolicy,
            boolean ephemeral,
            Consumer<ReclassSummary> betweenCollectAndSubmit) {
        java.util.Objects.requireNonNull(pipelineRunId, "pipelineRunId");
        java.util.Objects.requireNonNull(candidateStrategy, "candidateStrategy");
        java.util.Objects.requireNonNull(eligibilityPolicy, "eligibilityPolicy");
        java.util.Objects.requireNonNull(betweenCollectAndSubmit, "betweenCollectAndSubmit");
        if (!forecastBatchRunning.compareAndSet(false, true)) {
            LOG.warn("Forecast batch already running — orchestrator trigger dropped "
                    + "(pipelineRunId={})", pipelineRunId);
            return ForecastBatchSubmissionOutcome.dropped();
        }
        try {
            BatchSubmissionSummary summary = doSubmitForecastBatch(pipelineRunId, candidateStrategy,
                    eligibilityPolicy, ephemeral, betweenCollectAndSubmit);
            return ForecastBatchSubmissionOutcome.ran(summary);
        } finally {
            forecastBatchRunning.set(false);
        }
    }

    /**
     * Submits a forecast batch filtered to the given region IDs, using the same triage
     * and stability gates as the overnight scheduled job.
     *
     * @param regionIds region IDs to include — null or empty means all regions
     * @return submission result, or null if no requests were built
     */
    public BatchSubmitResult submitScheduledBatchForRegions(List<Long> regionIds) {
        if (!forecastBatchRunning.compareAndSet(false, true)) {
            LOG.warn("Forecast batch already running — skipping concurrent trigger");
            return null;
        }
        try {
            return doSubmitForecastBatchForRegions(regionIds);
        } finally {
            forecastBatchRunning.set(false);
        }
    }

    /**
     * Builds and submits an aurora evaluation batch to the Anthropic Batch API.
     *
     * <p>Guards against concurrent submissions with an {@link AtomicBoolean}. The
     * {@code finally} block guarantees the guard is always cleared, even if
     * {@link #doSubmitAuroraBatch()} throws.
     *
     * <p>Fetches current NOAA SWPC data, derives the alert level, and runs weather triage.
     * Submits a single batch request if any locations pass triage. Skips submission if the
     * alert level is QUIET or no locations are viable.
     */
    public void submitAuroraBatch() {
        if (!auroraBatchRunning.compareAndSet(false, true)) {
            LOG.warn("Aurora batch already running — skipping concurrent trigger");
            return;
        }
        try {
            doSubmitAuroraBatch();
        } finally {
            auroraBatchRunning.set(false);
        }
    }

    /**
     * The days-ahead threshold separating near-term from far-term batches.
     * Tasks with {@code daysAhead <= NEAR_TERM_MAX_DAYS} go to the near-term batch;
     * tasks with {@code daysAhead > NEAR_TERM_MAX_DAYS} go to the far-term batch
     * (subject to stability gating).
     */
    static final int NEAR_TERM_MAX_DAYS = 1;

    /**
     * Core forecast batch logic, split into two steps around a hook. Step 1
     * (collect) delegates briefing read, weather + cloud pre-fetch, triage,
     * stability re-classification + cost-gate, and bucketing to
     * {@link ForecastTaskCollector}. The {@code betweenCollectAndSubmit} hook
     * then fires with the cost-gate summary (this is the seam the orchestrator
     * uses to record intraday's RECLASSIFY→SUBMIT phase boundary). Step 2
     * (submit) sends each non-empty bucket via {@link EvaluationService} and
     * persists the cycle's dispositions.
     *
     * <p>Both nightly and intraday run these same two steps; only the hook
     * differs (no-op for nightly, phase-transition for intraday).
     *
     * @param pipelineRunId            orchestrated cycle id to tag the submitted batches
     *                                 with, or {@code null} for legacy cron-direct invocations
     * @param candidateStrategy        cycle-specific event-window filter
     * @param eligibilityPolicy        cycle-specific stability decision function
     * @param ephemeral                whether the stability snapshot write-through is suppressed
     * @param betweenCollectAndSubmit  hook invoked with the cost-gate summary between steps
     */
    private BatchSubmissionSummary doSubmitForecastBatch(Long pipelineRunId,
            CandidateCollectionStrategy candidateStrategy,
            EligibilityPolicy eligibilityPolicy,
            boolean ephemeral,
            Consumer<ReclassSummary> betweenCollectAndSubmit) {
        ScheduledBatchTasks tasks = forecastTaskCollector.collectScheduledBatches(
                candidateStrategy, eligibilityPolicy, ephemeral);

        // Hook fires between collect and submit, inside the concurrency guard.
        // Nightly: no-op. Intraday: closes STABILITY_RECLASSIFY, opens
        // FORECAST_BATCH_SUBMIT — so the cost-gate decision is a truthfully-timed
        // phase of its own. Fires with the ORIGINAL dispositions (before any
        // submission-failure rewrite below) — the cost-gate summary is about
        // collection's decisions, made before submission is even attempted, and
        // must reflect them exactly as collected.
        betweenCollectAndSubmit.accept(ReclassSummary.from(tasks.dispositions()));

        return submitBuckets(pipelineRunId, tasks);
    }

    /**
     * Submission step: sends each non-empty bucket to the Batch API and persists
     * the cycle's dispositions. Shared by every cycle type.
     *
     * @param pipelineRunId orchestrated cycle id to tag batches with, or {@code null}
     * @param tasks         the bucketed tasks + dispositions from collection
     * @return per-bucket submission accounting for this cycle
     */
    private BatchSubmissionSummary submitBuckets(Long pipelineRunId, ScheduledBatchTasks tasks) {
        // NOTE: deliberately no `if (tasks.isEmpty()) return;` here. PR #112
        // removed that early-return so the cycle's dispositions persist
        // unconditionally (the all-cached / all-skipped cycle still produced a
        // full disposition list that the guard used to drop). The strategy +
        // policy parameters from the orchestrator-extraction change thread
        // through unchanged.

        // Submit each non-empty bucket; capture the first non-null jobRunId so
        // every disposition the collector accumulated (across all four buckets,
        // plus skips) can be persisted against the cycle's representative
        // job_run. The other buckets create their own job_runs for cost/API
        // tracking but do not re-persist dispositions.
        //
        // cycleJobRunId stays null when no bucket is submitted — the all-cached /
        // all-skipped / all-triaged cycle. That is exactly the case where the
        // earlier `if (tasks.isEmpty()) return;` guard silently dropped the
        // collector's dispositions before persistence. Persistence is now
        // unconditional (see persistCycleDispositions below) and the empty case
        // gets a disposition-anchor run instead of being discarded.
        Long cycleJobRunId = null;
        // Counts every non-empty bucket this cycle attempted vs how many actually reached
        // Anthropic — the honesty fix for the 2026-09-29 incident, where all three buckets
        // failed to submit but the trailing INFO line below still said "total N requests" as
        // though every one of them had gone out.
        int bucketsAttempted = 0;
        int bucketsSubmitted = 0;
        // Per-bucket outcome, in submission order — the input to the disposition
        // rewrite below and to the BatchSubmissionSummary this method returns.
        List<BucketOutcome> bucketOutcomes = new ArrayList<>();
        // Every non-empty bucket this cycle WOULD submit, in submission order, built before any
        // of them is actually attempted — so if one orphans, persistOnOrphan can find every
        // bucket queued AFTER it (never attempted at all) without threading extra parameters
        // through all six submission sites below.
        List<LabeledBucket> allNonEmptyBuckets = buildNonEmptyBuckets(tasks);

        if (!tasks.nearInland().isEmpty()) {
            bucketsAttempted++;
            EvaluationHandle h;
            try {
                h = submitBucketSafely(tasks.nearInland(), pipelineRunId, "near-term inland");
            } catch (OrphanedBatchException e) {
                persistOnOrphan(pipelineRunId, cycleJobRunId, tasks.dispositions(), bucketOutcomes,
                        allNonEmptyBuckets, "near-term inland", e);
                throw e;
            }
            cycleJobRunId = firstNonNull(cycleJobRunId, h.jobRunId());
            boolean success = h.batchId() != null;
            bucketsSubmitted += success ? 1 : 0;
            logBatchBreakdown(tasks.nearInland(), "near-term inland", h);
            bucketOutcomes.add(new BucketOutcome("near-term inland", tasks.nearInland(), success));
        }
        if (!tasks.nearCoastal().isEmpty()) {
            bucketsAttempted++;
            EvaluationHandle h;
            try {
                h = submitBucketSafely(tasks.nearCoastal(), pipelineRunId, "near-term coastal");
            } catch (OrphanedBatchException e) {
                persistOnOrphan(pipelineRunId, cycleJobRunId, tasks.dispositions(), bucketOutcomes,
                        allNonEmptyBuckets, "near-term coastal", e);
                throw e;
            }
            cycleJobRunId = firstNonNull(cycleJobRunId, h.jobRunId());
            boolean success = h.batchId() != null;
            bucketsSubmitted += success ? 1 : 0;
            logBatchBreakdown(tasks.nearCoastal(), "near-term coastal", h);
            bucketOutcomes.add(new BucketOutcome("near-term coastal", tasks.nearCoastal(), success));
        }
        if (!tasks.farInland().isEmpty()) {
            bucketsAttempted++;
            EvaluationHandle h;
            try {
                h = submitBucketSafely(tasks.farInland(), pipelineRunId, "far-term inland");
            } catch (OrphanedBatchException e) {
                persistOnOrphan(pipelineRunId, cycleJobRunId, tasks.dispositions(), bucketOutcomes,
                        allNonEmptyBuckets, "far-term inland", e);
                throw e;
            }
            cycleJobRunId = firstNonNull(cycleJobRunId, h.jobRunId());
            boolean success = h.batchId() != null;
            bucketsSubmitted += success ? 1 : 0;
            logBatchBreakdown(tasks.farInland(), "far-term inland", h);
            bucketOutcomes.add(new BucketOutcome("far-term inland", tasks.farInland(), success));
        }
        if (!tasks.farCoastal().isEmpty()) {
            bucketsAttempted++;
            EvaluationHandle h;
            try {
                h = submitBucketSafely(tasks.farCoastal(), pipelineRunId, "far-term coastal");
            } catch (OrphanedBatchException e) {
                persistOnOrphan(pipelineRunId, cycleJobRunId, tasks.dispositions(), bucketOutcomes,
                        allNonEmptyBuckets, "far-term coastal", e);
                throw e;
            }
            cycleJobRunId = firstNonNull(cycleJobRunId, h.jobRunId());
            boolean success = h.batchId() != null;
            bucketsSubmitted += success ? 1 : 0;
            logBatchBreakdown(tasks.farCoastal(), "far-term coastal", h);
            bucketOutcomes.add(new BucketOutcome("far-term coastal", tasks.farCoastal(), success));
        }
        // Bluebell mini-batch: homogeneous bluebell-prompt tasks submitted as their own batch
        // so the bluebell system prompt caches across requests. Empty out of season.
        if (!tasks.bluebell().isEmpty()) {
            bucketsAttempted++;
            EvaluationHandle h;
            try {
                h = submitBucketSafely(tasks.bluebell(), pipelineRunId, "bluebell");
            } catch (OrphanedBatchException e) {
                persistOnOrphan(pipelineRunId, cycleJobRunId, tasks.dispositions(), bucketOutcomes,
                        allNonEmptyBuckets, "bluebell", e);
                throw e;
            }
            cycleJobRunId = firstNonNull(cycleJobRunId, h.jobRunId());
            boolean success = h.batchId() != null;
            bucketsSubmitted += success ? 1 : 0;
            logBatchBreakdown(tasks.bluebell(), "bluebell", h);
            bucketOutcomes.add(new BucketOutcome("bluebell", tasks.bluebell(), success));
        }
        // Woodland mini-batch: same homogeneity argument as bluebell, but year-round. A canopy
        // site is in exactly one of these two buckets on any given date, never both, so neither
        // batch is ever diluted by the other's system prompt.
        if (!tasks.woodland().isEmpty()) {
            bucketsAttempted++;
            EvaluationHandle h;
            try {
                h = submitBucketSafely(tasks.woodland(), pipelineRunId, "woodland");
            } catch (OrphanedBatchException e) {
                persistOnOrphan(pipelineRunId, cycleJobRunId, tasks.dispositions(), bucketOutcomes,
                        allNonEmptyBuckets, "woodland", e);
                throw e;
            }
            cycleJobRunId = firstNonNull(cycleJobRunId, h.jobRunId());
            boolean success = h.batchId() != null;
            bucketsSubmitted += success ? 1 : 0;
            logBatchBreakdown(tasks.woodland(), "woodland", h);
            bucketOutcomes.add(new BucketOutcome("woodland", tasks.woodland(), success));
        }

        // Rewrite EVALUATED/FORCE_EVALUATED dispositions to SUBMISSION_FAILED for every
        // candidate whose task(s) landed only in bucket(s) that failed to submit — see
        // applySubmissionFailures' own javadoc for the full rule, including the OPEN_FELL
        // sky+bluebell pairing where a candidate's tasks can span two buckets.
        List<CandidateDisposition> finalDispositions =
                applySubmissionFailures(tasks.dispositions(), bucketOutcomes);

        // Persist the cycle's per-candidate accounting UNCONDITIONALLY. This is
        // the single chokepoint every forecast submission path funnels through
        // (legacy submitForecastBatch + orchestrated submitForecastBatchForPipelineRun
        // + the future intraday cycle, all via this method), so the persist
        // cannot be routed around again.
        persistCycleDispositions(pipelineRunId, cycleJobRunId, finalDispositions);

        if (!tasks.isEmpty()) {
            LOG.info("Forecast batch split: near-term {} ({}i + {}c), far-term {} ({}i + {}c), "
                            + "bluebell {}, woodland {}, total {} requests, submitted {}/{} buckets, "
                            + "pipelineRunId={}",
                    tasks.nearInland().size() + tasks.nearCoastal().size(),
                    tasks.nearInland().size(), tasks.nearCoastal().size(),
                    tasks.farInland().size() + tasks.farCoastal().size(),
                    tasks.farInland().size(), tasks.farCoastal().size(),
                    tasks.bluebell().size(), tasks.woodland().size(),
                    tasks.totalSize(), bucketsSubmitted, bucketsAttempted, pipelineRunId);
        }

        List<BatchSubmissionSummary.FailedBucket> failedBuckets = bucketOutcomes.stream()
                .filter(b -> !b.success())
                .map(b -> new BatchSubmissionSummary.FailedBucket(b.label(), b.tasks().size()))
                .toList();
        return new BatchSubmissionSummary(bucketsAttempted, bucketsSubmitted, failedBuckets);
    }

    /**
     * Submits one bucket, converting most failures into the ordinary "this bucket failed" shape
     * the rest of {@link #submitBuckets} already handles ({@link EvaluationHandle#empty()}) —
     * except {@link OrphanedBatchException}, which is deliberately let through rather than
     * swallowed here.
     *
     * <p><b>Why request-building exceptions need this at all.</b> {@code
     * EvaluationServiceImpl#submitForecast} builds each request ({@code BatchRequestFactory}'s
     * builders, {@code CustomIdFactory}) OUTSIDE {@code BatchSubmissionService}'s own try/catch —
     * only the Anthropic call and persistence are guarded there. Before this method existed, an
     * exception thrown while building requests for one bucket propagated straight out of {@link
     * #submitBuckets}, aborting it before {@code persistCycleDispositions} ever ran — losing the
     * WHOLE cycle's disposition audit trail, not just the one bucket's.
     *
     * <p>{@link OrphanedBatchException} means a batch genuinely reached Anthropic (money spent,
     * a real request in flight) and only local bookkeeping failed — that is not "this bucket
     * failed to submit", it is "the whole cycle's accounting is now unreliable enough that the run
     * itself must be failed", so it is rethrown for the caller to handle via {@link
     * #persistOnOrphan}, not converted into a bucket failure here.
     *
     * @param tasks         the bucket's tasks
     * @param pipelineRunId orchestrated cycle id, or {@code null}
     * @param label         the bucket's human label, for the log line
     * @return the real handle on success; {@link EvaluationHandle#empty()} for any failure other
     *         than {@link OrphanedBatchException}
     * @throws OrphanedBatchException when a batch was created at Anthropic but its tracking row
     *                                could not be persisted — propagated to the caller unconverted
     */
    private EvaluationHandle submitBucketSafely(List<EvaluationTask.Forecast> tasks,
            Long pipelineRunId, String label) {
        try {
            return evaluationService.submit(tasks, BatchTriggerSource.SCHEDULED, pipelineRunId);
        } catch (OrphanedBatchException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.error("[BATCH DIAG] {} bucket submission threw before an outcome could be read "
                            + "(request building or another unexpected failure, not an ordinary "
                            + "Anthropic API failure — those already return an empty handle) — "
                            + "treating its {} candidate(s) as failed and continuing with the "
                            + "rest of the cycle: {}",
                    label, tasks.size(), e.getMessage(), e);
            return EvaluationHandle.empty();
        }
    }

    /**
     * Persists the cycle's dispositions collected so far when a bucket's submission orphaned a
     * batch, before the caller rethrows to fail the whole run.
     *
     * <p>The orphaned bucket's own candidates reached Claude for real (money spent, a request in
     * flight) even though its local tracking row was lost, so its bucket is recorded here as a
     * SUCCESS — not omitted — which leaves its own solo candidates {@code EVALUATED}/
     * {@code FORCE_EVALUATED} (found, not failed, unchanged) exactly as before, but ALSO lets an
     * OPEN_FELL candidate whose sky task landed here and whose paired bluebell task landed in a
     * bucket below go through {@link #applySubmissionFailures}' ordinary partial-pairing rule
     * rather than being missed entirely.
     *
     * <p><b>The bug this method exists to fix (found by review, 2026-09-30).</b> Every bucket
     * queued AFTER the orphaned one is never attempted at all — {@code submitBuckets} rethrows
     * before reaching them — yet the collector already wrote their candidates as {@code
     * EVALUATED}/{@code FORCE_EVALUATED}. The first cut of this method only knew about buckets
     * that had already run ({@code bucketOutcomesSoFar}), so a later bucket's candidates had no
     * matching entry in {@link #applySubmissionFailures}' lookup at all and were left untouched —
     * recreating, for every bucket after the orphaned one, exactly the false "a request reached
     * Claude" audit trail this whole PR exists to remove. {@code allNonEmptyBuckets} (built once
     * in {@code submitBuckets}, before any bucket is attempted) is what lets this method find
     * those never-attempted buckets and rewrite their candidates to {@code SUBMISSION_FAILED} too.
     *
     * @param cycleJobRunId       the job_run accumulated from buckets that succeeded BEFORE this
     *                            one — may still be {@code null} if this is the first bucket
     * @param dispositions        the cycle's full, uncorrected disposition list from collection
     * @param bucketOutcomesSoFar every earlier bucket's outcome, not including the orphaned one
     * @param allNonEmptyBuckets  every non-empty bucket this cycle would submit, in submission
     *                            order, built before any bucket was attempted
     * @param label                the orphaned bucket's human label — its own position in {@code
     *                             allNonEmptyBuckets} marks where "never attempted" begins
     * @param e                    the exception, for its batch id and cause
     */
    private void persistOnOrphan(Long pipelineRunId, Long cycleJobRunId,
            List<CandidateDisposition> dispositions,
            List<BucketOutcome> bucketOutcomesSoFar, List<LabeledBucket> allNonEmptyBuckets,
            String label, OrphanedBatchException e) {
        int orphanIndex = indexOfLabel(allNonEmptyBuckets, label);
        LabeledBucket orphanedBucket = allNonEmptyBuckets.get(orphanIndex);
        List<LabeledBucket> notYetAttempted =
                allNonEmptyBuckets.subList(orphanIndex + 1, allNonEmptyBuckets.size());

        LOG.error("[BATCH DIAG] {} bucket's batch (batchId={}) was created at Anthropic but its "
                        + "tracking row could not be persisted locally — its {} candidate(s) stay "
                        + "EVALUATED (a real request reached Claude for them); {} bucket(s) after "
                        + "it were never attempted at all ({}) and their candidate(s) become "
                        + "SUBMISSION_FAILED; persisting the cycle's dispositions collected so far "
                        + "before this failure takes down the whole run",
                label, e.getAnthropicBatchId(), orphanedBucket.tasks().size(),
                notYetAttempted.size(),
                notYetAttempted.stream().map(LabeledBucket::label)
                        .collect(Collectors.joining(", ")),
                e);

        List<BucketOutcome> outcomes = new ArrayList<>(bucketOutcomesSoFar);
        outcomes.add(new BucketOutcome(orphanedBucket.label(), orphanedBucket.tasks(), true));
        for (LabeledBucket notAttempted : notYetAttempted) {
            outcomes.add(
                    new BucketOutcome(notAttempted.label(), notAttempted.tasks(), false, true));
        }

        List<CandidateDisposition> finalDispositions = applySubmissionFailures(dispositions, outcomes);
        persistCycleDispositions(pipelineRunId, cycleJobRunId, finalDispositions);
    }

    /**
     * Finds a bucket's index within an ordered bucket list by its human label.
     *
     * @param buckets the ordered bucket list to search
     * @param label   the label to find
     * @return the matching bucket's index
     * @throws IllegalStateException if no bucket in the list carries that label — every label
     *                                passed to {@link #persistOnOrphan} is a literal from the same
     *                                six labels {@link #buildNonEmptyBuckets} uses, so this can
     *                                only fire on a future maintenance mismatch between the two
     */
    private static int indexOfLabel(List<LabeledBucket> buckets, String label) {
        for (int i = 0; i < buckets.size(); i++) {
            if (buckets.get(i).label().equals(label)) {
                return i;
            }
        }
        throw new IllegalStateException(
                "Bucket label \"" + label + "\" not found among submitted buckets — "
                        + "buildNonEmptyBuckets and the submission sites in submitBuckets have "
                        + "drifted out of sync");
    }

    /**
     * Builds the ordered, non-empty-only bucket list for one cycle, in the exact order {@link
     * #submitBuckets} submits them. Called once per cycle, before any bucket is attempted, so
     * {@link #persistOnOrphan} can identify every bucket queued after an orphaned one.
     *
     * @param tasks the bucketed tasks from collection
     * @return every non-empty bucket, in submission order
     */
    private static List<LabeledBucket> buildNonEmptyBuckets(ScheduledBatchTasks tasks) {
        List<LabeledBucket> buckets = new ArrayList<>();
        addIfNonEmpty(buckets, "near-term inland", tasks.nearInland());
        addIfNonEmpty(buckets, "near-term coastal", tasks.nearCoastal());
        addIfNonEmpty(buckets, "far-term inland", tasks.farInland());
        addIfNonEmpty(buckets, "far-term coastal", tasks.farCoastal());
        addIfNonEmpty(buckets, "bluebell", tasks.bluebell());
        addIfNonEmpty(buckets, "woodland", tasks.woodland());
        return buckets;
    }

    private static void addIfNonEmpty(List<LabeledBucket> buckets, String label,
            List<EvaluationTask.Forecast> tasks) {
        if (!tasks.isEmpty()) {
            buckets.add(new LabeledBucket(label, tasks));
        }
    }

    /**
     * One of the six possible submission buckets and its tasks, independent of whether it was
     * ever actually submitted. Built once per cycle by {@link #buildNonEmptyBuckets} so {@link
     * #persistOnOrphan} can find every bucket queued after an orphaned one without threading
     * extra parameters through all six submission sites in {@link #submitBuckets}.
     */
    private record LabeledBucket(String label, List<EvaluationTask.Forecast> tasks) {
    }

    /**
     * One bucket's submission outcome — its label, the tasks it carried, whether the batch
     * actually reached Anthropic, and whether it was never attempted at all (an orphan in an
     * earlier bucket aborted the cycle before this one's turn). Input to
     * {@link #applySubmissionFailures} and to the trailing {@link BatchSubmissionSummary}
     * {@link #submitBuckets} returns.
     *
     * @param unattempted {@code true} only for a bucket queued after an orphaned one and never
     *                    submitted at all — distinct from an ordinary failed submission ({@code
     *                    success = false, unattempted = false}), which DID reach the Anthropic
     *                    call and got an empty handle back. {@link #applySubmissionFailures} uses
     *                    this to write a "never submitted" detail rather than the ordinary
     *                    "submission failed" one.
     */
    private record BucketOutcome(String label, List<EvaluationTask.Forecast> tasks, boolean success,
            boolean unattempted) {
        BucketOutcome(String label, List<EvaluationTask.Forecast> tasks, boolean success) {
            this(label, tasks, success, false);
        }
    }

    /**
     * One candidate slot's identity — the same (location, date, event) triple both
     * {@link EvaluationTask.Forecast} and {@link CandidateDisposition} carry, used to match a
     * disposition back to the bucket(s) its task(s) landed in.
     */
    private record SlotKey(Long locationId, LocalDate date, TargetType eventType) {
        static SlotKey of(EvaluationTask.Forecast task) {
            return new SlotKey(task.location().getId(), task.date(), task.targetType());
        }

        static SlotKey of(CandidateDisposition disposition) {
            return new SlotKey(
                    disposition.locationId(), disposition.evaluationDate(), disposition.eventType());
        }
    }

    /**
     * Rewrites {@code EVALUATED}/{@code FORCE_EVALUATED} dispositions to
     * {@link DispositionCategory#SUBMISSION_FAILED} for every candidate whose task(s) landed only
     * in bucket(s) whose Anthropic submission failed — so the disposition trail says plainly that
     * no request reached Claude for that candidate, rather than the misleading {@code EVALUATED}
     * the 2026-09-29 incident left behind.
     *
     * <p><b>The pairing rule.</b> A candidate produces tasks in TWO buckets only for an OPEN_FELL
     * site in bluebell season: one sky task (in a near/far × inland/coastal bucket) plus one
     * paired bluebell task (in the {@code bluebell} bucket) — every other candidate's task(s) land
     * in exactly one bucket. There is still only ONE disposition row per candidate (the collector
     * never writes two), so a two-bucket candidate needs a single rule for what its one row should
     * say when the two buckets disagree:
     * <ul>
     *   <li><b>Both buckets failed</b> — no request reached Claude for this candidate at all, so
     *       the disposition becomes {@code SUBMISSION_FAILED}, naming both buckets. When every
     *       failed bucket is {@link BucketOutcome#unattempted()} (queued after an orphaned one,
     *       never attempted at all — see {@link #persistOnOrphan}), the detail says so ("batch not
     *       submitted: an earlier bucket's batch was orphaned") rather than claiming a submission
     *       was attempted and failed.</li>
     *   <li><b>Both buckets succeeded (or the candidate has only one task)</b> — the disposition is
     *       left exactly as the collector wrote it.</li>
     *   <li><b>One bucket failed, the other succeeded</b> — a real request DID reach Claude for
     *       this candidate (the sky rating, or the bluebell rating, whichever bucket landed), so
     *       rewriting to {@code SUBMISSION_FAILED} would be a false claim that nothing reached
     *       Claude. The disposition stays {@code EVALUATED}/{@code FORCE_EVALUATED} — it WAS
     *       evaluated — but the partial loss is APPENDED to whatever {@code detail} the collector
     *       already wrote, never replacing it: an ordinary {@code EVALUATED} row's {@code detail}
     *       is {@code null} (so the note becomes the whole string), but a {@code FORCE_EVALUATED}
     *       row's {@code detail} is ALWAYS {@code "Force-evaluated best-bet headline candidate"}
     *       ({@link ForecastTaskCollector}'s {@code includeDisposition}) — replacing it would
     *       silently erase the one fact {@code VerdictSampleGate}'s force-eval exemption exists to
     *       preserve. There is no forced-vs-not branch here the way the both-failed case above
     *       needs one: appending is correct either way, since a forced candidate's detail is never
     *       null to begin with. The same wording split applies here: a failed side that is {@link
     *       BucketOutcome#unattempted()} gets the "not submitted" phrasing instead of "submission
     *       failed" — this is exactly how an orphaned bucket's OPEN_FELL pairing is handled, since
     *       {@link #persistOnOrphan} records the orphaned bucket itself as a SUCCESS (a real
     *       request did reach it) and any bucket after it as {@code unattempted}.</li>
     * </ul>
     *
     * <p>A disposition whose slot key is not found in {@code bucketOutcomes} at all (should not
     * happen for an EVALUATED/FORCE_EVALUATED row — every included candidate produces at least one
     * task in exactly one of the six buckets) is left untouched rather than guessed at.
     *
     * @param dispositions   the cycle's per-candidate dispositions, exactly as the collector wrote
     *                       them
     * @param bucketOutcomes every bucket relevant to this rewrite — ordinarily every non-empty
     *                       bucket this cycle submitted, but {@link #persistOnOrphan} also passes
     *                       the orphaned bucket itself (as a success) and every bucket queued after
     *                       it (as {@code unattempted} failures) so those never-submitted
     *                       candidates are rewritten too
     * @return the dispositions to persist — identical to {@code dispositions} when every bucket
     *         succeeded
     */
    private static List<CandidateDisposition> applySubmissionFailures(
            List<CandidateDisposition> dispositions, List<BucketOutcome> bucketOutcomes) {
        if (bucketOutcomes.stream().allMatch(BucketOutcome::success)) {
            return dispositions;
        }
        Map<SlotKey, List<BucketOutcome>> outcomesBySlot = new HashMap<>();
        for (BucketOutcome bucket : bucketOutcomes) {
            for (EvaluationTask.Forecast task : bucket.tasks()) {
                outcomesBySlot.computeIfAbsent(SlotKey.of(task), k -> new ArrayList<>()).add(bucket);
            }
        }
        List<CandidateDisposition> result = new ArrayList<>(dispositions.size());
        for (CandidateDisposition d : dispositions) {
            if (d.category() != DispositionCategory.EVALUATED
                    && d.category() != DispositionCategory.FORCE_EVALUATED) {
                result.add(d);
                continue;
            }
            List<BucketOutcome> slotBuckets = outcomesBySlot.get(SlotKey.of(d));
            if (slotBuckets == null || slotBuckets.isEmpty()) {
                result.add(d);
                continue;
            }
            boolean anySucceeded = slotBuckets.stream().anyMatch(BucketOutcome::success);
            boolean anyFailed = slotBuckets.stream().anyMatch(b -> !b.success());
            if (!anyFailed) {
                result.add(d);
            } else if (!anySucceeded) {
                String bucketNames = slotBuckets.stream().map(BucketOutcome::label).distinct()
                        .collect(Collectors.joining("+"));
                boolean allUnattempted = slotBuckets.stream().allMatch(BucketOutcome::unattempted);
                String forcedNote = d.category() == DispositionCategory.FORCE_EVALUATED
                        ? " (forced)" : "";
                String detail = allUnattempted
                        ? bucketNames + " batch not submitted: an earlier bucket's batch was "
                                + "orphaned" + forcedNote
                        : bucketNames + " batch submission failed" + forcedNote;
                result.add(new CandidateDisposition(
                        d.locationId(), d.locationName(), d.evaluationDate(), d.eventType(),
                        d.daysAhead(), DispositionCategory.SUBMISSION_FAILED, detail));
            } else {
                List<BucketOutcome> failedBuckets =
                        slotBuckets.stream().filter(b -> !b.success()).toList();
                String failedNames = failedBuckets.stream()
                        .map(BucketOutcome::label).distinct()
                        .collect(Collectors.joining("+"));
                boolean allUnattemptedFailed =
                        failedBuckets.stream().allMatch(BucketOutcome::unattempted);
                String partialNote = allUnattemptedFailed
                        ? failedNames + " batch not submitted for part of this candidate's "
                                + "pairing — an earlier bucket's batch was orphaned; the rest was "
                                + "evaluated normally"
                        : failedNames + " batch submission failed for part of this "
                                + "candidate's pairing — the rest was evaluated normally";
                // APPEND, never replace — a FORCE_EVALUATED row's detail is never null (it always
                // carries ForecastTaskCollector's "Force-evaluated best-bet headline candidate"),
                // and replacing it here would silently lose that fact.
                String newDetail = d.detail() == null || d.detail().isBlank()
                        ? partialNote
                        : d.detail() + "; " + partialNote;
                result.add(new CandidateDisposition(
                        d.locationId(), d.locationName(), d.evaluationDate(), d.eventType(),
                        d.daysAhead(), d.category(), newDetail));
            }
        }
        return result;
    }

    /**
     * Persists the cycle's disposition rows, anchoring them to a job_run that is
     * visible in the Job Run detail UI.
     *
     * <p>When at least one batch was submitted, {@code cycleJobRunId} is the
     * first batch's job_run and the dispositions hang off it. When NO batch was
     * submitted (every candidate cached/skipped/triaged), there is no batch
     * job_run, so a disposition-anchor run is created — otherwise the cycle's
     * accounting would be invisible, which is precisely the "why was nothing
     * evaluated?" case an operator needs. The {@code [DISPOSITION] Persisting}
     * log is the operator's per-cycle smoke check that the write happened.
     *
     * <p>The job run the rows land on is recorded on the pipeline run's
     * {@code disposition_job_run_id}, durably, so the location auto-disable settle can find a
     * batchless cycle's anchor run (it has no {@code forecast_batch} row to be found through).
     *
     * @param pipelineRunId the orchestrated cycle id, or null for a legacy cron-direct invocation
     * @param cycleJobRunId the first submitted batch's job_run id, or null if none
     * @param dispositions  every candidate's disposition for this cycle
     */
    private void persistCycleDispositions(Long pipelineRunId, Long cycleJobRunId,
            List<CandidateDisposition> dispositions) {
        if (dispositions.isEmpty()) {
            LOG.info("[DISPOSITION] No candidates considered this cycle — nothing to persist");
            return;
        }
        Long anchorJobRunId = cycleJobRunId;
        String anchorKind = "batch job_run";
        if (anchorJobRunId == null) {
            anchorJobRunId = jobRunService.startDispositionAnchorRun(dispositions.size());
            anchorKind = "disposition-only anchor run";
        }
        LOG.info("[DISPOSITION] Persisting {} dispositions for cycle jobRunId={} ({})",
                dispositions.size(), anchorJobRunId, anchorKind);
        if (pipelineRunId == null) {
            dispositionService.persist(anchorJobRunId, dispositions);
        } else {
            // One transaction for the rows AND the link to the pipeline run (see the service's
            // javadoc): a link that cannot be written rolls the rows back with it, never swallowed.
            dispositionService.persist(pipelineRunId, anchorJobRunId, dispositions);
        }
    }

    private static Long firstNonNull(Long current, Long candidate) {
        return current != null ? current : candidate;
    }

    /**
     * Logs the date/event/region breakdown for a batch bucket — worded to say plainly whether
     * the bucket actually reached Anthropic, since a WARN line reading "Submitted N requests"
     * when {@code evaluationService.submit} had in fact returned an empty handle (2026-09-29:
     * all three cycle buckets failed with HTTP 500 and this line kept saying "Submitted" anyway)
     * is worse than no line at all — an operator trusts it.
     *
     * @param tasks  the bucket's tasks, whether or not submission succeeded
     * @param label  batch label (e.g. "inland" or "coastal")
     * @param handle the result of {@code evaluationService.submit} for this bucket — a non-null
     *               {@code batchId()} means Anthropic actually accepted the batch
     */
    private void logBatchBreakdown(List<EvaluationTask.Forecast> tasks, String label,
            EvaluationHandle handle) {
        LocalDate today = ForecastHorizon.today(clock);

        String dateBreakdown = tasks.stream()
                .collect(Collectors.groupingBy(
                        t -> "T+" + ChronoUnit.DAYS.between(today, t.date()),
                        Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));

        String eventBreakdown = tasks.stream()
                .collect(Collectors.groupingBy(
                        t -> t.targetType().name(),
                        Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));

        String regionBreakdown = tasks.stream()
                .collect(Collectors.groupingBy(
                        t -> t.location().getRegion() != null
                                ? t.location().getRegion().getName()
                                : t.location().getName(),
                        Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));

        if (handle != null && handle.batchId() != null) {
            LOG.warn("[BATCH DIAG] Submitted {} {} requests (batchId={}) — by date: [{}] "
                            + "| by event: [{}] | by region: [{}]",
                    tasks.size(), label, handle.batchId(), dateBreakdown, eventBreakdown,
                    regionBreakdown);
        } else {
            LOG.error("[BATCH DIAG] NOT submitted {} {} requests (submission failed) — by date: "
                            + "[{}] | by event: [{}] | by region: [{}]",
                    tasks.size(), label, dateBreakdown, eventBreakdown, regionBreakdown);
        }
    }

    /**
     * Region-filtered variant of the forecast batch. Delegates collection to
     * {@link ForecastTaskCollector} and submits the inland and coastal buckets
     * via the engine. Returns the first bucket Anthropic actually accepted
     * (inland preferred), or null if neither was accepted or nothing was built.
     */
    private BatchSubmitResult doSubmitForecastBatchForRegions(List<Long> regionIds) {
        RegionFilteredBatchTasks tasks =
                forecastTaskCollector.collectRegionFilteredBatches(regionIds);
        if (tasks.isEmpty()) {
            return null;
        }

        com.gregochr.goldenhour.service.evaluation.EvaluationHandle inlandHandle =
                tasks.inland().isEmpty() ? null
                        : evaluationService.submit(tasks.inland(), BatchTriggerSource.ADMIN);
        com.gregochr.goldenhour.service.evaluation.EvaluationHandle coastalHandle =
                tasks.coastal().isEmpty() ? null
                        : evaluationService.submit(tasks.coastal(), BatchTriggerSource.ADMIN);

        LOG.info("[BATCH DIAG] Admin batch split: {} inland in {}, {} coastal in {}",
                tasks.inland().size(), describeAdminBucket(tasks.inland(), inlandHandle),
                tasks.coastal().size(), describeAdminBucket(tasks.coastal(), coastalHandle));

        // Return whichever bucket Anthropic actually accepted — a null check alone
        // is not enough here, because a failed submission still returns a non-null
        // EvaluationHandle.empty() (batchId=null), so a plain null-coalescing ternary
        // would pick a failed inland handle over a genuinely submitted coastal one.
        return handleToResult(firstSubmitted(inlandHandle, coastalHandle));
    }

    /**
     * Describes one admin batch-split bucket for the {@code [BATCH DIAG] Admin batch split} log
     * line — {@code "(empty)"} is only correct when the bucket had no tasks to submit at all; a
     * bucket that had tasks but whose submission failed (a non-null, empty {@link
     * EvaluationHandle}) previously logged the same {@code "(empty)"}, indistinguishable from a
     * bucket that was never attempted.
     *
     * @param tasks  the bucket's tasks
     * @param handle the submission result, or {@code null} when the bucket was empty and
     *               {@code evaluationService.submit} was never called
     * @return the batch id, {@code "(empty)"}, or {@code "(failed)"}
     */
    private static String describeAdminBucket(List<EvaluationTask.Forecast> tasks,
            EvaluationHandle handle) {
        if (tasks.isEmpty()) {
            return "(empty)";
        }
        return handle != null && handle.batchId() != null ? handle.batchId() : "(failed)";
    }

    private static BatchSubmitResult handleToResult(
            com.gregochr.goldenhour.service.evaluation.EvaluationHandle handle) {
        if (handle == null || handle.batchId() == null) {
            return null;
        }
        return new BatchSubmitResult(handle.jobRunId(), handle.batchId(),
                handle.submittedCount());
    }

    // BatchSubmitResult was promoted to a top-level record in the same package.

    /**
     * Picks the first handle Anthropic actually accepted, inland preferred.
     *
     * <p>A bucket with no tasks is {@code null} here; a bucket that had tasks but
     * whose submission failed is a non-null {@link
     * com.gregochr.goldenhour.service.evaluation.EvaluationHandle#empty()} (a real
     * record with {@code batchId() == null}) — so neither a null check nor a plain
     * {@code a != null ? a : b} is sufficient to tell "no batch" apart from "batch
     * failed."
     *
     * @param inland  the inland handle, or null if the inland bucket was empty
     * @param coastal the coastal handle, or null if the coastal bucket was empty
     * @return the first of the two with a non-null {@code batchId()}, inland
     *     preferred; null if neither was accepted
     */
    private static com.gregochr.goldenhour.service.evaluation.EvaluationHandle firstSubmitted(
            com.gregochr.goldenhour.service.evaluation.EvaluationHandle inland,
            com.gregochr.goldenhour.service.evaluation.EvaluationHandle coastal) {
        if (inland != null && inland.batchId() != null) {
            return inland;
        }
        if (coastal != null && coastal.batchId() != null) {
            return coastal;
        }
        return null;
    }

    /**
     * Core aurora batch logic extracted to keep the public method a thin guard wrapper.
     */
    private void doSubmitAuroraBatch() {
        // Self-gate, matching AuroraPollingJob.poll(). The scheduler also marks aurora jobs
        // DISABLED_BY_CONFIG when aurora.enabled=false, but this is the layer that actually
        // stops the spend: without it, resuming or manually triggering this job from the
        // Scheduler UI would fetch NOAA and submit an Anthropic batch with the feature off.
        if (!auroraProperties.isEnabled()) {
            LOG.info("Aurora batch skipped — aurora.enabled=false");
            return;
        }

        SpaceWeatherData spaceWeather;
        try {
            spaceWeather = noaaSwpcClient.fetchAll();
        } catch (Exception e) {
            LOG.warn("Aurora batch skipped: NOAA fetch failed — {}", e.getMessage());
            return;
        }

        AlertLevel level = auroraOrchestrator.deriveAlertLevel(spaceWeather);
        if (level == AlertLevel.QUIET) {
            LOG.info("Aurora batch skipped: alert level is QUIET");
            return;
        }

        int threshold = (level == AlertLevel.STRONG)
                ? auroraProperties.getBortleThreshold().getStrong()
                : auroraProperties.getBortleThreshold().getModerate();

        List<LocationEntity> candidates = locationRepository
                .findByBortleClassLessThanEqualAndEnabledTrue(threshold);

        if (candidates.isEmpty()) {
            LOG.info("Aurora batch skipped: no Bortle-eligible locations (threshold={})", threshold);
            return;
        }

        WeatherTriageService.TriageResult triage = weatherTriageService.triage(candidates);
        if (triage.viable().isEmpty()) {
            LOG.info("Aurora batch skipped: no locations passed weather triage");
            return;
        }

        EvaluationModel model =
                modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION);
        // A pinned zone rather than the JVM default. ⚠️ Nothing *interprets* this date. On the
        // batch path it reaches CustomIdFactory.forAurora (EvaluationServiceImpl.submitAurora),
        // and the result processor discards the date it parses back out, keeping only the alert
        // level; its other reader anywhere is taskKey() ("au/LEVEL/date"), which appears in log
        // lines. So it is a label, and not the night selector it can look like. Do not start
        // deriving "which night" from it: an aurora night runs dusk-to-dawn across midnight, so no
        // calendar date names it correctly in the small hours. See
        // docs/engineering/aurora-night-selection.md.
        EvaluationTask.Aurora task = new EvaluationTask.Aurora(
                level, ForecastHorizon.today(clock), model,
                triage.viable(), triage.cloudByLocation(),
                spaceWeather, TriggerType.FORECAST_LOOKAHEAD, null);
        evaluationService.submit(List.of(task), BatchTriggerSource.SCHEDULED);
    }

    // Task construction, weather/cloud pre-fetch, triage, stability gating, and
    // bucketing live in ForecastTaskCollector. submitBatch / submitBatchWithResult
    // were collapsed into BatchSubmissionService.submit (called via EvaluationService
    // above, with the appropriate BatchTriggerSource).
}
