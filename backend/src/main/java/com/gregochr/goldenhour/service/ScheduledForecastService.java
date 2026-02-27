package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobName;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Triggers automatic forecast runs and tide data refreshes on configured cron schedules.
 *
 * <p>Two separate scheduled methods run evaluations on different cadences:
 * <ul>
 *   <li>{@link #runSonnetForecasts()} — every 6 h (0, 6, 12, 18 UTC); produces dual 0–100 score rows</li>
 *   <li>{@link #runHaikuForecasts()} — every 12 h (6, 18 UTC); produces 1–5 rating rows</li>
 * </ul>
 *
 * <p>For each configured location, forecasts are run for today through
 * {@link #FORECAST_HORIZON_DAYS} days ahead.
 *
 * <p>A separate weekly job refreshes 14 days of tide extremes from WorldTides for all
 * coastal locations so that {@link ForecastService} can classify tide state from the DB
 * without making a live API call on every evaluation.
 */
@Service
public class ScheduledForecastService {

    /** Maximum number of days ahead to forecast on each scheduled run. */
    public static final int FORECAST_HORIZON_DAYS = 7;

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledForecastService.class);

    private final ForecastService forecastService;
    private final LocationService locationService;
    private final TideService tideService;
    private final JobRunService jobRunService;
    private final ModelSelectionService modelSelectionService;
    private final SolarService solarService;
    private final Executor forecastExecutor;

    /**
     * Constructs a {@code ScheduledForecastService}.
     *
     * @param forecastService       the service that runs individual location forecasts
     * @param locationService       the service providing persisted locations
     * @param tideService           the service that fetches and stores tide extremes
     * @param jobRunService         the service for tracking job run metrics
     * @param modelSelectionService the service that provides the active evaluation model
     * @param solarService          the service that calculates solar event times
     * @param forecastExecutor      the executor used to run forecast calls in parallel
     */
    public ScheduledForecastService(ForecastService forecastService,
            LocationService locationService, TideService tideService,
            JobRunService jobRunService, ModelSelectionService modelSelectionService,
            SolarService solarService, Executor forecastExecutor) {
        this.forecastService = forecastService;
        this.locationService = locationService;
        this.tideService = tideService;
        this.jobRunService = jobRunService;
        this.modelSelectionService = modelSelectionService;
        this.solarService = solarService;
        this.forecastExecutor = forecastExecutor;
    }

    /**
     * Runs Sonnet (dual 0–100 score) forecasts for all configured locations every 6 h.
     *
     * <p>Cron defaults to 0, 6, 12, 18 UTC. Override via {@code forecast.schedule.sonnet.cron}.
     * DISABLED for cost optimization — only Haiku runs on schedule.
     */
    // @Scheduled(cron = "${forecast.schedule.sonnet.cron:0 0 0,6,12,18 * * *}")
    public void runSonnetForecasts() {
        runAll(EvaluationModel.SONNET);
    }

    /**
     * Runs near-term forecasts (T, T+1, T+2) twice daily at 6 AM and 6 PM UTC.
     * Near-term forecasts are more valuable as weather updates change predictions.
     *
     * <p>Cron defaults to 6, 18 UTC. Override via {@code forecast.schedule.haiku.cron}.
     *
     * @return all saved evaluation entities produced by the run
     */
    @Scheduled(cron = "${forecast.schedule.haiku.cron:0 0 6,18 * * *}")
    public List<ForecastEvaluationEntity> runNearTermForecasts() {
        return runNearTermForecasts(false);
    }

    /**
     * Runs near-term forecasts, optionally in dry-run mode (no API calls).
     *
     * @param dryRun if {@code true}, skip actual API calls and only log what would be evaluated
     * @return all saved evaluation entities produced by the run (empty list when dryRun is true)
     */
    public List<ForecastEvaluationEntity> runNearTermForecasts(boolean dryRun) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> nearTermDates = List.of(
                today,
                today.plusDays(1),
                today.plusDays(2)
        );
        EvaluationModel activeModel = modelSelectionService.getActiveModel();
        return runForecasts(activeModel, null, nearTermDates, dryRun);
    }

    /**
     * Runs distant forecasts (T+3 through T+7) once daily at 6 AM UTC.
     * Distant forecasts are less sensitive to frequent updates; once daily is sufficient.
     *
     * <p>Cron defaults to 6 AM UTC only. Override via {@code forecast.schedule.haiku.distant.cron}.
     *
     * @return all saved evaluation entities produced by the run
     */
    @Scheduled(cron = "${forecast.schedule.haiku.distant.cron:0 0 6 * * *}")
    public List<ForecastEvaluationEntity> runDistantForecasts() {
        return runDistantForecasts(false);
    }

    /**
     * Runs distant forecasts, optionally in dry-run mode (no API calls).
     *
     * @param dryRun if {@code true}, skip actual API calls and only log what would be evaluated
     * @return all saved evaluation entities produced by the run (empty list when dryRun is true)
     */
    public List<ForecastEvaluationEntity> runDistantForecasts(boolean dryRun) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> distantDates = today.plusDays(3)
                .datesUntil(today.plusDays(FORECAST_HORIZON_DAYS + 1))
                .toList();
        EvaluationModel activeModel = modelSelectionService.getActiveModel();
        return runForecasts(activeModel, null, distantDates, dryRun);
    }

    /**
     * Runs comfort-only (no Claude) forecasts for pure WILDLIFE locations every 12 h.
     *
     * <p>Cron defaults to 6, 18 UTC. Override via {@code forecast.schedule.wildlife.cron}.
     */
    @Scheduled(cron = "${forecast.schedule.wildlife.cron:0 0 6,18 * * *}")
    public void runWildlifeForecasts() {
        runAll(EvaluationModel.WILDLIFE);
    }

    /**
     * Refreshes 14 days of tide extremes from WorldTides for all coastal locations.
     *
     * <p>Runs once a week (default: Monday at 02:00 UTC). Configurable via
     * {@code tide.schedule.cron}. Exceptions per location are caught and logged.
     * Metrics are recorded in the job_run table.
     */
    @Scheduled(cron = "${tide.schedule.cron:0 0 2 * * MON}")
    public void refreshTideExtremes() {
        JobRunEntity jobRun = jobRunService.startRun(JobName.TIDE);
        List<LocationEntity> coastal = locationService.findAll().stream()
                .filter(locationService::isCoastal)
                .toList();
        LOG.info("Weekly tide refresh started — {} coastal location(s)", coastal.size());
        int succeeded = 0;
        int failed = 0;

        for (LocationEntity location : coastal) {
            try {
                tideService.fetchAndStoreTideExtremes(location, jobRun);
                succeeded++;
            } catch (Exception e) {
                LOG.error("Tide refresh failed for {}: {}", location.getName(), e.getMessage(), e);
                failed++;
            }
        }

        jobRunService.completeRun(jobRun, succeeded, failed);
        LOG.info("Weekly tide refresh complete — {} succeeded, {} failed",
                succeeded, failed);
    }

    /**
     * Returns {@code true} if the location has at least one colour photography type
     * (LANDSCAPE or SEASCAPE), or has no types at all (treated as colour).
     *
     * @param loc the location to check
     * @return {@code true} if colour forecasts should be generated for this location
     */
    boolean hasColourTypes(LocationEntity loc) {
        return loc.getLocationType().contains(LocationType.LANDSCAPE)
                || loc.getLocationType().contains(LocationType.SEASCAPE)
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
     * Runs forecasts for locations relevant to the given model and all dates using that model.
     *
     * <p>WILDLIFE model runs only on pure-WILDLIFE locations. HAIKU and SONNET models run
     * on colour-type locations (LANDSCAPE, SEASCAPE, untyped, or mixed).
     *
     * <p>Metrics are recorded in the job_run and api_call_log tables.
     *
     * @param model the evaluation model to use
     */
    /**
     * Runs forecasts for a given model, optionally filtered by locations and dates.
     *
     * <p>Used by both scheduled jobs and manual on-demand runs to ensure identical logic.
     *
     * @param model the evaluation model to use (HAIKU, SONNET, or WILDLIFE)
     * @param locations optional list of locations; if null, uses all configured locations
     * @param dates optional list of dates; if null, uses today through T+7
     * @return list of all forecast evaluation entities produced
     */
    public List<ForecastEvaluationEntity> runForecasts(
            EvaluationModel model,
            List<LocationEntity> locations,
            List<LocalDate> dates) {
        return runForecasts(model, locations, dates, false);
    }

    /**
     * Runs forecasts for a given model, optionally filtered by locations and dates.
     *
     * @param model   the evaluation model to use (HAIKU, SONNET, or WILDLIFE)
     * @param locations optional list of locations; if null, uses all configured locations
     * @param dates   optional list of dates; if null, uses today through T+7
     * @param dryRun  if {@code true}, skip actual API calls and only log what would be evaluated
     * @return list of all forecast evaluation entities produced
     */
    public List<ForecastEvaluationEntity> runForecasts(
            EvaluationModel model,
            List<LocationEntity> locations,
            List<LocalDate> dates,
            boolean dryRun) {
        // Determine job name and location filter
        JobName jobName = switch (model) {
            case SONNET -> JobName.SONNET;
            case HAIKU -> JobName.HAIKU;
            case WILDLIFE -> JobName.WILDLIFE;
        };
        JobRunEntity jobRun = jobRunService.startRun(jobName);

        // Use provided dates or default to today through T+7
        List<LocalDate> forecastDates = dates != null && !dates.isEmpty()
                ? dates
                : buildDefaultDates();

        // Use provided locations or default to all configured locations
        List<LocationEntity> forecastLocations = locations != null
                ? locations
                : locationService.findAll().stream()
                .filter(loc -> model == EvaluationModel.WILDLIFE
                        ? isPureWildlife(loc)
                        : hasColourTypes(loc))
                .toList();

        LOG.info("Forecast run started — model={}, {} location(s), {} date(s), dryRun={}",
                model, forecastLocations.size(), forecastDates.size(), dryRun);
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        java.time.LocalDateTime now = java.time.LocalDateTime.now(ZoneOffset.UTC);

        // Build the list of tasks to run (one per location/date/targetType combination).
        // Dry-run tasks log synchronously; live tasks are submitted to the executor in parallel.
        List<CompletableFuture<List<ForecastEvaluationEntity>>> futures = new ArrayList<>();

        for (LocationEntity location : forecastLocations) {
            for (LocalDate targetDate : forecastDates) {
                if (model == EvaluationModel.WILDLIFE) {
                    if (dryRun) {
                        LOG.info("[DRY RUN] Would evaluate {} WILDLIFE on {}", location.getName(), targetDate);
                        succeeded.incrementAndGet();
                    } else {
                        futures.add(CompletableFuture.supplyAsync(
                                () -> runForecast(location, targetDate, null, model, jobRun),
                                forecastExecutor));
                    }
                } else {
                    if (locationService.shouldEvaluateSunrise(location)
                            && !shouldSkipEvent(targetDate, TargetType.SUNRISE, location, today, now)) {
                        if (dryRun) {
                            LOG.info("[DRY RUN] Would evaluate {} SUNRISE on {}", location.getName(), targetDate);
                            succeeded.incrementAndGet();
                        } else {
                            futures.add(CompletableFuture.supplyAsync(
                                    () -> runForecast(location, targetDate, TargetType.SUNRISE, model, jobRun),
                                    forecastExecutor));
                        }
                    }
                    if (locationService.shouldEvaluateSunset(location)
                            && !shouldSkipEvent(targetDate, TargetType.SUNSET, location, today, now)) {
                        if (dryRun) {
                            LOG.info("[DRY RUN] Would evaluate {} SUNSET on {}", location.getName(), targetDate);
                            succeeded.incrementAndGet();
                        } else {
                            futures.add(CompletableFuture.supplyAsync(
                                    () -> runForecast(location, targetDate, TargetType.SUNSET, model, jobRun),
                                    forecastExecutor));
                        }
                    }
                }
            }
        }

        // Collect results — join() blocks until each future completes.
        List<ForecastEvaluationEntity> results = new ArrayList<>();
        for (CompletableFuture<List<ForecastEvaluationEntity>> future : futures) {
            List<ForecastEvaluationEntity> taskResults = future.join();
            if (taskResults != null && !taskResults.isEmpty()) {
                results.addAll(taskResults);
                succeeded.addAndGet(taskResults.size());
            } else {
                failed.incrementAndGet();
            }
        }

        jobRunService.completeRun(jobRun, succeeded.get(), failed.get());
        LOG.info("Forecast run complete — model={}, {} succeeded, {} failed",
                model, succeeded.get(), failed.get());

        return results;
    }

    private List<LocalDate> buildDefaultDates() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return java.util.stream.IntStream.rangeClosed(0, FORECAST_HORIZON_DAYS)
                .mapToObj(today::plusDays)
                .toList();
    }

    private void runAll(EvaluationModel model) {
        // Filter locations based on model type
        List<LocationEntity> locations = locationService.findAll().stream()
                .filter(loc -> model == EvaluationModel.WILDLIFE
                        ? isPureWildlife(loc)
                        : hasColourTypes(loc))
                .toList();
        // Delegate to public method with null dates (uses default T+0 to T+7)
        runForecasts(model, locations, null);
    }

    /**
     * Checks if a sunrise or sunset event has already passed for today, and should be skipped.
     *
     * <p>Returns {@code true} if targetDate is today and current time is after the event time,
     * indicating the forecast would not change if re-run. Returns {@code false} for future dates.
     *
     * @param targetDate the calendar date to check
     * @param targetType SUNRISE or SUNSET
     * @param location   the location to check event time for
     * @param today      today's date in UTC
     * @param now        current time in UTC
     * @return {@code true} if the event has already passed and should be skipped
     */
    private boolean shouldSkipEvent(LocalDate targetDate, TargetType targetType,
            LocationEntity location, LocalDate today, java.time.LocalDateTime now) {
        if (!targetDate.equals(today)) {
            return false;
        }
        java.time.LocalDateTime eventTime = targetType == TargetType.SUNRISE
                ? solarService.sunriseUtc(location.getLat(), location.getLon(), targetDate)
                : solarService.sunsetUtc(location.getLat(), location.getLon(), targetDate);
        return now.isAfter(eventTime);
    }

    /**
     * Runs a single forecast for a location, date, target type, and model, logging any failure.
     *
     * @param location   the location to forecast
     * @param targetDate the calendar date to forecast
     * @param targetType {@link TargetType#SUNRISE}, {@link TargetType#SUNSET}, or {@code null} for WILDLIFE
     * @param model      the evaluation model to use
     * @param jobRun     the parent job run for metrics tracking
     * @return the saved {@link ForecastEvaluationEntity} list, or {@code null} if the forecast failed
     */
    private List<ForecastEvaluationEntity> runForecast(LocationEntity location, LocalDate targetDate,
            TargetType targetType, EvaluationModel model, JobRunEntity jobRun) {
        try {
            return forecastService.runForecasts(
                    location.getName(), location.getLat(), location.getLon(),
                    location.getId(), targetDate, targetType, location.getTideType(), model, jobRun);
        } catch (Exception e) {
            LOG.error("Forecast failed for {} {} on {} [{}]: {}",
                    location.getName(), targetType, targetDate, model, e.getMessage(), e);
            return null;
        }
    }
}
