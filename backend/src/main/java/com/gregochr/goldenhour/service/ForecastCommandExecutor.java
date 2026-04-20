package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.RunPhase;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.evaluation.NoOpEvaluationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Executes {@link ForecastCommand} instances using a three-phase pipeline:
 *
 * <ol>
 *   <li><strong>TRIAGE</strong> — fetch weather data for all tasks and apply heuristic checks
 *       (solar low cloud &gt;80%, precipitation &gt;2mm, visibility &lt;5km). Triaged tasks get
 *       canned entities (rating=1) with zero Claude calls.</li>
 *   <li><strong>SENTINEL_SAMPLING</strong> — group surviving tasks by region. Per region, evaluate
 *       geographic sentinel locations first. If all sentinels score &le;2, skip the rest of that
 *       region with canned entities.</li>
 *   <li><strong>FULL_EVALUATION</strong> — evaluate all remaining tasks with Claude normally.</li>
 * </ol>
 *
 * <p>Wildlife runs bypass all three phases (existing shortcut via {@code ForecastService.runForecasts()}).
 */
@Service
public class ForecastCommandExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastCommandExecutor.class);
    private static final int DEFAULT_SENTINEL_RATING_THRESHOLD = 2;

    private final ForecastService forecastService;
    private final LocationService locationService;
    private final JobRunService jobRunService;
    private final SolarService solarService;
    private final ForecastCommandFactory commandFactory;
    private final Executor forecastExecutor;
    private final OptimisationSkipEvaluator optimisationSkipEvaluator;
    private final OptimisationStrategyService optimisationStrategyService;
    private final RunProgressTracker progressTracker;
    private final ApplicationEventPublisher eventPublisher;
    private final SentinelSelector sentinelSelector;
    private final AstroConditionsService astroConditionsService;
    private final ForecastStabilityClassifier stabilityClassifier;
    private final OpenMeteoService openMeteoService;

    /** Most recent stability snapshot, populated after each scheduled triage run. */
    private final AtomicReference<StabilitySummaryResponse> latestStabilitySummary =
            new AtomicReference<>();

    /**
     * Constructs a {@code ForecastCommandExecutor}.
     *
     * @param forecastService             the service that runs individual location forecasts
     * @param locationService             the service providing persisted locations
     * @param jobRunService               the service for tracking job run metrics
     * @param solarService                the service that calculates solar event times
     * @param commandFactory              the factory for resolving evaluation models from commands
     * @param forecastExecutor            the executor used to run forecast calls in parallel
     * @param optimisationSkipEvaluator   the evaluator for configurable skip strategies
     * @param optimisationStrategyService the service for loading active strategies
     * @param progressTracker             tracks live run progress for SSE broadcasting
     * @param eventPublisher              publishes location task state transition events
     * @param sentinelSelector            selects geographic sentinel locations per region
     * @param astroConditionsService      template scorer for nightly astro observing conditions
     * @param stabilityClassifier         classifies forecast stability per grid cell
     * @param openMeteoService            Open-Meteo service for batch weather pre-fetching
     */
    public ForecastCommandExecutor(ForecastService forecastService,
            LocationService locationService, JobRunService jobRunService,
            SolarService solarService, ForecastCommandFactory commandFactory,
            Executor forecastExecutor, OptimisationSkipEvaluator optimisationSkipEvaluator,
            OptimisationStrategyService optimisationStrategyService,
            RunProgressTracker progressTracker, ApplicationEventPublisher eventPublisher,
            SentinelSelector sentinelSelector,
            AstroConditionsService astroConditionsService,
            ForecastStabilityClassifier stabilityClassifier,
            OpenMeteoService openMeteoService) {
        this.forecastService = forecastService;
        this.locationService = locationService;
        this.jobRunService = jobRunService;
        this.solarService = solarService;
        this.commandFactory = commandFactory;
        this.forecastExecutor = forecastExecutor;
        this.optimisationSkipEvaluator = optimisationSkipEvaluator;
        this.optimisationStrategyService = optimisationStrategyService;
        this.progressTracker = progressTracker;
        this.eventPublisher = eventPublisher;
        this.sentinelSelector = sentinelSelector;
        this.astroConditionsService = astroConditionsService;
        this.stabilityClassifier = stabilityClassifier;
        this.openMeteoService = openMeteoService;
    }

    /**
     * Executes a forecast command, producing evaluation entities for each location/date slot.
     *
     * @param command the command to execute
     * @return all saved evaluation entities produced by the run
     */
    public List<ForecastEvaluationEntity> execute(ForecastCommand command) {
        return execute(command, null);
    }

    /**
     * Executes a forecast command with a pre-created job run entity.
     *
     * <p>When {@code preCreatedJobRun} is non-null, it is used instead of creating a new one.
     * This allows the controller to return the job run ID synchronously before execution starts.
     *
     * @param command           the command to execute
     * @param preCreatedJobRun  a pre-created job run entity, or null to create one internally
     * @return all saved evaluation entities produced by the run
     */
    public List<ForecastEvaluationEntity> execute(ForecastCommand command,
            JobRunEntity preCreatedJobRun) {
        RunType runType = command.runType();
        EvaluationModel evaluationModel = commandFactory.resolveEvaluationModel(command);
        boolean isWildlife = command.strategy() instanceof NoOpEvaluationStrategy;

        // Load strategies once before the main loop
        List<OptimisationStrategyEntity> enabledStrategies = isWildlife
                ? List.of()
                : optimisationStrategyService.getEnabledStrategies(runType);
        String strategiesAudit = isWildlife
                ? null
                : optimisationStrategyService.serialiseEnabledStrategies(runType);

        JobRunEntity jobRun = preCreatedJobRun != null
                ? preCreatedJobRun
                : jobRunService.startRun(runType, command.triggeredManually(),
                        evaluationModel, strategiesAudit);

        // Resolve locations
        List<LocationEntity> locations = command.locations() != null
                ? command.locations()
                : locationService.findAllEnabled().stream()
                .filter(loc -> isWildlife ? isPureWildlife(loc) : hasColourTypes(loc))
                .toList();

        // Apply drive-time exclusions (locations the user chose to skip this run)
        Set<String> excludedLocations = command.excludedLocations() != null
                ? command.excludedLocations() : Set.of();
        if (!excludedLocations.isEmpty()) {
            locations = locations.stream()
                    .filter(loc -> !excludedLocations.contains(loc.getName()))
                    .toList();
            LOG.info("Drive-time filter excluded {} location(s): {}", excludedLocations.size(), excludedLocations);
        }

        List<LocalDate> dates = command.dates();

        LOG.info("Forecast run started — runType={}, model={}, {} location(s), {} date(s), strategies=[{}]",
                runType, evaluationModel, locations.size(), dates.size(),
                strategiesAudit != null ? strategiesAudit : "none");

        // Wildlife runs bypass the three-phase pipeline
        List<ForecastEvaluationEntity> results;
        if (isWildlife) {
            results = executeWildlife(locations, dates, jobRun);
        } else {
            Set<String> excludedSlots = command.excludedSlots() != null
                    ? command.excludedSlots() : Set.of();
            results = executeThreePhasePipeline(locations, dates, enabledStrategies,
                    evaluationModel, runType, jobRun, excludedSlots,
                    command.triggeredManually());
        }

        // Astro conditions scoring (piggyback — template, no Claude)
        if (isColourRunType(runType)) {
            try {
                int astroCount = astroConditionsService.evaluateAndPersist(dates);
                LOG.info("Astro conditions: {} location-dates scored", astroCount);
            } catch (Exception e) {
                LOG.warn("Astro conditions scoring failed: {}", e.getMessage(), e);
            }
        }

        return results;
    }

    // -------------------------------------------------------------------------
    // Three-phase pipeline
    // -------------------------------------------------------------------------

    private List<ForecastEvaluationEntity> executeThreePhasePipeline(
            List<LocationEntity> locations, List<LocalDate> dates,
            List<OptimisationStrategyEntity> enabledStrategies,
            EvaluationModel evaluationModel, RunType runType, JobRunEntity jobRun,
            Set<String> excludedSlots, boolean triggeredManually) {

        int succeeded = 0;
        int failed = 0;

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Build non-skipped task descriptors; collect skipped keys to publish after tracker init
        List<TaskDescriptor> nonSkippedTasks = new ArrayList<>();
        List<String[]> allTaskKeys = new ArrayList<>();
        List<String[]> skippedKeys = new ArrayList<>();

        for (LocationEntity location : locations) {
            for (LocalDate targetDate : dates) {
                List<TargetType> applicableTypes = new ArrayList<>();
                if (locationService.shouldEvaluateSunrise(location)) {
                    applicableTypes.add(TargetType.SUNRISE);
                }
                if (locationService.shouldEvaluateSunset(location)) {
                    applicableTypes.add(TargetType.SUNSET);
                }

                for (TargetType targetType : applicableTypes) {
                    String taskKey = location.getName() + "|" + targetDate + "|" + targetType;
                    allTaskKeys.add(new String[]{taskKey, location.getName(),
                            targetDate.toString(), targetType.name()});

                    String slotKey = targetDate + "|" + targetType.name();
                    boolean optimisationSkip = !triggeredManually
                            && optimisationSkipEvaluator.shouldSkip(
                                    enabledStrategies, location, targetDate, targetType);
                    if (excludedSlots.contains(slotKey)
                            || shouldSkipEvent(targetDate, targetType, location, today, now)
                            || optimisationSkip) {
                        skippedKeys.add(new String[]{taskKey, location.getName(),
                                targetDate.toString(), targetType.name()});
                    } else {
                        nonSkippedTasks.add(new TaskDescriptor(
                                location, targetDate, targetType, evaluationModel));
                    }
                }
            }
        }

        // Initialise progress tracking, then publish deferred skip events
        progressTracker.initRun(jobRun.getId(), allTaskKeys);
        for (String[] sk : skippedKeys) {
            eventPublisher.publishEvent(new LocationTaskEvent(
                    this, jobRun.getId(), sk[0], sk[1], sk[2], sk[3],
                    LocationTaskState.SKIPPED, null, null));
        }

        List<ForecastEvaluationEntity> results = new ArrayList<>();

        boolean tideAlignmentEnabled = enabledStrategies.stream()
                .anyMatch(s -> s.getStrategyType() == OptimisationStrategyType.TIDE_ALIGNMENT);

        // Pre-fetch all weather data in batch (2 API calls instead of 2N)
        Map<String, WeatherExtractionResult> prefetchedWeather = prefetchWeather(
                nonSkippedTasks, jobRun);

        // Pre-fetch all cloud sampling points in 1 batch call (~300 calls → 1)
        CloudPointCache cloudCache = prefetchCloudPoints(
                nonSkippedTasks, prefetchedWeather, jobRun);

        // Phase 1: TRIAGE (uses pre-fetched data — no individual API calls)
        progressTracker.setPhase(jobRun.getId(), RunPhase.TRIAGE);
        List<ForecastPreEvalResult> triageResults = runTriagePhase(nonSkippedTasks,
                tideAlignmentEnabled, jobRun, prefetchedWeather, cloudCache);
        List<ForecastPreEvalResult> survivors = triageResults.stream()
                .filter(r -> !r.triaged())
                .toList();
        long triagedCount = triageResults.size() - survivors.size();
        LOG.info("Triage phase complete — {} triaged, {} survivors", triagedCount, survivors.size());

        if (survivors.isEmpty()) {
            // Everything was triaged — early stop
            progressTracker.setPhase(jobRun.getId(), RunPhase.EARLY_STOP);
            jobRunService.completeRun(jobRun, succeeded, failed, dates);
            progressTracker.completeRun(jobRun.getId());
            LOG.info("Forecast run early-stopped — all tasks triaged");
            return results;
        }

        // Phase 2: SENTINEL_SAMPLING (only if strategy is enabled)
        OptimisationStrategyEntity sentinelStrategy = enabledStrategies.stream()
                .filter(s -> s.getStrategyType() == OptimisationStrategyType.SENTINEL_SAMPLING)
                .findFirst().orElse(null);

        List<ForecastPreEvalResult> fullEvalBatch;
        if (sentinelStrategy != null) {
            int threshold = sentinelStrategy.getParamValue() != null
                    ? sentinelStrategy.getParamValue() : DEFAULT_SENTINEL_RATING_THRESHOLD;
            progressTracker.setPhase(jobRun.getId(), RunPhase.SENTINEL_SAMPLING);
            SentinelPhaseResult sentinelResult = runSentinelPhase(survivors, jobRun, threshold);
            results.addAll(sentinelResult.evaluated());
            succeeded += sentinelResult.succeeded();
            failed += sentinelResult.failed();
            fullEvalBatch = sentinelResult.remaining();
            LOG.info("Sentinel phase complete — {} tasks remaining for full evaluation",
                    fullEvalBatch.size());
        } else {
            fullEvalBatch = survivors;
            LOG.info("Sentinel sampling disabled — {} tasks go directly to full evaluation",
                    fullEvalBatch.size());
        }

        if (fullEvalBatch.isEmpty()) {
            RunPhase finalPhase = survivors.isEmpty() ? RunPhase.EARLY_STOP : RunPhase.COMPLETE;
            progressTracker.setPhase(jobRun.getId(), finalPhase);
            jobRunService.completeRun(jobRun, succeeded, failed, dates);
            progressTracker.completeRun(jobRun.getId());
            LOG.info("Forecast run complete — runType={}, model={}, {} succeeded, {} failed",
                    runType, evaluationModel, succeeded, failed);
            return results;
        }

        // Stability gating: classify per grid cell, skip tasks beyond the stability window.
        // Bypass for manually triggered runs — the user explicitly requested these evaluations.
        Map<String, GridCellStabilityResult> stabilityByCell = Map.of();
        if (!triggeredManually) {
            StabilityFilterResult stabilityResult = applyStabilityFilter(fullEvalBatch);
            fullEvalBatch = stabilityResult.filteredTasks();
            stabilityByCell = stabilityResult.stabilityByCell();
        } else {
            LOG.info("Stability filter bypassed — manual run");
        }

        if (fullEvalBatch.isEmpty()) {
            progressTracker.setPhase(jobRun.getId(), RunPhase.COMPLETE);
            jobRunService.completeRun(jobRun, succeeded, failed, dates);
            progressTracker.completeRun(jobRun.getId());
            LOG.info("Forecast run complete — all remaining tasks filtered by stability");
            return results;
        }

        // Enrich surviving tasks with stability classification for Claude prompt context.
        fullEvalBatch = enrichWithStability(fullEvalBatch, stabilityByCell);

        // Phase 3: FULL_EVALUATION
        progressTracker.setPhase(jobRun.getId(), RunPhase.FULL_EVALUATION);
        List<ForecastEvaluationEntity> fullResults = runFullEvalPhase(fullEvalBatch, jobRun);
        results.addAll(fullResults);
        succeeded += fullResults.size();
        failed += fullEvalBatch.size() - fullResults.size();

        progressTracker.setPhase(jobRun.getId(), RunPhase.COMPLETE);
        jobRunService.completeRun(jobRun, succeeded, failed, dates);
        progressTracker.completeRun(jobRun.getId());
        LOG.info("Forecast run complete — runType={}, model={}, {} succeeded, {} failed",
                runType, evaluationModel, succeeded, failed);

        return results;
    }

    /**
     * Pre-fetches forecast and air quality data for all unique locations in a batch.
     * Returns a map keyed by coordinate key for lookup during triage.
     */
    private Map<String, WeatherExtractionResult> prefetchWeather(
            List<TaskDescriptor> tasks, JobRunEntity jobRun) {
        // Deduplicate by location (same location returns the same 7-day forecast)
        Map<String, double[]> uniqueCoords = new LinkedHashMap<>();
        for (TaskDescriptor task : tasks) {
            String key = OpenMeteoService.coordKey(task.location().getLat(),
                    task.location().getLon());
            uniqueCoords.putIfAbsent(key, new double[]{
                    task.location().getLat(), task.location().getLon()});
        }
        List<double[]> coordList = new ArrayList<>(uniqueCoords.values());
        LOG.info("Pre-fetching weather for {} unique locations (from {} tasks)",
                coordList.size(), tasks.size());
        return openMeteoService.prefetchWeatherBatch(coordList, jobRun);
    }

    /**
     * Pre-fetches cloud-only data for all directional cloud and cloud approach sampling points.
     * Computes azimuth per task, generates 5 directional + 1 solar horizon + optional upwind
     * point, and batch-fetches all unique grid cells in a single API call.
     */
    private CloudPointCache prefetchCloudPoints(List<TaskDescriptor> tasks,
            Map<String, WeatherExtractionResult> prefetchedWeather, JobRunEntity jobRun) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        List<double[]> allPoints = new ArrayList<>();

        for (TaskDescriptor task : tasks) {
            double lat = task.location().getLat();
            double lon = task.location().getLon();
            LocalDate date = task.date();
            TargetType targetType = task.targetType();

            int azimuth = targetType == TargetType.SUNRISE
                    ? solarService.sunriseAzimuthDeg(lat, lon, date)
                    : solarService.sunsetAzimuthDeg(lat, lon, date);

            // 5 directional cloud points (cone + antisolar + far-solar)
            allPoints.addAll(openMeteoService.computeDirectionalCloudPoints(lat, lon, azimuth));

            // Solar horizon point for cloud approach trend (same as cone centre — already included)
            // Upwind point (needs wind from prefetched weather)
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
                    int idx = com.gregochr.goldenhour.util.TimeSlotUtils
                            .findNearestIndex(times, eventTime);
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

        LOG.info("Pre-fetching cloud points: {} raw points from {} tasks",
                allPoints.size(), tasks.size());
        return openMeteoService.prefetchCloudBatch(allPoints, jobRun);
    }

    /**
     * Phase 1: Fetch weather data and apply triage heuristics to all non-skipped tasks in parallel.
     *
     * @param tasks                all non-skipped task descriptors
     * @param tideAlignmentEnabled {@code true} if the TIDE_ALIGNMENT optimisation strategy is active
     * @param jobRun               the parent job run for metrics
     * @return triage results (triaged and surviving tasks combined)
     */
    private List<ForecastPreEvalResult> runTriagePhase(List<TaskDescriptor> tasks,
            boolean tideAlignmentEnabled, JobRunEntity jobRun,
            Map<String, WeatherExtractionResult> prefetchedWeather,
            CloudPointCache cloudCache) {
        return submitParallel(tasks,
                task -> forecastService.fetchWeatherAndTriage(
                        task.location(), task.date(), task.targetType(),
                        task.location().getTideType(), task.model(), tideAlignmentEnabled, jobRun,
                        prefetchedWeather, cloudCache),
                (task, e) -> LOG.error("Triage failed for {} {} on {} [{}]: {}",
                        task.location().getName(), task.targetType(), task.date(),
                        task.model(), e.getMessage(), e));
    }

    /**
     * Phase 2: Group survivors by region, evaluate sentinels, skip regions where all sentinels
     * score at or below the threshold.
     *
     * @param survivors       tasks that survived triage
     * @param jobRun          the parent job run for metrics
     * @param ratingThreshold sentinel rating at or below which a region is skipped
     * @return phase result containing evaluated entities, remaining tasks, and counts
     */
    private SentinelPhaseResult runSentinelPhase(List<ForecastPreEvalResult> survivors,
            JobRunEntity jobRun, int ratingThreshold) {

        Map<Long, List<ForecastPreEvalResult>> byRegion = new LinkedHashMap<>();
        List<ForecastPreEvalResult> noRegion = new ArrayList<>();

        for (ForecastPreEvalResult result : survivors) {
            RegionEntity region = result.location().getRegion();
            if (region == null) {
                noRegion.add(result);
            } else {
                byRegion.computeIfAbsent(region.getId(), k -> new ArrayList<>()).add(result);
            }
        }

        List<ForecastEvaluationEntity> evaluated = new ArrayList<>();
        List<ForecastPreEvalResult> remaining = new ArrayList<>(noRegion);
        int succeeded = 0;
        int failed = 0;

        for (Map.Entry<Long, List<ForecastPreEvalResult>> entry : byRegion.entrySet()) {
            List<ForecastPreEvalResult> regionTasks = entry.getValue();

            List<LocationEntity> regionLocations = regionTasks.stream()
                    .map(ForecastPreEvalResult::location)
                    .distinct()
                    .toList();

            List<LocationEntity> sentinelLocations = sentinelSelector.selectSentinels(regionLocations);

            List<ForecastPreEvalResult> sentinelTasks = new ArrayList<>();
            List<ForecastPreEvalResult> remainderTasks = new ArrayList<>();
            for (ForecastPreEvalResult task : regionTasks) {
                if (sentinelLocations.contains(task.location())) {
                    sentinelTasks.add(task);
                } else {
                    remainderTasks.add(task);
                }
            }

            boolean allSentinelsLow = true;
            for (ForecastPreEvalResult sentinel : sentinelTasks) {
                try {
                    ForecastEvaluationEntity entity = forecastService.evaluateAndPersist(
                            sentinel, jobRun);
                    evaluated.add(entity);
                    succeeded++;
                    if (entity.getRating() != null && entity.getRating() > ratingThreshold) {
                        allSentinelsLow = false;
                    }
                } catch (Exception e) {
                    LOG.error("Sentinel evaluation failed for {} {} on {}: {}",
                            sentinel.location().getName(), sentinel.targetType(),
                            sentinel.date(), e.getMessage(), e);
                    failed++;
                    allSentinelsLow = false; // Don't skip region on error
                }
            }

            if (allSentinelsLow && !sentinelTasks.isEmpty()) {
                String reason = "Region sentinel sampling — all sentinels rated "
                        + ratingThreshold + " or below";
                for (ForecastPreEvalResult task : remainderTasks) {
                    try {
                        ForecastEvaluationEntity entity = forecastService.persistCannedResult(
                                task, reason, jobRun);
                        evaluated.add(entity);
                    } catch (Exception e) {
                        LOG.error("Failed to persist canned result for {} {} on {}: {}",
                                task.location().getName(), task.targetType(),
                                task.date(), e.getMessage(), e);
                    }
                }
                LOG.info("Region {} sentinel early-stop — {} tasks skipped",
                        entry.getKey(), remainderTasks.size());
            } else {
                remaining.addAll(remainderTasks);
            }
        }

        return new SentinelPhaseResult(evaluated, remaining, succeeded, failed);
    }

    /**
     * Phase 3: Full Claude evaluation of all remaining tasks.
     *
     * @param tasks  tasks to evaluate
     * @param jobRun the parent job run for metrics
     * @return list of saved evaluation entities
     */
    private List<ForecastEvaluationEntity> runFullEvalPhase(List<ForecastPreEvalResult> tasks,
            JobRunEntity jobRun) {
        return submitParallel(tasks,
                task -> forecastService.evaluateAndPersist(task, jobRun),
                (task, e) -> LOG.error("Full evaluation failed for {} {} on {}: {}",
                        task.location().getName(), task.targetType(),
                        task.date(), e.getMessage(), e));
    }

    /**
     * Returns the most recent stability summary, or {@code null} if no scheduled run
     * has completed yet (manual runs bypass the stability filter and do not update this).
     *
     * @return latest snapshot, or {@code null}
     */
    public StabilitySummaryResponse getLatestStabilitySummary() {
        return latestStabilitySummary.get();
    }

    /**
     * Result of stability filtering: the filtered task list plus the underlying classification map.
     *
     * @param filteredTasks    tasks within their grid cell's stability window
     * @param stabilityByCell  stability classification keyed by grid cell key
     */
    private record StabilityFilterResult(
            List<ForecastPreEvalResult> filteredTasks,
            Map<String, GridCellStabilityResult> stabilityByCell) {
    }

    /**
     * Classifies forecast stability per grid cell and filters out tasks whose
     * {@code daysAhead} exceeds the stability evaluation window.
     *
     * <p>Each unique grid cell is classified once using the first available
     * Open-Meteo response for that cell. Tasks without a grid cell assignment
     * default to TRANSITIONAL (T+1 window).
     *
     * @param batch tasks surviving triage and sentinel phases
     * @return filter result containing tasks and the stability classification map
     */
    private StabilityFilterResult applyStabilityFilter(List<ForecastPreEvalResult> batch) {
        Map<String, GridCellStabilityResult> stabilityByCell = new ConcurrentHashMap<>();

        for (ForecastPreEvalResult task : batch) {
            LocationEntity loc = task.location();
            if (!loc.hasGridCell() || task.forecastResponse() == null) {
                continue;
            }
            String key = loc.gridCellKey();
            stabilityByCell.computeIfAbsent(key, k -> {
                OpenMeteoForecastResponse resp = task.forecastResponse();
                return stabilityClassifier.classify(
                        key, loc.getGridLat(), loc.getGridLng(),
                        resp != null ? resp.getHourly() : null);
            });
        }

        Map<ForecastStability, Long> countsByStability = stabilityByCell.values().stream()
                .collect(Collectors.groupingBy(GridCellStabilityResult::stability, Collectors.counting()));
        countsByStability.forEach((s, count) ->
                LOG.info("Stability: {} = {} grid cells", s, count));

        // Collect unique location names per grid cell for the summary snapshot.
        Map<String, Set<String>> locationsByCell = new LinkedHashMap<>();
        for (ForecastPreEvalResult task : batch) {
            if (task.location().hasGridCell()) {
                locationsByCell
                        .computeIfAbsent(task.location().gridCellKey(), k -> new LinkedHashSet<>())
                        .add(task.location().getName());
            }
        }

        // Build and cache summary for admin endpoint.
        List<StabilitySummaryResponse.GridCellDetail> cellDetails = stabilityByCell.values().stream()
                .map(r -> new StabilitySummaryResponse.GridCellDetail(
                        r.gridCellKey(), r.gridLat(), r.gridLng(),
                        r.stability(), r.reason(), r.evaluationWindowDays(),
                        List.copyOf(locationsByCell.getOrDefault(r.gridCellKey(), Set.of()))))
                .sorted(java.util.Comparator.comparing(StabilitySummaryResponse.GridCellDetail::gridCellKey))
                .toList();
        latestStabilitySummary.set(new StabilitySummaryResponse(
                Instant.now(), stabilityByCell.size(), countsByStability, cellDetails));

        int originalSize = batch.size();
        List<ForecastPreEvalResult> filtered = batch.stream()
                .filter(task -> {
                    if (!task.location().hasGridCell()) {
                        return task.daysAhead() <= 1;
                    }
                    GridCellStabilityResult stability =
                            stabilityByCell.get(task.location().gridCellKey());
                    if (stability == null) {
                        return task.daysAhead() <= 1;
                    }
                    int maxDays = Math.min(stability.evaluationWindowDays(), 3);
                    if (task.daysAhead() > maxDays) {
                        LOG.debug("Stability filter: skipping {} T+{} — {} ({})",
                                task.location().getName(), task.daysAhead(),
                                stability.stability(), stability.reason());
                        return false;
                    }
                    return true;
                })
                .toList();

        int skipped = originalSize - filtered.size();
        if (skipped > 0) {
            LOG.info("Stability filter: {}/{} tasks skipped (beyond stability window)",
                    skipped, originalSize);
        }
        return new StabilityFilterResult(filtered, stabilityByCell);
    }

    /**
     * Enriches each task's {@link AtmosphericData} with stability classification from the
     * grid cell stability map. Tasks without a matching grid cell are left unchanged.
     *
     * @param tasks           tasks to enrich
     * @param stabilityByCell stability results keyed by grid cell key
     * @return new list with stability-enriched atmospheric data
     */
    private List<ForecastPreEvalResult> enrichWithStability(
            List<ForecastPreEvalResult> tasks,
            Map<String, GridCellStabilityResult> stabilityByCell) {
        if (stabilityByCell.isEmpty()) {
            return tasks;
        }
        return tasks.stream()
                .map(task -> {
                    if (!task.location().hasGridCell() || task.atmosphericData() == null) {
                        return task;
                    }
                    GridCellStabilityResult result =
                            stabilityByCell.get(task.location().gridCellKey());
                    if (result == null) {
                        return task;
                    }
                    AtmosphericData enriched = task.atmosphericData()
                            .withStability(result.stability(), result.reason());
                    return new ForecastPreEvalResult(
                            task.triaged(), task.triageReason(), task.triageCategory(),
                            enriched, task.location(), task.date(), task.targetType(),
                            task.eventTime(), task.azimuth(), task.daysAhead(),
                            task.model(), task.tideTypes(), task.taskKey(),
                            task.forecastResponse());
                })
                .toList();
    }

    // -------------------------------------------------------------------------
    // Wildlife (bypass pipeline)
    // -------------------------------------------------------------------------

    private List<ForecastEvaluationEntity> executeWildlife(List<LocationEntity> locations,
            List<LocalDate> dates, JobRunEntity jobRun) {
        int succeeded = 0;
        int failed = 0;

        List<CompletableFuture<List<ForecastEvaluationEntity>>> futures = new ArrayList<>();
        List<String[]> taskKeys = new ArrayList<>();

        for (LocationEntity location : locations) {
            for (LocalDate targetDate : dates) {
                String taskKey = location.getName() + "|" + targetDate + "|HOURLY";
                taskKeys.add(new String[]{taskKey, location.getName(),
                        targetDate.toString(), "HOURLY"});
                futures.add(CompletableFuture.supplyAsync(
                        () -> runForecast(location, targetDate, null,
                                EvaluationModel.WILDLIFE, jobRun),
                        forecastExecutor));
            }
        }

        progressTracker.initRun(jobRun.getId(), taskKeys);

        List<ForecastEvaluationEntity> results = new ArrayList<>();
        for (CompletableFuture<List<ForecastEvaluationEntity>> future : futures) {
            try {
                List<ForecastEvaluationEntity> taskResults = future.join();
                if (taskResults != null && !taskResults.isEmpty()) {
                    results.addAll(taskResults);
                    succeeded += taskResults.size();
                } else {
                    failed++;
                }
            } catch (Exception e) {
                LOG.error("Wildlife future join failed: {}", e.getMessage(), e);
                failed++;
            }
        }

        jobRunService.completeRun(jobRun, succeeded, failed, dates);
        progressTracker.completeRun(jobRun.getId());
        return results;
    }

    // -------------------------------------------------------------------------
    // Utility methods
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the run type is a colour photography run
     * (sunrise/sunset evaluation) that should also trigger astro conditions scoring.
     */
    private static boolean isColourRunType(RunType runType) {
        return runType == RunType.VERY_SHORT_TERM
                || runType == RunType.SHORT_TERM
                || runType == RunType.LONG_TERM;
    }

    /**
     * Returns {@code true} if the location has at least one colour photography type
     * (LANDSCAPE, SEASCAPE, or WATERFALL), or has no types at all (treated as colour).
     *
     * @param loc the location to check
     * @return {@code true} if colour forecasts should be generated for this location
     */
    boolean hasColourTypes(LocationEntity loc) {
        return loc.getLocationType().contains(LocationType.LANDSCAPE)
                || loc.getLocationType().contains(LocationType.SEASCAPE)
                || loc.getLocationType().contains(LocationType.WATERFALL)
                || loc.getLocationType().isEmpty();
    }

    /**
     * Returns {@code true} if the location is exclusively a WILDLIFE location
     * (i.e. has WILDLIFE type and no colour photography types).
     *
     * @param loc the location to check
     * @return {@code true} if only wildlife comfort rows should be generated
     */
    boolean isPureWildlife(LocationEntity loc) {
        return loc.getLocationType().contains(LocationType.WILDLIFE) && !hasColourTypes(loc);
    }

    private boolean shouldSkipEvent(LocalDate targetDate, TargetType targetType,
            LocationEntity location, LocalDate today, LocalDateTime now) {
        if (!targetDate.equals(today)) {
            return false;
        }
        LocalDateTime eventTime = targetType == TargetType.SUNRISE
                ? solarService.sunriseUtc(location.getLat(), location.getLon(), targetDate)
                : solarService.sunsetUtc(location.getLat(), location.getLon(), targetDate);
        return now.isAfter(eventTime);
    }

    private List<ForecastEvaluationEntity> runForecast(LocationEntity location, LocalDate targetDate,
            TargetType targetType, EvaluationModel model, JobRunEntity jobRun) {
        try {
            return forecastService.runForecasts(
                    location, targetDate, targetType, location.getTideType(), model, jobRun);
        } catch (Exception e) {
            LOG.error("Forecast failed for {} {} on {} [{}]: {}",
                    location.getName(), targetType, targetDate, model, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Result of the sentinel sampling phase: evaluated entities, remaining tasks for full eval,
     * and incremental succeeded/failed counts.
     */
    private record SentinelPhaseResult(List<ForecastEvaluationEntity> evaluated,
            List<ForecastPreEvalResult> remaining, int succeeded, int failed) {
    }

    /**
     * Submits tasks to the forecast executor in parallel and collects non-null results.
     * Exceptions from individual tasks are reported via {@code onError}; join failures are logged.
     *
     * @param tasks   the tasks to execute
     * @param action  function to apply to each task
     * @param onError called when a task throws an exception
     * @param <T>     task type
     * @param <R>     result type
     * @return list of non-null results
     */
    private <T, R> List<R> submitParallel(List<T> tasks, Function<T, R> action,
            BiConsumer<T, Exception> onError) {
        List<CompletableFuture<R>> futures = tasks.stream()
                .map(t -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return action.apply(t);
                    } catch (Exception e) {
                        onError.accept(t, e);
                        return null;
                    }
                }, forecastExecutor))
                .toList();

        List<R> results = new ArrayList<>();
        for (CompletableFuture<R> f : futures) {
            try {
                R r = f.join();
                if (r != null) {
                    results.add(r);
                }
            } catch (Exception e) {
                LOG.error("Future join failed: {}", e.getMessage(), e);
            }
        }
        return results;
    }

    /**
     * Descriptor for a non-skipped task awaiting triage.
     */
    private record TaskDescriptor(LocationEntity location, LocalDate date,
            TargetType targetType, EvaluationModel model) {
    }
}
