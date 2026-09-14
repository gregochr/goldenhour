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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * Consecutive {@link AuroraPollingJob} polls driven through the REAL {@link AuroraOrchestrator} and
 * the REAL {@link AuroraStateCache}.
 *
 * <p>{@code AuroraPollingJobTest} mocks the orchestrator and {@code AuroraOrchestratorTest} mocks the
 * state machine, so neither can see what one poll does to the next. After dark, one poll runs the
 * forecast lookahead and then the real-time path against the one shared state machine, and the two
 * read tonight differently: the lookahead takes the highest Kp of every 3-hour block that overlaps
 * tonight's dark window, blocks that are already over included, while the real-time path takes the
 * latest completed Kp reading, the OVATION nowcast, and only those blocks still running or starting
 * within the next six hours. When they disagree, a poll NOTIFYs (weather triage and a synchronous
 * Claude call, which is a {@code job_run} and an {@code api_call_log} row in production), then
 * CLEARs the scores it has just paid for, so the next poll starts from IDLE and pays again.
 *
 * <p>Only the edges are faked: the NOAA product, weather triage, the location roster and the Claude
 * call. The sun is stubbed so that the job believes it is dark.
 *
 * <p>⚠️ Times are relative to the real clock. {@code AuroraPollingJob} and
 * {@code AuroraOrchestrator.maxForecastKp} read {@code now()} directly — there is no {@link Clock}
 * seam to pin — so every fixture is anchored at {@link #now}, with at least 30 minutes between any
 * edge the code compares against (a block boundary, the six-hour horizon, dusk, dawn) and the
 * instant a poll runs.
 */
@ExtendWith(MockitoExtension.class)
class AuroraPollingCycleTest {

    /** The job's twilight reference point (Durham; private to {@link AuroraPollingJob}). */
    private static final double DURHAM_LAT = 54.776;
    private static final double DURHAM_LON = -1.575;
    private static final ZoneId UTC = ZoneId.of("UTC");

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
    private SolarCalculator solarCalculator;
    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private final FakeNoaa noaa = new FakeNoaa();
    private final List<EvaluationTask.Aurora> claudeCalls = new ArrayList<>();
    private final LocationEntity kielder = LocationEntity.builder()
            .id(1L).name("Kielder").lat(55.23).lon(-2.58).bortleClass(2).build();

    private ZonedDateTime now;
    private AuroraStateCache stateCache;
    private AuroraPollingJob job;

    @BeforeEach
    void setUp() {
        now = ZonedDateTime.now(ZoneOffset.UTC);
        AuroraProperties properties = new AuroraProperties(); // Kp 5, OVATION 20%, 6 h horizon
        stateCache = new AuroraStateCache();
        AuroraOrchestrator orchestrator = new AuroraOrchestrator(noaa, weatherTriage, stateCache,
                locationRepository, properties, evaluationService, modelSelectionService,
                Clock.systemUTC());
        job = new AuroraPollingJob(orchestrator, properties, solarCalculator, dynamicSchedulerService);

        // What the edges answer IF they are asked. Whether a poll asks is the behaviour under test;
        // each test ends with a positive control that asks exactly once, so no stub goes unused.
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

    @Test
    @DisplayName("a storm that ended earlier tonight is not re-scored on every poll")
    void stormThatEndedEarlierTonight_isNotRescoredOnEveryPoll() {
        // 22:00 UTC on a December night: nautical dusk was at 17:00, nautical dawn is at 07:00.
        itIsDark(Duration.ofHours(5), Duration.ofHours(9));
        // The storm was 15:00-18:00 (observed Kp 6), overlapping the first hour of the dark window.
        // It has been quiet since: 18:00-21:00 read Kp 4, the latest completed reading, and nothing
        // still to come tonight reaches 4.
        List<KpReading> readings = List.of(reading(-10, 3.33), reading(-7, 6.00), reading(-4, 4.00));
        List<KpForecast> blocks = List.of(
                block(-7, 6.00),   // 15-18 observed: the storm, already over
                block(-4, 4.00),   // 18-21 observed
                block(-1, 3.67),   // 21-24 estimated, running now
                block(2, 3.33),    // 00-03 predicted
                block(5, 3.00),    // 03-06 predicted
                block(8, 2.67));   // 06-09 predicted
        noaa.serve(product(readings, blocks, OVATION_QUIET));

        List<AfterPoll> polls = List.of(poll(), poll());

        // Nothing has happened since the storm ended, so there is nothing to score or to show.
        assertThat(polls).containsExactly(new AfterPoll(0, false, 0), new AfterPoll(0, false, 0));

        // Positive control: a fresh substorm in the OVATION nowcast is still caught, once, and worded
        // as a real-time alert. Without it, a change that stopped the pipeline scoring anything at all
        // would pass the assertion above.
        noaa.serve(product(readings, blocks, OVATION_SUBSTORM));
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
    @DisplayName("a forecast peak more than six hours after dusk is not re-scored on every poll")
    void peakBeyondTheRealtimeHorizon_isNotRescoredOnEveryPoll() {
        // 17:30 UTC on a December night: nautical dusk was at 17:00, nautical dawn is at 07:00.
        itIsDark(Duration.ofMinutes(30), Duration.ofMinutes(13 * 60 + 30));
        // Kp 5.67 is predicted for 03:00-06:00, inside tonight's window but ten hours away, beyond
        // the real-time path's six-hour horizon. Everything nearer stays under Kp 4.
        List<KpReading> readings = List.of(reading(-8.5, 2.33), reading(-5.5, 2.67));
        List<KpForecast> blocks = List.of(
                block(-2.5, 3.00),   // 15-18 estimated, running now
                block(0.5, 3.33),    // 18-21 predicted
                block(3.5, 3.67),    // 21-24 predicted: the last block inside the six-hour horizon
                block(6.5, 4.33),    // 00-03 predicted
                block(9.5, 5.67),    // 03-06 predicted: the peak
                block(12.5, 4.67));  // 06-09 predicted, overlapping the last half-hour of the dark
        noaa.serve(product(readings, blocks, OVATION_QUIET));

        AfterPoll first = poll();
        AfterPoll second = poll();

        // Whether the evening carries a heads-up for a peak this far off is an open product decision:
        // say nothing until the real-time path sees the peak, or keep the lookahead's heads-up. Both
        // are coherent. Paying for scores and discarding them within the same poll is not.
        assertThat(first).isIn(new AfterPoll(0, false, 0), new AfterPoll(1, true, 1));
        // A second poll over the same NOAA product changes nothing and costs nothing.
        assertThat(second).isEqualTo(first);

        // Positive control: a fresh substorm in the OVATION nowcast leaves the state ACTIVE and
        // scored, whichever way the heads-up question is answered, and still for one Claude call.
        noaa.serve(product(readings, blocks, OVATION_SUBSTORM));
        assertThat(poll()).isEqualTo(new AfterPoll(1, true, 1));
        verify(weatherTriage).triage(List.of(kielder));
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

    private AfterPoll poll() {
        job.executePoll();
        return new AfterPoll(claudeCalls.size(), stateCache.isActive(), stateCache.getCachedScores().size());
    }

    /**
     * Stubs the sun so that tonight's dark window, as the job derives it, opened {@code sinceDusk}
     * ago and closes in {@code untilDawn}. The job asks for civil twilight and widens it by
     * {@link AuroraPollingJob#NAUTICAL_BUFFER_MINUTES} on each side. The stubs answer for any date
     * because the job takes its dates from the wall clock.
     */
    private void itIsDark(Duration sinceDusk, Duration untilDawn) {
        Duration buffer = Duration.ofMinutes(AuroraPollingJob.NAUTICAL_BUFFER_MINUTES);
        when(solarCalculator.civilDusk(eq(DURHAM_LAT), eq(DURHAM_LON), any(LocalDate.class), eq(UTC)))
                .thenReturn(now.minus(sinceDusk).minus(buffer).toLocalDateTime());
        when(solarCalculator.civilDawn(eq(DURHAM_LAT), eq(DURHAM_LON), any(LocalDate.class), eq(UTC)))
                .thenReturn(now.plus(untilDawn).plus(buffer).toLocalDateTime());
    }

    private ZonedDateTime hoursFromNow(double hours) {
        return now.plusMinutes(Math.round(hours * 60));
    }

    /** A completed 3-hour Kp reading, stamped (as NOAA stamps it) with the block's start. */
    private KpReading reading(double startHoursFromNow, double kp) {
        return new KpReading(hoursFromNow(startHoursFromNow), kp);
    }

    /** A 3-hour block of NOAA's Kp product (observed, estimated or predicted — it keeps all three). */
    private KpForecast block(double startHoursFromNow, double kp) {
        ZonedDateTime from = hoursFromNow(startHoursFromNow);
        return new KpForecast(from, from.plusHours(3), kp);
    }

    private SpaceWeatherData product(List<KpReading> readings, List<KpForecast> blocks,
            double ovationPercent) {
        return new SpaceWeatherData(readings, blocks, new OvationReading(now, ovationPercent, 55.0),
                List.of(), List.of());
    }

    /**
     * Serves one NOAA product to both paths. The real client builds {@code fetchAll()} from the same
     * cached forecast that {@code fetchKpForecast()} returns, so within a poll the lookahead and the
     * real-time path always read the same blocks — the disagreement is in how they read them.
     */
    private static final class FakeNoaa extends NoaaSwpcClient {

        private SpaceWeatherData product;

        FakeNoaa() {
            super(null, new AuroraProperties(), null, Clock.systemUTC());
        }

        void serve(SpaceWeatherData served) {
            this.product = served;
        }

        @Override
        public List<KpForecast> fetchKpForecast() {
            return product.kpForecast();
        }

        @Override
        public SpaceWeatherData fetchAll() {
            return product;
        }
    }
}
