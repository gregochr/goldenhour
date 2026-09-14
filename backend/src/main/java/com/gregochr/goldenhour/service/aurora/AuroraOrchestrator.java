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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the aurora alerting pipeline using NOAA SWPC data.
 *
 * <p>{@link AuroraPollingJob} calls one of two entry points per poll, chosen by whether tonight's
 * dark window has opened. <b>Each evaluates the state machine at most once.</b>
 * <ul>
 *   <li>{@link #runForecastLookahead} — in daylight: the forecast for tonight, a heads-up worded for
 *       planning.</li>
 *   <li>{@link #runNightPoll} — after dark: the higher of the forecast for the rest of tonight and
 *       the conditions now, attributed to whichever reaches it.</li>
 * </ul>
 *
 * <p>⚠️ <b>One evaluation per poll, and one reading of tonight.</b> Until 2026-09-14 a night poll
 * evaluated the state machine twice: a forecast lookahead that kept 3-hour blocks already over, then
 * a real-time path that looked only six hours ahead. When the lookahead reached an alert level and
 * the real-time path did not, every poll NOTIFIED, paid for triage and a Claude call, then CLEARED
 * the scores it had just bought, and the next poll started from IDLE and paid again. When the
 * real-time path came out higher, one poll NOTIFIED twice and threw the first scoring away. Now the
 * forecast term is {@link #maxKpRestOfTonight} everywhere, a night poll's level is never below it
 * (so the evening keeps a heads-up for a small-hours peak), and nothing evaluates twice. Do not
 * split the night poll back into two evaluations, and do not give anything its own horizon.
 * {@code AuroraPollingCycleTest} replays whole nights.
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
     * Daylight poll: the forecast for tonight.
     *
     * <p>Reads NOAA's Kp forecast and takes {@link #maxKpRestOfTonight}. Below MODERATE it leaves the
     * state machine alone, so a daylight poll only ever raises an alert, never clears one. At MODERATE
     * or above it reads the full NOAA snapshot <em>before</em> evaluating, so a NOTIFY always has the
     * data to score with, then evaluates once and on NOTIFY scores with
     * {@link TriggerType#FORECAST_LOOKAHEAD}, so Claude writes for planning.
     *
     * <p>Package-private: {@link AuroraPollingJob#runCycleIfIdle()} is the only way in, so that no two
     * cycles can evaluate the state machine at once.
     *
     * @param tonight tonight's dark window, from nautical dusk to nautical dawn
     * @param now     the instant of this poll
     * @return what the poll did
     */
    AuroraPollOutcome runForecastLookahead(TonightWindow tonight, ZonedDateTime now) {
        List<KpForecast> forecast;
        try {
            forecast = noaaClient.fetchKpForecast();
        } catch (Exception e) {
            LOG.warn("NOAA Kp forecast fetch failed — skipping forecast lookahead: {}",
                    e.getMessage());
            return AuroraPollOutcome.noaaUnavailable(false);
        }

        double restOfTonight = maxKpRestOfTonight(forecast, tonight, now);
        AlertLevel level = levelForKp(restOfTonight);
        if (!level.isAlertWorthy()) {
            LOG.debug("Forecast lookahead: max Kp for the rest of tonight = {} — no alert",
                    restOfTonight);
            return new AuroraPollOutcome(false, level, AuroraStateCache.Action.NONE,
                    TriggerType.FORECAST_LOOKAHEAD);
        }

        SpaceWeatherData snapshot;
        try {
            snapshot = noaaClient.fetchAll();
        } catch (Exception e) {
            LOG.warn("NOAA fetch failed — skipping forecast lookahead: {}", e.getMessage());
            return AuroraPollOutcome.noaaUnavailable(false);
        }

        AuroraStateCache.Evaluation eval = stateCache.evaluate(level);
        LOG.info("Forecast lookahead: maxKp={} level={} action={}", restOfTonight, level,
                eval.action());
        if (eval.action() == AuroraStateCache.Action.NOTIFY) {
            scoreAndCache(level, snapshot, TriggerType.FORECAST_LOOKAHEAD, tonight, restOfTonight);
        }
        return new AuroraPollOutcome(false, level, eval.action(), TriggerType.FORECAST_LOOKAHEAD);
    }

    /**
     * Night poll: one NOAA snapshot, one level, one evaluation.
     *
     * <p>The level is {@link #nightLevel}: the higher of the forecast for the rest of tonight and the
     * conditions now. It is attributed to the forecast when the forecast alone reaches it, so Claude
     * writes for planning about tonight's window. Only when the conditions now go beyond the forecast
     * is it {@link TriggerType#REALTIME}, and Claude writes for acting now.
     *
     * <p>A failed snapshot fetch skips the poll. {@link NoaaSwpcClient} fails open (an outage serves
     * the last cached data, or empty data), so that is a guard against the unexpected rather than
     * the outage path.
     *
     * <p>Package-private for the same reason as {@link #runForecastLookahead}.
     *
     * @param tonight tonight's dark window, which {@code now} is inside
     * @param now     the instant of this poll
     * @return what the poll did
     */
    AuroraPollOutcome runNightPoll(TonightWindow tonight, ZonedDateTime now) {
        SpaceWeatherData snapshot;
        try {
            snapshot = noaaClient.fetchAll();
        } catch (Exception e) {
            LOG.warn("NOAA fetch failed — skipping aurora cycle: {}", e.getMessage());
            return AuroraPollOutcome.noaaUnavailable(true);
        }

        double restOfTonight = maxKpRestOfTonight(snapshot.kpForecast(), tonight, now);
        double kpNow = currentKp(snapshot, now);
        AlertLevel level = nightLevel(snapshot, tonight, now);
        boolean forecastReachesIt = levelForKp(restOfTonight) == level;
        TriggerType trigger = forecastReachesIt ? TriggerType.FORECAST_LOOKAHEAD : TriggerType.REALTIME;

        AuroraStateCache.Evaluation eval = stateCache.evaluate(level);
        LOG.info("Aurora night poll: level={} from {} (rest of tonight Kp {}, Kp now {}) action={}",
                level, trigger, restOfTonight, kpNow, eval.action());

        if (eval.action() == AuroraStateCache.Action.NOTIFY) {
            scoreAndCache(level, snapshot, trigger, forecastReachesIt ? tonight : null,
                    forecastReachesIt ? restOfTonight : kpNow);
        } else if (eval.action() == AuroraStateCache.Action.CLEAR) {
            LOG.info("Aurora event ended — cached scores cleared");
        }
        return new AuroraPollOutcome(true, level, eval.action(), trigger);
    }

    /**
     * Derives the alert level for the aurora batch job ({@code ScheduledBatchEvaluationService}):
     * whether to submit at all, and at which level to pick the Bortle roster and write the prompt.
     * Neither polling path uses it.
     *
     * <p>The rule the real-time path used before 2026-09-14 — the latest published Kp reading, the
     * highest forecast Kp starting within the next {@code aurora.triggers.kp-forecast-lookahead-hours},
     * and the OVATION nowcast — read at this orchestrator's clock. It keeps that fixed horizon because
     * the batch runs on a cron with no dark window of its own, so it can disagree with the polling
     * rule about blocks after dawn. It never reaches the state machine's {@code evaluate}, but its
     * results do reach the cached scores: {@code AuroraResultHandler} writes them whatever the state
     * machine's state. The job is seeded PAUSED; un-pausing it would need that reconciled.
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
     * A night poll's level: the higher of {@link #currentKp} and {@link #maxKpRestOfTonight} through
     * the shared Kp rule, raised to MODERATE when the OVATION nowcast reaches
     * {@code aurora.triggers.ovation-probability-threshold}.
     *
     * <p>Never below {@link #levelForKp} of {@link #maxKpRestOfTonight}, which is what holds a
     * heads-up through the evening. Package-visible so tests can sweep that property directly.
     *
     * @param data    the poll's NOAA snapshot
     * @param tonight tonight's dark window
     * @param now     the instant of the poll
     * @return the night level
     */
    AlertLevel nightLevel(SpaceWeatherData data, TonightWindow tonight, ZonedDateTime now) {
        double kp = Math.max(currentKp(data, now), maxKpRestOfTonight(data.kpForecast(), tonight, now));
        return raisedByOvation(data, levelForKp(kp));
    }

    /**
     * Maps a Kp figure to an alert level with the configured MODERATE threshold. Every path in this
     * class maps through it.
     *
     * @param kp Kp figure
     * @return the alert level
     */
    AlertLevel levelForKp(double kp) {
        return AlertLevel.fromKp(kp, properties.getTriggers().getKpThreshold());
    }

    /**
     * The highest Kp NOAA gives for the rest of tonight: every 3-hour block that starts before
     * tonight's dawn and ends after both dusk and {@code now}, the running one included.
     *
     * <p>A block that has ended does not count, however recently. A storm that peaked earlier tonight
     * is not a forecast for what is left of it. NOAA's product keeps its observed blocks for a week, and
     * counting them held the old lookahead at a finished storm's Kp until dawn. Nothing that starts
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
     * The Kp for now: NOAA's value for the most recently completed 3-hour block. That is its
     * published reading once it is out, and the Kp product's value for the block until then.
     *
     * <p>⚠️ <b>The stand-in is what keeps this continuous.</b> A block's reading is published only
     * after the block ends, and the client caches readings for 15 minutes, so for a poll or more
     * after every block boundary the latest reading still describes the block before. The product
     * has a row for every block, past ones included, so its value for the block that has just ended
     * stands in until that block's own reading lands. Without it the level would dip at the end of
     * an isolated storm block: one poll would CLEAR, and a later one would NOTIFY again, and pay
     * again, when the reading landed.
     *
     * <p>The block still running is not "now": its value is NOAA's estimate or prediction, and it
     * already counts in {@link #maxKpRestOfTonight}. With no completed block in the product at all,
     * this is the latest published reading.
     *
     * @param data the poll's NOAA snapshot
     * @param now  the instant of the poll
     * @return the Kp for now, or 0 if NOAA gave nothing
     */
    static double currentKp(SpaceWeatherData data, ZonedDateTime now) {
        Optional<KpForecast> lastCompleted = data.kpForecast().stream()
                .filter(f -> !f.to().isAfter(now)
                        && f.to().plus(Duration.between(f.from(), f.to())).isAfter(now))
                .max(Comparator.comparing(KpForecast::to));
        if (lastCompleted.isEmpty()) {
            return latestKp(data);
        }
        KpForecast block = lastCompleted.get();
        return data.recentKp().stream()
                .filter(reading -> reading.timestamp().isEqual(block.from()))
                .reduce((earlier, later) -> later)
                .map(KpReading::kp)
                .orElse(block.kp());
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
     * @param triggerKp the Kp value that drove the NOTIFY: the rest-of-tonight maximum for a forecast
     *                  alert, the Kp for now for a real-time one
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
