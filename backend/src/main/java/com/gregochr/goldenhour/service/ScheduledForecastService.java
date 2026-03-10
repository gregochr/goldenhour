package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RunType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Triggers automatic forecast runs and tide data refreshes on configured cron schedules.
 *
 * <p>This is now a thin scheduling wrapper — all orchestration logic lives in
 * {@link ForecastCommandExecutor}.
 */
@Service
public class ScheduledForecastService {

    /** Maximum number of days ahead to forecast on each scheduled run. */
    public static final int FORECAST_HORIZON_DAYS = ForecastCommandFactory.FORECAST_HORIZON_DAYS;

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledForecastService.class);

    private final ForecastCommandFactory commandFactory;
    private final ForecastCommandExecutor commandExecutor;
    private final TideService tideService;
    private final LocationService locationService;
    private final JobRunService jobRunService;
    private final ExchangeRateService exchangeRateService;

    /**
     * Constructs a {@code ScheduledForecastService}.
     *
     * @param commandFactory       builds forecast commands
     * @param commandExecutor      executes forecast commands
     * @param tideService          the service that fetches and stores tide extremes
     * @param locationService      the service providing persisted locations
     * @param jobRunService        the service for tracking job run metrics
     * @param exchangeRateService  the service for fetching exchange rates
     */
    public ScheduledForecastService(ForecastCommandFactory commandFactory,
            ForecastCommandExecutor commandExecutor,
            TideService tideService, LocationService locationService,
            JobRunService jobRunService, ExchangeRateService exchangeRateService) {
        this.commandFactory = commandFactory;
        this.commandExecutor = commandExecutor;
        this.tideService = tideService;
        this.locationService = locationService;
        this.jobRunService = jobRunService;
        this.exchangeRateService = exchangeRateService;
    }

    /**
     * Warms the exchange rate cache for today, called before forecast runs.
     *
     * <p>If the Frankfurter API is unavailable, falls back to the most recent cached rate.
     */
    // @Scheduled(cron = "0 55 5 * * *")
    public void refreshExchangeRate() {
        try {
            double rate = exchangeRateService.getCurrentRate();
            LOG.info("Exchange rate warmed: {} GBP/USD", rate);
        } catch (Exception e) {
            LOG.warn("Exchange rate refresh failed — will use fallback: {}", e.getMessage());
        }
    }

    /**
     * Runs near-term forecasts (T, T+1, T+2) twice daily at 6 AM and 6 PM UTC.
     *
     * @return all saved evaluation entities produced by the run
     */
    // @Scheduled(cron = "${forecast.schedule.haiku.cron:0 0 6,18 * * *}")
    public List<ForecastEvaluationEntity> runNearTermForecasts() {
        ForecastCommand cmd = commandFactory.create(RunType.SHORT_TERM, false);
        return commandExecutor.execute(cmd);
    }

    /**
     * Runs distant forecasts (T+3 through T+5) once daily at 6 AM UTC.
     *
     * @return all saved evaluation entities produced by the run
     */
    // @Scheduled(cron = "${forecast.schedule.haiku.distant.cron:0 0 1 * * *}")
    public List<ForecastEvaluationEntity> runDistantForecasts() {
        ForecastCommand cmd = commandFactory.create(RunType.LONG_TERM, false);
        return commandExecutor.execute(cmd);
    }

    /**
     * Runs comfort-only (no Claude) weather forecasts for pure WILDLIFE locations every 12 h.
     */
    // @Scheduled(cron = "${forecast.schedule.weather.cron:0 0 6,18 * * *}")
    public void runWeatherForecasts() {
        ForecastCommand cmd = commandFactory.create(RunType.WEATHER, false);
        commandExecutor.execute(cmd);
    }

    /**
     * Refreshes 14 days of tide extremes from WorldTides for all coastal locations.
     *
     * <p>Runs once a week (default: Monday at 02:00 UTC).
     */
    // @Scheduled(cron = "${tide.schedule.cron:0 0 2 * * MON}")
    public void refreshTideExtremes() {
        JobRunEntity jobRun = jobRunService.startRun(RunType.TIDE, false, null);
        List<LocationEntity> coastal = locationService.findAllEnabled().stream()
                .filter(loc -> loc.getLocationType().contains(LocationType.SEASCAPE))
                .filter(locationService::isCoastal)
                .toList();
        LOG.info("Weekly tide refresh started — {} SEASCAPE coastal location(s)", coastal.size());
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
     * Backfills 12 months of historical tide data for all enabled SEASCAPE locations.
     *
     * <p>Fetches in 7-day chunks, skipping any chunk where data already exists to avoid
     * duplicate WorldTides API charges. Runs asynchronously from the admin UI.
     */
    public void backfillTideExtremes() {
        JobRunEntity jobRun = jobRunService.startRun(RunType.TIDE, true, null);
        List<LocationEntity> seascapeCoastal = locationService.findAllEnabled().stream()
                .filter(loc -> loc.getLocationType().contains(LocationType.SEASCAPE))
                .filter(locationService::isCoastal)
                .toList();
        LOG.info("Tide backfill started — {} SEASCAPE location(s)", seascapeCoastal.size());
        int succeeded = 0;
        int failed = 0;

        for (LocationEntity location : seascapeCoastal) {
            try {
                int chunks = tideService.backfillTideExtremes(location, jobRun);
                LOG.info("Backfilled {} chunks for {}", chunks, location.getName());
                succeeded++;
            } catch (Exception e) {
                LOG.error("Tide backfill failed for {}: {}",
                        location.getName(), e.getMessage(), e);
                failed++;
            }
        }

        jobRunService.completeRun(jobRun, succeeded, failed);
        LOG.info("Tide backfill complete — {} succeeded, {} failed", succeeded, failed);
    }
}
