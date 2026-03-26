package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.DailyBriefingCacheEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraLocationSlot;
import com.gregochr.goldenhour.model.AuroraRegionSummary;
import com.gregochr.goldenhour.model.AuroraTonightSummary;
import com.gregochr.goldenhour.model.AuroraTomorrowSummary;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.DailyBriefingCacheRepository;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.evaluation.BriefingBestBetAdvisor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Comparator;
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
import java.util.stream.Collectors;


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

    /** Minutes within which a high tide is considered coincident with the solar event. */
    private static final long TIDE_WINDOW_MINUTES = 90;

    /** Scale for decimal weather values. */
    private static final int DECIMAL_SCALE = 2;

    private final LocationService locationService;
    private final SolarService solarService;
    private final OpenMeteoClient openMeteoClient;
    private final TideService tideService;
    private final JobRunService jobRunService;
    private final DailyBriefingCacheRepository briefingCacheRepository;
    private final ObjectMapper objectMapper;
    private final BriefingVerdictEvaluator verdictEvaluator;
    private final BriefingHeadlineGenerator headlineGenerator;
    private final BriefingBestBetAdvisor bestBetAdvisor;
    private final AuroraStateCache auroraStateCache;
    private final NoaaSwpcClient noaaSwpcClient;
    private final Executor forecastExecutor;
    private final AtomicReference<DailyBriefingResponse> cache = new AtomicReference<>();

    /**
     * Constructs a {@code BriefingService}.
     *
     * @param locationService         service for retrieving enabled locations
     * @param solarService            service for calculating sunrise/sunset times
     * @param openMeteoClient         resilient Open-Meteo API client
     * @param tideService             service for tide data lookups
     * @param jobRunService           service for job run tracking
     * @param briefingCacheRepository repository for persisting the briefing across restarts
     * @param objectMapper            Jackson mapper for JSON serialization
     * @param verdictEvaluator        evaluator for slot verdicts, rollups and flags
     * @param headlineGenerator       generator for the briefing headline
     * @param bestBetAdvisor          Claude Haiku advisor producing ranked best-bet picks
     * @param auroraStateCache        aurora FSM cache for tonight's active-alert data
     * @param noaaSwpcClient          NOAA SWPC client for tomorrow's Kp forecast
     * @param forecastExecutor        virtual thread executor for parallel weather fetches
     */
    public BriefingService(LocationService locationService, SolarService solarService,
            OpenMeteoClient openMeteoClient, TideService tideService,
            JobRunService jobRunService, DailyBriefingCacheRepository briefingCacheRepository,
            ObjectMapper objectMapper, BriefingVerdictEvaluator verdictEvaluator,
            BriefingHeadlineGenerator headlineGenerator, BriefingBestBetAdvisor bestBetAdvisor,
            AuroraStateCache auroraStateCache, NoaaSwpcClient noaaSwpcClient,
            Executor forecastExecutor) {
        this.locationService = locationService;
        this.solarService = solarService;
        this.openMeteoClient = openMeteoClient;
        this.tideService = tideService;
        this.jobRunService = jobRunService;
        this.briefingCacheRepository = briefingCacheRepository;
        this.objectMapper = objectMapper;
        this.verdictEvaluator = verdictEvaluator;
        this.headlineGenerator = headlineGenerator;
        this.bestBetAdvisor = bestBetAdvisor;
        this.auroraStateCache = auroraStateCache;
        this.noaaSwpcClient = noaaSwpcClient;
        this.forecastExecutor = forecastExecutor;
    }

    /**
     * Loads the last persisted briefing from the database into the in-memory cache on startup.
     *
     * <p>This ensures the briefing is available immediately after a restart without waiting
     * for the next scheduled cron run.
     */
    @PostConstruct
    void loadPersistedBriefing() {
        briefingCacheRepository.findById(1).ifPresent(entity -> {
            try {
                DailyBriefingResponse persisted = objectMapper.readValue(
                        entity.getPayload(), DailyBriefingResponse.class);
                cache.set(persisted);
                LOG.info("Loaded persisted briefing from DB (generated {})", entity.getGeneratedAt());
            } catch (Exception e) {
                LOG.warn("Could not deserialize persisted briefing — will regenerate on next scheduled run", e);
            }
        });
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
        List<LocalDate> dates = List.of(today, today.plusDays(1), today.plusDays(2));

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

        Map<String, Integer> driveMap = colourLocations.stream()
                .filter(l -> l.getDriveDurationMinutes() != null)
                .collect(java.util.stream.Collectors.toMap(
                        LocationEntity::getName, LocationEntity::getDriveDurationMinutes));

        String headline = headlineGenerator.generateHeadline(days);
        List<BestBet> bestBets = bestBetAdvisor.advise(days, jobRun.getId(), driveMap);
        AuroraTonightSummary auroraTonight = buildAuroraTonight();
        AuroraTomorrowSummary auroraTomorrow = buildAuroraTomorrow();
        DailyBriefingResponse response = new DailyBriefingResponse(
                LocalDateTime.now(ZoneOffset.UTC), headline, days, bestBets,
                auroraTonight, auroraTomorrow);

        cache.set(response);
        persistBriefing(response);
        jobRunService.completeRun(jobRun, succeeded, failed, dates);
        LOG.info("Daily briefing refresh complete — {} locations, {} succeeded, {} failed",
                colourLocations.size(), succeeded, failed);
    }

    /**
     * Builds tonight's aurora summary from the active aurora state cache.
     * Returns {@code null} when the state machine is idle (no active alert).
     *
     * @return tonight's aurora summary, or null
     */
    AuroraTonightSummary buildAuroraTonight() {
        if (!auroraStateCache.isActive()) {
            return null;
        }
        AlertLevel alertLevel = auroraStateCache.getCurrentLevel();
        Double kp = auroraStateCache.getLastTriggerKp();
        List<AuroraForecastScore> scores = auroraStateCache.getCachedScores();

        // Group locations by region
        Map<String, List<AuroraLocationSlot>> regionSlots = new LinkedHashMap<>();
        for (AuroraForecastScore score : scores) {
            String regionName = score.location().getRegion() != null
                    ? score.location().getRegion().getName()
                    : score.location().getName();
            boolean clear = score.cloudPercent() < 75;
            AuroraLocationSlot slot = new AuroraLocationSlot(
                    score.location().getName(),
                    score.location().getBortleClass(),
                    clear,
                    score.cloudPercent());
            regionSlots.computeIfAbsent(regionName, k -> new ArrayList<>()).add(slot);
        }

        List<AuroraRegionSummary> regions = regionSlots.entrySet().stream()
                .map(e -> new AuroraRegionSummary(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        int clearCount = scores.stream()
                .filter(s -> s.cloudPercent() < 75)
                .mapToInt(s -> 1)
                .sum();

        return new AuroraTonightSummary(alertLevel, kp, clearCount, regions);
    }

    /**
     * Builds tomorrow night's aurora forecast summary from NOAA's 3-day Kp forecast.
     * Looks at windows 20–48 hours in the future to approximate tomorrow's dark window.
     * Returns {@code null} if the forecast cannot be fetched.
     *
     * @return tomorrow's aurora forecast summary, or null
     */
    AuroraTomorrowSummary buildAuroraTomorrow() {
        try {
            List<KpForecast> forecast = noaaSwpcClient.fetchKpForecast();
            ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
            ZonedDateTime windowStart = now.plusHours(20);
            ZonedDateTime windowEnd = now.plusHours(48);

            double peakKp = forecast.stream()
                    .filter(f -> f.from().isBefore(windowEnd) && f.to().isAfter(windowStart))
                    .mapToDouble(KpForecast::kp)
                    .max()
                    .orElse(0.0);

            String label;
            if (peakKp >= 6) {
                label = "Potentially strong";
            } else if (peakKp >= 4) {
                label = "Worth watching";
            } else {
                label = "Quiet";
            }

            return new AuroraTomorrowSummary(peakKp, label);
        } catch (Exception e) {
            LOG.debug("Could not fetch tomorrow Kp forecast for briefing: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Serializes the briefing response and upserts the single-row DB cache (id = 1).
     * Failures are logged as warnings and do not affect the in-memory cache or job run metrics.
     *
     * @param response the briefing response to persist
     */
    private void persistBriefing(DailyBriefingResponse response) {
        try {
            DailyBriefingCacheEntity entity = new DailyBriefingCacheEntity();
            entity.setId(1);
            entity.setGeneratedAt(response.generatedAt());
            entity.setPayload(objectMapper.writeValueAsString(response));
            briefingCacheRepository.save(entity);
            LOG.debug("Persisted briefing to DB (generated {})", response.generatedAt());
        } catch (Exception e) {
            LOG.warn("Could not persist briefing to DB — in-memory cache still updated", e);
        }
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
        Double apparentTemp = h.getApparentTemperature() != null && idx < h.getApparentTemperature().size()
                ? h.getApparentTemperature().get(idx) : null;
        Integer weatherCode = h.getWeatherCode() != null && idx < h.getWeatherCode().size()
                ? h.getWeatherCode().get(idx) : null;
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

        // Determine weather verdict
        Verdict verdict = verdictEvaluator.determineVerdict(lowCloud, precip, visibility, humidity);

        // Coastal tide demotion: if coastal, tide data is present, but tide is not aligned
        // → override to STANDDOWN regardless of weather. If tide data is absent (tideState == null),
        // leave the weather-only verdict intact so missing data does not penalise the location.
        boolean tidesNotAligned = false;
        if (locationService.isCoastal(loc) && tideState != null
                && !tideAligned && verdict != Verdict.STANDDOWN) {
            verdict = Verdict.STANDDOWN;
            tidesNotAligned = true;
        }

        // Build flags
        List<String> flags = verdictEvaluator.buildFlags(lowCloud, precip, visibility, humidity,
                tideState, tideAligned, isKingTide, isSpringTide, tidesNotAligned);

        return new BriefingSlot(
                loc.getName(), solarTime, verdict,
                lowCloud, precip, visibility, humidity, temp, apparentTemp, weatherCode, windSpeed,
                tideState, tideAligned, nearestHighTime, nearestHighHeight,
                isKingTide, isSpringTide, flags);
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
        Verdict verdict = verdictEvaluator.rollUpVerdict(slots);
        List<String> tideHighlights = verdictEvaluator.buildTideHighlights(slots);
        String summary = verdictEvaluator.buildRegionSummary(verdict, slots, tideHighlights);

        // Representative comfort: average of GO slots, falling back to all slots
        List<BriefingSlot> repSlots = slots.stream()
                .filter(s -> s.verdict() == Verdict.GO)
                .toList();
        if (repSlots.isEmpty()) {
            repSlots = slots;
        }

        double rawTemp = repSlots.stream()
                .filter(s -> s.temperatureCelsius() != null)
                .mapToDouble(BriefingSlot::temperatureCelsius)
                .average().orElse(Double.NaN);
        double rawApparent = repSlots.stream()
                .filter(s -> s.apparentTemperatureCelsius() != null)
                .mapToDouble(BriefingSlot::apparentTemperatureCelsius)
                .average().orElse(Double.NaN);
        double rawWind = repSlots.stream()
                .mapToDouble(s -> s.windSpeedMs().doubleValue())
                .average().orElse(Double.NaN);

        // Weather code: code of the median-temperature slot
        List<BriefingSlot> withCode = repSlots.stream()
                .filter(s -> s.temperatureCelsius() != null && s.weatherCode() != null)
                .sorted(Comparator.comparingDouble(BriefingSlot::temperatureCelsius))
                .toList();
        Integer medianWeatherCode = withCode.isEmpty() ? null
                : withCode.get(withCode.size() / 2).weatherCode();

        return new BriefingRegion(regionName, verdict, summary, tideHighlights, slots,
                Double.isNaN(rawTemp) ? null : rawTemp,
                Double.isNaN(rawApparent) ? null : rawApparent,
                Double.isNaN(rawWind) ? null : rawWind,
                medianWeatherCode);
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
