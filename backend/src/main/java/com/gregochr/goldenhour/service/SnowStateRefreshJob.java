package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.SnowStateEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.util.ForecastHorizon;
import com.gregochr.goldenhour.util.PointSnowpack;
import com.gregochr.goldenhour.util.SnowSummits;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

/**
 * Records the point snowpack at every enabled place and every reviewed summit, three times a day
 * (snow-topics-truth-plan §3.2, phase S2). The snow topics read these rows ({@code SnowStateReader}).
 *
 * <p><b>Why its own fetch.</b> The pack needs seven days of history, which the main forecast fetch must never
 * carry ({@code past_days} would shift the air-quality and stability arrays).
 * {@link OpenMeteoClient#fetchSnowStateBatch} is a separate, best-effort, chunked call with its own budget and
 * cool-off discipline. No Claude call.
 *
 * <p><b>Which rows.</b> Summits are fetched first (so a run cut short loses places, not backdrops), then the
 * enabled places. For each point, the UK civil dates today to today+{@value #DAYS_AHEAD}, SUNRISE and SUNSET, the
 * slot being the UTC hour containing the event ({@code floor}, as the wildlife comfort job does). Each slot
 * stores {@link PointSnowpack}'s water equivalent and 24 h snowfall, and the low cloud at that hour.
 *
 * <p><b>Every place at its own height, never the grid cell's.</b> A place's recorded {@code elevation_m} always
 * wins. One with none is run at its looked-up DEM height ({@link LocationEnrichmentService#lookupSurfaceHeights},
 * held in memory by location id and reused while its position is unchanged; nothing is written to
 * {@code locations}). With no height (lookup failed, or Open-Meteo cooling off) it is skipped this run, writes
 * nothing and counts as failed, so it is never sent as {@code nan}, the model's cell height.
 *
 * <p><b>Partial runs never erase.</b> A point that was not fetched, or whose lookback was not covered, writes
 * nothing for that slot, so its previous row stays; and the writer never lets a null overwrite a stored value.
 * "Unknown" is therefore a missing or stale row, never a zero.
 *
 * <p><b>Job-run counts.</b> {@code succeeded} and {@code failed} count points (a place or a summit) fetched and
 * not fetched; a place skipped for want of a height is in the total and so in {@code failed}.
 */
@Service
public class SnowStateRefreshJob {

    private static final Logger LOG = LoggerFactory.getLogger(SnowStateRefreshJob.class);

    /** Scheduler key; matches the {@code scheduler_job_config} row seeded by V231. */
    static final String JOB_KEY = "snow_state_refresh";

    /** The last day ahead written (today is day 0): days 0 to 4. */
    static final int DAYS_AHEAD = 4;

    /** The solar events recorded. */
    private static final List<TargetType> EVENTS = List.of(TargetType.SUNRISE, TargetType.SUNSET);

    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final LocationService locationService;
    private final OpenMeteoClient openMeteoClient;
    private final SolarService solarService;
    private final SnowStateWriter writer;
    private final JobRunService jobRunService;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final ServedBriefingEpoch servedEpoch;
    private final LocationEnrichmentService enrichmentService;
    private final Clock clock;

    /** Looked-up heights by location id; reused only while the place's position is unchanged. */
    private final Map<Long, Height> heightCache = new ConcurrentHashMap<>();

    /** Set while a run is in progress; a second fire is skipped rather than queued. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** One point to fetch: its key, position and the height in metres the model should run at. */
    private record Point(String key, double lat, double lon, double elevation) {
    }

    /** A looked-up surface height and the position it was looked up for. */
    private record Height(double lat, double lon, double metres) {
    }

    /** The points to fetch, and how many places were left out for want of a height. */
    private record Plan(List<Point> points, int skipped) {
        int total() {
            return points.size() + skipped;
        }
    }

    /**
     * Creates the job.
     *
     * @param locationService         the source of enabled locations
     * @param openMeteoClient         the client carrying the snow fetch
     * @param solarService            sunrise and sunset, to find each slot hour
     * @param writer                  the transactional merge and prune step
     * @param jobRunService           job-run and API-call bookkeeping
     * @param dynamicSchedulerService the DB-backed scheduler this job registers with
     * @param servedEpoch             bumped after a write, so served topics and the Ask snapshot refresh
     * @param enrichmentService       looks up the surface height of a place with no recorded one
     * @param clock                   clock resolving "today" on the UK civil calendar
     */
    public SnowStateRefreshJob(LocationService locationService, OpenMeteoClient openMeteoClient,
            SolarService solarService, SnowStateWriter writer, JobRunService jobRunService,
            DynamicSchedulerService dynamicSchedulerService, ServedBriefingEpoch servedEpoch,
            LocationEnrichmentService enrichmentService, Clock clock) {
        this.locationService = locationService;
        this.openMeteoClient = openMeteoClient;
        this.solarService = solarService;
        this.writer = writer;
        this.jobRunService = jobRunService;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.servedEpoch = servedEpoch;
        this.enrichmentService = enrichmentService;
        this.clock = clock;
    }

    /**
     * Registers the job with the dynamic scheduler. Manual-aware, so the Scheduler screen's "Run now" is recorded
     * on the job run as a manual trigger.
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
            LOG.info("Snow state refresh already running — skipping concurrent trigger");
            SchedulerFireOutcome.report(SchedulerFireStatus.SKIPPED, "Already running — skipped");
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
        List<LocalDate> dates = IntStream.rangeClosed(0, DAYS_AHEAD).mapToObj(today::plusDays).toList();

        JobRunEntity jobRun = jobRunService.startRun(RunType.SNOW_STATE, manual, null);
        Plan plan = plan(jobRun);
        List<Point> points = plan.points();
        int total = plan.total();
        jobRun.setLocationsProcessed(total);

        List<double[]> request = points.stream().map(p -> new double[]{p.lat(), p.lon(), p.elevation()}).toList();
        List<OpenMeteoForecastResponse> responses;
        long startMs = System.currentTimeMillis();
        try {
            responses = openMeteoClient.fetchSnowStateBatch(request);
        } catch (Exception e) {
            LOG.warn("Snow state refresh: fetch failed — previous rows kept: {}", e.getMessage());
            jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST, "GET",
                    "snow-state-batch(" + points.size() + ")", null,
                    System.currentTimeMillis() - startMs, null, null, false, e.getMessage());
            jobRunService.completeRun(jobRun, 0, total, dates);
            SchedulerFireOutcome.report(SchedulerFireStatus.FAILED, "Open-Meteo fetch failed: " + e.getMessage());
            return;
        }

        int fetched = (int) responses.stream().filter(r -> r != null).count();
        jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST, "GET",
                "snow-state-batch(" + points.size() + ")", null, System.currentTimeMillis() - startMs,
                fetched > 0 ? 200 : null, null, fetched > 0,
                fetched > 0 ? null : "no point fetched (cool-off, refusal or failure)");

        List<SnowStateWriter.Slot> slots = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            OpenMeteoForecastResponse response = i < responses.size() ? responses.get(i) : null;
            if (response != null) {
                slots.addAll(slotsFor(points.get(i), response, dates));
            }
        }

        int written;
        try {
            written = writer.write(slots, today, LocalDateTime.now(clock.withZone(ZoneOffset.UTC)));
        } catch (Exception e) {
            LOG.warn("Snow state refresh: write failed — previous rows kept: {}", e.getMessage());
            jobRunService.completeRun(jobRun, 0, total, dates);
            SchedulerFireOutcome.report(SchedulerFireStatus.FAILED, "Writing snow state failed: " + e.getMessage());
            return;
        }
        if (written > 0) {
            servedEpoch.bump();
        }

        int failed = total - fetched;
        jobRunService.completeRun(jobRun, fetched, failed, dates);
        LOG.info("Snow state refresh complete — {} of {} point(s) fetched, {} slot row(s) written",
                fetched, total, written);
        SchedulerFireOutcome.report(SchedulerFireOutcome.statusFor(failed, total),
                fetched + " of " + SchedulerFireOutcome.count(total, "point", "points") + " fetched · "
                        + SchedulerFireOutcome.count(written, "row", "rows") + " written");
    }

    /**
     * Summits first, then every enabled place at a height: its recorded one, else a cached or looked-up DEM
     * height. A place with none is skipped (never sent as the model's cell height) and counted.
     */
    private Plan plan(JobRunEntity jobRun) {
        List<Point> points = new ArrayList<>();
        for (SnowSummits.Summit summit : SnowSummits.ALL) {
            points.add(new Point(summit.pointKey(), summit.lat(), summit.lon(), summit.heightM()));
        }
        List<LocationEntity> locations = locationService.findAllEnabled();
        List<LocationEntity> unknown = new ArrayList<>();
        for (LocationEntity location : locations) {
            if (location.getElevationMetres() == null && cachedHeight(location) == null) {
                unknown.add(location);
            }
        }
        lookUp(unknown, jobRun);
        int skipped = 0;
        for (LocationEntity location : locations) {
            Double height = location.getElevationMetres() != null
                    ? Double.valueOf(location.getElevationMetres()) : cachedHeight(location);
            if (height == null) {
                skipped++;
                LOG.warn("Snow state refresh: no height for location {} — skipped this run", location.getId());
            } else {
                points.add(new Point(SnowStateEntity.locationKey(location.getId()), location.getLat(),
                        location.getLon(), height));
            }
        }
        return new Plan(points, skipped);
    }

    /** The place's cached looked-up height, or null when none is held or the place has moved since. */
    private Double cachedHeight(LocationEntity location) {
        Height cached = heightCache.get(location.getId());
        if (cached != null && cached.lat() == location.getLat() && cached.lon() == location.getLon()) {
            return cached.metres();
        }
        return null;
    }

    /**
     * Looks up the heights of places that have none, remembering each one found. Skipped while Open-Meteo is
     * cooling off. A failure only leaves its places without a height; it never starts or extends the cool-off.
     */
    private void lookUp(List<LocationEntity> places, JobRunEntity jobRun) {
        if (places.isEmpty()) {
            return;
        }
        if (openMeteoClient.isCoolingOff()) {
            LOG.warn("Snow state refresh: Open-Meteo cooling off — {} height lookup(s) skipped", places.size());
            return;
        }
        List<double[]> coordinates = places.stream().map(l -> new double[]{l.getLat(), l.getLon()}).toList();
        long startMs = System.currentTimeMillis();
        List<Double> heights = enrichmentService.lookupSurfaceHeights(coordinates);
        long durationMs = System.currentTimeMillis() - startMs;
        int chunk = LocationEnrichmentService.ELEVATION_CHUNK_SIZE;
        for (int from = 0; from < places.size(); from += chunk) {
            int to = Math.min(from + chunk, places.size());
            boolean ok = true;
            for (int i = from; i < to; i++) {
                Double metres = i < heights.size() ? heights.get(i) : null;
                if (metres == null) {
                    ok = false;
                } else {
                    LocationEntity place = places.get(i);
                    heightCache.put(place.getId(), new Height(place.getLat(), place.getLon(), metres));
                }
            }
            jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST, "GET",
                    "elevation-lookup(" + (to - from) + ")", null, durationMs, ok ? 200 : null, null, ok,
                    ok ? null : "elevation lookup failed for part of the chunk");
        }
    }

    /** The point's slots for the dates; never throws, so one malformed response loses only its own point. */
    private List<SnowStateWriter.Slot> slotsFor(Point point, OpenMeteoForecastResponse response,
            List<LocalDate> dates) {
        List<SnowStateWriter.Slot> slots = new ArrayList<>();
        try {
            OpenMeteoForecastResponse.Hourly hourly = response.getHourly();
            if (hourly == null || hourly.getTime() == null || !isHourly(hourly.getTime())) {
                LOG.warn("Snow state refresh: no usable hourly series for {} — previous rows kept", point.key());
                return slots;
            }
            List<String> times = hourly.getTime();
            List<PointSnowpack.Hour> series = new ArrayList<>(times.size());
            for (int i = 0; i < times.size(); i++) {
                series.add(new PointSnowpack.Hour(at(hourly.getPrecipitation(), i),
                        at(hourly.getTemperature2m(), i), toDouble(at(hourly.getRelativeHumidity2m(), i)),
                        at(hourly.getWindSpeed10m(), i), at(hourly.getShortwaveRadiation(), i)));
            }
            for (LocalDate date : dates) {
                for (TargetType event : EVENTS) {
                    LocalDateTime slotHour = (event == TargetType.SUNRISE
                            ? solarService.sunriseUtc(point.lat(), point.lon(), date)
                            : solarService.sunsetUtc(point.lat(), point.lon(), date))
                            .truncatedTo(ChronoUnit.HOURS);
                    int index = times.indexOf(slotHour.format(HOUR_FORMAT));
                    Optional<PointSnowpack.Result> pack = index < 0 ? Optional.empty()
                            : PointSnowpack.compute(series, index);
                    if (pack.isPresent()) {
                        slots.add(new SnowStateWriter.Slot(point.key(), date, event, pack.get().snowWaterMm(),
                                pack.get().snowfall24hCm(), at(hourly.getCloudCoverLow(), index)));
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Snow state refresh: {} could not be read, previous rows kept: {}", point.key(),
                    e.getMessage());
            return new ArrayList<>();
        }
        return slots;
    }

    /** True when the times are consecutive whole hours, which the lookback arithmetic assumes. */
    private static boolean isHourly(List<String> times) {
        if (times.isEmpty()) {
            return false;
        }
        LocalDateTime first = LocalDateTime.parse(times.get(0), HOUR_FORMAT);
        LocalDateTime last = LocalDateTime.parse(times.get(times.size() - 1), HOUR_FORMAT);
        return Duration.between(first, last).toHours() == times.size() - 1;
    }

    private static <T> T at(List<T> values, int index) {
        return values == null || index >= values.size() ? null : values.get(index);
    }

    private static Double toDouble(Integer value) {
        return value == null ? null : value.doubleValue();
    }
}
