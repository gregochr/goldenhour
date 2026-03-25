package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.model.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the daily briefing: fetches live Open-Meteo weather and existing DB tide data
 * for all enabled colour locations, rolls results up by region per solar event, and caches
 * the result for the frontend to serve instantly.
 *
 * <p>No Claude calls. No directional cloud sampling. No cloud approach analysis.
 * Cost: ~2 free Open-Meteo API calls per location per refresh.
 */
@Service
public class BriefingService {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingService.class);

    /** Low cloud percentage above which conditions are STANDDOWN. */
    static final int CLOUD_STANDDOWN = 80;

    /** Low cloud percentage above which conditions are MARGINAL. */
    static final int CLOUD_MARGINAL = 50;

    /** Precipitation in mm above which conditions are STANDDOWN. */
    static final BigDecimal PRECIP_STANDDOWN = new BigDecimal("2.0");

    /** Precipitation in mm above which conditions are MARGINAL. */
    static final BigDecimal PRECIP_MARGINAL = new BigDecimal("0.5");

    /** Visibility in metres below which conditions are STANDDOWN. */
    static final int VISIBILITY_STANDDOWN = 5000;

    /** Visibility in metres below which conditions are MARGINAL. */
    static final int VISIBILITY_MARGINAL = 10000;

    /** Humidity percentage above which conditions are MARGINAL (mist risk). */
    static final int HUMIDITY_MARGINAL = 90;

    /** Minutes within which a high tide is considered coincident with the solar event. */
    private static final long TIDE_WINDOW_MINUTES = 90;

    /** Scale for decimal weather values. */
    private static final int DECIMAL_SCALE = 2;

    private final LocationService locationService;
    private final SolarService solarService;
    private final OpenMeteoClient openMeteoClient;
    private final TideService tideService;
    private final JobRunService jobRunService;
    private final Executor forecastExecutor;
    private final AtomicReference<DailyBriefingResponse> cache = new AtomicReference<>();

    /**
     * Constructs a {@code BriefingService}.
     *
     * @param locationService  service for retrieving enabled locations
     * @param solarService     service for calculating sunrise/sunset times
     * @param openMeteoClient  resilient Open-Meteo API client
     * @param tideService      service for tide data lookups
     * @param jobRunService    service for job run tracking
     * @param forecastExecutor virtual thread executor for parallel weather fetches
     */
    public BriefingService(LocationService locationService, SolarService solarService,
            OpenMeteoClient openMeteoClient, TideService tideService,
            JobRunService jobRunService, Executor forecastExecutor) {
        this.locationService = locationService;
        this.solarService = solarService;
        this.openMeteoClient = openMeteoClient;
        this.tideService = tideService;
        this.jobRunService = jobRunService;
        this.forecastExecutor = forecastExecutor;
    }

    /**
     * Returns the cached daily briefing, or {@code null} if no briefing has been generated yet.
     *
     * @return the most recent briefing response, or null
     */
    public DailyBriefingResponse getCachedBriefing() {
        return cache.get();
    }

    /**
     * Refreshes the daily briefing by fetching live weather data for all enabled colour
     * locations across today and tomorrow, rolling up by region per solar event.
     *
     * <p>Logs a {@link RunType#BRIEFING} job run for metrics tracking.
     */
    public void refreshBriefing() {
        LOG.info("Daily briefing refresh started");
        JobRunEntity jobRun = jobRunService.startRun(RunType.BRIEFING, false, null);

        List<LocationEntity> colourLocations = locationService.findAllEnabled().stream()
                .filter(this::isColourLocation)
                .toList();

        if (colourLocations.isEmpty()) {
            LOG.info("No enabled colour locations — skipping briefing");
            jobRunService.completeRun(jobRun, 0, 0);
            return;
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate tomorrow = today.plusDays(1);
        List<LocalDate> dates = List.of(today, tomorrow);

        int succeeded = 0;
        int failed = 0;

        // Fetch weather for all locations in parallel
        List<LocationWeather> locationWeathers = fetchWeatherParallel(colourLocations, jobRun);

        // Build slots for each location × date × event type
        List<BriefingSlot> allSlots = new ArrayList<>();
        for (LocationWeather lw : locationWeathers) {
            if (lw.forecast() == null) {
                failed++;
                continue;
            }
            succeeded++;
            for (LocalDate date : dates) {
                for (TargetType eventType : List.of(TargetType.SUNRISE, TargetType.SUNSET)) {
                    BriefingSlot slot = buildSlot(lw, date, eventType);
                    if (slot != null) {
                        allSlots.add(slot);
                    }
                }
            }
        }

        // Group into days → event summaries → regions
        List<BriefingDay> days = buildDays(allSlots, colourLocations, dates);

        String headline = generateHeadline(days);
        DailyBriefingResponse response = new DailyBriefingResponse(
                LocalDateTime.now(ZoneOffset.UTC), headline, days);

        cache.set(response);
        jobRunService.completeRun(jobRun, succeeded, failed, dates);
        LOG.info("Daily briefing refresh complete — {} locations, {} succeeded, {} failed",
                colourLocations.size(), succeeded, failed);
    }

    /**
     * Determines whether a location is a colour photography location (not pure wildlife).
     *
     * @param location the location to check
     * @return true if the location has at least one colour type (LANDSCAPE, SEASCAPE, WATERFALL)
     */
    boolean isColourLocation(LocationEntity location) {
        if (location.getLocationType().isEmpty()) {
            return true;
        }
        return location.getLocationType().stream()
                .anyMatch(t -> t != LocationType.WILDLIFE);
    }

    /**
     * Fetches Open-Meteo forecast data for all locations in parallel using virtual threads.
     *
     * @param locations the locations to fetch weather for
     * @param jobRun    the job run for API call tracking
     * @return list of location-weather pairs (forecast may be null on failure)
     */
    private List<LocationWeather> fetchWeatherParallel(List<LocationEntity> locations,
            JobRunEntity jobRun) {
        List<CompletableFuture<LocationWeather>> futures = locations.stream()
                .map(loc -> CompletableFuture.supplyAsync(() -> {
                    try {
                        long startMs = System.currentTimeMillis();
                        OpenMeteoForecastResponse forecast = openMeteoClient.fetchForecast(
                                loc.getLat(), loc.getLon());
                        long durationMs = System.currentTimeMillis() - startMs;
                        jobRunService.logApiCall(jobRun.getId(),
                                com.gregochr.goldenhour.entity.ServiceName.OPEN_METEO_FORECAST,
                                "GET", "briefing-forecast/" + loc.getName(), null,
                                durationMs, 200, null, true, null);
                        return new LocationWeather(loc, forecast);
                    } catch (Exception e) {
                        LOG.warn("Briefing weather fetch failed for {}: {}",
                                loc.getName(), e.getMessage());
                        return new LocationWeather(loc, null);
                    }
                }, forecastExecutor))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    /**
     * Builds a briefing slot for a location at a specific date and event type.
     *
     * @param lw        the location and its fetched forecast data
     * @param date      the target date
     * @param eventType SUNRISE or SUNSET
     * @return the briefing slot, or null if the solar event time cannot be determined
     */
    BriefingSlot buildSlot(LocationWeather lw, LocalDate date, TargetType eventType) {
        LocationEntity loc = lw.location();
        OpenMeteoForecastResponse forecast = lw.forecast();

        LocalDateTime solarTime;
        try {
            solarTime = eventType == TargetType.SUNRISE
                    ? solarService.sunriseUtc(loc.getLat(), loc.getLon(), date)
                    : solarService.sunsetUtc(loc.getLat(), loc.getLon(), date);
        } catch (Exception e) {
            LOG.debug("Cannot compute {} for {} on {}: {}",
                    eventType, loc.getName(), date, e.getMessage());
            return null;
        }

        // Find nearest hourly slot
        List<String> times = forecast.getHourly().getTime();
        int idx = findBestIndex(times, solarTime, eventType);
        OpenMeteoForecastResponse.Hourly h = forecast.getHourly();

        int lowCloud = h.getCloudCoverLow().get(idx);
        BigDecimal precip = BigDecimal.valueOf(h.getPrecipitation().get(idx))
                .setScale(DECIMAL_SCALE, RoundingMode.HALF_UP);
        int visibility = h.getVisibility().get(idx).intValue();
        int humidity = h.getRelativeHumidity2m().get(idx);
        Double temp = h.getTemperature2m() != null && idx < h.getTemperature2m().size()
                ? h.getTemperature2m().get(idx) : null;
        BigDecimal windSpeed = BigDecimal.valueOf(h.getWindSpeed10m().get(idx))
                .setScale(DECIMAL_SCALE, RoundingMode.HALF_UP);

        // Tide data from DB
        String tideState = null;
        boolean tideAligned = false;
        LocalDateTime nearestHighTime = null;
        BigDecimal nearestHighHeight = null;
        boolean isKingTide = false;
        boolean isSpringTide = false;

        if (locationService.isCoastal(loc)) {
            Optional<TideData> tideOpt = tideService.deriveTideData(loc.getId(), solarTime);
            if (tideOpt.isPresent()) {
                TideData td = tideOpt.get();
                tideState = td.tideState().name();
                tideAligned = tideService.calculateTideAligned(td, loc.getTideType());
                nearestHighTime = td.nearestHighTideTime();
                nearestHighHeight = td.nextHighTideHeightMetres();

                // Check king/spring tide from stats
                if (td.tideState() == TideState.HIGH && nearestHighTime != null
                        && Math.abs(ChronoUnit.MINUTES.between(nearestHighTime, solarTime))
                                <= TIDE_WINDOW_MINUTES) {
                    Optional<TideStats> statsOpt = tideService.getTideStats(loc.getId());
                    if (statsOpt.isPresent()) {
                        TideStats stats = statsOpt.get();
                        BigDecimal height = td.nextHighTideHeightMetres();
                        if (height != null && stats.p95HighMetres() != null
                                && height.compareTo(stats.p95HighMetres()) > 0) {
                            isKingTide = true;
                        }
                        if (height != null && stats.springTideThreshold() != null
                                && height.compareTo(stats.springTideThreshold()) > 0) {
                            isSpringTide = true;
                        }
                    }
                }
            }
        }

        // Determine verdict
        Verdict verdict = determineVerdict(lowCloud, precip, visibility, humidity);

        // Build flags
        List<String> flags = buildFlags(lowCloud, precip, visibility, humidity,
                tideState, tideAligned, isKingTide, isSpringTide);

        return new BriefingSlot(
                loc.getName(), solarTime, verdict,
                lowCloud, precip, visibility, humidity, temp, windSpeed,
                tideState, tideAligned, nearestHighTime, nearestHighHeight,
                isKingTide, isSpringTide, flags);
    }

    /**
     * Determines the verdict for a slot based on weather conditions.
     *
     * @param lowCloud   low cloud cover percentage
     * @param precip     precipitation in mm
     * @param visibility visibility in metres
     * @param humidity   relative humidity percentage
     * @return the verdict
     */
    static Verdict determineVerdict(int lowCloud, BigDecimal precip, int visibility, int humidity) {
        if (lowCloud > CLOUD_STANDDOWN || precip.compareTo(PRECIP_STANDDOWN) > 0
                || visibility < VISIBILITY_STANDDOWN) {
            return Verdict.STANDDOWN;
        }
        if (lowCloud > CLOUD_MARGINAL || precip.compareTo(PRECIP_MARGINAL) > 0
                || visibility < VISIBILITY_MARGINAL || humidity > HUMIDITY_MARGINAL) {
            return Verdict.MARGINAL;
        }
        return Verdict.GO;
    }

    /**
     * Builds human-readable flag strings for a slot.
     *
     * @param lowCloud    low cloud cover percentage
     * @param precip      precipitation in mm
     * @param visibility  visibility in metres
     * @param humidity    relative humidity percentage
     * @param tideState   HIGH/MID/LOW or null
     * @param tideAligned whether tide matches preference
     * @param isKingTide  whether this is a king tide
     * @param isSpringTide whether this is a spring tide
     * @return list of flag strings
     */
    static List<String> buildFlags(int lowCloud, BigDecimal precip, int visibility,
            int humidity, String tideState, boolean tideAligned,
            boolean isKingTide, boolean isSpringTide) {
        List<String> flags = new ArrayList<>();
        if (lowCloud > CLOUD_STANDDOWN) {
            flags.add("Sun blocked");
        } else if (lowCloud > CLOUD_MARGINAL) {
            flags.add("Partial cloud");
        }
        if (precip.compareTo(PRECIP_STANDDOWN) > 0) {
            flags.add("Active rain");
        } else if (precip.compareTo(PRECIP_MARGINAL) > 0) {
            flags.add("Light rain");
        }
        if (visibility < VISIBILITY_STANDDOWN) {
            flags.add("Poor visibility");
        } else if (visibility < VISIBILITY_MARGINAL) {
            flags.add("Reduced visibility");
        }
        if (humidity > HUMIDITY_MARGINAL) {
            flags.add("Mist risk");
        }
        if (isKingTide) {
            flags.add("King tide");
        } else if (isSpringTide) {
            flags.add("Spring tide");
        }
        if (tideAligned) {
            flags.add("Tide aligned");
        }
        return flags;
    }

    /**
     * Groups slots into the day → event summary → region hierarchy.
     *
     * @param allSlots   all briefing slots across all locations, dates, and event types
     * @param locations  the source locations (for region lookup)
     * @param dates      the dates covered
     * @return structured briefing days
     */
    List<BriefingDay> buildDays(List<BriefingSlot> allSlots, List<LocationEntity> locations,
            List<LocalDate> dates) {
        // Build location-to-region map
        Map<String, String> locationToRegion = new LinkedHashMap<>();
        for (LocationEntity loc : locations) {
            String regionName = loc.getRegion() != null ? loc.getRegion().getName() : null;
            locationToRegion.put(loc.getName(), regionName);
        }

        List<BriefingDay> days = new ArrayList<>();
        for (LocalDate date : dates) {
            List<BriefingEventSummary> eventSummaries = new ArrayList<>();
            for (TargetType eventType : List.of(TargetType.SUNRISE, TargetType.SUNSET)) {
                List<BriefingSlot> eventSlots = allSlots.stream()
                        .filter(s -> s.solarEventTime().toLocalDate().equals(date))
                        .filter(s -> isEventType(s, eventType))
                        .toList();

                BriefingEventSummary summary = buildEventSummary(eventType, eventSlots,
                        locationToRegion);
                eventSummaries.add(summary);
            }
            days.add(new BriefingDay(date, eventSummaries));
        }
        return days;
    }

    /**
     * Classifies a slot as sunrise or sunset based on its event time relative to solar noon.
     * Slots with event times before noon are sunrise; after noon are sunset.
     *
     * @param slot      the briefing slot
     * @param eventType the target event type to match
     * @return true if the slot matches the event type
     */
    private boolean isEventType(BriefingSlot slot, TargetType eventType) {
        int hour = slot.solarEventTime().getHour();
        return eventType == TargetType.SUNRISE ? hour < 12 : hour >= 12;
    }

    /**
     * Builds an event summary (sunrise or sunset) from the slots, grouping by region.
     *
     * @param eventType        the solar event type
     * @param slots            slots for this date and event type
     * @param locationToRegion map of location name to region name (null for unregioned)
     * @return the event summary with region rollups
     */
    BriefingEventSummary buildEventSummary(TargetType eventType, List<BriefingSlot> slots,
            Map<String, String> locationToRegion) {
        // Group slots by region
        Map<String, List<BriefingSlot>> regionSlots = new LinkedHashMap<>();
        List<BriefingSlot> unregioned = new ArrayList<>();

        for (BriefingSlot slot : slots) {
            String region = locationToRegion.get(slot.locationName());
            if (region != null) {
                regionSlots.computeIfAbsent(region, k -> new ArrayList<>()).add(slot);
            } else {
                unregioned.add(slot);
            }
        }

        // Build region rollups
        List<BriefingRegion> regions = new ArrayList<>();
        for (Map.Entry<String, List<BriefingSlot>> entry : regionSlots.entrySet()) {
            regions.add(buildRegion(entry.getKey(), entry.getValue()));
        }

        return new BriefingEventSummary(eventType, regions, unregioned);
    }

    /**
     * Builds a region rollup from its child slots.
     *
     * @param regionName the region display name
     * @param slots      the location slots within this region
     * @return the region rollup with verdict, summary, and tide highlights
     */
    BriefingRegion buildRegion(String regionName, List<BriefingSlot> slots) {
        Verdict verdict = rollUpVerdict(slots);
        List<String> tideHighlights = buildTideHighlights(slots);
        String summary = buildRegionSummary(verdict, slots, tideHighlights);
        return new BriefingRegion(regionName, verdict, summary, tideHighlights, slots);
    }

    /**
     * Rolls up individual slot verdicts to a region-level verdict using majority vote.
     *
     * @param slots the location slots
     * @return GO if majority GO, STANDDOWN if majority STANDDOWN, MARGINAL otherwise
     */
    static Verdict rollUpVerdict(List<BriefingSlot> slots) {
        if (slots.isEmpty()) {
            return Verdict.MARGINAL;
        }
        long goCount = slots.stream().filter(s -> s.verdict() == Verdict.GO).count();
        long standdownCount = slots.stream().filter(s -> s.verdict() == Verdict.STANDDOWN).count();

        if (goCount > slots.size() / 2) {
            return Verdict.GO;
        }
        if (standdownCount > slots.size() / 2) {
            return Verdict.STANDDOWN;
        }
        return Verdict.MARGINAL;
    }

    /**
     * Extracts tide highlights from slots.
     *
     * @param slots the location slots
     * @return list of notable tide events (e.g. "King tide at Bamburgh")
     */
    static List<String> buildTideHighlights(List<BriefingSlot> slots) {
        List<String> highlights = new ArrayList<>();
        for (BriefingSlot slot : slots) {
            if (slot.isKingTide()) {
                highlights.add("King tide at " + slot.locationName());
            } else if (slot.isSpringTide()) {
                highlights.add("Spring tide at " + slot.locationName());
            }
        }
        return highlights;
    }

    /**
     * Builds a one-line summary for a region.
     *
     * @param verdict        the region verdict
     * @param slots          the child slots
     * @param tideHighlights tide highlight strings
     * @return human-readable summary
     */
    static String buildRegionSummary(Verdict verdict, List<BriefingSlot> slots,
            List<String> tideHighlights) {
        long goCount = slots.stream().filter(s -> s.verdict() == Verdict.GO).count();
        int total = slots.size();

        String conditionText;
        if (verdict == Verdict.GO) {
            conditionText = "Clear at " + goCount + " of " + total + " location"
                    + (total != 1 ? "s" : "");
        } else if (verdict == Verdict.STANDDOWN) {
            long standdownCount = slots.stream()
                    .filter(s -> s.verdict() == Verdict.STANDDOWN).count();
            if (standdownCount == total) {
                conditionText = "Heavy cloud and rain across all " + total + " location"
                        + (total != 1 ? "s" : "");
            } else {
                conditionText = "Poor conditions at " + standdownCount + " of " + total
                        + " location" + (total != 1 ? "s" : "");
            }
        } else {
            conditionText = "Mixed conditions across " + total + " location"
                    + (total != 1 ? "s" : "");
        }

        if (!tideHighlights.isEmpty()) {
            return conditionText + ", " + String.join(", ", tideHighlights).toLowerCase();
        }
        return conditionText;
    }

    /**
     * Generates a headline summarising the best opportunities across all days and events.
     *
     * @param days the briefing days
     * @return one-line headline
     */
    String generateHeadline(List<BriefingDay> days) {
        // Find the best region-event combination
        String bestRegion = null;
        String bestEvent = null;
        String bestDay = null;
        Verdict bestVerdict = Verdict.STANDDOWN;

        for (BriefingDay day : days) {
            for (BriefingEventSummary es : day.eventSummaries()) {
                for (BriefingRegion region : es.regions()) {
                    if (isBetterVerdict(region.verdict(), bestVerdict)) {
                        bestVerdict = region.verdict();
                        bestRegion = region.regionName();
                        bestEvent = es.targetType() == TargetType.SUNRISE ? "sunrise" : "sunset";
                        boolean isToday = day.date().equals(LocalDate.now(ZoneOffset.UTC));
                        bestDay = isToday ? "Today" : "Tomorrow";
                    }
                }
            }
        }

        if (bestRegion == null || bestVerdict == Verdict.STANDDOWN) {
            return "No promising conditions in the next two days";
        }

        if (bestVerdict == Verdict.GO) {
            return bestDay + " " + bestEvent + " looks promising in " + bestRegion;
        }
        return bestDay + " " + bestEvent + " has marginal conditions in " + bestRegion;
    }

    /**
     * Compares two verdicts, with GO being better than MARGINAL, which is better than STANDDOWN.
     *
     * @param candidate the candidate verdict
     * @param current   the current best verdict
     * @return true if the candidate is strictly better
     */
    private boolean isBetterVerdict(Verdict candidate, Verdict current) {
        return candidate.ordinal() < current.ordinal();
    }

    /**
     * Finds the best hourly slot index for a solar event.
     * Mirrors the logic in {@link OpenMeteoService#findBestIndex}.
     *
     * @param times      list of ISO-8601 time strings from the API response
     * @param targetTime the solar event time
     * @param targetType SUNRISE or SUNSET
     * @return the index of the best matching slot
     */
    int findBestIndex(List<String> times, LocalDateTime targetTime, TargetType targetType) {
        int bestIdx = -1;
        long bestDiff = Long.MAX_VALUE;

        for (int i = 0; i < times.size(); i++) {
            LocalDateTime slotTime = LocalDateTime.parse(times.get(i));
            long diffSeconds = ChronoUnit.SECONDS.between(slotTime, targetTime);

            boolean validSide = targetType == TargetType.SUNSET
                    ? diffSeconds >= 0
                    : diffSeconds <= 0;

            long absDiff = Math.abs(diffSeconds);
            if (validSide && absDiff < bestDiff) {
                bestDiff = absDiff;
                bestIdx = i;
            }
        }

        if (bestIdx == -1) {
            bestIdx = 0;
            long minDiff = Long.MAX_VALUE;
            for (int i = 0; i < times.size(); i++) {
                long diff = Math.abs(ChronoUnit.SECONDS.between(
                        LocalDateTime.parse(times.get(i)), targetTime));
                if (diff < minDiff) {
                    minDiff = diff;
                    bestIdx = i;
                }
            }
        }

        return bestIdx;
    }

    /**
     * Location and its fetched forecast data (forecast may be null on failure).
     *
     * @param location the location entity
     * @param forecast the Open-Meteo forecast response, or null if fetch failed
     */
    record LocationWeather(LocationEntity location, OpenMeteoForecastResponse forecast) {
    }
}
