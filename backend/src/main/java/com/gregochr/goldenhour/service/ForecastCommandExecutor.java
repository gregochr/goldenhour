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
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.RunPhase;
import com.gregochr.goldenhour.model.RunProgress;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.batch.GridCellStabilityService;
import com.gregochr.goldenhour.service.batch.NightlyEligibilityPolicy;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

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

    /** Reason on a task the stability filter dropped from evaluation. */
    static final String STABILITY_SKIP_REASON = "Skipped: forecast too unsettled this far ahead to evaluate.";

    /** Reason on a wildlife task whose hourly forecast could not be produced. */
    static final String WILDLIFE_FAILED_REASON = "Hourly forecast failed (see server log).";

    private final ForecastService forecastService;
    private final LocationService locationService;
    private final JobRunService jobRunService;
    private final SolarService solarService;
    private final ForecastCommandFactory commandFactory;
    private final Executor forecastExecutor;
    private final OptimisationStrategyService optimisationStrategyService;
    private final RunProgressTracker progressTracker;
    private final ApplicationEventPublisher eventPublisher;
    private final SentinelSelector sentinelSelector;
    private final AstroConditionsService astroConditionsService;
    private final OpenMeteoService openMeteoService;
    private final Clock clock;
    private final RunCompletion runCompletion;

    /**
     * Shared classifier + snapshot producer, wrapping the injected classifier and
     * snapshot provider — the same seam the batch pipeline's {@code ForecastTaskCollector}
     * constructs, so both forecast engines share one snapshot schema and publish rule.
     */
    private final GridCellStabilityService gridCellStabilityService;

    /**
     * Constructs a {@code ForecastCommandExecutor}.
     *
     * @param forecastService             the service that runs individual location forecasts
     * @param locationService             the service providing persisted locations
     * @param jobRunService               the service for tracking job run metrics
     * @param solarService                the service that calculates solar event times
     * @param commandFactory              the factory for resolving evaluation models from commands
     * @param forecastExecutor            the executor used to run forecast calls in parallel
     * @param optimisationStrategyService the service for loading active strategies
     * @param progressTracker             tracks live run progress for SSE broadcasting
     * @param eventPublisher              publishes location task state transition events
     * @param sentinelSelector            selects geographic sentinel locations per region
     * @param astroConditionsService      template scorer for nightly astro observing conditions
     * @param stabilityClassifier         classifies forecast stability per grid cell
     * @param openMeteoService            Open-Meteo service for batch weather pre-fetching
     * @param stabilitySnapshotProvider   in-memory + DB store for stability snapshots
     * @param clock                       single reference clock for the run: supplies "today" on the
     *                                    UK civil calendar via {@link ForecastHorizon}, and the UTC
     *                                    instant the already-past event check compares against
     */
    public ForecastCommandExecutor(ForecastService forecastService,
            LocationService locationService, JobRunService jobRunService,
            SolarService solarService, ForecastCommandFactory commandFactory,
            Executor forecastExecutor,
            OptimisationStrategyService optimisationStrategyService,
            RunProgressTracker progressTracker, ApplicationEventPublisher eventPublisher,
            SentinelSelector sentinelSelector,
            AstroConditionsService astroConditionsService,
            ForecastStabilityClassifier stabilityClassifier,
            OpenMeteoService openMeteoService,
            StabilitySnapshotProvider stabilitySnapshotProvider,
            Clock clock) {
        this.forecastService = forecastService;
        this.locationService = locationService;
        this.jobRunService = jobRunService;
        this.solarService = solarService;
        this.commandFactory = commandFactory;
        this.forecastExecutor = forecastExecutor;
        this.optimisationStrategyService = optimisationStrategyService;
        this.progressTracker = progressTracker;
        this.eventPublisher = eventPublisher;
        this.sentinelSelector = sentinelSelector;
        this.astroConditionsService = astroConditionsService;
        this.openMeteoService = openMeteoService;
        this.clock = clock;
        this.runCompletion = new RunCompletion(jobRunService, progressTracker, eventPublisher);
        this.gridCellStabilityService =
                new GridCellStabilityService(stabilityClassifier, stabilitySnapshotProvider);
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
     * <p>⚠️ <b>This is the one guard every hand-started run passes through</b> (all five
     * {@code ForecastController} endpoints discard the future this runs on, so nothing else can
     * see a failure). Whatever escapes the pipeline, the run is still told to the admin and
     * closed: unfinished tasks are published FAILED, the tracker emits {@code run-complete}
     * (registering the run first if it failed before {@code initRun}), and the {@code job_run} is
     * closed unless a normal completion already did. An {@link Exception} is logged once at ERROR
     * and swallowed, returning no results; an {@link Error} gets the same best-effort completion
     * and is rethrown. If no job run exists yet (none was pre-created and creating one is what
     * failed) there is nothing to complete, and the exception propagates.
     *
     * @param command           the command to execute
     * @param preCreatedJobRun  a pre-created job run entity, or null to create one internally
     * @return all saved evaluation entities produced by the run
     */
    public List<ForecastEvaluationEntity> execute(ForecastCommand command,
            JobRunEntity preCreatedJobRun) {
        AtomicReference<JobRunEntity> runRef = new AtomicReference<>(preCreatedJobRun);
        try {
            return executePipeline(command, runRef);
        } catch (Exception e) {
            if (runRef.get() == null) {
                throw e;
            }
            abortRun(runRef.get(), e);
            return List.of();
        } catch (Error e) {
            if (runRef.get() != null) {
                abortRun(runRef.get(), e);
            }
            throw e;
        }
    }

    /**
     * Logs a run that threw — once, at ERROR, with the stack trace — then completes it.
     */
    private void abortRun(JobRunEntity jobRun, Throwable cause) {
        LOG.error("Forecast run {} aborted: {}", jobRun.getId(), cause.toString(), cause);
        runCompletion.abort(jobRun, RunCompletion.reasonForForecastRun(cause));
    }

    private List<ForecastEvaluationEntity> executePipeline(ForecastCommand command,
            AtomicReference<JobRunEntity> runRef) {
        JobRunEntity preCreatedJobRun = runRef.get();
        RunType runType = command.runType();
        EvaluationModel evaluationModel = commandFactory.resolveEvaluationModel(command);
        // Null-safe like the `instanceof` this replaced: ForecastCommand.strategy is
        // documented nullable (ForecastCommandFactory.resolveStrategy returns null for the
        // non-evaluating run types), and the sibling check in the factory guards the same way.
        boolean isWildlife = command.strategy() != null
                && command.strategy().getEvaluationModel() == EvaluationModel.WILDLIFE;

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
        runRef.set(jobRun);

        List<LocationEntity> locations = resolveLocations(command, isWildlife);

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

        // Two questions, two calendars, one clock. "Which day is today" is a UK civil-calendar
        // question, because a target date names a solar event at a UK location — see
        // ForecastHorizon. "Has this moment passed" is an instant comparison, and the eventTime it
        // is measured against comes back from solarService as a UTC LocalDateTime, so `now` must
        // stay UTC; reading it in Europe/London would put the gate an hour into the future in
        // summer. Both are drawn from the injected clock so the two cannot drift apart mid-run.
        LocalDate today = ForecastHorizon.today(clock);
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));

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
                    if (excludedSlots.contains(slotKey)
                            || shouldSkipEvent(targetDate, targetType, location, today, now)) {
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

        // Pre-fetch all weather data in batch (2 API calls instead of 2N). A failed prefetch
        // degrades instead of aborting the run, as the cloud prefetch below and the batch
        // pipeline's resilient prefetch already do: an empty (non-null) map makes triage read the
        // cache with no network call, so every task fails through the ordinary weather FAILED path,
        // the run completes, and "Retry failed" has failed tasks to retry.
        Map<String, WeatherExtractionResult> prefetchedWeather;
        boolean weatherPrefetchFailed = false;
        try {
            prefetchedWeather = prefetchWeather(nonSkippedTasks, jobRun);
        } catch (Exception e) {
            LOG.warn("Weather prefetch failed — every task of this run will fail individually: {}",
                    e.toString());
            prefetchedWeather = Map.of();
            weatherPrefetchFailed = true;
            // The reason follows the exception, not an assumption: prefetchWeatherBatch also does
            // bookkeeping (api_call_log) inside the try that failed, so this need not be Open-Meteo.
            progressTracker.noteFailure(jobRun.getId(), RunCompletion.reasonForForecastRun(e));
        }

        // Pre-fetch all cloud sampling points in 1 batch call (~300 calls → 1). Pointless when the
        // weather prefetch just failed: every task fails at weather before it would read this.
        CloudPointCache cloudCache = weatherPrefetchFailed
                ? new CloudPointCache(Map.of())
                : prefetchCloudPoints(nonSkippedTasks, prefetchedWeather, jobRun);

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
            if (weatherPrefetchFailed) {
                // Not "all triaged": weather could not be fetched, so every live task failed. Close the
                // job_run with that failed count (the tracker's, as abort does); the general alignment
                // of job_run counts with the tracker is a separate change.
                RunProgress progress = progressTracker.getProgress(jobRun.getId());
                int weatherFailed = progress != null ? progress.getFailed() : nonSkippedTasks.size();
                runCompletion.complete(jobRun, succeeded, weatherFailed, dates);
                LOG.info("Forecast run ended — weather could not be fetched, {} task(s) failed",
                        weatherFailed);
            } else {
                runCompletion.complete(jobRun, succeeded, failed, dates);
                LOG.info("Forecast run early-stopped — all tasks triaged");
            }
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
            runCompletion.complete(jobRun, succeeded, failed, dates);
            LOG.info("Forecast run complete — runType={}, model={}, {} succeeded, {} failed",
                    runType, evaluationModel, succeeded, failed);
            return results;
        }

        // Stability gating: classify per grid cell, skip tasks beyond the stability window.
        // Bypass for manually triggered runs — the user explicitly requested these evaluations.
        Map<String, GridCellStabilityResult> stabilityByCell = Map.of();
        if (!triggeredManually) {
            StabilityFilterResult stabilityResult = applyStabilityFilter(fullEvalBatch);
            publishStabilitySkips(fullEvalBatch, stabilityResult.filteredTasks(), jobRun);
            fullEvalBatch = stabilityResult.filteredTasks();
            stabilityByCell = stabilityResult.stabilityByCell();
        } else {
            LOG.info("Stability filter bypassed — manual run");
        }

        if (fullEvalBatch.isEmpty()) {
            progressTracker.setPhase(jobRun.getId(), RunPhase.COMPLETE);
            runCompletion.complete(jobRun, succeeded, failed, dates);
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
        runCompletion.complete(jobRun, succeeded, failed, dates);
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
        // Same injected clock as the pipeline's gate, so "single reference clock for the run" is
        // true rather than nearly true. UTC because this instant is differenced against a UTC
        // eventTime to work out how far cloud advects.
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
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
                // Must resolve the steering wind exactly as CloudPointCacheReader will, or the
                // prefetched coordinate and the looked-up one diverge and the upwind sample
                // silently vanishes. See OpenMeteoResponseParser.resolveEventWind.
                OpenMeteoResponseParser.EventWind wind =
                        OpenMeteoResponseParser.resolveEventWind(h, eventTime, targetType);
                if (wind != null) {
                    double[] upwind = openMeteoService.computeUpwindPoint(
                            lat, lon, wind.windFromDeg(), wind.windSpeedMs(), now, eventTime);
                    if (upwind != null) {
                        allPoints.add(upwind);
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
     * Classifies forecast stability per grid cell and filters out tasks beyond the
     * Gate 4 horizon-depth table.
     *
     * <p>Classification and snapshot publishing are delegated to the shared
     * {@link GridCellStabilityService}, and the include/skip decision to
     * {@link NightlyEligibilityPolicy} — the same policy the nightly batch pipeline
     * applies — so the synchronous and batch engines cannot drift apart. Tasks without
     * a grid cell or forecast response fall back to TRANSITIONAL, matching the batch
     * path.
     *
     * @param batch tasks surviving triage and sentinel phases
     * @return filter result containing tasks and the stability classification map
     */
    private StabilityFilterResult applyStabilityFilter(List<ForecastPreEvalResult> batch) {
        Map<String, GridCellStabilityResult> stabilityByCell =
                gridCellStabilityService.classifyGridCellsAndPublishSnapshot(batch);

        int originalSize = batch.size();
        List<ForecastPreEvalResult> filtered = batch.stream()
                .filter(task -> {
                    ForecastStability stability = gridCellStabilityService.stabilityFor(
                            task.location(), task, stabilityByCell);
                    if (!NightlyEligibilityPolicy.INSTANCE.permitsHorizon(
                            task.daysAhead(), task.targetType(), stability)) {
                        LOG.debug("Stability filter: skipping {} T+{} — {}",
                                task.location().getName(), task.daysAhead(), stability);
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
     * Publishes SKIPPED for every task the stability filter dropped. Those tasks were left in a
     * fetching state with no further event, so without this a non-manual run could not complete
     * without sweeping them to FAILED.
     */
    private void publishStabilitySkips(List<ForecastPreEvalResult> before,
            List<ForecastPreEvalResult> kept, JobRunEntity jobRun) {
        Set<String> keptKeys = new HashSet<>();
        kept.forEach(t -> keptKeys.add(t.taskKey()));
        for (ForecastPreEvalResult task : before) {
            if (!keptKeys.contains(task.taskKey())) {
                eventPublisher.publishEvent(new LocationTaskEvent(
                        this, jobRun.getId(), task.taskKey(), task.location().getName(),
                        task.date().toString(), task.targetType().name(),
                        LocationTaskState.SKIPPED, STABILITY_SKIP_REASON, null));
            }
        }
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

    /**
     * Runs the legacy wildlife comfort engine: two un-batched Open-Meteo calls per location per
     * date, inserting rows without replacing any.
     *
     * <p>No trigger reaches this any more — {@code RunType.WEATHER}'s only schedule was removed on
     * 2026-02-27 — and {@code WildlifeComfortRefreshJob} has superseded it with a batched,
     * replace-in-place refresh of the same {@code HOURLY} rows.
     */
    private List<ForecastEvaluationEntity> executeWildlife(List<LocationEntity> locations,
            List<LocalDate> dates, JobRunEntity jobRun) {
        int succeeded = 0;
        int failed = 0;

        List<String[]> taskKeys = new ArrayList<>();
        List<LocationEntity> taskLocations = new ArrayList<>();
        List<LocalDate> taskDates = new ArrayList<>();

        for (LocationEntity location : locations) {
            for (LocalDate targetDate : dates) {
                String taskKey = location.getName() + "|" + targetDate + "|HOURLY";
                taskKeys.add(new String[]{taskKey, location.getName(),
                        targetDate.toString(), "HOURLY"});
                taskLocations.add(location);
                taskDates.add(targetDate);
            }
        }

        // Register BEFORE submitting: the hourly forecast publishes no task events of its own, so this
        // loop reports each task's outcome, and the tracker must already hold the tasks it reports on.
        progressTracker.initRun(jobRun.getId(), taskKeys);

        List<CompletableFuture<List<ForecastEvaluationEntity>>> futures = new ArrayList<>();
        for (int i = 0; i < taskKeys.size(); i++) {
            LocationEntity location = taskLocations.get(i);
            LocalDate targetDate = taskDates.get(i);
            futures.add(CompletableFuture.supplyAsync(
                    () -> runForecast(location, targetDate, null,
                            EvaluationModel.WILDLIFE, jobRun),
                    forecastExecutor));
        }

        List<ForecastEvaluationEntity> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            boolean ok;
            try {
                List<ForecastEvaluationEntity> taskResults = futures.get(i).join();
                ok = taskResults != null && !taskResults.isEmpty();
                if (ok) {
                    results.addAll(taskResults);
                    succeeded += taskResults.size();
                } else {
                    failed++;
                }
            } catch (Exception e) {
                LOG.error("Wildlife future join failed: {}", e.getMessage(), e);
                ok = false;
                failed++;
            }
            String[] key = taskKeys.get(i);
            eventPublisher.publishEvent(new LocationTaskEvent(
                    this, jobRun.getId(), key[0], key[1], key[2], key[3],
                    ok ? LocationTaskState.COMPLETE : LocationTaskState.FAILED,
                    ok ? null : WILDLIFE_FAILED_REASON, ok ? null : "HOURLY"));
        }

        runCompletion.complete(jobRun, succeeded, failed, dates);
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
     * Resolves the locations a run evaluates — the one place this engine decides which places
     * are sky subjects.
     *
     * <p>A colour (sky) run keeps only locations for which {@link LocationEntity#hasColourTypes()}
     * holds, whether the command named its locations or left them to be defaulted to every enabled
     * one. The filter used to apply to the defaulted list alone, so {@code POST /api/forecast/run}
     * (which always hands over an explicit list) sent a wildlife hide, a canopy wood or an
     * out-of-season bluebell wood to the SKY prompt. WOODLAND and BLUEBELL places are not lost by
     * this: this engine has never had a woodland or bluebell lane (those exist only in the
     * scheduled batch pipeline), so they only ever reached the sky prompt by mistake here.
     *
     * <p>The wildlife branch is unchanged: an explicit list is used as given, the default is
     * {@link #isPureWildlife}.
     *
     * @param command    the command being executed
     * @param isWildlife whether the command's strategy is the wildlife one
     * @return the locations to run, never null
     */
    private List<LocationEntity> resolveLocations(ForecastCommand command, boolean isWildlife) {
        if (isWildlife) {
            return command.locations() != null
                    ? command.locations()
                    : locationService.findAllEnabled().stream().filter(this::isPureWildlife).toList();
        }
        List<LocationEntity> candidates = command.locations() != null
                ? command.locations()
                : locationService.findAllEnabled();
        List<LocationEntity> skyLocations = candidates.stream()
                .filter(LocationEntity::hasColourTypes)
                .toList();
        int excluded = candidates.size() - skyLocations.size();
        if (excluded > 0) {
            LOG.info("Sky run excluded {} location(s) that are not sky subjects "
                    + "(wildlife, woodland or bluebell only)", excluded);
        }
        return skyLocations;
    }

    /**
     * Returns {@code true} if the location is exclusively a WILDLIFE location
     * (i.e. has WILDLIFE type and no colour photography types).
     *
     * @param loc the location to check
     * @return {@code true} if only wildlife comfort rows should be generated
     */
    boolean isPureWildlife(LocationEntity loc) {
        return loc.getLocationType().contains(LocationType.WILDLIFE) && !loc.hasColourTypes();
    }

    /**
     * Whether a slot's solar event has already happened, and so should never be evaluated.
     *
     * <p>Only same-day slots can qualify, and {@code today} is therefore a <em>UK civil</em> date —
     * a target date names a UK solar event. {@code now} and {@code eventTime} are both UTC and are
     * compared as instants; that comparison is not a calendar question and must not become one.
     *
     * @param targetDate the slot's target date
     * @param targetType SUNRISE or SUNSET
     * @param location   the location whose solar times decide the event instant
     * @param today      today on the UK civil calendar
     * @param now        the current instant, in UTC
     * @return {@code true} if the slot is today and its event is already past
     */
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
