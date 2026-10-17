package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HourlyComfort;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.util.ForecastHorizon;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Refreshes the hourly comfort forecast (temperature, feels-like, wind, rain between sunrise and
 * sunset) for every WILDLIFE-only hide, twice a day.
 *
 * <p><b>Why a dedicated job.</b> The comfort table once came from {@code RunType.WEATHER}, whose
 * only schedule was commented out by accident on 2026-02-27 and never replaced; from then on
 * production held no {@code HOURLY} rows and every hide's table read "No hourly forecast
 * available". That engine is the wrong thing to re-trigger: it makes two un-batched Open-Meteo
 * calls per location per date (forecast plus air quality) for a table that needs neither the air
 * quality nor most of the forecast. This job makes <em>one</em> batched forecast request for all
 * hides and no air-quality request at all, with no Claude call.
 *
 * <p><b>Which places.</b> Enabled locations for which {@link LocationEntity#isWildlifeOnly()} is
 * true — deliberately narrower than {@code ForecastCommandExecutor.isPureWildlife}; see that
 * method's javadoc. Waterfalls are not included: the backend has never written waterfall hourly
 * rows.
 *
 * <p><b>Which dates.</b> The six UK civil dates today..today+5 ({@code RunType.WEATHER}'s own
 * date range), the forward window {@code GET /api/forecast} serves.
 *
 * <p><b>Which rows.</b> Per place and date, one {@code HOURLY} {@code forecast_evaluation} row for
 * each full UTC hour from {@code floor(sunrise)} to {@code floor(sunset)} inclusive, carrying the
 * six comfort fields the read side maps ({@code ForecastDtoMapper.toListDto}). Every other weather
 * column — cloud, visibility, humidity, weather code, boundary-layer height, shortwave radiation,
 * dew point and the aerosol fields the old path filled — is left null: nothing reads them for an
 * {@code HOURLY} row, and the batched request does not fetch air quality.
 *
 * <p><b>Replace, not append.</b> {@code forecast_evaluation} is insert-only everywhere else; this
 * is its one exception. A run replaces the previous {@code HOURLY} rows for each place and date
 * (see {@link WildlifeComfortWriter}), so twice-daily runs keep storage flat rather than adding
 * ~230 rows a run. A place-date whose fetch or extraction yielded nothing keeps its previous rows —
 * a stale comfort table is better than none.
 *
 * <p><b>Job-run counts, in one unit.</b> {@code succeeded} and {@code failed} count
 * <em>location-dates</em> (one hide on one date) written and not written. {@code
 * locations_processed} is the number of qualifying hides the run considered — a separate, coarser
 * figure, never a sum of the other two. A hide missing from the batch response, or a whole-batch
 * failure, fails all of that hide's (or every hide's) dates.
 */
@Service
public class WildlifeComfortRefreshJob {

    private static final Logger LOG = LoggerFactory.getLogger(WildlifeComfortRefreshJob.class);

    /** Scheduler key; matches the {@code scheduler_job_config} row seeded by V161. */
    static final String JOB_KEY = "wildlife_comfort_refresh";

    private final LocationService locationService;
    private final OpenMeteoClient openMeteoClient;
    private final SolarService solarService;
    private final WildlifeComfortWriter writer;
    private final JobRunService jobRunService;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final Clock clock;

    /** Set while a run is in progress; a second fire is skipped rather than queued. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates the job.
     *
     * @param locationService         the source of enabled locations
     * @param openMeteoClient         the batched Open-Meteo forecast client
     * @param solarService            sunrise and sunset, to bound each day's hours
     * @param writer                  the transactional replace step
     * @param jobRunService           job-run and API-call bookkeeping
     * @param dynamicSchedulerService the DB-backed scheduler this job registers with
     * @param clock                   clock resolving "today" on the UK civil calendar
     */
    public WildlifeComfortRefreshJob(LocationService locationService, OpenMeteoClient openMeteoClient,
            SolarService solarService, WildlifeComfortWriter writer, JobRunService jobRunService,
            DynamicSchedulerService dynamicSchedulerService, Clock clock) {
        this.locationService = locationService;
        this.openMeteoClient = openMeteoClient;
        this.solarService = solarService;
        this.writer = writer;
        this.jobRunService = jobRunService;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.clock = clock;
    }

    /**
     * Registers the job with the dynamic scheduler. Manual-aware, so the Scheduler screen's
     * "Run now" is recorded on the job run as a manual trigger.
     */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerManualAwareJobTarget(JOB_KEY, this::run);
    }

    /**
     * Runs one refresh unless one is already running.
     *
     * @param manual true when fired by an administrator rather than by the schedule
     */
    public void run(boolean manual) {
        if (!running.compareAndSet(false, true)) {
            LOG.info("Wildlife comfort refresh already running — skipping concurrent trigger");
            return;
        }
        try {
            doRun(manual);
        } finally {
            running.set(false);
        }
    }

    private void doRun(boolean manual) {
        LocalDate today = ForecastHorizon.today(clock);
        List<LocalDate> dates = RunType.WEATHER.defaultDateRange(today);
        List<LocationEntity> hides = locationService.findAllEnabled().stream()
                .filter(LocationEntity::isWildlifeOnly)
                .toList();

        JobRunEntity jobRun = jobRunService.startRun(RunType.WEATHER, manual, EvaluationModel.WILDLIFE);
        jobRun.setLocationsProcessed(hides.size());
        if (hides.isEmpty()) {
            LOG.info("Wildlife comfort refresh: no WILDLIFE-only locations — nothing to fetch");
            jobRunService.completeRun(jobRun, 0, 0, dates);
            return;
        }

        List<double[]> coords = new ArrayList<>();
        for (LocationEntity hide : hides) {
            coords.add(new double[]{hide.getLat(), hide.getLon()});
        }

        List<OpenMeteoForecastResponse> responses;
        long startMs = System.currentTimeMillis();
        try {
            responses = openMeteoClient.fetchForecastBriefingBatch(coords);
            jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST, "GET",
                    "wildlife-comfort-forecast-batch(" + coords.size() + ")", null,
                    System.currentTimeMillis() - startMs, 200, null, true, null);
        } catch (Exception e) {
            LOG.warn("Wildlife comfort refresh: forecast batch failed — previous rows kept: {}",
                    e.getMessage());
            jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST, "GET",
                    "wildlife-comfort-forecast-batch(" + coords.size() + ")", null,
                    System.currentTimeMillis() - startMs, null, null, false, e.getMessage());
            jobRunService.completeRun(jobRun, 0, hides.size() * dates.size(), dates);
            return;
        }

        LocalDateTime runAt = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        int succeeded = 0;
        int failed = 0;
        for (int i = 0; i < hides.size(); i++) {
            LocationEntity hide = hides.get(i);
            OpenMeteoForecastResponse forecast = i < responses.size() ? responses.get(i) : null;
            if (forecast == null) {
                LOG.warn("Wildlife comfort refresh: no forecast returned for '{}' — previous rows kept",
                        hide.getName());
                failed += dates.size();
                continue;
            }
            for (LocalDate date : dates) {
                if (refreshDate(hide, date, forecast, runAt)) {
                    succeeded++;
                } else {
                    failed++;
                }
            }
        }

        jobRunService.completeRun(jobRun, succeeded, failed, dates);
        LOG.info("Wildlife comfort refresh complete — {} hide(s), {} location-date(s) written, {} failed",
                hides.size(), succeeded, failed);
    }

    /**
     * Rebuilds and replaces one hide's rows for one date. Never throws: one place-date's failure
     * must not stop the others.
     *
     * @return true if rows were written, false if the previous rows were left in place
     */
    private boolean refreshDate(LocationEntity hide, LocalDate date, OpenMeteoForecastResponse forecast,
            LocalDateTime runAt) {
        try {
            LocalDateTime from = solarService.sunriseUtc(hide.getLat(), hide.getLon(), date)
                    .truncatedTo(ChronoUnit.HOURS);
            LocalDateTime to = solarService.sunsetUtc(hide.getLat(), hide.getLon(), date)
                    .truncatedTo(ChronoUnit.HOURS);
            List<HourlyComfort> readings = OpenMeteoResponseParser.extractComfortHours(forecast, from, to);
            int expected = (int) ChronoUnit.HOURS.between(from, to) + 1;
            if (readings.isEmpty()) {
                LOG.warn("Wildlife comfort refresh: no usable hours for '{}' on {} — previous rows kept",
                        hide.getName(), date);
                return false;
            }
            if (readings.size() < expected) {
                LOG.warn("Wildlife comfort refresh: '{}' on {} — {} of {} hour(s) had no temperature "
                        + "and were skipped", hide.getName(), date, expected - readings.size(), expected);
            }
            int daysAhead = ForecastHorizon.daysAhead(date, clock);
            List<ForecastEvaluationEntity> rows = new ArrayList<>();
            for (HourlyComfort reading : readings) {
                rows.add(buildRow(hide, date, daysAhead, runAt, reading));
            }
            return writer.replaceHourlyRows(hide.getId(), date, rows) > 0;
        } catch (Exception e) {
            LOG.warn("Wildlife comfort refresh failed for '{}' on {} — previous rows kept: {}",
                    hide.getName(), date, e.getMessage());
            return false;
        }
    }

    /**
     * Builds one unsaved {@code HOURLY} row. The common columns are stamped exactly as
     * {@code ForecastService.buildEntity} stamps them (UTC {@code forecastRunAt}, the
     * horizon-derived confidence); the comfort columns come from {@code reading}; everything else
     * stays null.
     */
    static ForecastEvaluationEntity buildRow(LocationEntity hide, LocalDate date, int daysAhead,
            LocalDateTime runAt, HourlyComfort reading) {
        return ForecastEvaluationEntity.builder()
                .locationLat(BigDecimal.valueOf(hide.getLat()))
                .locationLon(BigDecimal.valueOf(hide.getLon()))
                .location(hide)
                .targetDate(date)
                .targetType(TargetType.HOURLY)
                .forecastRunAt(runAt)
                .daysAhead(daysAhead)
                .confidence(ConfidenceDeriver.fromHorizon(daysAhead).name())
                .temperatureCelsius(reading.temperatureCelsius())
                .apparentTemperatureCelsius(reading.apparentTemperatureCelsius())
                .precipitationProbabilityPercent(reading.precipitationProbability())
                .windSpeed(reading.windSpeedMs())
                .windDirection(reading.windDirectionDegrees())
                .precipitation(reading.precipitationMm())
                .evaluationModel(EvaluationModel.WILDLIFE)
                .solarEventTime(reading.hour())
                .build();
    }
}
