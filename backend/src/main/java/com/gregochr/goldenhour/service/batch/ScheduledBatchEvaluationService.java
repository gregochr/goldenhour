package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.ForecastCommandExecutor;
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.evaluation.CacheKeyFactory;
import com.gregochr.goldenhour.service.evaluation.CoastalPromptBuilder;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    private final AnthropicClient anthropicClient;
    private final ForecastBatchRepository batchRepository;
    private final LocationService locationService;
    private final BriefingService briefingService;
    private final BriefingEvaluationService briefingEvaluationService;
    private final ForecastService forecastService;
    private final ForecastStabilityClassifier stabilityClassifier;
    private final PromptBuilder promptBuilder;
    private final CoastalPromptBuilder coastalPromptBuilder;
    private final ModelSelectionService modelSelectionService;
    private final NoaaSwpcClient noaaSwpcClient;
    private final WeatherTriageService weatherTriageService;
    private final ClaudeAuroraInterpreter claudeAuroraInterpreter;
    private final AuroraOrchestrator auroraOrchestrator;
    private final LocationRepository locationRepository;
    private final AuroraProperties auroraProperties;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final JobRunService jobRunService;
    private final OpenMeteoService openMeteoService;
    private final SolarService solarService;
    private final FreshnessResolver freshnessResolver;
    private final ForecastCommandExecutor forecastCommandExecutor;

    /** Minimum ratio of successful weather pre-fetches to proceed with batch submission. */
    private final double minPrefetchSuccessRatio;

    /** Prevents concurrent forecast batch submissions. */
    private final AtomicBoolean forecastBatchRunning = new AtomicBoolean(false);

    /** Prevents concurrent aurora batch submissions. */
    private final AtomicBoolean auroraBatchRunning = new AtomicBoolean(false);

    /**
     * Constructs the batch evaluation service.
     *
     * @param anthropicClient             raw Anthropic SDK client for batch API access
     * @param batchRepository             repository for persisting batch records
     * @param locationService             service for retrieving enabled locations
     * @param briefingService             service for accessing the cached daily briefing
     * @param briefingEvaluationService   evaluation cache — checked before building requests
     * @param forecastService             service for weather fetch and triage
     * @param stabilityClassifier         classifies forecast stability per grid cell
     * @param promptBuilder               builds system prompt and user messages for inland locations
     * @param coastalPromptBuilder        builds system prompt and user messages for coastal locations
     * @param modelSelectionService       resolves the active Claude model per run type
     * @param noaaSwpcClient              NOAA SWPC space weather data client
     * @param weatherTriageService        aurora weather triage
     * @param claudeAuroraInterpreter     aurora prompt builder and response parser
     * @param auroraOrchestrator          derives alert level from space weather data
     * @param locationRepository          location JPA repository for Bortle-filtered candidates
     * @param auroraProperties            aurora configuration (Bortle thresholds)
     * @param dynamicSchedulerService     scheduler to register job targets
     * @param jobRunService               service for creating and updating job run records
     * @param openMeteoService            Open-Meteo service for bulk weather pre-fetch
     * @param solarService                solar calculation service for azimuth and event times
     * @param freshnessResolver           resolves per-stability cache freshness thresholds
     * @param forecastCommandExecutor     provides the latest stability snapshot
     * @param minPrefetchSuccessRatio    minimum ratio of successful pre-fetches to proceed
     */
    public ScheduledBatchEvaluationService(AnthropicClient anthropicClient,
            ForecastBatchRepository batchRepository,
            LocationService locationService,
            BriefingService briefingService,
            BriefingEvaluationService briefingEvaluationService,
            ForecastService forecastService,
            ForecastStabilityClassifier stabilityClassifier,
            PromptBuilder promptBuilder,
            CoastalPromptBuilder coastalPromptBuilder,
            ModelSelectionService modelSelectionService,
            NoaaSwpcClient noaaSwpcClient,
            WeatherTriageService weatherTriageService,
            ClaudeAuroraInterpreter claudeAuroraInterpreter,
            AuroraOrchestrator auroraOrchestrator,
            LocationRepository locationRepository,
            AuroraProperties auroraProperties,
            DynamicSchedulerService dynamicSchedulerService,
            JobRunService jobRunService,
            OpenMeteoService openMeteoService,
            SolarService solarService,
            FreshnessResolver freshnessResolver,
            ForecastCommandExecutor forecastCommandExecutor,
            @Value("${photocast.batch.min-prefetch-success-ratio:0.5}")
            double minPrefetchSuccessRatio) {
        this.anthropicClient = anthropicClient;
        this.batchRepository = batchRepository;
        this.locationService = locationService;
        this.briefingService = briefingService;
        this.briefingEvaluationService = briefingEvaluationService;
        this.forecastService = forecastService;
        this.stabilityClassifier = stabilityClassifier;
        this.promptBuilder = promptBuilder;
        this.coastalPromptBuilder = coastalPromptBuilder;
        this.modelSelectionService = modelSelectionService;
        this.noaaSwpcClient = noaaSwpcClient;
        this.weatherTriageService = weatherTriageService;
        this.claudeAuroraInterpreter = claudeAuroraInterpreter;
        this.auroraOrchestrator = auroraOrchestrator;
        this.locationRepository = locationRepository;
        this.auroraProperties = auroraProperties;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.jobRunService = jobRunService;
        this.openMeteoService = openMeteoService;
        this.solarService = solarService;
        this.freshnessResolver = freshnessResolver;
        this.forecastCommandExecutor = forecastCommandExecutor;
        this.minPrefetchSuccessRatio = minPrefetchSuccessRatio;
        LOG.info("Batch config: min-prefetch-success-ratio={}", minPrefetchSuccessRatio);
    }

    /**
     * Registers job targets with the dynamic scheduler.
     */
    @PostConstruct
    public void registerJobTargets() {
        dynamicSchedulerService.registerJobTarget(
                "near_term_batch_evaluation", this::submitForecastBatch);
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
     * the guard is always cleared, even if {@link #doSubmitForecastBatch()} throws.
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
            doSubmitForecastBatch();
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
     * Core forecast batch logic: collect candidates from the briefing, pre-fetch weather
     * in bulk, triage, then split into near-term and far-term batches with independent
     * model selection.
     */
    private void doSubmitForecastBatch() {
        DailyBriefingResponse briefing = briefingService.getCachedBriefing();
        if (briefing == null) {
            LOG.warn("[BATCH DIAG] Forecast batch skipped: no cached briefing available");
            return;
        }

        EvaluationModel nearTermModel =
                modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM);
        EvaluationModel farTermModel =
                modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM);
        LOG.warn("[BATCH DIAG] Starting forecast batch — nearTermModel={}, farTermModel={}, "
                        + "briefing days={}",
                nearTermModel, farTermModel,
                briefing.days() != null ? briefing.days().size() : 0);

        // First pass: collect all GO/MARGINAL tasks without fetching weather yet
        List<ForecastTask> tasks = collectForecastTasks(briefing);
        if (tasks.isEmpty()) {
            LOG.warn("[BATCH DIAG] Forecast batch: no evaluable locations found after "
                    + "task collection, skipping submission");
            return;
        }

        LOG.info("Forecast batch: {} candidate task(s) — bulk pre-fetching weather", tasks.size());

        // Bulk weather pre-fetch: resilient chunked calls with per-chunk retry/isolation
        int uniqueLocationCount = countUniqueLocations(tasks);
        Map<String, WeatherExtractionResult> prefetchedWeather = prefetchBatchWeather(tasks);
        double successRatio = uniqueLocationCount > 0
                ? (double) prefetchedWeather.size() / uniqueLocationCount : 0.0;
        if (prefetchedWeather.isEmpty()) {
            LOG.error("Forecast batch: weather pre-fetch returned 0/{} locations "
                    + "— aborting (likely Open-Meteo outage)", uniqueLocationCount);
            return;
        }
        if (successRatio < minPrefetchSuccessRatio) {
            LOG.error("Forecast batch: weather pre-fetch too degraded — {}/{} locations "
                            + "(ratio {}, threshold {}) — aborting (likely Open-Meteo outage)",
                    prefetchedWeather.size(), uniqueLocationCount,
                    String.format("%.2f", successRatio),
                    String.format("%.2f", minPrefetchSuccessRatio));
            return;
        }
        if (prefetchedWeather.size() < uniqueLocationCount) {
            LOG.warn("Forecast batch: weather pre-fetch partial — {}/{} locations fetched, "
                            + "continuing with available data",
                    prefetchedWeather.size(), uniqueLocationCount);
        }

        // Bulk cloud point pre-fetch for directional cloud and approach sampling
        CloudPointCache cloudCache;
        try {
            cloudCache = prefetchBatchCloudPoints(tasks, prefetchedWeather);
        } catch (Exception e) {
            LOG.warn("Forecast batch: cloud pre-fetch failed — continuing without cloud cache: {}",
                    e.getMessage());
            cloudCache = null;
        }

        // Second pass: triage each task, then split by near/far term and inland/coastal
        List<BatchCreateParams.Request> nearInlandRequests = new ArrayList<>();
        List<BatchCreateParams.Request> nearCoastalRequests = new ArrayList<>();
        List<BatchCreateParams.Request> farInlandRequests = new ArrayList<>();
        List<BatchCreateParams.Request> farCoastalRequests = new ArrayList<>();
        List<ForecastTask> nearInlandTasks = new ArrayList<>();
        List<ForecastTask> nearCoastalTasks = new ArrayList<>();
        List<ForecastTask> farInlandTasks = new ArrayList<>();
        List<ForecastTask> farCoastalTasks = new ArrayList<>();
        Map<String, GridCellStabilityResult> stabilityByCell = new HashMap<>();

        int skippedTriage = 0;
        int skippedStability = 0;
        int skippedError = 0;
        int includedNear = 0;
        int includedFar = 0;

        LOG.warn("[BATCH DIAG] Starting triage loop — {} candidate tasks", tasks.size());

        for (ForecastTask task : tasks) {
            try {
                // Use near-term model for triage — the model choice only affects the
                // batch request, not the weather/triage logic
                ForecastPreEvalResult preEval = forecastService.fetchWeatherAndTriage(
                        task.location(), task.date(), task.targetType(),
                        task.location().getTideType(), nearTermModel, false, null,
                        prefetchedWeather, cloudCache);
                if (preEval.triaged()) {
                    LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | reason=TRIAGED ({})",
                            task.location().getName(), task.date(), task.targetType(),
                            preEval.triageReason());
                    skippedTriage++;
                    continue;
                }
                int daysAhead = preEval.daysAhead();
                int maxDays = getStabilityWindowDays(task.location(), preEval, stabilityByCell);
                if (daysAhead > maxDays) {
                    LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | reason=STABILITY "
                                    + "T+{}d maxDays={}",
                            task.location().getName(), task.date(), task.targetType(),
                            daysAhead, maxDays);
                    skippedStability++;
                    continue;
                }

                boolean isNearTerm = daysAhead <= NEAR_TERM_MAX_DAYS;
                EvaluationModel model = isNearTerm ? nearTermModel : farTermModel;
                BatchCreateParams.Request request = buildForecastRequest(task.date(),
                        task.targetType(), task.location(), preEval.atmosphericData(), model);
                boolean isCoastal = preEval.atmosphericData().tide() != null;
                String locationType = isCoastal ? "coastal" : "inland";

                if (isNearTerm) {
                    if (isCoastal) {
                        nearCoastalRequests.add(request);
                        nearCoastalTasks.add(task);
                    } else {
                        nearInlandRequests.add(request);
                        nearInlandTasks.add(task);
                    }
                    includedNear++;
                } else {
                    if (isCoastal) {
                        farCoastalRequests.add(request);
                        farCoastalTasks.add(task);
                    } else {
                        farInlandRequests.add(request);
                        farInlandTasks.add(task);
                    }
                    includedFar++;
                }
                LOG.warn("[BATCH DIAG] INCLUDE {} | date={} event={} | tier={} type={}",
                        task.location().getName(), task.date(), task.targetType(),
                        isNearTerm ? "near" : "far", locationType);
            } catch (Exception e) {
                LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | reason=ERROR ({})",
                        task.location().getName(), task.date(), task.targetType(),
                        e.getMessage());
                skippedError++;
            }
        }

        int totalIncluded = includedNear + includedFar;
        LOG.warn("[BATCH DIAG] Triage complete — {} included (near={}, far={}), {} skipped "
                        + "(triage={}, stability={}, error={})",
                totalIncluded, includedNear, includedFar,
                skippedTriage + skippedStability + skippedError,
                skippedTriage, skippedStability, skippedError);

        if (totalIncluded == 0) {
            LOG.info("Forecast batch: no evaluable locations after triage, skipping submission");
            return;
        }

        // Submit near-term batches (inland + coastal)
        if (!nearInlandRequests.isEmpty()) {
            submitBatch(nearInlandRequests, BatchType.FORECAST,
                    "Near-term batch (inland)");
            logBatchBreakdown(nearInlandTasks, "near-term inland");
        }
        if (!nearCoastalRequests.isEmpty()) {
            submitBatch(nearCoastalRequests, BatchType.FORECAST,
                    "Near-term batch (coastal)");
            logBatchBreakdown(nearCoastalTasks, "near-term coastal");
        }

        // Submit far-term batches (inland + coastal)
        if (!farInlandRequests.isEmpty()) {
            submitBatch(farInlandRequests, BatchType.FORECAST,
                    "Far-term batch (inland)");
            logBatchBreakdown(farInlandTasks, "far-term inland");
        }
        if (!farCoastalRequests.isEmpty()) {
            submitBatch(farCoastalRequests, BatchType.FORECAST,
                    "Far-term batch (coastal)");
            logBatchBreakdown(farCoastalTasks, "far-term coastal");
        }

        LOG.info("Forecast batch split: near-term {} ({}i + {}c), far-term {} ({}i + {}c), "
                        + "total {} requests",
                includedNear, nearInlandRequests.size(), nearCoastalRequests.size(),
                includedFar, farInlandRequests.size(), farCoastalRequests.size(),
                totalIncluded);
    }

    /**
     * Logs the date/event/region breakdown for a submitted batch.
     *
     * @param tasks the included tasks for this batch
     * @param label batch label (e.g. "inland" or "coastal")
     */
    private void logBatchBreakdown(List<ForecastTask> tasks, String label) {
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
     * Region-filtered variant of the forecast batch. Same triage and stability gates,
     * but only locations in the specified regions are considered. Uses the near-term
     * model for all requests since region-filtered batches are typically admin-triggered
     * for immediate results.
     */
    private BatchSubmitResult doSubmitForecastBatchForRegions(List<Long> regionIds) {
        DailyBriefingResponse briefing = briefingService.getCachedBriefing();
        if (briefing == null) {
            LOG.warn("[BATCH] Scheduled batch skipped: no cached briefing available");
            return null;
        }

        java.util.Set<Long> regionFilter = (regionIds != null && !regionIds.isEmpty())
                ? new java.util.HashSet<>(regionIds) : null;

        EvaluationModel model = modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM);
        List<ForecastTask> tasks = collectForecastTasks(briefing);

        if (regionFilter != null) {
            tasks = tasks.stream()
                    .filter(t -> t.location().getRegion() != null
                            && regionFilter.contains(t.location().getRegion().getId()))
                    .toList();
        }

        if (tasks.isEmpty()) {
            LOG.warn("[BATCH] No evaluable locations after region filtering");
            return null;
        }

        Map<String, WeatherExtractionResult> prefetchedWeather = prefetchBatchWeather(tasks);
        if (prefetchedWeather.isEmpty()) {
            LOG.error("[BATCH] Weather pre-fetch returned zero results — aborting");
            return null;
        }

        CloudPointCache cloudCache;
        try {
            cloudCache = prefetchBatchCloudPoints(tasks, prefetchedWeather);
        } catch (Exception e) {
            LOG.warn("[BATCH] Cloud pre-fetch failed — continuing without: {}", e.getMessage());
            cloudCache = null;
        }

        List<BatchCreateParams.Request> inlandRequests = new ArrayList<>();
        List<BatchCreateParams.Request> coastalRequests = new ArrayList<>();
        Map<String, GridCellStabilityResult> stabilityByCell = new HashMap<>();

        for (ForecastTask task : tasks) {
            try {
                ForecastPreEvalResult preEval = forecastService.fetchWeatherAndTriage(
                        task.location(), task.date(), task.targetType(),
                        task.location().getTideType(), model, false, null,
                        prefetchedWeather, cloudCache);
                if (preEval.triaged()) {
                    continue;
                }
                int daysAhead = preEval.daysAhead();
                int maxDays = getStabilityWindowDays(task.location(), preEval, stabilityByCell);
                if (daysAhead > maxDays) {
                    continue;
                }
                BatchCreateParams.Request request = buildForecastRequest(task.date(),
                        task.targetType(), task.location(), preEval.atmosphericData(), model);
                if (preEval.atmosphericData().tide() != null) {
                    coastalRequests.add(request);
                } else {
                    inlandRequests.add(request);
                }
            } catch (Exception e) {
                LOG.warn("[BATCH] Failed data assembly for {}: {}",
                        task.location().getName(), e.getMessage());
            }
        }

        if (inlandRequests.isEmpty() && coastalRequests.isEmpty()) {
            return null;
        }

        BatchSubmitResult inlandResult = null;
        BatchSubmitResult coastalResult = null;

        if (!inlandRequests.isEmpty()) {
            inlandResult = submitBatchWithResult(inlandRequests, BatchType.FORECAST,
                    "Scheduled batch — admin (inland)");
        }
        if (!coastalRequests.isEmpty()) {
            coastalResult = submitBatchWithResult(coastalRequests, BatchType.FORECAST,
                    "Scheduled batch — admin (coastal)");
        }

        LOG.info("[BATCH DIAG] Admin batch split: {} inland in {}, {} coastal in {}",
                inlandRequests.size(),
                inlandResult != null ? inlandResult.batchId() : "(empty)",
                coastalRequests.size(),
                coastalResult != null ? coastalResult.batchId() : "(empty)");

        // Return whichever result succeeded — prefer inland (typically larger)
        return inlandResult != null ? inlandResult : coastalResult;
    }

    /**
     * Result of an admin-triggered batch submission.
     *
     * @param batchId      the Anthropic batch ID
     * @param requestCount number of requests submitted
     */
    public record BatchSubmitResult(String batchId, int requestCount) {}

    /**
     * Submits requests and returns a result record (for admin-triggered submissions).
     */
    private BatchSubmitResult submitBatchWithResult(List<BatchCreateParams.Request> requests,
            BatchType batchType, String logPrefix) {
        try {
            BatchCreateParams params = BatchCreateParams.builder()
                    .requests(requests)
                    .build();

            MessageBatch batch = anthropicClient.messages().batches().create(params);
            java.time.Instant expiresAt = batch.expiresAt().toInstant();

            com.gregochr.goldenhour.entity.JobRunEntity jobRun =
                    jobRunService.startBatchRun(requests.size(), batch.id());

            ForecastBatchEntity entity = new ForecastBatchEntity(
                    batch.id(), batchType, requests.size(), expiresAt);
            if (jobRun != null) {
                entity.setJobRunId(jobRun.getId());
            }
            batchRepository.save(entity);

            LOG.info("{} submitted: batchId={}, {} request(s), expires={}, jobRunId={}",
                    logPrefix, batch.id(), requests.size(), expiresAt,
                    jobRun != null ? jobRun.getId() : null);

            return new BatchSubmitResult(batch.id(), requests.size());
        } catch (Exception e) {
            LOG.error("{} submission failed: {}", logPrefix, e.getMessage(), e);
            return null;
        }
    }

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

        String userMessage = claudeAuroraInterpreter.buildUserMessage(
                level, triage.viable(), triage.cloudByLocation(), spaceWeather,
                TriggerType.FORECAST_LOOKAHEAD, null);

        EvaluationModel model =
                modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION);
        String customId = String.format("au-%s-%s", level.name(), LocalDate.now());

        BatchCreateParams.Request request = BatchCreateParams.Request.builder()
                .customId(customId)
                .params(BatchCreateParams.Request.Params.builder()
                        .model(model.getModelId())
                        .maxTokens(1024)
                        .addUserMessage(userMessage)
                        .build())
                .build();

        submitBatch(List.of(request), BatchType.AURORA, "Aurora batch");
    }

    /**
     * Submits a list of requests to the Anthropic Batch API and persists the tracking entity.
     *
     * <p>Creates a {@link com.gregochr.goldenhour.entity.JobRunEntity} with status
     * IN_PROGRESS (completedAt = null) so the batch appears in the Job Runs screen.
     * The job run ID is stored on the batch entity so the poller can update progress.
     */
    private void submitBatch(List<BatchCreateParams.Request> requests,
            BatchType batchType, String logPrefix) {
        try {
            if (LOG.isDebugEnabled() && !requests.isEmpty()) {
                LOG.debug("[BATCH DIAG] Output config schema: {}",
                        requests.get(0).params().outputConfig());
            }

            BatchCreateParams params = BatchCreateParams.builder()
                    .requests(requests)
                    .build();

            MessageBatch batch = anthropicClient.messages().batches().create(params);

            java.time.Instant expiresAt = batch.expiresAt().toInstant();

            // Create the job run first so we can link the batch entity to it
            com.gregochr.goldenhour.entity.JobRunEntity jobRun =
                    jobRunService.startBatchRun(requests.size(), batch.id());

            ForecastBatchEntity entity = new ForecastBatchEntity(
                    batch.id(), batchType, requests.size(), expiresAt);
            if (jobRun != null) {
                entity.setJobRunId(jobRun.getId());
            }
            batchRepository.save(entity);

            LOG.info("{} submitted: batchId={}, {} request(s), expires={}, jobRunId={}",
                    logPrefix, batch.id(), requests.size(), expiresAt,
                    jobRun != null ? jobRun.getId() : null);
        } catch (Exception e) {
            LOG.error("{} submission failed: {}", logPrefix, e.getMessage(), e);
        }
    }

    /**
     * First pass over the briefing: collects all GO/MARGINAL tasks that are not already
     * cached by the SSE evaluation path. No API calls are made here.
     *
     * @param briefing the current cached daily briefing
     * @return list of candidate tasks (location, date, targetType triples)
     */
    private List<ForecastTask> collectForecastTasks(DailyBriefingResponse briefing) {
        List<ForecastTask> tasks = new ArrayList<>();
        int skippedCache = 0;
        int skippedVerdict = 0;
        int skippedUnknown = 0;
        int skippedPastDate = 0;
        int totalSlots = 0;
        Map<ForecastStability, int[]> cachedByStability = new HashMap<>();
        Map<ForecastStability, int[]> eligibleByStability = new HashMap<>();
        for (ForecastStability s : ForecastStability.values()) {
            cachedByStability.put(s, new int[]{0});
            eligibleByStability.put(s, new int[]{0});
        }

        // Build location → stability lookup from the latest snapshot.
        // Falls back to UNSETTLED (aggressive refresh) if no snapshot exists.
        Map<String, ForecastStability> stabilityByLocation = buildStabilityLookup();

        // Use Europe/London because solar events are for UK locations — a sunrise
        // in Northumberland on April 19th BST is what matters, not the UTC date.
        LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));

        for (BriefingDay day : briefing.days()) {
            LocalDate date = day.date();
            if (date.isBefore(today)) {
                int daySlots = day.eventSummaries().stream()
                        .flatMap(es -> es.regions().stream())
                        .mapToInt(r -> r.slots() != null ? r.slots().size() : 0)
                        .sum();
                skippedPastDate += daySlots;
                totalSlots += daySlots;
                LOG.warn("[BATCH DIAG] SKIP date {} | reason=PAST_DATE ({} slots skipped)",
                        date, daySlots);
                continue;
            }
            for (BriefingEventSummary eventSummary : day.eventSummaries()) {
                TargetType targetType = eventSummary.targetType();
                for (BriefingRegion region : eventSummary.regions()) {
                    String cacheKey = CacheKeyFactory.build(
                            region.regionName(), date, targetType);
                    ForecastStability regionStability = mostVolatileStability(
                            region, stabilityByLocation);
                    Duration freshness = freshnessResolver.maxAgeFor(regionStability);
                    int regionSlots = region.slots() != null ? region.slots().size() : 0;
                    eligibleByStability.get(regionStability)[0] += regionSlots;
                    if (briefingEvaluationService.hasFreshEvaluation(cacheKey, freshness)) {
                        LOG.warn("[BATCH DIAG] SKIP region {} | reason=CACHED "
                                        + "(stability={}, threshold={}h, {} slots skipped)",
                                cacheKey, regionStability,
                                freshness.toHours(), regionSlots);
                        cachedByStability.get(regionStability)[0] += regionSlots;
                        skippedCache += regionSlots;
                        totalSlots += regionSlots;
                        continue;
                    }
                    for (BriefingSlot slot : region.slots()) {
                        totalSlots++;
                        if (slot.verdict() != Verdict.GO && slot.verdict() != Verdict.MARGINAL) {
                            LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | "
                                            + "reason=VERDICT_{}", slot.locationName(),
                                    date, targetType, slot.verdict());
                            skippedVerdict++;
                            continue;
                        }
                        LocationEntity location = findLocation(slot.locationName());
                        if (location == null) {
                            LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | "
                                            + "reason=UNKNOWN_LOCATION",
                                    slot.locationName(), date, targetType);
                            skippedUnknown++;
                            continue;
                        }
                        tasks.add(new ForecastTask(location, date, targetType));
                    }
                }
            }
        }

        LOG.warn("[BATCH DIAG] Task collection complete — {} tasks from {} total slots "
                        + "(pastDate={}, cached={}, verdict={}, unknownLoc={})",
                tasks.size(), totalSlots, skippedPastDate, skippedCache,
                skippedVerdict, skippedUnknown);
        logStabilityBreakdown(eligibleByStability, cachedByStability);
        return tasks;
    }

    /**
     * Counts the number of unique coordinate pairs in the task list.
     */
    private int countUniqueLocations(List<ForecastTask> tasks) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ForecastTask task : tasks) {
            seen.add(OpenMeteoService.coordKey(task.location().getLat(),
                    task.location().getLon()));
        }
        return seen.size();
    }

    /**
     * Bulk-fetches weather (forecast + air quality) for all unique location coordinates
     * in the task list. Uses resilient chunked calls with per-chunk retry and isolation.
     *
     * <p>Returns partial results if some chunks fail — callers must handle missing entries.
     *
     * @param tasks candidate tasks collected from the briefing
     * @return map from coordinate key to pre-fetched weather extraction result (may be partial)
     */
    private Map<String, WeatherExtractionResult> prefetchBatchWeather(List<ForecastTask> tasks) {
        Map<String, double[]> uniqueCoords = new LinkedHashMap<>();
        for (ForecastTask task : tasks) {
            String key = OpenMeteoService.coordKey(task.location().getLat(),
                    task.location().getLon());
            uniqueCoords.putIfAbsent(key,
                    new double[]{task.location().getLat(), task.location().getLon()});
        }
        LOG.info("Forecast batch: weather pre-fetch for {} unique location(s) (from {} tasks)",
                uniqueCoords.size(), tasks.size());
        Map<String, WeatherExtractionResult> result =
                openMeteoService.prefetchWeatherBatchResilient(
                        new ArrayList<>(uniqueCoords.values()));
        return result != null ? result : Map.of();
    }

    /**
     * Bulk-fetches cloud-only data for all directional cloud and cloud-approach sampling
     * points across every task. Mirrors the pre-fetch logic in {@code ForecastCommandExecutor}.
     *
     * @param tasks             candidate tasks
     * @param prefetchedWeather pre-fetched weather (used to compute upwind points)
     * @return cloud point cache for use in per-location triage
     */
    private CloudPointCache prefetchBatchCloudPoints(List<ForecastTask> tasks,
            Map<String, WeatherExtractionResult> prefetchedWeather) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<double[]> allPoints = new ArrayList<>();

        for (ForecastTask task : tasks) {
            double lat = task.location().getLat();
            double lon = task.location().getLon();
            LocalDate date = task.date();
            TargetType targetType = task.targetType();

            int azimuth = targetType == TargetType.SUNRISE
                    ? solarService.sunriseAzimuthDeg(lat, lon, date)
                    : solarService.sunsetAzimuthDeg(lat, lon, date);

            // 5 directional cloud points (cone + antisolar + far-solar)
            allPoints.addAll(openMeteoService.computeDirectionalCloudPoints(lat, lon, azimuth));

            // Upwind point for cloud approach trend (requires wind from pre-fetched weather)
            if (prefetchedWeather != null) {
                String coordKey = OpenMeteoService.coordKey(lat, lon);
                WeatherExtractionResult cached = prefetchedWeather.get(coordKey);
                if (cached != null && cached.forecastResponse() != null
                        && cached.forecastResponse().getHourly() != null) {
                    LocalDateTime eventTime = targetType == TargetType.SUNRISE
                            ? solarService.sunriseUtc(lat, lon, date)
                            : solarService.sunsetUtc(lat, lon, date);
                    OpenMeteoForecastResponse.Hourly h = cached.forecastResponse().getHourly();
                    List<String> times = h.getTime();
                    if (times != null && h.getWindDirection10m() != null
                            && h.getWindSpeed10m() != null) {
                        int idx = TimeSlotUtils.findNearestIndex(times, eventTime);
                        if (idx < h.getWindDirection10m().size()
                                && idx < h.getWindSpeed10m().size()) {
                            Integer windDir = h.getWindDirection10m().get(idx);
                            Double windSpeed = h.getWindSpeed10m().get(idx);
                            if (windDir != null && windSpeed != null) {
                                double[] upwind = openMeteoService.computeUpwindPoint(
                                        lat, lon, windDir, windSpeed, now, eventTime);
                                if (upwind != null) {
                                    allPoints.add(upwind);
                                }
                            }
                        }
                    }
                }
            }
        }

        LOG.info("Forecast batch: cloud point pre-fetch — {} raw points from {} tasks",
                allPoints.size(), tasks.size());
        return openMeteoService.prefetchCloudBatch(allPoints, null);
    }

    /**
     * Builds a single batch request for a forecast location.
     *
     * <p>The {@code customId} format is {@code "fc-{locationId}-{date}-{targetType}"}
     * (e.g. {@code "fc-42-2026-04-16-SUNRISE"}), which satisfies the Anthropic pattern
     * {@code ^[a-zA-Z0-9_-]{1,64}$}. {@link BatchResultProcessor} reconstructs the region
     * name by looking up the location by ID.
     */
    private BatchCreateParams.Request buildForecastRequest(LocalDate date,
            TargetType targetType, LocationEntity location, AtmosphericData data,
            EvaluationModel model) {
        PromptBuilder builder = data.tide() != null
                ? coastalPromptBuilder : promptBuilder;

        String userMessage = data.surge() != null
                ? builder.buildUserMessage(data, data.surge(),
                        data.adjustedRangeMetres(), data.astronomicalRangeMetres())
                : builder.buildUserMessage(data);

        String customId = String.format("fc-%s-%s-%s",
                location.getId(), date, targetType.name());

        return BatchCreateParams.Request.builder()
                .customId(customId)
                .params(BatchCreateParams.Request.Params.builder()
                        .model(model.getModelId())
                        .maxTokens(model.getMaxTokens())
                        .systemOfTextBlockParams(List.of(
                                TextBlockParam.builder()
                                        .text(builder.getSystemPrompt())
                                        .cacheControl(CacheControlEphemeral.builder().build())
                                        .build()))
                        .outputConfig(builder.buildOutputConfig())
                        .addUserMessage(userMessage)
                        .build())
                .build();
    }

    /**
     * Returns the evaluation window in days for the given location, capped at 3.
     *
     * <p>Mirrors the logic in {@code ForecastCommandExecutor.applyStabilityFilter()}: each
     * unique grid cell is classified once via {@link ForecastStabilityClassifier} and the result
     * cached in {@code stabilityByCell} for the duration of the batch submission run.
     *
     * <p>Defaults to 1 if the location has no grid cell or no forecast response is available.
     *
     * @param location        the location to evaluate
     * @param preEval         the pre-evaluation result containing the raw forecast response
     * @param stabilityByCell per-grid-cell stability cache shared across the current batch run
     * @return max days ahead to include, between 0 and 3
     */
    private int getStabilityWindowDays(LocationEntity location, ForecastPreEvalResult preEval,
            Map<String, GridCellStabilityResult> stabilityByCell) {
        if (!location.hasGridCell() || preEval.forecastResponse() == null) {
            return 1;
        }
        String key = location.gridCellKey();
        GridCellStabilityResult stability = stabilityByCell.computeIfAbsent(key, k ->
                stabilityClassifier.classify(
                        key, location.getGridLat(), location.getGridLng(),
                        preEval.forecastResponse().getHourly()));
        return stability != null ? Math.min(stability.evaluationWindowDays(), 3) : 1;
    }

    private LocationEntity findLocation(String name) {
        return locationService.findAllEnabled().stream()
                .filter(loc -> loc.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Logs a stability-level breakdown of cached vs eligible candidates.
     */
    private void logStabilityBreakdown(Map<ForecastStability, int[]> eligible,
            Map<ForecastStability, int[]> cached) {
        StringBuilder sb = new StringBuilder("[BATCH DIAG] Candidate breakdown by stability:");
        for (ForecastStability level : ForecastStability.values()) {
            int elig = eligible.get(level)[0];
            int cach = cached.get(level)[0];
            if (elig == 0) {
                continue;
            }
            int refreshed = elig - cach;
            double pct = elig > 0 ? (refreshed * 100.0 / elig) : 0;
            Duration threshold = freshnessResolver.maxAgeFor(level);
            sb.append(String.format(" %s: %d of %d (%.1f%% refreshed, threshold %dh) |",
                    level, refreshed, elig, pct, threshold.toHours()));
        }
        // Remove trailing pipe
        if (sb.charAt(sb.length() - 1) == '|') {
            sb.setLength(sb.length() - 1);
        }
        LOG.warn("{}", sb);
    }

    /**
     * Builds a location-name → stability lookup from the latest stability snapshot
     * held in {@link ForecastCommandExecutor}. Returns an empty map if no snapshot
     * exists (e.g. first run), causing all regions to fall back to UNSETTLED.
     */
    private Map<String, ForecastStability> buildStabilityLookup() {
        StabilitySummaryResponse snapshot = forecastCommandExecutor.getLatestStabilitySummary();
        if (snapshot == null || snapshot.cells() == null) {
            LOG.warn("[BATCH DIAG] Stability snapshot unavailable — no snapshot in memory or DB, "
                    + "all regions treated as UNSETTLED ({}h threshold)",
                    freshnessResolver.maxAgeFor(ForecastStability.UNSETTLED).toHours());
            return Map.of();
        }
        long ageHours = java.time.temporal.ChronoUnit.HOURS.between(
                snapshot.generatedAt(), java.time.Instant.now());
        String source = ageHours > 12 ? "DB (recovered after restart)" : "in-memory";
        LOG.info("[BATCH DIAG] Stability snapshot loaded from {}: age={}h, {} grid cells",
                source, ageHours, snapshot.cells().size());
        Map<String, ForecastStability> lookup = new HashMap<>();
        for (StabilitySummaryResponse.GridCellDetail cell : snapshot.cells()) {
            for (String locName : cell.locationNames()) {
                lookup.put(locName, cell.stability());
            }
        }
        return lookup;
    }

    /**
     * Returns the most volatile stability level among a region's slots.
     * UNSETTLED > TRANSITIONAL > SETTLED — if any location in the region is
     * UNSETTLED, the whole region uses the UNSETTLED (shortest) freshness threshold.
     * Falls back to UNSETTLED if no stability data is available for any slot.
     */
    private ForecastStability mostVolatileStability(BriefingRegion region,
            Map<String, ForecastStability> stabilityByLocation) {
        if (region.slots() == null || region.slots().isEmpty()
                || stabilityByLocation.isEmpty()) {
            return ForecastStability.UNSETTLED;
        }
        ForecastStability most = ForecastStability.SETTLED;
        for (BriefingSlot slot : region.slots()) {
            ForecastStability slotStability = stabilityByLocation.getOrDefault(
                    slot.locationName(), ForecastStability.UNSETTLED);
            if (slotStability == ForecastStability.UNSETTLED) {
                return ForecastStability.UNSETTLED;
            }
            if (slotStability == ForecastStability.TRANSITIONAL) {
                most = ForecastStability.TRANSITIONAL;
            }
        }
        return most;
    }

    /**
     * Lightweight task descriptor for the first-pass briefing scan.
     *
     * @param location   the location entity
     * @param date       the target date
     * @param targetType SUNRISE or SUNSET
     */
    private record ForecastTask(LocationEntity location, LocalDate date, TargetType targetType) {}
}
