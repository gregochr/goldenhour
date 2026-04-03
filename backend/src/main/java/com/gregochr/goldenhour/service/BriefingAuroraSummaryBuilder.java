package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraLocationSlot;
import com.gregochr.goldenhour.model.AuroraRegionSummary;
import com.gregochr.goldenhour.model.AuroraTonightSummary;
import com.gregochr.goldenhour.model.AuroraTomorrowSummary;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.util.RegionGroupingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds aurora summary sections for the daily briefing.
 *
 * <p>Tonight's summary comes from the in-memory {@link AuroraStateCache} FSM,
 * enriched with live weather from Open-Meteo via {@link AuroraWeatherEnricher}.
 * Tomorrow's summary comes from a NOAA SWPC 3-day Kp forecast lookup with
 * per-region weather and verdicts.
 */
@Component
public class BriefingAuroraSummaryBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingAuroraSummaryBuilder.class);

    /** Cloud cover percentage below which a location is considered clear for aurora viewing. */
    static final int CLEAR_SKY_THRESHOLD = 75;

    /** Cache duration for tonight's weather enrichment (5 minutes). */
    private static final long TONIGHT_CACHE_TTL_MS = 5 * 60 * 1000L;

    /** Cache duration for tomorrow's weather enrichment (30 minutes). */
    private static final long TOMORROW_CACHE_TTL_MS = 30 * 60 * 1000L;

    private final AuroraStateCache auroraStateCache;
    private final NoaaSwpcClient noaaSwpcClient;
    private final AuroraWeatherEnricher weatherEnricher;
    private final LocationRepository locationRepository;

    private volatile Map<Long, AuroraWeatherEnricher.AuroraWeather> tonightWeatherCache;
    private volatile long tonightWeatherCacheTimestamp;

    private volatile Map<Long, AuroraWeatherEnricher.AuroraWeather> tomorrowWeatherCache;
    private volatile long tomorrowWeatherCacheTimestamp;

    /**
     * Constructs a {@code BriefingAuroraSummaryBuilder}.
     *
     * @param auroraStateCache   aurora FSM cache for tonight's active-alert data
     * @param noaaSwpcClient     NOAA SWPC client for tomorrow's Kp forecast
     * @param weatherEnricher    fetches weather from Open-Meteo for aurora locations
     * @param locationRepository repository for finding Bortle-eligible locations
     */
    public BriefingAuroraSummaryBuilder(AuroraStateCache auroraStateCache,
            NoaaSwpcClient noaaSwpcClient,
            AuroraWeatherEnricher weatherEnricher,
            LocationRepository locationRepository) {
        this.auroraStateCache = auroraStateCache;
        this.noaaSwpcClient = noaaSwpcClient;
        this.weatherEnricher = weatherEnricher;
        this.locationRepository = locationRepository;
    }

    /**
     * Builds tonight's aurora summary from the active aurora state cache.
     * Returns {@code null} when the state machine is idle (no active alert).
     *
     * @return tonight's aurora summary, or null
     */
    public AuroraTonightSummary buildAuroraTonight() {
        if (!auroraStateCache.isActive()) {
            return null;
        }
        try {
            AlertLevel alertLevel = auroraStateCache.getCurrentLevel();
            Double kp = auroraStateCache.getLastTriggerKp();
            List<AuroraForecastScore> scores = auroraStateCache.getCachedScores();

            // Fetch weather for tonight's locations (cached 5 min)
            Map<Long, AuroraWeatherEnricher.AuroraWeather> weatherMap =
                    fetchTonightWeather(scores);

            // Group scores by region, then convert to location slots
            RegionGroupingUtils.GroupResult<AuroraForecastScore> grouped =
                    RegionGroupingUtils.groupByRegion(scores, score ->
                            score.location().getRegion() != null
                                    ? score.location().getRegion().getName()
                                    : score.location().getName());

            List<AuroraRegionSummary> regions = grouped.grouped().entrySet().stream()
                    .map(e -> buildRegionSummary(e.getKey(), e.getValue(), weatherMap))
                    .collect(Collectors.toList());

            int clearCount = (int) scores.stream()
                    .filter(s -> s.cloudPercent() < CLEAR_SKY_THRESHOLD)
                    .count();

            return new AuroraTonightSummary(alertLevel, kp, clearCount, regions);
        } catch (Exception e) {
            LOG.warn("Tonight aurora summary build failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds tomorrow night's aurora forecast summary from NOAA's 3-day Kp forecast.
     * Looks at windows 20–48 hours in the future to approximate tomorrow's dark window.
     * Includes per-region weather and verdicts when Bortle-eligible locations exist.
     * Returns {@code null} if the forecast cannot be fetched.
     *
     * @return tomorrow's aurora forecast summary, or null
     */
    public AuroraTomorrowSummary buildAuroraTomorrow() {
        try {
            List<KpForecast> forecast = noaaSwpcClient.fetchKpForecast();
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
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

            List<AuroraRegionSummary> regions = buildTomorrowRegions(now);

            return new AuroraTomorrowSummary(peakKp, label,
                    AlertLevel.fromKp(peakKp).name(), regions);
        } catch (Exception e) {
            LOG.debug("Could not fetch tomorrow Kp forecast for briefing: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fetches tonight's weather with a 5-minute cache to avoid spamming Open-Meteo
     * when {@code getCachedBriefing()} calls this method repeatedly.
     */
    private Map<Long, AuroraWeatherEnricher.AuroraWeather> fetchTonightWeather(
            List<AuroraForecastScore> scores) {
        long now = System.currentTimeMillis();
        Map<Long, AuroraWeatherEnricher.AuroraWeather> cached = tonightWeatherCache;
        if (cached != null && (now - tonightWeatherCacheTimestamp) < TONIGHT_CACHE_TTL_MS) {
            return cached;
        }
        try {
            List<LocationEntity> locations = scores.stream()
                    .map(AuroraForecastScore::location)
                    .toList();
            // Use midnight UTC tonight — the middle of the aurora viewing window
            ZonedDateTime utcNow = ZonedDateTime.now(ZoneOffset.UTC);
            ZonedDateTime targetHour = utcNow.getHour() >= 6
                    ? utcNow.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC)
                    : utcNow.toLocalDate().atStartOfDay(ZoneOffset.UTC);
            cached = weatherEnricher.fetchWeather(locations, targetHour);
            tonightWeatherCache = cached;
            tonightWeatherCacheTimestamp = now;
            return cached;
        } catch (Exception e) {
            LOG.debug("Tonight aurora weather enrichment failed: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Builds per-region summaries for tomorrow using Bortle-eligible locations
     * with weather enrichment (30-minute cache).
     */
    private List<AuroraRegionSummary> buildTomorrowRegions(ZonedDateTime now) {
        try {
            List<LocationEntity> bortleLocations =
                    locationRepository.findByBortleClassIsNotNullAndEnabledTrue();
            if (bortleLocations.isEmpty()) {
                return null;
            }

            Map<Long, AuroraWeatherEnricher.AuroraWeather> weatherMap =
                    fetchTomorrowWeather(bortleLocations, now);

            RegionGroupingUtils.GroupResult<LocationEntity> grouped =
                    RegionGroupingUtils.groupByRegion(bortleLocations, loc ->
                            loc.getRegion() != null
                                    ? loc.getRegion().getName() : loc.getName());

            return grouped.grouped().entrySet().stream()
                    .map(e -> buildTomorrowRegionSummary(
                            e.getKey(), e.getValue(), weatherMap))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.debug("Tomorrow aurora region enrichment failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fetches tomorrow's weather with a 30-minute cache.
     */
    private Map<Long, AuroraWeatherEnricher.AuroraWeather> fetchTomorrowWeather(
            List<LocationEntity> locations, ZonedDateTime now) {
        long currentMs = System.currentTimeMillis();
        Map<Long, AuroraWeatherEnricher.AuroraWeather> cached = tomorrowWeatherCache;
        if (cached != null && (currentMs - tomorrowWeatherCacheTimestamp) < TOMORROW_CACHE_TTL_MS) {
            return cached;
        }
        // Use midnight UTC of tomorrow night (2 nights ahead if afternoon, 1 if early morning)
        ZonedDateTime targetHour = now.getHour() >= 6
                ? now.toLocalDate().plusDays(2).atStartOfDay(ZoneOffset.UTC)
                : now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        cached = weatherEnricher.fetchWeather(locations, targetHour);
        tomorrowWeatherCache = cached;
        tomorrowWeatherCacheTimestamp = currentMs;
        return cached;
    }

    /**
     * Builds a region summary for tonight from grouped aurora forecast scores.
     */
    private AuroraRegionSummary buildRegionSummary(String regionName,
            List<AuroraForecastScore> scores,
            Map<Long, AuroraWeatherEnricher.AuroraWeather> weatherMap) {
        List<AuroraLocationSlot> slots = scores.stream()
                .map(s -> {
                    AuroraWeatherEnricher.AuroraWeather w =
                            weatherMap.getOrDefault(s.location().getId(), null);
                    return new AuroraLocationSlot(
                            s.location().getName(),
                            s.location().getBortleClass(),
                            s.cloudPercent() < CLEAR_SKY_THRESHOLD,
                            s.cloudPercent(),
                            w != null ? w.temperatureCelsius() : null,
                            w != null ? w.windSpeedMs() : null,
                            w != null ? w.weatherCode() : null);
                })
                .toList();
        List<AuroraLocationSlot> darkSky = slots.stream()
                .filter(s -> s.bortleClass() != null).toList();
        int clearCount = (int) darkSky.stream().filter(AuroraLocationSlot::clear).count();
        Integer bestBortle = darkSky.stream()
                .map(AuroraLocationSlot::bortleClass)
                .min(Integer::compareTo).orElse(null);
        String verdict = darkSky.isEmpty() ? null
                : clearCount > 0 ? "GO" : "STANDDOWN";

        return new AuroraRegionSummary(regionName, verdict, clearCount, darkSky.size(),
                bestBortle, slots,
                averageDouble(slots, AuroraLocationSlot::temperatureCelsius),
                averageDouble(slots, AuroraLocationSlot::windSpeedMs),
                mostCommon(slots, AuroraLocationSlot::weatherCode));
    }

    /**
     * Builds a region summary for tomorrow from Bortle-eligible locations + weather.
     */
    private AuroraRegionSummary buildTomorrowRegionSummary(String regionName,
            List<LocationEntity> locations,
            Map<Long, AuroraWeatherEnricher.AuroraWeather> weatherMap) {
        List<AuroraLocationSlot> slots = locations.stream()
                .map(loc -> {
                    AuroraWeatherEnricher.AuroraWeather w =
                            weatherMap.getOrDefault(loc.getId(), null);
                    int cloud = w != null ? w.cloudPercent() : 50;
                    return new AuroraLocationSlot(
                            loc.getName(), loc.getBortleClass(),
                            cloud < CLEAR_SKY_THRESHOLD, cloud,
                            w != null ? w.temperatureCelsius() : null,
                            w != null ? w.windSpeedMs() : null,
                            w != null ? w.weatherCode() : null);
                })
                .toList();
        int clearCount = (int) slots.stream().filter(AuroraLocationSlot::clear).count();
        Integer bestBortle = slots.stream()
                .map(AuroraLocationSlot::bortleClass)
                .min(Integer::compareTo).orElse(null);
        String verdict = slots.isEmpty() ? null
                : clearCount > 0 ? "GO" : "STANDDOWN";

        return new AuroraRegionSummary(regionName, verdict, clearCount, slots.size(),
                bestBortle, slots,
                averageDouble(slots, AuroraLocationSlot::temperatureCelsius),
                averageDouble(slots, AuroraLocationSlot::windSpeedMs),
                mostCommon(slots, AuroraLocationSlot::weatherCode));
    }

    /**
     * Averages a nullable Double field across slots, returning null if no values present.
     */
    private static Double averageDouble(List<AuroraLocationSlot> slots,
            java.util.function.Function<AuroraLocationSlot, Double> extractor) {
        double sum = 0;
        int count = 0;
        for (AuroraLocationSlot s : slots) {
            Double val = extractor.apply(s);
            if (val != null) {
                sum += val;
                count++;
            }
        }
        return count > 0 ? sum / count : null;
    }

    /**
     * Returns the most common non-null Integer value, or null if none present.
     */
    private static Integer mostCommon(List<AuroraLocationSlot> slots,
            java.util.function.Function<AuroraLocationSlot, Integer> extractor) {
        return slots.stream()
                .map(extractor)
                .filter(v -> v != null)
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
