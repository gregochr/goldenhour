package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
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
import java.util.List;

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

    /**
     * Constructs a {@code ScheduledForecastService}.
     *
     * @param forecastService the service that runs individual location forecasts
     * @param locationService the service providing persisted locations
     * @param tideService     the service that fetches and stores tide extremes
     * @param jobRunService   the service for tracking job run metrics
     */
    public ScheduledForecastService(ForecastService forecastService,
            LocationService locationService, TideService tideService,
            JobRunService jobRunService) {
        this.forecastService = forecastService;
        this.locationService = locationService;
        this.tideService = tideService;
        this.jobRunService = jobRunService;
    }

    /**
     * Runs Sonnet (dual 0–100 score) forecasts for all configured locations every 6 h.
     *
     * <p>Cron defaults to 0, 6, 12, 18 UTC. Override via {@code forecast.schedule.sonnet.cron}.
     */
    @Scheduled(cron = "${forecast.schedule.sonnet.cron:0 0 0,6,12,18 * * *}")
    public void runSonnetForecasts() {
        runAll(EvaluationModel.SONNET);
    }

    /**
     * Runs Haiku (1–5 rating) forecasts for all colour-type locations every 12 h.
     *
     * <p>Cron defaults to 6, 18 UTC. Override via {@code forecast.schedule.haiku.cron}.
     */
    @Scheduled(cron = "${forecast.schedule.haiku.cron:0 0 6,18 * * *}")
    public void runHaikuForecasts() {
        runAll(EvaluationModel.HAIKU);
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
    private void runAll(EvaluationModel model) {
        JobName jobName = switch (model) {
            case SONNET -> JobName.SONNET;
            case HAIKU -> JobName.HAIKU;
            case WILDLIFE -> JobName.WILDLIFE;
        };
        JobRunEntity jobRun = jobRunService.startRun(jobName);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocationEntity> locations = locationService.findAll().stream()
                .filter(loc -> model == EvaluationModel.WILDLIFE
                        ? isPureWildlife(loc)
                        : hasColourTypes(loc))
                .toList();
        LOG.info("Scheduled {} forecast run started — {} location(s), T+0 to T+{}",
                model, locations.size(), FORECAST_HORIZON_DAYS);
        int succeeded = 0;
        int failed = 0;

        for (LocationEntity location : locations) {
            for (int daysAhead = 0; daysAhead <= FORECAST_HORIZON_DAYS; daysAhead++) {
                LocalDate targetDate = today.plusDays(daysAhead);
                if (model == EvaluationModel.WILDLIFE) {
                    // Single call per day: runForecasts handles the hourly loop internally
                    if (runForecast(location, targetDate, null, model, jobRun)) {
                        succeeded++;
                    } else {
                        failed++;
                    }
                } else {
                    if (locationService.shouldEvaluateSunrise(location)) {
                        if (runForecast(location, targetDate, TargetType.SUNRISE, model, jobRun)) {
                            succeeded++;
                        } else {
                            failed++;
                        }
                    }
                    if (locationService.shouldEvaluateSunset(location)) {
                        if (runForecast(location, targetDate, TargetType.SUNSET, model, jobRun)) {
                            succeeded++;
                        } else {
                            failed++;
                        }
                    }
                }
            }
        }

        jobRunService.completeRun(jobRun, succeeded, failed);
        LOG.info("Scheduled {} forecast run complete — {} succeeded, {} failed",
                model, succeeded, failed);
    }

    /**
     * Runs a single forecast for a location, date, target type, and model, logging any failure.
     *
     * @param location   the location to forecast
     * @param targetDate the calendar date to forecast
     * @param targetType {@link TargetType#SUNRISE} or {@link TargetType#SUNSET}
     * @param model      the evaluation model to use
     * @param jobRun     the parent job run for metrics tracking
     * @return {@code true} if the forecast succeeded, {@code false} if it failed
     */
    private boolean runForecast(LocationEntity location, LocalDate targetDate,
            TargetType targetType, EvaluationModel model, JobRunEntity jobRun) {
        try {
            forecastService.runForecasts(
                    location.getName(), location.getLat(), location.getLon(),
                    location.getId(), targetDate, targetType, location.getTideType(), model, jobRun);
            return true;
        } catch (Exception e) {
            LOG.error("Forecast failed for {} {} on {} [{}]: {}",
                    location.getName(), targetType, targetDate, model, e.getMessage(), e);
            return false;
        }
    }
}
