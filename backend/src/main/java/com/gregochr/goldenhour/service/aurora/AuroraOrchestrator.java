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
import com.gregochr.goldenhour.model.TonightWindow;
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
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #runForecastLookahead(TonightWindow)} — checks Kp forecast for tonight's dark
 *       window. Runs without a daylight gate, giving the user advance warning during the day.</li>
 *   <li>{@link #run()} — checks current Kp and OVATION. Runs only at night to confirm,
 *       escalate, or clear an alert once conditions are actually happening.</li>
 * </ul>
 *
 * <p>Both paths share the same {@link AuroraStateCache} so daytime forecast NOTIFYs suppress
 * duplicate evening real-time NOTIFYs; real-time escalations still produce a second NOTIFY.
 *
 * <p>On any NOTIFY action, the pipeline:
 * <ol>
 *   <li>Filters locations by Bortle threshold.</li>
 *   <li>Triages locations by cloud cover.</li>
 *   <li>Calls Claude once for all viable locations.</li>
 *   <li>Assigns 1★ to overcast-rejected locations.</li>
 *   <li>Caches all results in the state machine.</li>
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
     * Forecast-lookahead path — runs any time, day or night.
     *
     * <p>Fetches the NOAA 3-day Kp forecast and checks whether any window within
     * {@code tonightWindow} reaches the alert threshold. If so, evaluates the state machine
     * and (on NOTIFY) runs the full location scoring pipeline with
     * {@link TriggerType#FORECAST_LOOKAHEAD} context so Claude uses planning language.
     *
     * @param tonightWindow tonight's dark period (nautical dusk → nautical dawn)
     * @return the state machine action taken (NOTIFY, SUPPRESS, CLEAR, or NONE)
     */
    public AuroraStateCache.Action runForecastLookahead(TonightWindow tonightWindow) {
        List<KpForecast> forecast;
        try {
            forecast = noaaClient.fetchKpForecast();
        } catch (Exception e) {
            LOG.warn("NOAA Kp forecast fetch failed — skipping forecast lookahead: {}",
                    e.getMessage());
            return AuroraStateCache.Action.NONE;
        }

        double maxKpTonight = forecast.stream()
                .filter(f -> tonightWindow.overlaps(f.from(), f.to()))
                .mapToDouble(KpForecast::kp)
                .max()
                .orElse(0.0);

        double threshold = properties.getTriggers().getKpThreshold();
        if (maxKpTonight < threshold) {
            LOG.debug("Forecast lookahead: max Kp tonight = {} — below threshold {}",
                    maxKpTonight, threshold);
            return AuroraStateCache.Action.NONE;
        }

        AlertLevel level = AlertLevel.fromKp(maxKpTonight);
        AuroraStateCache.Evaluation eval = stateCache.evaluate(level);
        LOG.info("Forecast lookahead: maxKp={} level={} action={}", maxKpTonight, level,
                eval.action());

        if (eval.action() == AuroraStateCache.Action.NOTIFY) {
            SpaceWeatherData spaceWeather;
            try {
                spaceWeather = noaaClient.fetchAll();
            } catch (Exception e) {
                LOG.warn("Full NOAA fetch failed during forecast-lookahead scoring: {}",
                        e.getMessage());
                return AuroraStateCache.Action.NONE;
            }
            scoreAndCache(level, spaceWeather, TriggerType.FORECAST_LOOKAHEAD, tonightWindow,
                    maxKpTonight);
        }

        return eval.action();
    }

    /**
     * Real-time path — intended to run only after nautical twilight.
     *
     * <p>Fetches current Kp, OVATION probability, and all other NOAA signals.
     * Derives {@link AlertLevel} using dual-condition logic (Kp + OVATION + short-horizon
     * forecast), evaluates the state machine, and (on NOTIFY) runs the full scoring pipeline
     * with {@link TriggerType#REALTIME} context so Claude uses urgent action language.
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

        double currentKp = latestKp(spaceWeather);
        AlertLevel level = deriveAlertLevel(spaceWeather);
        AuroraStateCache.Evaluation eval = stateCache.evaluate(level);
        LOG.info("Aurora real-time: level={} action={}", level, eval.action());

        if (eval.action() == AuroraStateCache.Action.NOTIFY) {
            scoreAndCache(level, spaceWeather, TriggerType.REALTIME, null, currentKp);
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
    public AlertLevel deriveAlertLevel(SpaceWeatherData data) {
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
     *
     * @param triggerKp the Kp value that drove the NOTIFY (forecast max or current real-time Kp)
     */
    private void scoreAndCache(AlertLevel level, SpaceWeatherData spaceWeather,
            TriggerType triggerType, TonightWindow tonightWindow, double triggerKp) {
        stateCache.updateTrigger(triggerType, triggerKp);
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
        stateCache.updateLocationCounts(candidates.size(), triage.viable().size());

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
                    level, triage.viable(), triage.cloudByLocation(), spaceWeather, metOfficeText,
                    triggerType, tonightWindow);
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
