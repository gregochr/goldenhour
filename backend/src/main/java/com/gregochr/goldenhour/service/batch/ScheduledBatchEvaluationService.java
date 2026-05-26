package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.evaluation.EvaluationHandle;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
            ForecastDispositionService dispositionService) {
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
    }

    /**
     * Registers the aurora batch job target with the dynamic scheduler.
     *
     * <p>The {@code near_term_batch_evaluation} target is registered by
     * {@code NightlyPipelineOrchestrator} since V102 — the cron now invokes
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
            doSubmitForecastBatch(null);
        } finally {
            forecastBatchRunning.set(false);
        }
    }

    /**
     * Cycle-aware variant called by {@code NightlyPipelineOrchestrator}. Identical to
     * {@link #submitForecastBatch()} except every {@code forecast_batch} row it produces
     * is tagged with the given {@code pipelineRunId} so the orchestrator can detect
     * cycle completion later via a single DB query.
     *
     * @param pipelineRunId orchestrated cycle id
     */
    public void submitForecastBatchForPipelineRun(Long pipelineRunId) {
        java.util.Objects.requireNonNull(pipelineRunId, "pipelineRunId");
        if (!forecastBatchRunning.compareAndSet(false, true)) {
            LOG.warn("Forecast batch already running — orchestrator trigger dropped "
                    + "(pipelineRunId={})", pipelineRunId);
            return;
        }
        try {
            doSubmitForecastBatch(pipelineRunId);
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
     * Core forecast batch logic. Delegates task collection (briefing read, weather +
     * cloud pre-fetch, triage, stability, bucketing) to {@link ForecastTaskCollector}
     * and submits each non-empty bucket separately via {@link EvaluationService}.
     *
     * @param pipelineRunId orchestrated cycle id to tag the submitted batches with,
     *                      or {@code null} for legacy cron-direct invocations
     */
    private void doSubmitForecastBatch(Long pipelineRunId) {
        ScheduledBatchTasks tasks = forecastTaskCollector.collectScheduledBatches();
        if (tasks.isEmpty()) {
            return;
        }

        // Submit each non-empty bucket; capture the first non-null jobRunId so
        // every disposition the collector accumulated (across all four buckets,
        // plus skips) can be persisted against the cycle's representative
        // job_run. The other buckets create their own job_runs for cost/API
        // tracking but do not re-persist dispositions.
        Long cycleJobRunId = null;

        if (!tasks.nearInland().isEmpty()) {
            EvaluationHandle h = evaluationService.submit(
                    tasks.nearInland(), BatchTriggerSource.SCHEDULED, pipelineRunId);
            cycleJobRunId = firstNonNull(cycleJobRunId, h.jobRunId());
            logBatchBreakdown(tasks.nearInland(), "near-term inland");
        }
        if (!tasks.nearCoastal().isEmpty()) {
            EvaluationHandle h = evaluationService.submit(
                    tasks.nearCoastal(), BatchTriggerSource.SCHEDULED, pipelineRunId);
            cycleJobRunId = firstNonNull(cycleJobRunId, h.jobRunId());
            logBatchBreakdown(tasks.nearCoastal(), "near-term coastal");
        }
        if (!tasks.farInland().isEmpty()) {
            EvaluationHandle h = evaluationService.submit(
                    tasks.farInland(), BatchTriggerSource.SCHEDULED, pipelineRunId);
            cycleJobRunId = firstNonNull(cycleJobRunId, h.jobRunId());
            logBatchBreakdown(tasks.farInland(), "far-term inland");
        }
        if (!tasks.farCoastal().isEmpty()) {
            EvaluationHandle h = evaluationService.submit(
                    tasks.farCoastal(), BatchTriggerSource.SCHEDULED, pipelineRunId);
            cycleJobRunId = firstNonNull(cycleJobRunId, h.jobRunId());
            logBatchBreakdown(tasks.farCoastal(), "far-term coastal");
        }

        // Persist every disposition the collector recorded (EVALUATED plus all
        // SKIPPED_*) against the cycle's first real job_run. Skipped if every
        // bucket's submission failed at the Anthropic call — the [BATCH DIAG]
        // logs still cover that case for live tailing.
        dispositionService.persist(cycleJobRunId, tasks.dispositions());

        LOG.info("Forecast batch split: near-term {} ({}i + {}c), far-term {} ({}i + {}c), "
                        + "total {} requests, pipelineRunId={}",
                tasks.nearInland().size() + tasks.nearCoastal().size(),
                tasks.nearInland().size(), tasks.nearCoastal().size(),
                tasks.farInland().size() + tasks.farCoastal().size(),
                tasks.farInland().size(), tasks.farCoastal().size(),
                tasks.totalSize(), pipelineRunId);
    }

    private static Long firstNonNull(Long current, Long candidate) {
        return current != null ? current : candidate;
    }

    /**
     * Logs the date/event/region breakdown for a submitted batch.
     *
     * @param tasks the included tasks for this batch
     * @param label batch label (e.g. "inland" or "coastal")
     */
    private void logBatchBreakdown(List<EvaluationTask.Forecast> tasks, String label) {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));

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

        LOG.warn("[BATCH DIAG] Submitted {} {} requests — by date: [{}] | by event: [{}] "
                        + "| by region: [{}]",
                tasks.size(), label, dateBreakdown, eventBreakdown, regionBreakdown);
    }

    /**
     * Region-filtered variant of the forecast batch. Delegates collection to
     * {@link ForecastTaskCollector} and submits the inland and coastal buckets
     * via the engine. Returns the inland handle if any (preferring inland as
     * typically larger), or the coastal handle, or null if both were empty.
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
                tasks.inland().size(),
                inlandHandle != null ? inlandHandle.batchId() : "(empty)",
                tasks.coastal().size(),
                coastalHandle != null ? coastalHandle.batchId() : "(empty)");

        // Return whichever result succeeded — prefer inland (typically larger)
        return handleToResult(inlandHandle != null ? inlandHandle : coastalHandle);
    }

    private static BatchSubmitResult handleToResult(
            com.gregochr.goldenhour.service.evaluation.EvaluationHandle handle) {
        if (handle == null || handle.batchId() == null) {
            return null;
        }
        return new BatchSubmitResult(handle.batchId(), handle.submittedCount());
    }

    // BatchSubmitResult was promoted to a top-level record in the same package.

    /**
     * Core aurora batch logic extracted to keep the public method a thin guard wrapper.
     */
    private void doSubmitAuroraBatch() {
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
        EvaluationTask.Aurora task = new EvaluationTask.Aurora(
                level, LocalDate.now(), model,
                triage.viable(), triage.cloudByLocation(),
                spaceWeather, TriggerType.FORECAST_LOOKAHEAD, null);
        evaluationService.submit(List.of(task), BatchTriggerSource.SCHEDULED);
    }

    // Task construction, weather/cloud pre-fetch, triage, stability gating, and
    // bucketing live in ForecastTaskCollector. submitBatch / submitBatchWithResult
    // were collapsed into BatchSubmissionService.submit (called via EvaluationService
    // above, with the appropriate BatchTriggerSource).
}
