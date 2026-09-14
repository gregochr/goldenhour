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
import com.gregochr.goldenhour.model.OvationReading;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import com.gregochr.goldenhour.service.evaluation.EvaluationResult;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.solarutils.SolarCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Consecutive {@link AuroraPollingJob} polls driven through the REAL {@link AuroraOrchestrator}, the
 * REAL {@link AuroraStateCache} and the REAL solar-utils twilight for Durham.
 *
 * <p>{@code AuroraPollingJobTest} mocks the orchestrator and {@code AuroraOrchestratorTest} mocks the
 * state machine, so neither can see what one poll does to the next. That is where the flap lived.
 * After dark, a poll runs the forecast lookahead and then the real-time path against the one shared
 * state machine. The two used to read tonight differently: the lookahead kept blocks that were
 * already over, and the real-time path looked only six hours ahead. When they disagreed, a poll
 * NOTIFIED (triage, then a synchronous Claude call), then CLEARED the scores it had just paid for,
 * and the next poll started from IDLE and paid again.
 *
 * <p>Only the edges are faked: NOAA, weather triage, the location roster and the Claude call. The
 * fake NOAA serves what the real client does: the Kp product with every block in it, observed,
 * estimated and predicted alike, and the published readings — a block's reading only
 * {@link #READING_LAG} after the block ends, the way the live feed and the client's 15-minute
 * cache deliver it.
 *
 * <p>Everything happens on the night of 14 January 2027, on a clock the test moves by hand.
 * solar-utils puts Durham's nautical dusk (as the job derives it) at 17:25:42 UTC and nautical dawn
 * at 07:03:38 UTC on the 15th.
 */
@ExtendWith(MockitoExtension.class)
class AuroraPollingCycleTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** A block's reading appears this long after the block ends: NOAA's lag plus the client cache. */
    private static final Duration READING_LAG = Duration.ofMinutes(20);

    /** The polling job's fixed delay. */
    private static final Duration POLL_INTERVAL = Duration.ofMinutes(5);

    /** OVATION probability (%) at 55°N; the MODERATE trigger is 20. */
    private static final double OVATION_QUIET = 5.0;
    private static final double OVATION_SUBSTORM = 35.0;

    @Mock
    private WeatherTriageService weatherTriage;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private EvaluationService evaluationService;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private final MovableClock clock = new MovableClock();
    private final FakeNoaa noaa = new FakeNoaa();
    private final List<EvaluationTask.Aurora> claudeCalls = new ArrayList<>();
    private final LocationEntity kielder = LocationEntity.builder()
            .id(1L).name("Kielder").lat(55.23).lon(-2.58).bortleClass(2).build();

    private AuroraStateCache stateCache;
    private AuroraPollingJob job;

    @BeforeEach
    void setUp() {
        AuroraProperties properties = new AuroraProperties(); // Kp 5, OVATION 20%
        stateCache = new AuroraStateCache();
        AuroraOrchestrator orchestrator = new AuroraOrchestrator(noaa, weatherTriage, stateCache,
                locationRepository, properties, evaluationService, modelSelectionService, clock);
        job = new AuroraPollingJob(orchestrator, properties, new SolarCalculator(),
                dynamicSchedulerService, clock);

        // What the edges answer IF they are asked. Whether a poll asks is the behaviour under test;
        // every test here reaches one NOTIFY that asks, so no stub goes unused.
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(
                properties.getBortleThreshold().getModerate()))
                .thenReturn(List.of(kielder));
        when(weatherTriage.triage(List.of(kielder))).thenReturn(new WeatherTriageService.TriageResult(
                List.of(kielder), List.of(), Map.of(kielder, 10)));
        when(modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION))
                .thenReturn(EvaluationModel.HAIKU);
        when(evaluationService.evaluateNow(any(EvaluationTask.Aurora.class), eq(BatchTriggerSource.SCHEDULED)))
                .thenAnswer(invocation -> {
                    EvaluationTask.Aurora task = invocation.getArgument(0);
                    claudeCalls.add(task);
                    return new EvaluationResult.Scored(List.of(new AuroraForecastScore(kielder, 4,
                            task.alertLevel(), 10, "Clear to the north", "✓ Clear northern horizon")));
                });
    }

    // -------------------------------------------------------------------------
    // The two nights the flap was traced on
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a storm that ended earlier tonight is not re-scored on every poll")
    void stormThatEndedEarlierTonight_isNotRescoredOnEveryPoll() {
        // The storm was 15:00-18:00 (Kp 6), overlapping the first half-hour of darkness. It has been
        // quiet since: 18:00-21:00 read Kp 4, and nothing still to come tonight reaches 4.
        clock.set("2027-01-14T22:00");
        noaa.blocks = kpProduct(2.67, Map.of(
                "2027-01-14T15:00", 6.00,
                "2027-01-14T18:00", 4.00,
                "2027-01-14T21:00", 3.67,
                "2027-01-15T00:00", 3.33,
                "2027-01-15T03:00", 3.00));

        List<AfterPoll> polls = List.of(poll(), poll());

        // Nothing has happened since the storm ended, so there is nothing to score or to show.
        assertThat(polls).containsExactly(new AfterPoll(0, false, 0), new AfterPoll(0, false, 0));

        // Positive control: a fresh substorm in the OVATION nowcast is still caught, once, and worded
        // as a real-time alert. Without it, a change that stopped the pipeline scoring anything at all
        // would pass the assertion above.
        noaa.ovation = OVATION_SUBSTORM;
        assertThat(poll()).isEqualTo(new AfterPoll(1, true, 1));
        assertThat(claudeCalls).singleElement().satisfies(task -> {
            assertThat(task.triggerType()).isEqualTo(TriggerType.REALTIME);
            assertThat(task.alertLevel()).isEqualTo(AlertLevel.MODERATE);
            assertThat(task.viableLocations()).containsExactly(kielder);
        });
        // Exactly one triage across all three polls: the quiet ones cost no Open-Meteo call either.
        verify(weatherTriage).triage(List.of(kielder));
    }

    @Test
    @DisplayName("a forecast peak ten hours after dusk keeps its heads-up, scored once, through the evening")
    void peakBeyondTheSixHourHorizon_keepsItsHeadsUp() {
        // 17:45, twenty minutes into the dark. Kp 5.67 is predicted for 03:00-06:00 — inside tonight,
        // but beyond the six hours the real-time path used to look. Everything nearer is under Kp 4.
        clock.set("2027-01-14T17:45");
        noaa.blocks = kpProduct(2.33, Map.of(
                "2027-01-14T15:00", 3.00,
                "2027-01-14T18:00", 3.33,
                "2027-01-14T21:00", 3.67,
                "2027-01-15T00:00", 4.33,
                "2027-01-15T03:00", 5.67,
                "2027-01-15T06:00", 4.67));

        List<AfterPoll> polls = List.of(poll(), poll());

        // The lookahead raises the heads-up and pays for it once; the real-time path agrees rather
        // than clearing it; the second poll changes nothing and costs nothing.
        assertThat(polls).containsExactly(new AfterPoll(1, true, 1), new AfterPoll(1, true, 1));
        assertThat(claudeCalls).singleElement().satisfies(task -> {
            assertThat(task.triggerType()).isEqualTo(TriggerType.FORECAST_LOOKAHEAD);
            assertThat(task.alertLevel()).isEqualTo(AlertLevel.MODERATE);
            assertThat(task.tonightWindow().dusk()).isEqualTo(utc("2027-01-14T17:25:42"));
            assertThat(task.tonightWindow().dawn()).isEqualTo(utc("2027-01-15T07:03:38"));
        });
        assertThat(stateCache.getLastTriggerKp()).isEqualTo(5.67);
    }

    // -------------------------------------------------------------------------
    // Whole nights, a poll every five minutes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a forecast storm: one heads-up in the morning, held all evening, cleared once after it")
    void wholeNight_forecastStorm_scoredOnceAndClearedOnce() {
        // Kp 6.33 predicted all day for 21:00-24:00; everything else Kp 2.33.
        noaa.blocks = kpProduct(2.33, Map.of("2027-01-14T21:00", 6.33));

        List<Transition> transitions = pollEveryFiveMinutes("2027-01-14T09:00", "2027-01-15T11:00");

        // The morning's heads-up, and one CLEAR when the storm's own reading has been superseded:
        // 00:00-03:00's quiet reading is out at 03:20. Nothing in between, dusk included — the
        // real-time path took over at 17:25:42 without clearing what the lookahead had raised —
        // and nothing after, dawn included.
        assertThat(transitions).containsExactly(
                new Transition("2027-01-14T09:00", "lookahead", AuroraStateCache.Action.NOTIFY),
                new Transition("2027-01-15T03:20", "real-time", AuroraStateCache.Action.CLEAR));
        assertThat(claudeCalls).singleElement()
                .satisfies(task -> assertThat(task.triggerType())
                        .isEqualTo(TriggerType.FORECAST_LOOKAHEAD));
    }

    @Test
    @DisplayName("two peaks with a quiet gap are one alert: no clear in the gap, one Claude call")
    void wholeNight_twoPeaksWithAQuietGap_areOneAlert() {
        // Kp 5.67 at 18:00-21:00, Kp 3 at 21:00-24:00, Kp 5.33 at 00:00-03:00. While the second peak
        // is still ahead, tonight is still worth an alert, so the gap must not clear it.
        noaa.blocks = kpProduct(2.33, Map.of(
                "2027-01-14T18:00", 5.67,
                "2027-01-14T21:00", 3.00,
                "2027-01-15T00:00", 5.33));

        List<Transition> transitions = pollEveryFiveMinutes("2027-01-14T09:00", "2027-01-15T11:00");

        // One CLEAR, at 06:20 when 03:00-06:00's quiet reading lands — still before dawn at 07:03.
        assertThat(transitions).containsExactly(
                new Transition("2027-01-14T09:00", "lookahead", AuroraStateCache.Action.NOTIFY),
                new Transition("2027-01-15T06:20", "real-time", AuroraStateCache.Action.CLEAR));
        assertThat(claudeCalls).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------

    /**
     * What one poll leaves behind: what it has cost so far, and what {@code GET /api/aurora/status}
     * (polled by the frontend every five minutes) reads from the state machine until the next poll.
     *
     * @param claudeCallsSoFar synchronous Claude calls since the test began
     * @param active           whether the state machine is ACTIVE
     * @param cachedScores     number of location scores the state machine holds
     */
    private record AfterPoll(int claudeCallsSoFar, boolean active, int cachedScores) {
    }

    /**
     * A NOTIFY or a CLEAR, and the poll and path that made it.
     *
     * @param at     the poll's instant, as a UTC local date-time
     * @param path   "lookahead" or "real-time"
     * @param action NOTIFY or CLEAR
     */
    private record Transition(String at, String path, AuroraStateCache.Action action) {
    }

    private AfterPoll poll() {
        job.executePoll();
        return new AfterPoll(claudeCalls.size(), stateCache.isActive(),
                stateCache.getCachedScores().size());
    }

    /**
     * Polls every five minutes from {@code from} until {@code until} and returns every NOTIFY and
     * CLEAR in order. Also checks, at every poll, the property the flap broke: a poll never CLEARS
     * what its own lookahead raised or held, and the status never shows ACTIVE without scores.
     */
    private List<Transition> pollEveryFiveMinutes(String from, String until) {
        List<Transition> transitions = new ArrayList<>();
        clock.set(from);
        while (clock.instant().isBefore(utc(until).toInstant())) {
            AuroraOrchestrator.PollOutcome outcome = job.executePoll();
            String at = LocalDateTime.ofInstant(clock.instant(), UTC).toString();
            if (outcome.lookahead() != AuroraStateCache.Action.NONE) {
                assertThat(outcome.realtime())
                        .as("at %s the real-time path cleared what the lookahead had %s", at,
                                outcome.lookahead())
                        .isNotEqualTo(AuroraStateCache.Action.CLEAR);
            }
            assertThat(stateCache.getCachedScores().isEmpty())
                    .as("at %s the state is %s", at, stateCache.isActive() ? "ACTIVE" : "IDLE")
                    .isEqualTo(!stateCache.isActive());
            noteTransition(transitions, at, "lookahead", outcome.lookahead());
            if (outcome.dark()) {
                noteTransition(transitions, at, "real-time", outcome.realtime());
            }
            clock.advance(POLL_INTERVAL);
        }
        return transitions;
    }

    private static void noteTransition(List<Transition> transitions, String at, String path,
            AuroraStateCache.Action action) {
        if (action == AuroraStateCache.Action.NOTIFY || action == AuroraStateCache.Action.CLEAR) {
            transitions.add(new Transition(at, path, action));
        }
    }

    private static ZonedDateTime utc(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(UTC);
    }

    /**
     * NOAA's Kp product from midnight on the 14th to midnight on the 16th: Kp {@code background} in
     * every 3-hour block except the ones named, by start time, in {@code named}.
     */
    private static List<KpForecast> kpProduct(double background, Map<String, Double> named) {
        List<KpForecast> blocks = new ArrayList<>();
        for (ZonedDateTime start = utc("2027-01-14T00:00"); start.isBefore(utc("2027-01-16T00:00"));
                start = start.plusHours(3)) {
            double kp = named.getOrDefault(start.toLocalDateTime().toString(), background);
            blocks.add(new KpForecast(start, start.plusHours(3), kp));
        }
        return blocks;
    }

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {

        private Instant instant = Instant.EPOCH;

        void set(String utcLocalDateTime) {
            instant = utc(utcLocalDateTime).toInstant();
        }

        void advance(Duration by) {
            instant = instant.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /**
     * NOAA as the client serves it, on the test's clock: the whole Kp product, and the readings that
     * would be published by now — each block's, once {@link #READING_LAG} has passed since it ended.
     */
    private final class FakeNoaa extends NoaaSwpcClient {

        private List<KpForecast> blocks = List.of();
        private double ovation = OVATION_QUIET;

        FakeNoaa() {
            super(null, new AuroraProperties(), null, Clock.systemUTC());
        }

        @Override
        public List<KpForecast> fetchKpForecast() {
            return blocks;
        }

        @Override
        public SpaceWeatherData fetchAll() {
            Instant now = clock.instant();
            List<KpReading> readings = blocks.stream()
                    .filter(block -> !block.to().plus(READING_LAG).toInstant().isAfter(now))
                    .map(block -> new KpReading(block.from(), block.kp()))
                    .toList();
            return new SpaceWeatherData(readings, blocks,
                    new OvationReading(now.atZone(UTC), ovation, 55.0), List.of(), List.of());
        }
    }
}
