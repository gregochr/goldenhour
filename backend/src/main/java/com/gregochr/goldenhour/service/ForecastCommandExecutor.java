package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.RunPhase;
import com.gregochr.goldenhour.service.evaluation.NoOpEvaluationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

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
     */
    public ForecastCommandExecutor(ForecastService forecastService,
            LocationService locationService, JobRunService jobRunService,
            SolarService solarService, ForecastCommandFactory commandFactory,
            Executor forecastExecutor, OptimisationSkipEvaluator optimisationSkipEvaluator,
            OptimisationStrategyService optimisationStrategyService,
            RunProgressTracker progressTracker, ApplicationEventPublisher eventPublisher,
            SentinelSelector sentinelSelector) {
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

        List<LocalDate> dates = command.dates();

        LOG.info("Forecast run started — runType={}, model={}, {} location(s), {} date(s), strategies=[{}]",
                runType, evaluationModel, locations.size(), dates.size(),
                strategiesAudit != null ? strategiesAudit : "none");

        // Wildlife runs bypass the three-phase pipeline
        if (isWildlife) {
            return executeWildlife(locations, dates, jobRun);
        }

        return executeThreePhasePipeline(locations, dates, enabledStrategies,
                evaluationModel, runType, jobRun);
    }

    // -------------------------------------------------------------------------
    // Three-phase pipeline
    // -------------------------------------------------------------------------

    private List<ForecastEvaluationEntity> executeThreePhasePipeline(
            List<LocationEntity> locations, List<LocalDate> dates,
            List<OptimisationStrategyEntity> enabledStrategies,
            EvaluationModel evaluationModel, RunType runType, JobRunEntity jobRun) {

        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

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

                    if (shouldSkipEvent(targetDate, targetType, location, today, now)
                            || optimisationSkipEvaluator.shouldSkip(
                                    enabledStrategies, location, targetDate, targetType)) {
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

        // Phase 1: TRIAGE
        progressTracker.setPhase(jobRun.getId(), RunPhase.TRIAGE);
        List<ForecastPreEvalResult> triageResults = runTriagePhase(nonSkippedTasks,
                tideAlignmentEnabled, jobRun);
        List<ForecastPreEvalResult> survivors = triageResults.stream()
                .filter(r -> !r.triaged())
                .toList();
        long triagedCount = triageResults.size() - survivors.size();
        LOG.info("Triage phase complete — {} triaged, {} survivors", triagedCount, survivors.size());

        if (survivors.isEmpty()) {
            // Everything was triaged — early stop
            progressTracker.setPhase(jobRun.getId(), RunPhase.EARLY_STOP);
            jobRunService.completeRun(jobRun, succeeded.get(), failed.get(), dates);
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
            fullEvalBatch = runSentinelPhase(survivors, jobRun,
                    succeeded, failed, results, threshold);
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
            jobRunService.completeRun(jobRun, succeeded.get(), failed.get(), dates);
            progressTracker.completeRun(jobRun.getId());
            LOG.info("Forecast run complete — runType={}, model={}, {} succeeded, {} failed",
                    runType, evaluationModel, succeeded.get(), failed.get());
            return results;
        }

        // Phase 3: FULL_EVALUATION
        progressTracker.setPhase(jobRun.getId(), RunPhase.FULL_EVALUATION);
        runFullEvalPhase(fullEvalBatch, jobRun, succeeded, failed, results);

        progressTracker.setPhase(jobRun.getId(), RunPhase.COMPLETE);
        jobRunService.completeRun(jobRun, succeeded.get(), failed.get(), dates);
        progressTracker.completeRun(jobRun.getId());
        LOG.info("Forecast run complete — runType={}, model={}, {} succeeded, {} failed",
                runType, evaluationModel, succeeded.get(), failed.get());

        return results;
    }

    /**
     * Phase 1: Fetch weather data and apply triage heuristics to all non-skipped tasks in parallel.
     *
     * @param tasks                all non-skipped task descriptors
     * @param tideAlignmentEnabled {@code true} if the TIDE_ALIGNMENT optimisation strategy is active
     * @param jobRun               the parent job run for metrics
     */
    private List<ForecastPreEvalResult> runTriagePhase(List<TaskDescriptor> tasks,
            boolean tideAlignmentEnabled, JobRunEntity jobRun) {
        List<CompletableFuture<ForecastPreEvalResult>> futures = new ArrayList<>();

        for (TaskDescriptor task : tasks) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return forecastService.fetchWeatherAndTriage(
                            task.location, task.date, task.targetType,
                            task.location.getTideType(), task.model, tideAlignmentEnabled, jobRun);
                } catch (Exception e) {
                    LOG.error("Triage failed for {} {} on {} [{}]: {}",
                            task.location.getName(), task.targetType, task.date,
                            task.model, e.getMessage(), e);
                    return null;
                }
            }, forecastExecutor));
        }

        List<ForecastPreEvalResult> results = new ArrayList<>();
        for (CompletableFuture<ForecastPreEvalResult> future : futures) {
            try {
                ForecastPreEvalResult result = future.join();
                if (result != null) {
                    results.add(result);
                }
            } catch (Exception e) {
                LOG.error("Triage future join failed: {}", e.getMessage(), e);
            }
        }
        return results;
    }

    /**
     * Phase 2: Group survivors by region, evaluate sentinels, skip regions where all sentinels ≤2.
     */
    private List<ForecastPreEvalResult> runSentinelPhase(List<ForecastPreEvalResult> survivors,
            JobRunEntity jobRun, AtomicInteger succeeded, AtomicInteger failed,
            List<ForecastEvaluationEntity> results, int ratingThreshold) {

        // Group by region (null region key = "no-region")
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

        List<ForecastPreEvalResult> fullEvalBatch = new ArrayList<>();

        // Null-region tasks go directly to full evaluation
        fullEvalBatch.addAll(noRegion);

        // Per region: select sentinels, evaluate, decide
        for (Map.Entry<Long, List<ForecastPreEvalResult>> entry : byRegion.entrySet()) {
            List<ForecastPreEvalResult> regionTasks = entry.getValue();

            // Get unique locations in this region
            List<LocationEntity> regionLocations = regionTasks.stream()
                    .map(ForecastPreEvalResult::location)
                    .distinct()
                    .toList();

            List<LocationEntity> sentinelLocations = sentinelSelector.selectSentinels(regionLocations);

            // Partition tasks into sentinel vs remainder
            List<ForecastPreEvalResult> sentinelTasks = new ArrayList<>();
            List<ForecastPreEvalResult> remainderTasks = new ArrayList<>();
            for (ForecastPreEvalResult task : regionTasks) {
                if (sentinelLocations.contains(task.location())) {
                    sentinelTasks.add(task);
                } else {
                    remainderTasks.add(task);
                }
            }

            // Evaluate sentinels
            boolean allSentinelsLow = true;
            for (ForecastPreEvalResult sentinel : sentinelTasks) {
                try {
                    ForecastEvaluationEntity entity = forecastService.evaluateAndPersist(
                            sentinel, jobRun);
                    results.add(entity);
                    succeeded.incrementAndGet();
                    if (entity.getRating() != null && entity.getRating() > ratingThreshold) {
                        allSentinelsLow = false;
                    }
                } catch (Exception e) {
                    LOG.error("Sentinel evaluation failed for {} {} on {}: {}",
                            sentinel.location().getName(), sentinel.targetType(),
                            sentinel.date(), e.getMessage(), e);
                    failed.incrementAndGet();
                    allSentinelsLow = false; // Don't skip region on error
                }
            }

            if (allSentinelsLow && !sentinelTasks.isEmpty()) {
                // Skip remainder — persist canned entities
                String reason = "Region sentinel sampling — all sentinels rated "
                        + ratingThreshold + " or below";
                for (ForecastPreEvalResult task : remainderTasks) {
                    try {
                        ForecastEvaluationEntity entity = forecastService.persistCannedResult(
                                task, reason, jobRun);
                        results.add(entity);
                    } catch (Exception e) {
                        LOG.error("Failed to persist canned result for {} {} on {}: {}",
                                task.location().getName(), task.targetType(),
                                task.date(), e.getMessage(), e);
                    }
                }
                LOG.info("Region {} sentinel early-stop — {} tasks skipped",
                        entry.getKey(), remainderTasks.size());
            } else {
                // Sentinels passed — remainder goes to full evaluation
                fullEvalBatch.addAll(remainderTasks);
            }
        }

        return fullEvalBatch;
    }

    /**
     * Phase 3: Full Claude evaluation of all remaining tasks.
     */
    private void runFullEvalPhase(List<ForecastPreEvalResult> tasks,
            JobRunEntity jobRun, AtomicInteger succeeded, AtomicInteger failed,
            List<ForecastEvaluationEntity> results) {
        List<CompletableFuture<ForecastEvaluationEntity>> futures = new ArrayList<>();

        for (ForecastPreEvalResult task : tasks) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return forecastService.evaluateAndPersist(task, jobRun);
                } catch (Exception e) {
                    LOG.error("Full evaluation failed for {} {} on {}: {}",
                            task.location().getName(), task.targetType(),
                            task.date(), e.getMessage(), e);
                    return null;
                }
            }, forecastExecutor));
        }

        for (CompletableFuture<ForecastEvaluationEntity> future : futures) {
            try {
                ForecastEvaluationEntity entity = future.join();
                if (entity != null) {
                    results.add(entity);
                    succeeded.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                }
            } catch (Exception e) {
                LOG.error("Full evaluation future join failed: {}", e.getMessage(), e);
                failed.incrementAndGet();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Wildlife (bypass pipeline)
    // -------------------------------------------------------------------------

    private List<ForecastEvaluationEntity> executeWildlife(List<LocationEntity> locations,
            List<LocalDate> dates, JobRunEntity jobRun) {
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

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
                    succeeded.addAndGet(taskResults.size());
                } else {
                    failed.incrementAndGet();
                }
            } catch (Exception e) {
                LOG.error("Wildlife future join failed: {}", e.getMessage(), e);
                failed.incrementAndGet();
            }
        }

        jobRunService.completeRun(jobRun, succeeded.get(), failed.get(), dates);
        progressTracker.completeRun(jobRun.getId());
        return results;
    }

    // -------------------------------------------------------------------------
    // Utility methods
    // -------------------------------------------------------------------------

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
     * Descriptor for a non-skipped task awaiting triage.
     */
    private record TaskDescriptor(LocationEntity location, LocalDate date,
            TargetType targetType, EvaluationModel model) {
    }
}
