package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Triggers automatic forecast runs on the configured cron schedule.
 *
 * <p>For each configured location, forecasts are run for today through
 * {@link #FORECAST_HORIZON_DAYS} days ahead. This allows accuracy tracking as the
 * forecast horizon narrows from T+{@value #FORECAST_HORIZON_DAYS} to T+0.
 */
@Service
public class ScheduledForecastService {

    /** Maximum number of days ahead to forecast on each scheduled run. */
    public static final int FORECAST_HORIZON_DAYS = 7;

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledForecastService.class);

    private final ForecastService forecastService;
    private final LocationService locationService;

    /**
     * Constructs a {@code ScheduledForecastService}.
     *
     * @param forecastService the service that runs individual location forecasts
     * @param locationService the service providing persisted locations
     */
    public ScheduledForecastService(ForecastService forecastService,
            LocationService locationService) {
        this.forecastService = forecastService;
        this.locationService = locationService;
    }

    /**
     * Runs forecasts for all configured locations across the forecast horizon.
     *
     * <p>Exceptions from individual location runs are caught and logged so that a failure
     * for one location does not prevent the remaining locations from being processed.
     */
    @Scheduled(cron = "${forecast.schedule.cron:0 0 6,18 * * *}")
    public void runScheduledForecasts() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (LocationEntity location : locationService.findAll()) {
            for (int daysAhead = 0; daysAhead <= FORECAST_HORIZON_DAYS; daysAhead++) {
                LocalDate targetDate = today.plusDays(daysAhead);
                if (locationService.shouldEvaluateSunrise(location)) {
                    runForecast(location, targetDate, TargetType.SUNRISE);
                }
                if (locationService.shouldEvaluateSunset(location)) {
                    runForecast(location, targetDate, TargetType.SUNSET);
                }
            }
        }
    }

    /**
     * Runs a single forecast for a location, date, and target type, logging any failure.
     *
     * @param location   the location to forecast
     * @param targetDate the calendar date to forecast
     * @param targetType {@link TargetType#SUNRISE} or {@link TargetType#SUNSET}
     */
    private void runForecast(LocationEntity location, LocalDate targetDate, TargetType targetType) {
        try {
            forecastService.runForecasts(
                    location.getName(), location.getLat(), location.getLon(),
                    targetDate, targetType);
        } catch (Exception e) {
            LOG.error("Forecast failed for {} {} on {}: {}",
                    location.getName(), targetType, targetDate, e.getMessage(), e);
        }
    }
}
