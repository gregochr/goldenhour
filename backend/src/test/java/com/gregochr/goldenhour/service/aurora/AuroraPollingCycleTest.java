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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Consecutive {@link AuroraPollingJob} polls driven through the REAL {@link AuroraOrchestrator}, the
 * REAL {@link AuroraStateCache} and the REAL solar-utils twilight for Durham.
 *
 * <p>{@code AuroraPollingJobTest} mocks the orchestrator and {@code AuroraOrchestratorTest} mocks the
 * state machine, so neither can see what one poll does to the next. That is where the flap lived.
 * A night poll used to evaluate the state machine twice: a forecast lookahead that kept 3-hour blocks
 * already over, then a real-time path that looked only six hours ahead. When the lookahead reached
 * an alert level and the real-time path did not, a poll NOTIFIED (triage, then a synchronous Claude
 * call), then CLEARED the scores it had just paid for, and the next poll started from IDLE and paid
 * again. Now every poll evaluates once, and these tests replay whole nights to show it.
 *
 * <p>Only the edges are faked: NOAA, weather triage, the location roster and the Claude call. The
 * fake NOAA serves what the real client does: the Kp product with every block in it, observed,
 * estimated and predicted alike, and the published readings — a block's reading only
 * {@link #READING_LAG} after the block ends, the way the live feed and the client's 15-minute
 * cache deliver it. A test can publish a reading that revises NOAA's estimate, or stop the readings
 * feed, which the client then serves from its cache.
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

    private AuroraProperties properties;
    private AuroraStateCache stateCache;
    private AuroraPollingJob job;

    @BeforeEach
    void setUp() {
        properties = new AuroraProperties(); // Kp 5, OVATION 20%
        stateCache = new AuroraStateCache();
        AuroraOrchestrator orchestrator = new AuroraOrchestrator(noaa, weatherTriage, stateCache,
                locationRepository, properties, evaluationService, modelSelectionService, clock);
        job = new AuroraPollingJob(orchestrator, properties, new SolarCalculator(),
                dynamicSchedulerService, clock);

        // What the edges answer IF they are asked. Whether a poll asks is the behaviour under test;
        // every test here reaches at least one NOTIFY that asks, so no stub goes unused. The Bortle
        // roster is stubbed per test, at the level that test scores at.
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
        kielderIsEligibleAt(properties.getBortleThreshold().getModerate());
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
        // but beyond the six hours the real-time path used to look. Everything that path read, the
        // blocks starting by 23:45, is under Kp 4.
        kielderIsEligibleAt(properties.getBortleThreshold().getModerate());
        clock.set("2027-01-14T17:45");
        noaa.blocks = kpProduct(2.33, Map.of(
                "2027-01-14T15:00", 3.00,
                "2027-01-14T18:00", 3.33,
                "2027-01-14T21:00", 3.67,
                "2027-01-15T00:00", 4.33,
                "2027-01-15T03:00", 5.67,
                "2027-01-15T06:00", 4.67));

        List<AfterPoll> polls = List.of(poll(), poll());

        // The heads-up is raised and paid for once, and the second poll changes nothing.
        assertThat(polls).containsExactly(new AfterPoll(1, true, 1), new AfterPoll(1, true, 1));
        assertThat(claudeCalls).singleElement().satisfies(task -> {
            assertThat(task.triggerType()).isEqualTo(TriggerType.FORECAST_LOOKAHEAD);
            assertThat(task.alertLevel()).isEqualTo(AlertLevel.MODERATE);
            assertThat(task.tonightWindow().dusk()).isEqualTo(utc("2027-01-14T17:25:42"));
            assertThat(task.tonightWindow().dawn()).isEqualTo(utc("2027-01-15T07:03:38"));
        });
        assertThat(stateCache.getLastTriggerKp()).isEqualTo(5.67);
    }

    @Test
    @DisplayName("Kp now above everything left tonight is one STRONG real-time alert — one Claude call, not two")
    void kpNowAboveTheForecast_paysOnce() {
        // 22:00, the state machine IDLE (after a restart, say). 18:00-21:00 read Kp 7.33 and its
        // reading is out; the rest of tonight is forecast Kp 5.33 at most. A poll that evaluated the
        // rest-of-tonight forecast and the conditions now separately would NOTIFY at MODERATE, score,
        // then NOTIFY again at STRONG and score again.
        clock.set("2027-01-14T22:00");
        noaa.blocks = kpProduct(2.33, Map.of(
                "2027-01-14T18:00", 7.33,
                "2027-01-14T21:00", 5.33,
                "2027-01-15T00:00", 5.00));
        kielderIsEligibleAt(properties.getBortleThreshold().getStrong());

        assertThat(poll()).isEqualTo(new AfterPoll(1, true, 1));

        assertThat(claudeCalls).singleElement().satisfies(task -> {
            assertThat(task.alertLevel()).isEqualTo(AlertLevel.STRONG);
            assertThat(task.triggerType()).isEqualTo(TriggerType.REALTIME);
        });
        assertThat(stateCache.getLastTriggerKp()).isEqualTo(7.33);
        // A second poll over the same data changes nothing, and no MODERATE scoring ever happened.
        assertThat(poll()).isEqualTo(new AfterPoll(1, true, 1));
        verify(locationRepository, never()).findByBortleClassLessThanEqualAndEnabledTrue(
                properties.getBortleThreshold().getModerate());
    }

    // -------------------------------------------------------------------------
    // Whole nights, a poll every five minutes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a forecast storm: one heads-up in the morning, held all evening, cleared once after it")
    void wholeNight_forecastStorm_scoredOnceAndClearedOnce() {
        // Kp 6.33 predicted all day for 21:00-24:00; everything else Kp 2.33.
        kielderIsEligibleAt(properties.getBortleThreshold().getModerate());
        noaa.blocks = kpProduct(2.33, Map.of("2027-01-14T21:00", 6.33));

        List<Transition> transitions = pollEveryFiveMinutes("2027-01-14T09:00", "2027-01-15T11:00");

        // The morning's heads-up, held through dusk at 17:25:42 and through the storm; then one CLEAR
        // at 03:20, when the quiet 00:00-03:00 block's reading is out. From 03:00 the level is quiet
        // on NOAA's estimate, and an estimate does not end an alert. Nothing after, dawn included.
        assertThat(transitions).containsExactly(
                new Transition("2027-01-14T09:00", "day", AuroraStateCache.Action.NOTIFY),
                new Transition("2027-01-15T03:20", "night", AuroraStateCache.Action.CLEAR));
        assertThat(claudeCalls).singleElement()
                .satisfies(task -> assertThat(task.triggerType())
                        .isEqualTo(TriggerType.FORECAST_LOOKAHEAD));
    }

    @Test
    @DisplayName("two peaks with a quiet gap are one alert: no clear in the gap, one Claude call")
    void wholeNight_twoPeaksWithAQuietGap_areOneAlert() {
        // Kp 5.67 at 18:00-21:00, Kp 3 at 21:00-24:00, Kp 5.33 at 00:00-03:00. While the second peak
        // is still ahead, tonight is still worth an alert, so the gap must not clear it.
        kielderIsEligibleAt(properties.getBortleThreshold().getModerate());
        noaa.blocks = kpProduct(2.33, Map.of(
                "2027-01-14T18:00", 5.67,
                "2027-01-14T21:00", 3.00,
                "2027-01-15T00:00", 5.33));

        List<Transition> transitions = pollEveryFiveMinutes("2027-01-14T09:00", "2027-01-15T11:00");

        // One CLEAR, at 06:20 when the quiet 03:00-06:00 block's reading is out — before dawn.
        assertThat(transitions).containsExactly(
                new Transition("2027-01-14T09:00", "day", AuroraStateCache.Action.NOTIFY),
                new Transition("2027-01-15T06:20", "night", AuroraStateCache.Action.CLEAR));
        assertThat(claudeCalls).hasSize(1);
    }

    @Test
    @DisplayName("a block NOAA under-estimated holds the alert until its reading is out — one Claude call")
    void wholeNight_underEstimatedBlock_holdsUntilItsReadingIsOut() {
        // Kp 5.33 predicted for 18:00-21:00 raises the morning's heads-up. NOAA has 21:00-24:00 at
        // Kp 4, but the storm carried on and that block is published at Kp 5.67. From midnight until
        // the reading lands at 00:20, NOAA's figure for the block is its estimate, which reads MINOR.
        // Ending the alert on it would CLEAR at midnight and NOTIFY again, and pay again, at 00:20.
        kielderIsEligibleAt(properties.getBortleThreshold().getModerate());
        noaa.blocks = kpProduct(2.33, Map.of(
                "2027-01-14T18:00", 5.33,
                "2027-01-14T21:00", 4.00));
        noaa.published = Map.of("2027-01-14T21:00", 5.67);

        List<Transition> transitions = pollEveryFiveMinutes("2027-01-14T09:00", "2027-01-15T11:00");

        // One CLEAR, at 03:20 when the quiet 00:00-03:00 block's reading is out.
        assertThat(transitions).containsExactly(
                new Transition("2027-01-14T09:00", "day", AuroraStateCache.Action.NOTIFY),
                new Transition("2027-01-15T03:20", "night", AuroraStateCache.Action.CLEAR));
        assertThat(claudeCalls).hasSize(1);
    }

    @Test
    @DisplayName("an alert OVATION raised is held, not cleared and re-bought, while a low estimate's reading is due")
    void ovationAlert_underEstimatedBlock_isHeldUntilItsReadingIsOut() {
        // IDLE at 23:00, when an OVATION substorm (35%) raises MODERATE on its own: 18:00-21:00 read
        // Kp 3, and NOAA estimates the running 21:00-24:00 block at 4.67. At midnight OVATION falls
        // quiet just as 21:00-24:00 ends, and its reading, due at 00:20, will be 5.33. On the estimate
        // alone the level is MINOR: a CLEAR at midnight, and a second NOTIFY, paid, at 00:20.
        // Whatever raised the alert, an estimate does not end it.
        kielderIsEligibleAt(properties.getBortleThreshold().getModerate());
        noaa.blocks = kpProduct(2.33, Map.of(
                "2027-01-14T18:00", 3.00,
                "2027-01-14T21:00", 4.67));
        noaa.published = Map.of("2027-01-14T21:00", 5.33);
        noaa.ovation = OVATION_SUBSTORM;

        List<Transition> substorm = pollEveryFiveMinutes("2027-01-14T23:00", "2027-01-15T00:00");
        noaa.ovation = OVATION_QUIET;
        List<Transition> afterIt = pollEveryFiveMinutes("2027-01-15T00:00", "2027-01-15T11:00");

        assertThat(substorm).containsExactly(
                new Transition("2027-01-14T23:00", "night", AuroraStateCache.Action.NOTIFY));
        // One CLEAR, at 03:20 when the quiet 00:00-03:00 block's reading is out.
        assertThat(afterIt).containsExactly(
                new Transition("2027-01-15T03:20", "night", AuroraStateCache.Action.CLEAR));
        assertThat(claudeCalls).singleElement()
                .satisfies(task -> assertThat(task.triggerType()).isEqualTo(TriggerType.REALTIME));
    }

    @Test
    @DisplayName("a restart just after a block boundary does not pay for the block before's storm")
    void restartJustAfterABoundary_doesNotPayForTheBlockBefore() {
        // Back up at 18:05, IDLE. 12:00-15:00 read Kp 5.67. 15:00-18:00 ended five minutes ago; NOAA
        // estimates it at 3.33, and its reading, 3.33 too, lands at 18:20. A reading three hours old
        // is not "now": raising an alert on it would pay for a scoring that 15:00-18:00's own reading
        // clears a quarter of an hour later.
        noaa.blocks = kpProduct(2.33, Map.of(
                "2027-01-14T12:00", 5.67,
                "2027-01-14T15:00", 3.33));

        List<Transition> transitions = pollEveryFiveMinutes("2027-01-14T18:05", "2027-01-14T21:00");

        assertThat(transitions).isEmpty();
        assertThat(claudeCalls).isEmpty();

        // Positive control: a fresh substorm at 21:00 is still caught, once.
        kielderIsEligibleAt(properties.getBortleThreshold().getModerate());
        noaa.ovation = OVATION_SUBSTORM;
        assertThat(poll()).isEqualTo(new AfterPoll(1, true, 1));
    }

    // -------------------------------------------------------------------------
    // A readings feed that stops — the client keeps serving what it last fetched
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a readings feed that stops after a daytime storm raises nothing that night")
    void readingsFeedStopsAfterAStorm_raisesNothing() {
        // 09:00-12:00 read Kp 6.33, published at 12:20, and then the readings feed stopped. The Kp
        // product carries on, quiet. Holding that last reading as "now" would raise an alert at dusk
        // on a storm that ended at noon, and hold it all night.
        noaa.blocks = kpProduct(2.33, Map.of("2027-01-14T09:00", 6.33));
        noaa.readingsStopAt = utc("2027-01-14T12:30").toInstant();

        List<Transition> transitions = pollEveryFiveMinutes("2027-01-14T09:00", "2027-01-15T11:00");

        assertThat(transitions).isEmpty();
        assertThat(claudeCalls).isEmpty();

        // Positive control: the next night, an OVATION substorm is caught.
        kielderIsEligibleAt(properties.getBortleThreshold().getModerate());
        noaa.ovation = OVATION_SUBSTORM;
        clock.set("2027-01-15T22:00");
        assertThat(poll()).isEqualTo(new AfterPoll(1, true, 1));
    }

    @Test
    @DisplayName("a readings feed that stops during an alert holds it at most an hour past the boundary")
    void readingsFeedStopsDuringAnAlert_holdsAtMostAnHour() {
        // Kp 6.33 predicted for 21:00-24:00 raises the morning's heads-up; the readings feed stops
        // after 18:00-21:00's lands at 21:20. From 03:00 the level is quiet on NOAA's estimate for
        // 00:00-03:00, and the hold waits for a reading that never comes. At 04:00 it is an hour
        // overdue, the feed is taken as stale, and the estimate ends the alert.
        kielderIsEligibleAt(properties.getBortleThreshold().getModerate());
        noaa.blocks = kpProduct(2.33, Map.of("2027-01-14T21:00", 6.33));
        noaa.readingsStopAt = utc("2027-01-14T21:30").toInstant();

        List<Transition> transitions = pollEveryFiveMinutes("2027-01-14T09:00", "2027-01-15T11:00");

        assertThat(transitions).containsExactly(
                new Transition("2027-01-14T09:00", "day", AuroraStateCache.Action.NOTIFY),
                new Transition("2027-01-15T04:00", "night", AuroraStateCache.Action.CLEAR));
        assertThat(claudeCalls).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // The daylight poll's snapshot
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a heads-up held all day reads the full snapshot once, for its one scoring")
    void daylightHeadsUp_readsTheSnapshotOnce() {
        kielderIsEligibleAt(properties.getBortleThreshold().getModerate());
        noaa.blocks = kpProduct(2.33, Map.of("2027-01-14T21:00", 6.33));

        // Every poll from 09:00 to 17:25 is before dusk (17:25:42).
        List<Transition> transitions = pollEveryFiveMinutes("2027-01-14T09:00", "2027-01-14T17:30");

        assertThat(transitions).containsExactly(
                new Transition("2027-01-14T09:00", "day", AuroraStateCache.Action.NOTIFY));
        assertThat(noaa.fetchAllCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("a storm after dawn is never raised — nothing reads past tonight's dark window")
    void wholeNight_stormAfterDawn_isNeverRaised() {
        // Kp 7.33 at 09:00-12:00 on the 15th: two hours after dawn, and over before the next dusk.
        // Anything reading tonight to a horizon of its own would raise it, and pay for it.
        kielderIsEligibleAt(properties.getBortleThreshold().getModerate());
        noaa.blocks = kpProduct(2.33, Map.of("2027-01-15T09:00", 7.33));

        List<Transition> transitions = pollEveryFiveMinutes("2027-01-14T09:00", "2027-01-15T12:00");

        assertThat(transitions).isEmpty();
        assertThat(claudeCalls).isEmpty();

        // Positive control: the next night, an OVATION substorm is caught.
        noaa.ovation = OVATION_SUBSTORM;
        clock.set("2027-01-15T22:00");
        assertThat(poll()).isEqualTo(new AfterPoll(1, true, 1));
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
     * A NOTIFY or a CLEAR, and the poll that made it.
     *
     * @param at     the poll's instant, as a UTC local date-time
     * @param kind   "day" or "night"
     * @param action NOTIFY or CLEAR
     */
    private record Transition(String at, String kind, AuroraStateCache.Action action) {
    }

    /** Kielder is the one location on the Bortle roster a NOTIFY at {@code bortleThreshold} reads. */
    private void kielderIsEligibleAt(int bortleThreshold) {
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(bortleThreshold))
                .thenReturn(List.of(kielder));
    }

    private AfterPoll poll() {
        job.executePoll();
        return new AfterPoll(claudeCalls.size(), stateCache.isActive(),
                stateCache.getCachedScores().size());
    }

    /**
     * Polls every five minutes from {@code from} until {@code until} and returns every NOTIFY and
     * CLEAR in order. At every poll it also checks the two properties the flap broke: no poll pays
     * for more than one Claude call, and the status shows ACTIVE exactly when it has scores.
     */
    private List<Transition> pollEveryFiveMinutes(String from, String until) {
        List<Transition> transitions = new ArrayList<>();
        clock.set(from);
        while (clock.instant().isBefore(utc(until).toInstant())) {
            int callsBefore = claudeCalls.size();
            AuroraPollOutcome outcome = job.executePoll();
            String at = LocalDateTime.ofInstant(clock.instant(), UTC).toString();
            assertThat(claudeCalls.size() - callsBefore)
                    .as("Claude calls paid for by the poll at %s", at)
                    .isLessThanOrEqualTo(1);
            assertThat(stateCache.getCachedScores().isEmpty())
                    .as("at %s the state is %s", at, stateCache.isActive() ? "ACTIVE" : "IDLE")
                    .isEqualTo(!stateCache.isActive());
            if (outcome.action() == AuroraStateCache.Action.NOTIFY
                    || outcome.action() == AuroraStateCache.Action.CLEAR) {
                transitions.add(new Transition(at, outcome.dark() ? "night" : "day", outcome.action()));
            }
            clock.advance(POLL_INTERVAL);
        }
        return transitions;
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
     * A block's reading is its product value unless {@link #published} gives it another, as when NOAA
     * revises an estimate. From {@link #readingsStopAt} the readings feed stops, and the client keeps
     * serving what it had fetched by then, as it does through an outage.
     */
    private final class FakeNoaa extends NoaaSwpcClient {

        private List<KpForecast> blocks = List.of();
        /** Published readings that differ from the product, by block start (UTC local date-time). */
        private Map<String, Double> published = Map.of();
        /** When the readings feed stops, or {@code null} while it keeps publishing. */
        private Instant readingsStopAt;
        private double ovation = OVATION_QUIET;
        /** How many full snapshots have been read. */
        private int fetchAllCalls;

        FakeNoaa() {
            super(null, new AuroraProperties(), null, Clock.systemUTC());
        }

        @Override
        public List<KpForecast> fetchKpForecast() {
            return blocks;
        }

        @Override
        public SpaceWeatherData fetchAll() {
            fetchAllCalls++;
            Instant now = clock.instant();
            Instant readingsAsOf = readingsStopAt != null && readingsStopAt.isBefore(now)
                    ? readingsStopAt : now;
            List<KpReading> readings = blocks.stream()
                    .filter(block -> !block.to().plus(READING_LAG).toInstant().isAfter(readingsAsOf))
                    .map(block -> new KpReading(block.from(), published.getOrDefault(
                            block.from().toLocalDateTime().toString(), block.kp())))
                    .toList();
            return new SpaceWeatherData(readings, blocks,
                    new OvationReading(now.atZone(UTC), ovation, 55.0), List.of(), List.of());
        }
    }
}
