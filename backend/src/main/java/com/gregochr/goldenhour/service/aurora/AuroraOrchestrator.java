package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.TonightWindow;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import com.gregochr.goldenhour.service.evaluation.EvaluationResult;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Orchestrates the aurora alerting pipeline using NOAA SWPC data.
 *
 * <p>{@link AuroraPollingJob} calls one of two entry points per poll, chosen by whether it is dark:
 * <ul>
 *   <li>{@link #runForecastLookahead(TonightWindow, ZonedDateTime)} — in daylight, the forecast
 *       lookahead alone: a heads-up for tonight, worded for planning.</li>
 *   <li>{@link #runNightPoll(TonightWindow, ZonedDateTime)} — after dark, the lookahead and then the
 *       real-time path, over one NOAA snapshot and one instant.</li>
 * </ul>
 *
 * <p>⚠️ <b>Both paths read tonight through one figure</b>, {@link #maxKpRestOfTonight}: the highest
 * Kp of the 3-hour blocks still ahead in tonight's dark window, the running one included. The
 * lookahead's level is that figure's. The real-time level is the higher of that figure and the
 * current conditions ({@link #currentKp} and the OVATION nowcast), mapped through the same
 * {@link AlertLevel#fromKp(double, double)}. So after dark the real-time level is never below the
 * lookahead's, and the real-time path can never CLEAR what the lookahead has just NOTIFIED.
 *
 * <p>They used to read tonight differently: the lookahead kept blocks that were already over, and
 * the real-time path looked only six hours ahead. Whenever that made them disagree, every poll
 * NOTIFIED, paid for triage and a Claude call, then CLEARED the scores it had just bought, and the
 * next poll started from IDLE and paid again — about twelve times an hour. Do not give either path a
 * horizon of its own again. {@code AuroraPollingCycleTest} replays those nights.
 *
 * <p>On any NOTIFY the pipeline filters locations by Bortle class, triages them by cloud cover, calls
 * Claude once for all viable locations, gives overcast-rejected locations 1★, and caches everything
 * in the state machine.
 */
@Component
public class AuroraOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraOrchestrator.class);

    private final NoaaSwpcClient noaaClient;
    private final WeatherTriageService weatherTriage;
    private final AuroraStateCache stateCache;
    private final LocationRepository locationRepository;
    private final AuroraProperties properties;
    private final EvaluationService evaluationService;
    private final ModelSelectionService modelSelectionService;
    private final Clock clock;

    /**
     * What one poll did to the state machine.
     *
     * @param lookahead the forecast lookahead's action
     * @param realtime  the real-time path's action, or {@code null} when it did not run — a daylight
     *                  poll runs the lookahead alone
     */
    public record PollOutcome(AuroraStateCache.Action lookahead, AuroraStateCache.Action realtime) {

        /**
         * Rejects a missing lookahead action, which every poll has.
         */
        public PollOutcome {
            Objects.requireNonNull(lookahead, "lookahead");
        }

        /**
         * Returns whether the real-time path ran, which it does only after dark.
         *
         * @return {@code true} for a night poll
         */
        public boolean dark() {
            return realtime != null;
        }
    }

    /**
     * Constructs the orchestrator with all required dependencies.
     *
     * @param noaaClient            NOAA SWPC data client
     * @param weatherTriage         cloud cover triage service
     * @param stateCache            aurora state machine
     * @param locationRepository    location data access
     * @param properties            aurora configuration
     * @param evaluationService     unified Pass 3.2 engine — used for the synchronous
     *                              Claude call (replaces direct {@link ClaudeAuroraInterpreter}
     *                              invocation; gains {@code api_call_log} + {@code job_run}
     *                              observability for the aurora real-time path that previously
     *                              had none)
     * @param modelSelectionService resolves the active aurora model
     * @param clock                 supplies the label date on the submitted evaluation task,
     *                              resolved in {@code Europe/London} by {@link ForecastHorizon},
     *                              and the instant {@link #deriveAlertLevel} reads at; the polling
     *                              paths are handed their instant by {@link AuroraPollingJob}
     */
    public AuroraOrchestrator(NoaaSwpcClient noaaClient,
            WeatherTriageService weatherTriage,
            AuroraStateCache stateCache,
            LocationRepository locationRepository,
            AuroraProperties properties,
            EvaluationService evaluationService,
            ModelSelectionService modelSelectionService,
            Clock clock) {
        this.noaaClient = noaaClient;
        this.weatherTriage = weatherTriage;
        this.stateCache = stateCache;
        this.locationRepository = locationRepository;
        this.properties = properties;
        this.evaluationService = evaluationService;
        this.modelSelectionService = modelSelectionService;
        this.clock = clock;
    }

    /**
     * Daylight poll — the forecast lookahead alone.
     *
     * <p>Reads NOAA's Kp forecast and takes {@link #maxKpRestOfTonight}. If that is alert-worthy it
     * evaluates the state machine, and on NOTIFY scores the locations with
     * {@link TriggerType#FORECAST_LOOKAHEAD}, so Claude writes for planning. Below that it leaves the
     * state machine alone: the lookahead only ever raises an alert, never clears one.
     *
     * @param tonight tonight's dark window, from nautical dusk to nautical dawn
     * @param now     the instant of this poll
     * @return the state machine's action, or NONE if nothing reached an alert-worthy level or the
     *         forecast could not be fetched
     */
    public AuroraStateCache.Action runForecastLookahead(TonightWindow tonight, ZonedDateTime now) {
        List<KpForecast> forecast;
        try {
            forecast = noaaClient.fetchKpForecast();
        } catch (Exception e) {
            LOG.warn("NOAA Kp forecast fetch failed — skipping forecast lookahead: {}",
                    e.getMessage());
            return AuroraStateCache.Action.NONE;
        }
        return lookahead(forecast, tonight, now, noaaClient::fetchAll);
    }

    /**
     * Night poll — the forecast lookahead, then the real-time path, over one NOAA snapshot.
     *
     * <p>One {@link NoaaSwpcClient#fetchAll()} feeds both paths and both read it at {@code now}. The
     * client caches each NOAA endpoint separately, so fetching once per path would let a cache
     * expire between the two reads and put the paths on different data within one poll.
     *
     * <p>The real-time path confirms, escalates or clears. On NOTIFY it scores the locations with
     * {@link TriggerType#REALTIME}, so Claude writes for acting now. Because its level is built on the
     * lookahead's own figure it only NOTIFIES above the lookahead, which means something current —
     * the Kp now or the OVATION nowcast — has gone beyond what the forecast holds for the rest of the
     * night.
     *
     * @param tonight tonight's dark window, which {@code now} is inside
     * @param now     the instant of this poll
     * @return both paths' actions; NONE for both if NOAA could not be fetched
     */
    public PollOutcome runNightPoll(TonightWindow tonight, ZonedDateTime now) {
        SpaceWeatherData snapshot;
        try {
            snapshot = noaaClient.fetchAll();
        } catch (Exception e) {
            LOG.warn("NOAA fetch failed — skipping aurora cycle: {}", e.getMessage());
            return new PollOutcome(AuroraStateCache.Action.NONE, AuroraStateCache.Action.NONE);
        }
        AuroraStateCache.Action lookahead =
                lookahead(snapshot.kpForecast(), tonight, now, () -> snapshot);
        AuroraStateCache.Action realtime = realtime(snapshot, tonight, now);
        return new PollOutcome(lookahead, realtime);
    }

    /**
     * Derives the alert level the aurora batch job gates its submission on
     * ({@code ScheduledBatchEvaluationService}). Neither polling path uses it.
     *
     * <p>The real-time rule — the latest published Kp reading, the highest forecast Kp starting
     * within the next {@code aurora.triggers.kp-forecast-lookahead-hours}, and the OVATION nowcast —
     * read at this orchestrator's clock. It keeps the fixed horizon the polling paths have dropped
     * because the batch runs on a cron and has no dark window to read to. Nothing it derives reaches
     * the state machine's {@code evaluate}, so it cannot disagree with the polling paths there.
     *
     * @param data live NOAA SWPC data
     * @return the derived {@link AlertLevel}
     */
    public AlertLevel deriveAlertLevel(SpaceWeatherData data) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime cutoff = now.plusHours(properties.getTriggers().getKpForecastLookaheadHours());
        double forecastKp = data.kpForecast().stream()
                .filter(f -> !f.from().isAfter(cutoff) && !f.to().isBefore(now))
                .mapToDouble(KpForecast::kp)
                .max()
                .orElse(0.0);
        return raisedByOvation(data, levelForKp(Math.max(latestKp(data), forecastKp)));
    }

    /**
     * The real-time level at {@code now}: the higher of {@link #currentKp} and
     * {@link #maxKpRestOfTonight}, through the shared Kp rule, raised to MODERATE when the OVATION
     * nowcast reaches {@code aurora.triggers.ovation-probability-threshold}.
     *
     * <p>Package-visible so the invariant it exists for — never below the lookahead's level, which is
     * {@link #levelForKp} of {@link #maxKpRestOfTonight} — can be swept directly in tests.
     *
     * @param data    the poll's NOAA snapshot
     * @param tonight tonight's dark window
     * @param now     the instant of the poll
     * @return the real-time alert level
     */
    AlertLevel realtimeLevel(SpaceWeatherData data, TonightWindow tonight, ZonedDateTime now) {
        double kp = Math.max(currentKp(data, now), maxKpRestOfTonight(data.kpForecast(), tonight, now));
        return raisedByOvation(data, levelForKp(kp));
    }

    /**
     * Maps a Kp figure to an alert level with the configured MODERATE threshold — the one mapping
     * every path in this class uses.
     *
     * @param kp Kp figure
     * @return the alert level
     */
    AlertLevel levelForKp(double kp) {
        return AlertLevel.fromKp(kp, properties.getTriggers().getKpThreshold());
    }

    /**
     * The highest Kp NOAA gives for the rest of tonight: every 3-hour block that starts before
     * tonight's dawn and ends after both dusk and {@code now}.
     *
     * <p>A block that has ended does not count, however recently: a storm that peaked earlier tonight
     * is not a forecast for what is left of it. NOAA's product keeps its observed blocks for a week,
     * and counting them held the lookahead at a finished storm's Kp until dawn. Nothing that starts
     * at or after dawn counts either, and once dawn has passed nothing of tonight is left.
     *
     * @param forecast NOAA's Kp product: observed, estimated and predicted blocks alike
     * @param tonight  tonight's dark window
     * @param now      the instant of the poll
     * @return the highest such Kp, or 0 if nothing of tonight is left
     */
    static double maxKpRestOfTonight(List<KpForecast> forecast, TonightWindow tonight,
            ZonedDateTime now) {
        if (!now.isBefore(tonight.dawn())) {
            return 0.0;
        }
        ZonedDateTime restFrom = now.isAfter(tonight.dusk()) ? now : tonight.dusk();
        return forecast.stream()
                .filter(f -> f.from().isBefore(tonight.dawn()) && f.to().isAfter(restFrom))
                .mapToDouble(KpForecast::kp)
                .max()
                .orElse(0.0);
    }

    /**
     * The Kp NOAA gives for right now: the higher of the latest published reading and the forecast
     * product's block that is running or has just ended.
     *
     * <p>⚠️ The block that has just ended is what keeps this continuous. A block's reading is
     * published only after the block ends, and the client caches readings for 15 minutes, so for a
     * poll or more after every block boundary the latest reading still describes the block before.
     * The forecast product has a row for every block, past ones included, so counting the one that
     * has just ended bridges that gap. Without it the real-time level would dip at every storm
     * block's end: one poll would CLEAR, and a later one would NOTIFY again, and pay again, when the
     * reading landed.
     *
     * @param data the poll's NOAA snapshot
     * @param now  the instant of the poll
     * @return the Kp for now, or 0 if NOAA gave nothing
     */
    static double currentKp(SpaceWeatherData data, ZonedDateTime now) {
        double runningOrJustEnded = data.kpForecast().stream()
                .filter(f -> !f.from().isAfter(now)
                        && f.to().plus(Duration.between(f.from(), f.to())).isAfter(now))
                .mapToDouble(KpForecast::kp)
                .max()
                .orElse(0.0);
        return Math.max(latestKp(data), runningOrJustEnded);
    }

    private AuroraStateCache.Action lookahead(List<KpForecast> forecast, TonightWindow tonight,
            ZonedDateTime now, Supplier<SpaceWeatherData> scoringData) {
        double maxKp = maxKpRestOfTonight(forecast, tonight, now);
        AlertLevel level = levelForKp(maxKp);
        if (!level.isAlertWorthy()) {
            LOG.debug("Forecast lookahead: max Kp for the rest of tonight = {} — no alert", maxKp);
            return AuroraStateCache.Action.NONE;
        }

        AuroraStateCache.Evaluation eval = stateCache.evaluate(level);
        LOG.info("Forecast lookahead: maxKp={} level={} action={}", maxKp, level, eval.action());

        if (eval.action() == AuroraStateCache.Action.NOTIFY) {
            SpaceWeatherData spaceWeather;
            try {
                spaceWeather = scoringData.get();
            } catch (Exception e) {
                LOG.warn("Full NOAA fetch failed during forecast-lookahead scoring: {}",
                        e.getMessage());
                return AuroraStateCache.Action.NONE;
            }
            scoreAndCache(level, spaceWeather, TriggerType.FORECAST_LOOKAHEAD, tonight, maxKp);
        }
        return eval.action();
    }

    private AuroraStateCache.Action realtime(SpaceWeatherData snapshot, TonightWindow tonight,
            ZonedDateTime now) {
        AlertLevel level = realtimeLevel(snapshot, tonight, now);
        AuroraStateCache.Evaluation eval = stateCache.evaluate(level);
        LOG.info("Aurora real-time: level={} action={}", level, eval.action());

        if (eval.action() == AuroraStateCache.Action.NOTIFY) {
            scoreAndCache(level, snapshot, TriggerType.REALTIME, null, currentKp(snapshot, now));
        } else if (eval.action() == AuroraStateCache.Action.CLEAR) {
            LOG.info("Aurora event ended — cached scores cleared");
        }
        return eval.action();
    }

    private AlertLevel raisedByOvation(SpaceWeatherData data, AlertLevel byKp) {
        boolean ovationAlert = data.ovation() != null
                && data.ovation().probabilityAtLatitude()
                        >= properties.getTriggers().getOvationProbabilityThreshold();
        return ovationAlert && byKp.severity() < AlertLevel.MODERATE.severity()
                ? AlertLevel.MODERATE
                : byKp;
    }

    /**
     * Scores viable locations via Claude and caches results (including auto-1★ for
     * weather-rejected locations).
     *
     * @param triggerKp the Kp value that drove the NOTIFY (the rest-of-tonight maximum for the
     *                  lookahead, the Kp for now for the real-time path)
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

        // Claude call for viable locations — routed through EvaluationService for
        // unified observability (api_call_log + job_run rows). Closes the Pass 1 §6
        // aurora-real-time observability gap.
        if (!triage.viable().isEmpty()) {
            EvaluationModel model =
                    modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION);
            // A pinned zone rather than the JVM default. ⚠️ Nothing *interprets* this date. On this
            // class's path the call below is evaluateNow, and evaluateNowAurora builds its message
            // from alertLevel/viableLocations/cloudByLocation/spaceWeather/triggerType/
            // tonightWindow — never task.date(). The date's only readers anywhere are two strings:
            // taskKey() ("au/LEVEL/date"), which AuroraResultHandler puts in log lines, and — on
            // the batch twin's path only — CustomIdFactory.forAurora, whose parsed date the result
            // processor discards. So it is a label, in both senses, and a night selector nowhere.
            // The night this task is about is `tonightWindow`, passed below as real instants. Do
            // not start deriving "which night" from the date — an aurora night runs dusk-to-dawn
            // across midnight, so no calendar date names it correctly in the small hours. See
            // docs/engineering/aurora-night-selection.md.
            EvaluationTask.Aurora task = new EvaluationTask.Aurora(
                    level, ForecastHorizon.today(clock), model,
                    triage.viable(), triage.cloudByLocation(),
                    spaceWeather, triggerType, tonightWindow);
            EvaluationResult result =
                    evaluationService.evaluateNow(task, BatchTriggerSource.SCHEDULED);
            if (result instanceof EvaluationResult.Scored scored
                    && scored.payload() instanceof List<?> rawScores) {
                @SuppressWarnings("unchecked")
                List<AuroraForecastScore> claudeScores =
                        (List<AuroraForecastScore>) rawScores;
                allScores.addAll(claudeScores);
            } else if (result instanceof EvaluationResult.Errored err) {
                LOG.warn("Aurora {} scoring failed: {}: {}",
                        triggerType, err.errorType(), err.message());
            }
        }

        stateCache.updateScores(allScores);

        int highestStars = allScores.stream().mapToInt(AuroraForecastScore::stars).max().orElse(0);
        LOG.info("Aurora scoring complete: {} location(s) scored, highest={}★",
                allScores.size(), highestStars);
    }

    /**
     * Returns the most recent published Kp reading from the NOAA data.
     *
     * @param data space weather data
     * @return latest Kp, or 0.0 if no readings available
     */
    private static double latestKp(SpaceWeatherData data) {
        List<KpReading> readings = data.recentKp();
        if (readings.isEmpty()) {
            return 0.0;
        }
        return readings.get(readings.size() - 1).kp();
    }
}
