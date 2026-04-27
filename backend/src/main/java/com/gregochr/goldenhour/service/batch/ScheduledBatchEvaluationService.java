package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
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
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.ForecastCommandExecutor;
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.evaluation.CacheKeyFactory;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
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

    private final LocationService locationService;
    private final BriefingService briefingService;
    private final BriefingEvaluationService briefingEvaluationService;
    private final ForecastService forecastService;
    private final ForecastStabilityClassifier stabilityClassifier;
    private final ModelSelectionService modelSelectionService;
    private final NoaaSwpcClient noaaSwpcClient;
    private final WeatherTriageService weatherTriageService;
    private final AuroraOrchestrator auroraOrchestrator;
    private final LocationRepository locationRepository;
    private final AuroraProperties auroraProperties;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final OpenMeteoService openMeteoService;
    private final SolarService solarService;
    private final FreshnessResolver freshnessResolver;
    private final ForecastCommandExecutor forecastCommandExecutor;
    private final EvaluationService evaluationService;
    private final ForecastTaskCollector forecastTaskCollector;

    /** Minimum ratio of successful weather pre-fetches to proceed with batch submission. */
    private final double minPrefetchSuccessRatio;

    /** Prevents concurrent forecast batch submissions. */
    private final AtomicBoolean forecastBatchRunning = new AtomicBoolean(false);

    /** Prevents concurrent aurora batch submissions. */
    private final AtomicBoolean auroraBatchRunning = new AtomicBoolean(false);

    /**
     * Constructs the batch evaluation service.
     *
     * @param locationService             service for retrieving enabled locations
     * @param briefingService             service for accessing the cached daily briefing
     * @param briefingEvaluationService   evaluation cache — checked before building requests
     * @param forecastService             service for weather fetch and triage
     * @param stabilityClassifier         classifies forecast stability per grid cell
     * @param modelSelectionService       resolves the active Claude model per run type
     * @param noaaSwpcClient              NOAA SWPC space weather data client
     * @param weatherTriageService        aurora weather triage
     * @param auroraOrchestrator          derives alert level from space weather data
     * @param locationRepository          location JPA repository for Bortle-filtered candidates
     * @param auroraProperties            aurora configuration (Bortle thresholds)
     * @param dynamicSchedulerService     scheduler to register job targets
     * @param openMeteoService            Open-Meteo service for bulk weather pre-fetch
     * @param solarService                solar calculation service for azimuth and event times
     * @param freshnessResolver           resolves per-stability cache freshness thresholds
     * @param forecastCommandExecutor     provides the latest stability snapshot
     * @param evaluationService           Pass 3.2 engine — builds requests + submits + processes
     * @param forecastTaskCollector       Pass 3.2.1 collector — task construction + triage + bucketing
     * @param minPrefetchSuccessRatio    minimum ratio of successful pre-fetches to proceed
     */
    public ScheduledBatchEvaluationService(LocationService locationService,
            BriefingService briefingService,
            BriefingEvaluationService briefingEvaluationService,
            ForecastService forecastService,
            ForecastStabilityClassifier stabilityClassifier,
            ModelSelectionService modelSelectionService,
            NoaaSwpcClient noaaSwpcClient,
            WeatherTriageService weatherTriageService,
            AuroraOrchestrator auroraOrchestrator,
            LocationRepository locationRepository,
            AuroraProperties auroraProperties,
            DynamicSchedulerService dynamicSchedulerService,
            OpenMeteoService openMeteoService,
            SolarService solarService,
            FreshnessResolver freshnessResolver,
            ForecastCommandExecutor forecastCommandExecutor,
            EvaluationService evaluationService,
            ForecastTaskCollector forecastTaskCollector,
            @Value("${photocast.batch.min-prefetch-success-ratio:0.5}")
            double minPrefetchSuccessRatio) {
        this.locationService = locationService;
        this.briefingService = briefingService;
        this.briefingEvaluationService = briefingEvaluationService;
        this.forecastService = forecastService;
        this.stabilityClassifier = stabilityClassifier;
        this.modelSelectionService = modelSelectionService;
        this.noaaSwpcClient = noaaSwpcClient;
        this.weatherTriageService = weatherTriageService;
        this.auroraOrchestrator = auroraOrchestrator;
        this.locationRepository = locationRepository;
        this.auroraProperties = auroraProperties;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.openMeteoService = openMeteoService;
        this.solarService = solarService;
        this.freshnessResolver = freshnessResolver;
        this.forecastCommandExecutor = forecastCommandExecutor;
        this.evaluationService = evaluationService;
        this.forecastTaskCollector = forecastTaskCollector;
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
     * Core forecast batch logic. Delegates task collection (briefing read, weather +
     * cloud pre-fetch, triage, stability, bucketing) to {@link ForecastTaskCollector}
     * and submits each non-empty bucket separately via {@link EvaluationService}.
     */
    private void doSubmitForecastBatch() {
        ScheduledBatchTasks tasks = forecastTaskCollector.collectScheduledBatches();
        if (tasks.isEmpty()) {
            return;
        }

        if (!tasks.nearInland().isEmpty()) {
            evaluationService.submit(tasks.nearInland(), BatchTriggerSource.SCHEDULED);
            logBatchBreakdown(tasks.nearInland(), "near-term inland");
        }
        if (!tasks.nearCoastal().isEmpty()) {
            evaluationService.submit(tasks.nearCoastal(), BatchTriggerSource.SCHEDULED);
            logBatchBreakdown(tasks.nearCoastal(), "near-term coastal");
        }
        if (!tasks.farInland().isEmpty()) {
            evaluationService.submit(tasks.farInland(), BatchTriggerSource.SCHEDULED);
            logBatchBreakdown(tasks.farInland(), "far-term inland");
        }
        if (!tasks.farCoastal().isEmpty()) {
            evaluationService.submit(tasks.farCoastal(), BatchTriggerSource.SCHEDULED);
            logBatchBreakdown(tasks.farCoastal(), "far-term coastal");
        }

        LOG.info("Forecast batch split: near-term {} ({}i + {}c), far-term {} ({}i + {}c), "
                        + "total {} requests",
                tasks.nearInland().size() + tasks.nearCoastal().size(),
                tasks.nearInland().size(), tasks.nearCoastal().size(),
                tasks.farInland().size() + tasks.farCoastal().size(),
                tasks.farInland().size(), tasks.farCoastal().size(),
                tasks.totalSize());
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

    // submitBatch / submitBatchWithResult were collapsed into BatchSubmissionService.submit
    // (called inline above with the appropriate BatchTriggerSource).

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
