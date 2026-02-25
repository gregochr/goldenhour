package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
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

    /**
     * Constructs a {@code ScheduledForecastService}.
     *
     * @param forecastService the service that runs individual location forecasts
     * @param locationService the service providing persisted locations
     * @param tideService     the service that fetches and stores tide extremes
     */
    public ScheduledForecastService(ForecastService forecastService,
            LocationService locationService, TideService tideService) {
        this.forecastService = forecastService;
        this.locationService = locationService;
        this.tideService = tideService;
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
     * Runs Haiku (1–5 rating) forecasts for all configured locations every 12 h.
     *
     * <p>Cron defaults to 6, 18 UTC. Override via {@code forecast.schedule.haiku.cron}.
     */
    @Scheduled(cron = "${forecast.schedule.haiku.cron:0 0 6,18 * * *}")
    public void runHaikuForecasts() {
        runAll(EvaluationModel.HAIKU);
    }

    /**
     * Refreshes 14 days of tide extremes from WorldTides for all coastal locations.
     *
     * <p>Runs once a week (default: Monday at 02:00 UTC). Configurable via
     * {@code tide.schedule.cron}. Exceptions per location are caught and logged.
     */
    @Scheduled(cron = "${tide.schedule.cron:0 0 2 * * MON}")
    public void refreshTideExtremes() {
        List<LocationEntity> coastal = locationService.findAll().stream()
                .filter(locationService::isCoastal)
                .toList();
        LOG.info("Weekly tide refresh started — {} coastal location(s)", coastal.size());
        long startMs = System.currentTimeMillis();
        int succeeded = 0;
        int failed = 0;

        for (LocationEntity location : coastal) {
            try {
                tideService.fetchAndStoreTideExtremes(location);
                succeeded++;
            } catch (Exception e) {
                LOG.error("Tide refresh failed for {}: {}", location.getName(), e.getMessage(), e);
                failed++;
            }
        }

        LOG.info("Weekly tide refresh complete — {} succeeded, {} failed, took {}ms",
                succeeded, failed, System.currentTimeMillis() - startMs);
    }

    /**
     * Runs forecasts for all locations and dates using the given model.
     *
     * @param model the evaluation model to use
     */
    private void runAll(EvaluationModel model) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocationEntity> locations = locationService.findAll();
        LOG.info("Scheduled {} forecast run started — {} location(s), T+0 to T+{}",
                model, locations.size(), FORECAST_HORIZON_DAYS);
        long startMs = System.currentTimeMillis();
        int succeeded = 0;
        int failed = 0;

        for (LocationEntity location : locations) {
            for (int daysAhead = 0; daysAhead <= FORECAST_HORIZON_DAYS; daysAhead++) {
                LocalDate targetDate = today.plusDays(daysAhead);
                if (locationService.shouldEvaluateSunrise(location)) {
                    if (runForecast(location, targetDate, TargetType.SUNRISE, model)) {
                        succeeded++;
                    } else {
                        failed++;
                    }
                }
                if (locationService.shouldEvaluateSunset(location)) {
                    if (runForecast(location, targetDate, TargetType.SUNSET, model)) {
                        succeeded++;
                    } else {
                        failed++;
                    }
                }
            }
        }

        LOG.info("Scheduled {} forecast run complete — {} succeeded, {} failed, took {}ms",
                model, succeeded, failed, System.currentTimeMillis() - startMs);
    }

    /**
     * Runs a single forecast for a location, date, target type, and model, logging any failure.
     *
     * @param location   the location to forecast
     * @param targetDate the calendar date to forecast
     * @param targetType {@link TargetType#SUNRISE} or {@link TargetType#SUNSET}
     * @param model      the evaluation model to use
     * @return {@code true} if the forecast succeeded, {@code false} if it failed
     */
    private boolean runForecast(LocationEntity location, LocalDate targetDate,
            TargetType targetType, EvaluationModel model) {
        try {
            forecastService.runForecasts(
                    location.getName(), location.getLat(), location.getLon(),
                    location.getId(), targetDate, targetType, location.getTideType(), model);
            return true;
        } catch (Exception e) {
            LOG.error("Forecast failed for {} {} on {} [{}]: {}",
                    location.getName(), targetType, targetDate, model, e.getMessage(), e);
            return false;
        }
    }
}
