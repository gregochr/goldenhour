package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.service.evaluation.NoOpEvaluationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes {@link ForecastCommand} instances — the core forecast run loop.
 *
 * <p>Handles location filtering, configurable skip logic (via {@link OptimisationSkipEvaluator}),
 * parallel execution, metrics tracking, and error isolation per location/date combination.
 */
@Service
public class ForecastCommandExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastCommandExecutor.class);

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
     */
    public ForecastCommandExecutor(ForecastService forecastService,
            LocationService locationService, JobRunService jobRunService,
            SolarService solarService, ForecastCommandFactory commandFactory,
            Executor forecastExecutor, OptimisationSkipEvaluator optimisationSkipEvaluator,
            OptimisationStrategyService optimisationStrategyService,
            RunProgressTracker progressTracker, ApplicationEventPublisher eventPublisher) {
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

        // Resolve locations — use command-provided or filter from all configured
        List<LocationEntity> locations = command.locations() != null
                ? command.locations()
                : locationService.findAllEnabled().stream()
                .filter(loc -> isWildlife ? isPureWildlife(loc) : hasColourTypes(loc))
                .toList();

        List<LocalDate> dates = command.dates();

        LOG.info("Forecast run started — runType={}, model={}, {} location(s), {} date(s), strategies=[{}]",
                runType, evaluationModel, locations.size(), dates.size(),
                strategiesAudit != null ? strategiesAudit : "none");

        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Build the list of tasks — one per location/date/targetType combination.
        // Also collect task metadata for progress tracking.
        List<CompletableFuture<List<ForecastEvaluationEntity>>> futures = new ArrayList<>();
        List<String[]> taskKeys = new ArrayList<>();

        for (LocationEntity location : locations) {
            for (LocalDate targetDate : dates) {
                if (isWildlife) {
                    String taskKey = location.getName() + "|" + targetDate + "|HOURLY";
                    taskKeys.add(new String[]{taskKey, location.getName(),
                            targetDate.toString(), "HOURLY"});
                    futures.add(CompletableFuture.supplyAsync(
                            () -> runForecast(location, targetDate, null,
                                    EvaluationModel.WILDLIFE, jobRun),
                            forecastExecutor));
                } else {
                    List<TargetType> applicableTypes = new ArrayList<>();
                    if (locationService.shouldEvaluateSunrise(location)) {
                        applicableTypes.add(TargetType.SUNRISE);
                    }
                    if (locationService.shouldEvaluateSunset(location)) {
                        applicableTypes.add(TargetType.SUNSET);
                    }

                    for (TargetType targetType : applicableTypes) {
                        String taskKey = location.getName() + "|" + targetDate + "|" + targetType;
                        if (shouldSkipEvent(targetDate, targetType, location, today, now)
                                || optimisationSkipEvaluator.shouldSkip(
                                        enabledStrategies, location,
                                        targetDate, targetType)) {
                            // Publish SKIPPED event
                            taskKeys.add(new String[]{taskKey, location.getName(),
                                    targetDate.toString(), targetType.name()});
                            eventPublisher.publishEvent(new LocationTaskEvent(
                                    this, jobRun.getId(), taskKey, location.getName(),
                                    targetDate.toString(), targetType.name(),
                                    LocationTaskState.SKIPPED, null, null));
                        } else {
                            taskKeys.add(new String[]{taskKey, location.getName(),
                                    targetDate.toString(), targetType.name()});
                            futures.add(CompletableFuture.supplyAsync(
                                    () -> runForecast(location, targetDate, targetType,
                                            evaluationModel, jobRun),
                                    forecastExecutor));
                        }
                    }
                }
            }
        }

        // Initialise progress tracking
        progressTracker.initRun(jobRun.getId(), taskKeys);

        // Collect results — join() blocks until each future completes.
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
                LOG.error("Future join failed for {} [{}]: {}",
                        runType, evaluationModel, e.getMessage(), e);
                failed.incrementAndGet();
            }
        }

        jobRunService.completeRun(jobRun, succeeded.get(), failed.get(), dates);
        progressTracker.completeRun(jobRun.getId());
        LOG.info("Forecast run complete — runType={}, model={}, {} succeeded, {} failed",
                runType, evaluationModel, succeeded.get(), failed.get());

        return results;
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

    /**
     * Checks if a sunrise or sunset event has already passed for today, and should be skipped.
     *
     * @param targetDate the calendar date to check
     * @param targetType SUNRISE or SUNSET
     * @param location   the location to check event time for
     * @param today      today's date in UTC
     * @param now        current time in UTC
     * @return {@code true} if the event has already passed and should be skipped
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

    /**
     * Runs a single forecast for a location, date, target type, and model, logging any failure.
     *
     * @param location   the location to forecast
     * @param targetDate the calendar date to forecast
     * @param targetType SUNRISE, SUNSET, or null for WILDLIFE
     * @param model      the evaluation model to use
     * @param jobRun     the parent job run for metrics tracking
     * @return the saved entity list, or null if the forecast failed
     */
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
}
