package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AstroConditionsEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.repository.AstroConditionsRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.LunarPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores nightly astro observing conditions at all dark-sky locations.
 *
 * <p>Template-scored (no Claude call) using three factors:
 * <ol>
 *   <li><b>Cloud cover</b> — max of total cloud layers, averaged across night hours</li>
 *   <li><b>Visibility</b> — average visibility across night hours</li>
 *   <li><b>Moonlight</b> — illumination and altitude at the night-window midpoint</li>
 * </ol>
 *
 * <p>Base score is 3★. Each factor applies a modifier. Persistent fog
 * (&lt;1 km visibility for every night hour) hard-caps to 1★.
 */
@Service
public class AstroConditionsService {

    private static final Logger LOG = LoggerFactory.getLogger(AstroConditionsService.class);

    private final OpenMeteoClient openMeteoClient;
    private final SolarService solarService;
    private final LunarCalculator lunarCalculator;
    private final LocationRepository locationRepository;
    private final AstroConditionsRepository astroConditionsRepository;

    /**
     * Constructs the astro conditions service.
     *
     * @param openMeteoClient          Open-Meteo API client
     * @param solarService             solar/twilight calculator
     * @param lunarCalculator          moon position/illumination calculator (solar-utils)
     * @param locationRepository       location data access
     * @param astroConditionsRepository astro conditions data access
     */
    public AstroConditionsService(OpenMeteoClient openMeteoClient,
                                  SolarService solarService,
                                  LunarCalculator lunarCalculator,
                                  LocationRepository locationRepository,
                                  AstroConditionsRepository astroConditionsRepository) {
        this.openMeteoClient = openMeteoClient;
        this.solarService = solarService;
        this.lunarCalculator = lunarCalculator;
        this.locationRepository = locationRepository;
        this.astroConditionsRepository = astroConditionsRepository;
    }

    /**
     * Scores all dark-sky locations for the given dates and persists results.
     *
     * @param dates the nights to score
     * @return total number of location-dates scored
     */
    @Transactional
    public int evaluateAndPersist(List<LocalDate> dates) {
        List<LocationEntity> locations = locationRepository.findByBortleClassIsNotNullAndEnabledTrue();
        if (locations.isEmpty()) {
            LOG.info("Astro conditions: no dark-sky locations to score");
            return 0;
        }

        // Pre-load existing records for merge (avoids duplicate key violations on re-runs)
        Map<String, Long> existingIds = new HashMap<>();
        for (AstroConditionsEntity e : astroConditionsRepository.findByForecastDateIn(dates)) {
            existingIds.put(e.getLocation().getId() + ":" + e.getForecastDate(), e.getId());
        }

        Instant now = Instant.now();
        List<AstroConditionsEntity> allResults = new ArrayList<>();

        // Batch-fetch weather for all dark-sky locations in 1 API call
        Map<String, OpenMeteoForecastResponse> forecastCache = prefetchForecasts(locations);

        for (LocalDate date : dates) {
            LocalDateTime dusk = solarService.nauticalDuskUtc(
                    REFERENCE_LAT, REFERENCE_LON, date);
            LocalDateTime dawn = solarService.nauticalDawnUtc(
                    REFERENCE_LAT, REFERENCE_LON, date.plusDays(1));

            for (LocationEntity loc : locations) {
                try {
                    AstroConditionsEntity result = scoreLocation(
                            loc, date, dusk, dawn, now, forecastCache);
                    if (result != null) {
                        String key = result.getLocation().getId() + ":" + date;
                        Long existingId = existingIds.get(key);
                        if (existingId != null) {
                            result.setId(existingId);
                        }
                        allResults.add(result);
                    }
                } catch (Exception e) {
                    LOG.warn("Astro conditions scoring failed for {}: {}",
                            loc.getName(), e.getMessage());
                }
            }
        }

        if (!allResults.isEmpty()) {
            astroConditionsRepository.saveAll(allResults);
        }
        LOG.info("Astro conditions: scored {} location-date(s) across {} date(s)",
                allResults.size(), dates.size());
        return allResults.size();
    }

    /**
     * Batch-fetches forecast data for all locations in a single API call.
     *
     * @param locations the dark-sky locations to fetch
     * @return map from coordKey to forecast response
     */
    private Map<String, OpenMeteoForecastResponse> prefetchForecasts(
            List<LocationEntity> locations) {
        // Deduplicate by coordinate (same lat/lon = same forecast)
        Map<String, double[]> uniqueCoords = new java.util.LinkedHashMap<>();
        for (LocationEntity loc : locations) {
            String key = OpenMeteoService.coordKey(loc.getLat(), loc.getLon());
            uniqueCoords.putIfAbsent(key, new double[]{loc.getLat(), loc.getLon()});
        }
        List<String> keys = new ArrayList<>(uniqueCoords.keySet());
        List<double[]> coords = new ArrayList<>(uniqueCoords.values());

        LOG.info("Astro conditions: batch-fetching weather for {} unique locations "
                + "(from {} dark-sky locations)", coords.size(), locations.size());

        if (coords.isEmpty()) {
            return Map.of();
        }

        try {
            List<OpenMeteoForecastResponse> responses =
                    openMeteoClient.fetchForecastBatch(coords);
            Map<String, OpenMeteoForecastResponse> cache = new HashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                cache.put(keys.get(i), responses.get(i));
            }
            return cache;
        } catch (Exception e) {
            LOG.warn("Astro conditions batch prefetch failed, scoring will be skipped: {}",
                    e.getMessage());
            return Map.of();
        }
    }

    /**
     * Scores a single location for a single night using pre-fetched forecast data.
     */
    AstroConditionsEntity scoreLocation(LocationEntity location, LocalDate date,
                                        LocalDateTime dusk, LocalDateTime dawn, Instant now,
                                        Map<String, OpenMeteoForecastResponse> forecastCache) {
        try {
            String coordKey = OpenMeteoService.coordKey(location.getLat(), location.getLon());
            OpenMeteoForecastResponse forecast = forecastCache.get(coordKey);
            if (forecast == null || forecast.getHourly() == null) {
                LOG.warn("No forecast data for {} on {}", location.getName(), date);
                return null;
            }

            List<NightHour> nightHours = extractNightHours(forecast, dusk, dawn);
            if (nightHours.isEmpty()) {
                LOG.warn("No night hours for {} on {}", location.getName(), date);
                return null;
            }

            // Moon at night-window midpoint
            LocalDateTime midpoint = dusk.plusMinutes(
                    java.time.Duration.between(dusk, dawn).toMinutes() / 2);
            ZonedDateTime midpointZdt = midpoint.atZone(ZoneOffset.UTC);
            LunarPosition moon = lunarCalculator.calculate(
                    midpointZdt, location.getLat(), location.getLon());

            // Factor scoring
            CloudResult cloudResult = scoreCloud(nightHours);
            VisibilityResult visResult = scoreVisibility(nightHours);
            MoonResult moonResult = scoreMoon(moon);

            // Fog hard cap: ALL night hours < 1 km visibility
            boolean fogCapped = nightHours.stream()
                    .allMatch(h -> h.visibilityM < FOG_THRESHOLD_M);

            double rawScore = BASE_SCORE + cloudResult.modifier
                    + visResult.modifier + moonResult.modifier;
            int stars = fogCapped ? 1 : clamp((int) Math.round(rawScore), 1, 5);

            String summary = buildSummary(stars, cloudResult, visResult, moonResult, fogCapped);

            return AstroConditionsEntity.builder()
                    .location(location)
                    .forecastDate(date)
                    .runTimestamp(now)
                    .stars(stars)
                    .cloudModifier(cloudResult.modifier)
                    .visibilityModifier(visResult.modifier)
                    .moonModifier(moonResult.modifier)
                    .fogCapped(fogCapped)
                    .cloudExplanation(cloudResult.explanation)
                    .visibilityExplanation(visResult.explanation)
                    .moonExplanation(moonResult.explanation)
                    .summary(summary)
                    .nauticalDuskUtc(dusk)
                    .nauticalDawnUtc(dawn)
                    .meanCloudPct(cloudResult.meanPct)
                    .meanVisibilityM(visResult.meanM)
                    .moonIlluminationPct(moon.illuminationPercent())
                    .moonAltitudeDeg(moon.altitude())
                    .moonPhase(moon.phase().name())
                    .build();
        } catch (Exception e) {
            LOG.warn("Astro conditions scoring failed for {}: {}", location.getName(), e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Night hour extraction
    // -------------------------------------------------------------------------

    /**
     * Extracts hourly weather data falling within the night window.
     */
    List<NightHour> extractNightHours(OpenMeteoForecastResponse forecast, LocalDateTime dusk,
                                      LocalDateTime dawn) {
        OpenMeteoForecastResponse.Hourly hourly = forecast.getHourly();
        List<String> times = hourly.getTime();
        List<NightHour> result = new ArrayList<>();

        for (int i = 0; i < times.size(); i++) {
            LocalDateTime time = LocalDateTime.parse(times.get(i));
            if (!time.isBefore(dusk) && time.isBefore(dawn)) {
                int cloudLow = safeInt(hourly.getCloudCoverLow(), i);
                int cloudMid = safeInt(hourly.getCloudCoverMid(), i);
                int cloudHigh = safeInt(hourly.getCloudCoverHigh(), i);
                int effectiveCloud = Math.max(cloudLow, Math.max(cloudMid, cloudHigh));

                double visM = safeDouble(hourly.getVisibility(), i);

                result.add(new NightHour(effectiveCloud, visM));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Factor scoring
    // -------------------------------------------------------------------------

    CloudResult scoreCloud(List<NightHour> hours) {
        double mean = hours.stream().mapToInt(h -> h.cloudPct).average().orElse(50);
        int meanPct = (int) Math.round(mean);
        double modifier;
        String explanation;

        if (mean < 20) {
            modifier = 1.0;
            explanation = "Clear skies — excellent visibility";
        } else if (mean < 40) {
            modifier = 0.5;
            explanation = "Mostly clear — good conditions";
        } else if (mean < 60) {
            modifier = 0.0;
            explanation = "Broken cloud — gaps likely";
        } else if (mean < 80) {
            modifier = -1.0;
            explanation = "Mostly cloudy — limited windows";
        } else {
            modifier = -1.5;
            explanation = "Overcast — poor observing conditions";
        }
        return new CloudResult(modifier, explanation, meanPct);
    }

    VisibilityResult scoreVisibility(List<NightHour> hours) {
        double meanM = hours.stream().mapToDouble(h -> h.visibilityM).average().orElse(10000);
        int meanMInt = (int) Math.round(meanM);
        double meanKm = meanM / 1000.0;
        double modifier;
        String explanation;

        if (meanKm > 20) {
            modifier = 0.5;
            explanation = "Crystal clear air";
        } else if (meanKm >= 10) {
            modifier = 0.0;
            explanation = "Good transparency";
        } else if (meanKm >= 5) {
            modifier = -0.5;
            explanation = "Some haze reducing transparency";
        } else if (meanKm >= 1) {
            modifier = -1.0;
            explanation = "Misty — poor transparency";
        } else {
            modifier = -1.5;
            explanation = "Dense fog — stay home";
        }
        return new VisibilityResult(modifier, explanation, meanMInt);
    }

    MoonResult scoreMoon(LunarPosition moon) {
        double modifier;
        String explanation;
        double illumPct = moon.illuminationPercent();

        if (!moon.isAboveHorizon()) {
            modifier = 0.5;
            explanation = moonBelowHorizonExplanation(illumPct);
        } else if (illumPct < 25) {
            modifier = 0.25;
            explanation = "Thin crescent moon — minimal light pollution";
        } else if (illumPct < 50) {
            modifier = 0.0;
            explanation = "Quarter moon — moderate sky glow";
        } else if (illumPct < 75) {
            modifier = -0.5;
            explanation = "Gibbous moon — noticeable sky brightening";
        } else {
            modifier = -1.0;
            explanation = "Bright moon washing out the sky";
        }
        return new MoonResult(modifier, explanation);
    }

    private String moonBelowHorizonExplanation(double illumPct) {
        if (illumPct < 5) {
            return "New moon — perfect dark sky";
        } else if (illumPct < 50) {
            return "Moon below horizon — dark sky";
        } else {
            return "Moon below horizon — dark sky window";
        }
    }

    // -------------------------------------------------------------------------
    // Summary
    // -------------------------------------------------------------------------

    String buildSummary(int stars, CloudResult cloud, VisibilityResult vis,
                        MoonResult moon, boolean fogCapped) {
        if (fogCapped) {
            return "Persistent fog — unsuitable for observing.";
        }
        if (stars >= 4) {
            return cloud.explanation + ". " + moon.explanation + ".";
        }
        if (stars <= 2) {
            // Lead with the worst factor
            if (cloud.modifier <= vis.modifier && cloud.modifier <= moon.modifier) {
                return cloud.explanation + ". " + vis.explanation + ".";
            }
            return vis.explanation + ". " + cloud.explanation + ".";
        }
        return cloud.explanation + ". " + moon.explanation + ".";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int safeInt(List<Integer> list, int idx) {
        if (list == null || idx >= list.size() || list.get(idx) == null) {
            return 0;
        }
        return list.get(idx);
    }

    private static double safeDouble(List<Double> list, int idx) {
        if (list == null || idx >= list.size() || list.get(idx) == null) {
            return 0.0;
        }
        return list.get(idx);
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Base score before modifiers. */
    static final double BASE_SCORE = 3.0;

    /** Visibility below this value (in metres) is considered fog. */
    static final double FOG_THRESHOLD_M = 1000.0;

    /** Reference latitude for twilight calculations (Durham, UK). */
    private static final double REFERENCE_LAT = 54.776;

    /** Reference longitude for twilight calculations (Durham, UK). */
    private static final double REFERENCE_LON = -1.575;

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

    /** A single hour's weather data within the night window. */
    record NightHour(int cloudPct, double visibilityM) {
    }

    /** Cloud scoring result. */
    record CloudResult(double modifier, String explanation, int meanPct) {
    }

    /** Visibility scoring result. */
    record VisibilityResult(double modifier, String explanation, int meanM) {
    }

    /** Moon scoring result. */
    record MoonResult(double modifier, String explanation) {
    }
}
