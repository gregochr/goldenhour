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
 * could evaluate the state machine twice: a forecast lookahead that counted 3-hour blocks already
 * over, then a real-time path that looked only six hours ahead. When the lookahead reached an alert
 * level and the real-time path did not, every poll NOTIFIED, paid for triage and (with a clear
 * location) a Claude call, then CLEARED the scores it had just bought, and the next poll started
 * from IDLE and paid again. From IDLE, a real-time level above an alert-worthy lookahead's NOTIFIED
 * twice in one poll. Now both polls read the forecast through {@link #maxKpRestOfTonight}, a night
 * poll's level is never below it (so the evening keeps a heads-up for a small-hours peak), and
 * nothing evaluates twice. Do not split the night poll back into two evaluations, and do not give
 * either poll a horizon of its own — {@link #deriveAlertLevel}, the paused batch job's rule, is the
 * one deliberate exception. {@code AuroraPollingCycleTest} replays whole nights.
 *
 * <p>⚠️ <b>A night poll does not end an alert on the estimate for the block just ended while that
 * block's reading is due.</b> For a poll or more after each 3-hour boundary the Kp for now is NOAA's
 * estimate for that block ({@link #currentKp}), and an estimate can be revised up when the block's
 * reading is published. A night poll that would CLEAR while that reading is still due holds instead
 * ({@link #readingDue}), so a block NOAA under-estimated is not cleared and then paid for again when
 * its reading lands. If the night ends while an alert is held, the first daylight poll makes the
 * CLEAR the night deferred. The hold's limits are set out on {@link #runNightPoll}.
 *
 * <p>On any NOTIFY the pipeline filters locations by Bortle class, triages them by cloud cover, calls
 * Claude once for all viable locations, gives overcast-rejected locations 1★, and caches everything
 * in the state machine.
 */
@Component
public class AuroraOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraOrchestrator.class);

    /**
     * How long after a block ends its published reading is still expected, and so the longest a
     * night poll holds an alert for it. NOAA publishes a block's reading after the block ends (within
     * about twenty minutes, as observed), and the client caches readings for 15 minutes, so a reading
     * normally reaches a poll within about 35 minutes. One still missing an hour after its block ended
     * is late, or the readings feed is stale, and the estimate stands.
     */
    static final Duration READING_EXPECTED_WITHIN = Duration.ofHours(1);

    private final NoaaSwpcClient noaaClient;
    private final WeatherTriageService weatherTriage;
    private final AuroraStateCache stateCache;
    private final LocationRepository locationRepository;
    private final AuroraProperties properties;
    private final EvaluationService evaluationService;
    private final ModelSelectionService modelSelectionService;
    private final Clock clock;

    /**
     * The hold the last night poll made, or {@code null} once a night poll has evaluated. Set only by
     * a night poll that holds, cleared by any night poll that evaluates, and read by the first
     * daylight poll: if the night ended while an alert was held, that poll makes the CLEAR the night
     * deferred. Only a polling cycle touches it, and no two cycles run at once.
     */
    private volatile AuroraPollOutcome pendingHold;

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
     * state machine alone, so a daylight poll never ends an alert on its own reading of tonight. At
     * MODERATE or above it evaluates once, and on NOTIFY scores with
     * {@link TriggerType#FORECAST_LOOKAHEAD}, so Claude writes for planning.
     *
     * <p>⚠️ One exception: if the night ended while a night poll was holding an alert for a reading
     * ({@link #runNightPoll}), this poll makes the CLEAR the night deferred instead, and reads nothing.
     * After dawn no poll acts on the Kp for now, so the reading could decide nothing, and no other
     * poll would ever end that alert. It is this poll's one evaluation; tonight's forecast is read by
     * the next. The full NOAA snapshot a scoring needs is fetched only when
     * {@link AuroraStateCache#wouldNotify} says a scoring is coming, and before the state machine
     * moves, so a held heads-up does not refetch it every poll (fetching it may download the ~900 KB
     * OVATION grid, which the client caches for five minutes). Only an admin reset or simulation
     * landing between the check and the evaluation can make the check wrong: a NOTIFY it missed
     * still fetches, after the state has moved, and a SUPPRESS it did not foresee has fetched for
     * nothing.
     *
     * <p>Package-private, so nothing outside the aurora package can reach it; inside, only
     * {@link AuroraPollingJob}'s guarded cycle calls it, so no two cycles evaluate at once.
     *
     * @param tonight tonight's dark window, from nautical dusk to nautical dawn
     * @param now     the instant of this poll
     * @return what the poll did
     */
    AuroraPollOutcome runForecastLookahead(TonightWindow tonight, ZonedDateTime now) {
        AuroraPollOutcome deferred = pendingHold;
        if (deferred != null) {
            pendingHold = null;
            AuroraStateCache.Evaluation eval = stateCache.evaluate(deferred.level());
            LOG.info("Night ended with an alert held for its reading: level={} action={}",
                    deferred.level(), eval.action());
            return new AuroraPollOutcome(false, deferred.level(), eval.action(), deferred.trigger());
        }

        List<KpForecast> forecast;
        try {
            forecast = noaaClient.fetchKpForecast();
        } catch (Exception e) {
            LOG.warn("NOAA Kp forecast fetch failed — skipping forecast lookahead: {}",
                    e.getMessage());
            return AuroraPollOutcome.noaaReadFailed(false);
        }

        double restOfTonight = maxKpRestOfTonight(forecast, tonight, now);
        AlertLevel level = levelForKp(restOfTonight);
        if (!level.isAlertWorthy()) {
            LOG.debug("Forecast lookahead: max Kp for the rest of tonight = {} — no alert",
                    restOfTonight);
            return new AuroraPollOutcome(false, level, AuroraStateCache.Action.NONE,
                    TriggerType.FORECAST_LOOKAHEAD);
        }

        SpaceWeatherData snapshot = null;
        if (stateCache.wouldNotify(level)) {
            try {
                snapshot = noaaClient.fetchAll();
            } catch (Exception e) {
                LOG.warn("NOAA fetch failed — skipping forecast lookahead: {}", e.getMessage());
                return AuroraPollOutcome.noaaReadFailed(false);
            }
        }

        AuroraStateCache.Evaluation eval = stateCache.evaluate(level);
        LOG.info("Forecast lookahead: maxKp={} level={} action={}", restOfTonight, level,
                eval.action());
        if (eval.action() == AuroraStateCache.Action.NOTIFY) {
            if (snapshot == null) {
                // Only a write outside the polling cycle (the admin reset or simulate endpoints)
                // can move the state between the check and the evaluation.
                try {
                    snapshot = noaaClient.fetchAll();
                } catch (Exception e) {
                    // The state has moved; with nothing to score it stays ACTIVE and unscored until
                    // it escalates or clears, as every daylight NOTIFY whose fetch failed used to.
                    LOG.warn("NOAA fetch failed after a forecast NOTIFY — alert left unscored: {}",
                            e.getMessage());
                    return new AuroraPollOutcome(false, level, eval.action(),
                            TriggerType.FORECAST_LOOKAHEAD);
                }
            }
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
     * <p>⚠️ <b>It does not end an alert on the estimate for the block just ended while that block's
     * reading is due.</b> While that reading is still due ({@link #readingDue}), a poll whose level
     * would CLEAR the alert holds it instead: it leaves the state machine alone
     * ({@link AuroraPollOutcome#held(AlertLevel, TriggerType)}), and the reading decides once it
     * lands. Until then the Kp for now is NOAA's estimate, and an estimate can be revised up: a block
     * NOAA under-estimated would CLEAR at the block boundary, then NOTIFY and pay again when its
     * reading landed. The hold keeps whatever raised the alert, OVATION included. Its limits:
     * <ul>
     *   <li>It lasts at most {@link #READING_EXPECTED_WITHIN} after the block ends. A reading later
     *       than that means a late or stale feed, and the estimate ends the alert.</li>
     *   <li>It lasts no longer than the night. If dawn comes first, the first daylight poll makes the
     *       CLEAR the night deferred ({@link #runForecastLookahead}): after dawn no poll acts on the
     *       Kp for now, so the reading could decide nothing.</li>
     *   <li>It covers only the block that has ended. NOAA's estimate for the block still running
     *       counts in the forecast for the rest of tonight, and an alert that falls while that
     *       estimate is low is cleared, and bought again if the block is published higher.</li>
     * </ul>
     *
     * <p>A snapshot fetch that throws skips the poll. {@link NoaaSwpcClient} fails open, so that is a
     * guard against the unexpected, not the outage path: in an outage the client serves the last data
     * it cached, or empty data on a cold start, and the poll evaluates that — on empty data, as quiet.
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
            return AuroraPollOutcome.noaaReadFailed(true);
        }

        double restOfTonight = maxKpRestOfTonight(snapshot.kpForecast(), tonight, now);
        double kpNow = currentKp(snapshot, now);
        AlertLevel level = nightLevel(snapshot, tonight, now);
        boolean forecastReachesIt = levelForKp(restOfTonight) == level;
        TriggerType trigger = forecastReachesIt ? TriggerType.FORECAST_LOOKAHEAD : TriggerType.REALTIME;

        if (readingDue(snapshot, now) && stateCache.wouldClear(level)) {
            LOG.info("Aurora night poll: level={} from {} (rest of tonight Kp {}, Kp now {}) — alert "
                    + "held until the last block's reading is published", level, trigger, restOfTonight,
                    kpNow);
            pendingHold = AuroraPollOutcome.held(level, trigger);
            return pendingHold;
        }

        pendingHold = null;
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
     * rule about blocks after dawn. It never reaches the state machine's {@code evaluate}, but the
     * batch it gates does reach the cached scores: {@code AuroraResultHandler} writes the batch's
     * results whatever the state machine's state. The job is seeded PAUSED; un-pausing it would need
     * that reconciled.
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
     * The Kp for now: NOAA's figure for the most recently completed 3-hour block — its published
     * reading once it is out, and until then the Kp product's value for the block, which is NOAA's
     * estimate.
     *
     * <p>⚠️ <b>The estimate is what keeps this current.</b> A block's reading is published only after
     * the block ends, and the client caches readings for 15 minutes, so for a poll or more after
     * every boundary the latest reading still describes the block before. Without the estimate the
     * level would dip at the end of an isolated storm block, and a reading three hours old would stand
     * for "now" — enough, after a restart or at a dusk just past a boundary, to raise an alert on a
     * block the state machine never saw, which the next reading would clear minutes later. The latest
     * reading is no floor either: on a readings feed gone stale it would be hours old.
     *
     * <p>An estimate can be wrong either way (09:00-12:00 on 2026-09-14 ran estimated at 3.0 and was
     * published at 2.0). One too high can raise an alert that its reading then ends, which is the
     * price of reading the estimate at all. One too low could end an alert that its reading would
     * raise again, and while that reading is due {@link #runNightPoll} holds the alert instead.
     *
     * <p>The block still running is not "now": its value is NOAA's estimate or prediction, and it
     * already counts in {@link #maxKpRestOfTonight}. With no row for a block completed within the
     * last block's length (no product, or a gap in it), this is the latest published reading — which,
     * until the missing block's own reading is out, is an older block's, and with no row nothing is
     * held for it either. A gap in NOAA's product is rare.
     *
     * @param data the poll's NOAA snapshot
     * @param now  the instant of the poll
     * @return the Kp for now, or 0 if NOAA gave nothing
     */
    static double currentKp(SpaceWeatherData data, ZonedDateTime now) {
        Optional<KpForecast> lastCompleted = lastCompletedBlock(data, now);
        if (lastCompleted.isEmpty()) {
            return latestKp(data);
        }
        KpForecast block = lastCompleted.get();
        return publishedReading(data, block).orElse(block.kp());
    }

    /**
     * Whether the most recently completed block's reading is still due: the block ended less than
     * {@link #READING_EXPECTED_WITHIN} ago and its reading is not yet published, so the Kp for now
     * is still NOAA's estimate. While it is, a night poll holds an alert rather than CLEAR it.
     *
     * @param data the poll's NOAA snapshot
     * @param now  the instant of the poll
     * @return {@code true} while the reading that will decide the Kp for now is still to come
     */
    static boolean readingDue(SpaceWeatherData data, ZonedDateTime now) {
        return lastCompletedBlock(data, now)
                .filter(block -> block.to().plus(READING_EXPECTED_WITHIN).isAfter(now))
                .filter(block -> publishedReading(data, block).isEmpty())
                .isPresent();
    }

    /**
     * The most recently completed block: ended at or before {@code now}, and less than one block's
     * length ago.
     */
    private static Optional<KpForecast> lastCompletedBlock(SpaceWeatherData data, ZonedDateTime now) {
        return data.kpForecast().stream()
                .filter(f -> !f.to().isAfter(now)
                        && f.to().plus(Duration.between(f.from(), f.to())).isAfter(now))
                .max(Comparator.comparing(KpForecast::to));
    }

    /**
     * The block's published reading, if it is out. Should NOAA list a block twice, the later row
     * wins.
     */
    private static Optional<Double> publishedReading(SpaceWeatherData data, KpForecast block) {
        return data.recentKp().stream()
                .filter(reading -> reading.timestamp().isEqual(block.from()))
                .reduce((earlier, later) -> later)
                .map(KpReading::kp);
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
