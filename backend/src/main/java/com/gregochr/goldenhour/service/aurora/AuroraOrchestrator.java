package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.client.MetOfficeSpaceWeatherScraper;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full aurora alerting pipeline using NOAA SWPC data.
 *
 * <p>One orchestration cycle:
 * <ol>
 *   <li>Fetch live NOAA SWPC data (Kp, forecast, OVATION, solar wind, alerts).</li>
 *   <li>Derive the current {@link AlertLevel} using dual-condition logic:
 *       CLEAR requires BOTH Kp below threshold AND OVATION below threshold.</li>
 *   <li>Feed the level to {@link AuroraStateCache} to obtain an action.</li>
 *   <li>On {@link AuroraStateCache.Action#NOTIFY}: filter Bortle-eligible locations,
 *       triage by cloud cover, call Claude once for viable locations, assign 1★ to
 *       rejected (overcast) locations, and cache all results.</li>
 *   <li>On {@link AuroraStateCache.Action#CLEAR}: the state machine clears scores.</li>
 *   <li>On {@link AuroraStateCache.Action#SUPPRESS} or {@link AuroraStateCache.Action#NONE}:
 *       do nothing.</li>
 * </ol>
 */
@Component
public class AuroraOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraOrchestrator.class);

    private final NoaaSwpcClient noaaClient;
    private final MetOfficeSpaceWeatherScraper metOfficeScraper;
    private final WeatherTriageService weatherTriage;
    private final ClaudeAuroraInterpreter claudeInterpreter;
    private final AuroraStateCache stateCache;
    private final LocationRepository locationRepository;
    private final AuroraProperties properties;

    /**
     * Constructs the orchestrator with all required dependencies.
     *
     * @param noaaClient         NOAA SWPC data client
     * @param metOfficeScraper   Met Office space weather scraper
     * @param weatherTriage      cloud cover triage service
     * @param claudeInterpreter  Claude aurora scoring service
     * @param stateCache         aurora state machine
     * @param locationRepository location data access
     * @param properties         aurora configuration
     */
    public AuroraOrchestrator(NoaaSwpcClient noaaClient,
            MetOfficeSpaceWeatherScraper metOfficeScraper,
            WeatherTriageService weatherTriage,
            ClaudeAuroraInterpreter claudeInterpreter,
            AuroraStateCache stateCache,
            LocationRepository locationRepository,
            AuroraProperties properties) {
        this.noaaClient = noaaClient;
        this.metOfficeScraper = metOfficeScraper;
        this.weatherTriage = weatherTriage;
        this.claudeInterpreter = claudeInterpreter;
        this.stateCache = stateCache;
        this.locationRepository = locationRepository;
        this.properties = properties;
    }

    /**
     * Runs one full orchestration cycle and returns the state machine action taken.
     *
     * @return the action from the state machine (NOTIFY, SUPPRESS, CLEAR, or NONE)
     */
    public AuroraStateCache.Action run() {
        SpaceWeatherData spaceWeather;
        try {
            spaceWeather = noaaClient.fetchAll();
        } catch (Exception e) {
            LOG.warn("NOAA fetch failed — skipping aurora cycle: {}", e.getMessage());
            return AuroraStateCache.Action.NONE;
        }

        AlertLevel level = deriveAlertLevel(spaceWeather);
        AuroraStateCache.Evaluation eval = stateCache.evaluate(level);
        LOG.info("Aurora orchestration: level={} action={}", level, eval.action());

        if (eval.action() == AuroraStateCache.Action.NOTIFY) {
            scoreAndCache(level, spaceWeather);
        } else if (eval.action() == AuroraStateCache.Action.CLEAR) {
            LOG.info("Aurora event ended — cached scores cleared");
        }

        return eval.action();
    }

    /**
     * Derives the current {@link AlertLevel} from live NOAA data using dual-signal logic.
     *
     * <p>STRONG requires Kp ≥ 7 OR a forecast Kp ≥ 7 within the lookahead window.
     * MODERATE requires Kp ≥ 5 OR OVATION ≥ threshold, or an imminent forecast.
     * MINOR requires Kp ≥ 4.
     * QUIET otherwise.
     *
     * <p>De-escalation to QUIET/MINOR (CLEAR condition) requires BOTH Kp below
     * the clear threshold AND OVATION below the OVATION clear threshold.
     *
     * @param data live NOAA SWPC data
     * @return the derived {@link AlertLevel}
     */
    AlertLevel deriveAlertLevel(SpaceWeatherData data) {
        double currentKp = latestKp(data);
        double ovationProbability = data.ovation() != null
                ? data.ovation().probabilityAtLatitude() : 0.0;
        double forecastKp = maxForecastKp(data, properties.getTriggers().getKpForecastLookaheadHours());

        double kpThreshold = properties.getTriggers().getKpThreshold();
        double ovationThreshold = properties.getTriggers().getOvationProbabilityThreshold();

        // Escalation: either current OR imminent forecast signal
        double effectiveKp = Math.max(currentKp, forecastKp);

        if (effectiveKp >= 7.0) {
            return AlertLevel.STRONG;
        }
        if (effectiveKp >= kpThreshold || ovationProbability >= ovationThreshold) {
            return AlertLevel.MODERATE;
        }
        if (effectiveKp >= 4.0) {
            return AlertLevel.MINOR;
        }
        return AlertLevel.QUIET;
    }

    /**
     * Scores viable locations via Claude and caches results (including auto-1★ for
     * weather-rejected locations).
     */
    private void scoreAndCache(AlertLevel level, SpaceWeatherData spaceWeather) {
        int threshold = (level == AlertLevel.STRONG)
                ? properties.getBortleThreshold().getStrong()
                : properties.getBortleThreshold().getModerate();

        List<LocationEntity> candidates = locationRepository
                .findByBortleClassLessThanEqualAndEnabledTrue(threshold);

        if (candidates.isEmpty()) {
            LOG.info("Aurora NOTIFY ({}): no Bortle-eligible locations (threshold={})",
                    level, threshold);
            stateCache.updateScores(List.of());
            return;
        }

        LOG.info("Aurora NOTIFY ({}): {} candidate location(s) (Bortle ≤ {})",
                level, candidates.size(), threshold);

        WeatherTriageService.TriageResult triage = weatherTriage.triage(candidates);

        // Auto-assign 1★ to overcast-rejected locations
        List<AuroraForecastScore> allScores = new ArrayList<>();
        for (LocationEntity rejected : triage.rejected()) {
            int cloud = triage.cloudByLocation().getOrDefault(rejected, 100);
            allScores.add(new AuroraForecastScore(rejected, 1, level, cloud,
                    "★☆☆☆☆ Completely overcast — aurora obscured by cloud",
                    "✗ Cloud cover: Overcast every hour of the aurora window\n"
                            + "– Geomagnetic activity: " + level.description()));
        }

        // Claude call for viable locations
        if (!triage.viable().isEmpty()) {
            String metOfficeText = metOfficeScraper.getForecastText();
            List<AuroraForecastScore> claudeScores = claudeInterpreter.interpret(
                    level, triage.viable(), triage.cloudByLocation(), spaceWeather, metOfficeText);
            allScores.addAll(claudeScores);
        }

        stateCache.updateScores(allScores);

        int highestStars = allScores.stream().mapToInt(AuroraForecastScore::stars).max().orElse(0);
        LOG.info("Aurora scoring complete: {} location(s) scored, highest={}★",
                allScores.size(), highestStars);
    }

    /**
     * Returns the most recent Kp value from the NOAA data.
     *
     * @param data space weather data
     * @return latest Kp, or 0.0 if no readings available
     */
    private double latestKp(SpaceWeatherData data) {
        List<KpReading> readings = data.recentKp();
        if (readings.isEmpty()) {
            return 0.0;
        }
        return readings.get(readings.size() - 1).kp();
    }

    /**
     * Returns the maximum Kp value from forecast windows that start within the
     * given lookahead window.
     *
     * @param data             space weather data
     * @param lookaheadHours   how many hours ahead to check
     * @return max forecast Kp in the window, or 0.0 if no forecasts available
     */
    private double maxForecastKp(SpaceWeatherData data, int lookaheadHours) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime cutoff = now.plusHours(lookaheadHours);
        return data.kpForecast().stream()
                .filter(f -> !f.from().isAfter(cutoff) && !f.to().isBefore(now))
                .mapToDouble(KpForecast::kp)
                .max()
                .orElse(0.0);
    }
}
