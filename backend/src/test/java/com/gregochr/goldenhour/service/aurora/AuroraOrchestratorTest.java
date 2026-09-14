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
import com.gregochr.goldenhour.model.TonightWindow;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import com.gregochr.goldenhour.service.evaluation.EvaluationResult;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuroraOrchestrator}: how each path reads tonight, the levels they reach, and
 * the pipeline a NOTIFY drives.
 *
 * <p>The state machine is a mock here, so these tests pin what each path asks of it. What that does
 * over consecutive polls against the real one is {@code AuroraPollingCycleTest}'s job.
 *
 * <p>All instants are in January 2027, and the clock is pinned there, so nothing depends on the day
 * the suite runs.
 */
@ExtendWith(MockitoExtension.class)
class AuroraOrchestratorTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** Tonight's dark window in these tests: 17:30 on 14 January 2027 to 07:00 on the 15th. */
    private static final TonightWindow TONIGHT =
            new TonightWindow(utc("2027-01-14T17:30"), utc("2027-01-15T07:00"));

    /** What the orchestrator's own clock reads — only {@code deriveAlertLevel} (the batch) uses it. */
    private static final Instant BATCH_NOW = Instant.parse("2027-01-14T22:00:00Z");

    @Mock
    private NoaaSwpcClient noaaClient;
    @Mock
    private WeatherTriageService weatherTriage;
    @Mock
    private AuroraStateCache stateCache;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private EvaluationService evaluationService;
    @Mock
    private ModelSelectionService modelSelectionService;

    private AuroraOrchestrator orchestrator;
    private AuroraProperties properties;

    private final LocationEntity kielder = LocationEntity.builder()
            .id(1L).name("Kielder").lat(55.23).lon(-2.58).bortleClass(2).build();

    @BeforeEach
    void setUp() {
        properties = new AuroraProperties(); // Kp 5, OVATION 20%, 6 h batch horizon, Bortle 4/5
        orchestrator = new AuroraOrchestrator(noaaClient, weatherTriage, stateCache,
                locationRepository, properties, evaluationService, modelSelectionService,
                Clock.fixed(BATCH_NOW, UTC));
    }

    // -------------------------------------------------------------------------
    // maxKpRestOfTonight — the one figure both paths read tonight through
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a block that is over does not count, even though it overlapped tonight's darkness")
    void maxKpRestOfTonight_blockAlreadyOver_doesNotCount() {
        List<KpForecast> blocks = List.of(
                block("2027-01-14T15:00", 6.00),   // overlapped 17:30-18:00, over at 18:00
                block("2027-01-14T21:00", 3.67));  // running at 22:00

        assertThat(AuroraOrchestrator.maxKpRestOfTonight(blocks, TONIGHT, utc("2027-01-14T22:00")))
                .isEqualTo(3.67);
    }

    @Test
    @DisplayName("a block ending exactly now is over; one starting exactly now is running")
    void maxKpRestOfTonight_blockBoundaryAtNow_countsOnlyTheBlockStarting() {
        List<KpForecast> blocks = List.of(
                block("2027-01-14T18:00", 6.00),   // ends 21:00
                block("2027-01-14T21:00", 3.00));  // starts 21:00

        assertThat(AuroraOrchestrator.maxKpRestOfTonight(blocks, TONIGHT, utc("2027-01-14T21:00")))
                .isEqualTo(3.00);
        assertThat(AuroraOrchestrator.maxKpRestOfTonight(blocks, TONIGHT,
                utc("2027-01-14T20:59")))
                .as("a minute earlier the Kp 6 block is still running")
                .isEqualTo(6.00);
    }

    @Test
    @DisplayName("the running block and every block still to come before dawn count")
    void maxKpRestOfTonight_countsRunningAndFutureBlocksBeforeDawn() {
        List<KpForecast> blocks = List.of(
                block("2027-01-14T21:00", 3.33),
                block("2027-01-15T03:00", 5.67),   // ten hours ahead of 17:45 — still tonight
                block("2027-01-15T06:00", 4.67));

        assertThat(AuroraOrchestrator.maxKpRestOfTonight(blocks, TONIGHT, utc("2027-01-14T17:45")))
                .isEqualTo(5.67);
    }

    @Test
    @DisplayName("in daylight, tonight starts at dusk: a block running now but over by dusk does not count")
    void maxKpRestOfTonight_beforeDusk_countsFromDusk() {
        List<KpForecast> blocks = List.of(
                block("2027-01-14T12:00", 7.00),   // running at 13:00, over at 15:00
                block("2027-01-14T15:00", 5.33));  // runs on into darkness until 18:00

        assertThat(AuroraOrchestrator.maxKpRestOfTonight(blocks, TONIGHT, utc("2027-01-14T13:00")))
                .isEqualTo(5.33);
    }

    @Test
    @DisplayName("a block starting at or after dawn is not tonight's")
    void maxKpRestOfTonight_blockStartingAtDawn_doesNotCount() {
        TonightWindow dawnOnABlockEdge =
                new TonightWindow(utc("2027-01-14T17:30"), utc("2027-01-15T06:00"));
        List<KpForecast> blocks = List.of(
                block("2027-01-15T03:00", 4.33),
                block("2027-01-15T06:00", 8.00));  // starts exactly at dawn

        assertThat(AuroraOrchestrator.maxKpRestOfTonight(blocks, dawnOnABlockEdge,
                utc("2027-01-14T22:00")))
                .isEqualTo(4.33);
    }

    @Test
    @DisplayName("once dawn has come nothing of tonight is left, even a block still running")
    void maxKpRestOfTonight_atDawn_isZero() {
        List<KpForecast> blocks = List.of(block("2027-01-15T06:00", 6.00)); // 06:00-09:00

        assertThat(AuroraOrchestrator.maxKpRestOfTonight(blocks, TONIGHT, TONIGHT.dawn()))
                .isEqualTo(0.0);
        assertThat(AuroraOrchestrator.maxKpRestOfTonight(blocks, TONIGHT,
                TONIGHT.dawn().minusMinutes(1)))
                .as("a minute before dawn the block still has a minute of darkness")
                .isEqualTo(6.00);
    }

    @Test
    @DisplayName("an empty forecast leaves nothing for the rest of tonight")
    void maxKpRestOfTonight_noBlocks_isZero() {
        assertThat(AuroraOrchestrator.maxKpRestOfTonight(List.of(), TONIGHT, utc("2027-01-14T22:00")))
                .isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // currentKp — the real-time path's "now", continuous across block boundaries
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the running block's Kp counts as now, above an older published reading")
    void currentKp_runningBlockAboveReading_isTheRunningBlock() {
        SpaceWeatherData data = snapshot(List.of(reading("2027-01-14T18:00", 3.00)),
                List.of(block("2027-01-14T18:00", 3.00), block("2027-01-14T21:00", 6.00)), 0.0);

        assertThat(AuroraOrchestrator.currentKp(data, utc("2027-01-14T22:00"))).isEqualTo(6.00);
        assertThat(AuroraOrchestrator.currentKp(data, utc("2027-01-14T21:00")))
                .as("a block counts as now from the instant it starts")
                .isEqualTo(6.00);
    }

    @Test
    @DisplayName("a block that has just ended still counts while its reading is unpublished")
    void currentKp_justEndedBlock_bridgesTheUnpublishedReading() {
        // 00:10: the 21:00-24:00 storm block ended ten minutes ago, but the latest published reading
        // is still the block before it. Without the bridge the level would dip here and bounce back.
        SpaceWeatherData data = snapshot(List.of(reading("2027-01-14T18:00", 3.00)),
                List.of(block("2027-01-14T18:00", 3.00), block("2027-01-14T21:00", 6.33),
                        block("2027-01-15T00:00", 3.00)),
                0.0);

        assertThat(AuroraOrchestrator.currentKp(data, utc("2027-01-15T00:10"))).isEqualTo(6.33);
    }

    @Test
    @DisplayName("a block that ended a whole block-length ago no longer counts as now")
    void currentKp_blockEndedABlockLengthAgo_doesNotCount() {
        SpaceWeatherData data = snapshot(List.of(reading("2027-01-14T21:00", 3.00)),
                List.of(block("2027-01-14T18:00", 7.00), block("2027-01-14T21:00", 3.00),
                        block("2027-01-15T00:00", 3.00)),
                0.0);

        // 18:00-21:00 ended at 21:00; three hours later, at midnight, it has dropped out.
        assertThat(AuroraOrchestrator.currentKp(data, utc("2027-01-15T00:00"))).isEqualTo(3.00);
        assertThat(AuroraOrchestrator.currentKp(data, utc("2027-01-14T23:59")))
                .as("a minute earlier it is still the block that has just ended")
                .isEqualTo(7.00);
    }

    @Test
    @DisplayName("a published reading above every recent block wins")
    void currentKp_readingAboveBlocks_isTheReading() {
        SpaceWeatherData data = snapshot(List.of(reading("2027-01-14T18:00", 7.33)),
                List.of(block("2027-01-14T21:00", 4.00)), 0.0);

        assertThat(AuroraOrchestrator.currentKp(data, utc("2027-01-14T22:00"))).isEqualTo(7.33);
    }

    @Test
    @DisplayName("no readings and no blocks give Kp 0")
    void currentKp_nothing_isZero() {
        assertThat(AuroraOrchestrator.currentKp(snapshot(List.of(), List.of(), 0.0),
                utc("2027-01-14T22:00")))
                .isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // The invariant: after dark the real-time level is never below the lookahead's
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the real-time level is never below the lookahead's, for any product, instant or threshold")
    void realtimeLevel_isNeverBelowTheLookaheadLevel() {
        // The flap was the real-time path reading tonight lower than the lookahead did, so each poll
        // NOTIFIED and then CLEARED. The real-time level is built on the lookahead's own figure; this
        // sweep keeps anyone from quietly giving it a shorter horizon again.
        Random random = new Random(20_270_114L);
        List<ZonedDateTime> instants = sweepInstants();
        for (double threshold : new double[] {4.5, 5.0, 6.0}) {
            properties.getTriggers().setKpThreshold(threshold);
            for (int product = 0; product < 150; product++) {
                SpaceWeatherData data = randomProduct(random);
                for (ZonedDateTime now : instants) {
                    AlertLevel lookahead = orchestrator.levelForKp(
                            AuroraOrchestrator.maxKpRestOfTonight(data.kpForecast(), TONIGHT, now));
                    AlertLevel realtime = orchestrator.realtimeLevel(data, TONIGHT, now);
                    assertThat(realtime.severity())
                            .as("threshold %s, product %d, %s: real-time %s against lookahead %s",
                                    threshold, product, now, realtime, lookahead)
                            .isGreaterThanOrEqualTo(lookahead.severity());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // runNightPoll — lookahead then real-time, over one snapshot
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a night poll reads NOAA once and hands the same snapshot to both paths")
    void runNightPoll_readsOneSnapshotForBothPaths() {
        SpaceWeatherData data = peakInTheSmallHours(3.0);
        when(noaaClient.fetchAll()).thenReturn(data);
        when(stateCache.evaluate(AlertLevel.MODERATE)).thenReturn(
                evaluation(AuroraStateCache.Action.NOTIFY, AlertLevel.MODERATE),
                evaluation(AuroraStateCache.Action.SUPPRESS, AlertLevel.MODERATE));
        scoringReturns(properties.getBortleThreshold().getModerate(), AlertLevel.MODERATE);

        orchestrator.runNightPoll(TONIGHT, utc("2027-01-14T17:45"));

        verify(noaaClient).fetchAll();
        verify(noaaClient, never()).fetchKpForecast();
        assertThat(theClaudeTask().spaceWeather())
                .as("the lookahead scores with the poll's snapshot, not a second fetch")
                .isSameAs(data);
    }

    @Test
    @DisplayName("a night poll after a storm has ended leaves the lookahead silent and the real-time path at MINOR")
    void runNightPoll_stormAlreadyOver_noAlert() {
        when(noaaClient.fetchAll()).thenReturn(afterTheStorm(5.0));
        when(stateCache.evaluate(AlertLevel.MINOR))
                .thenReturn(evaluation(AuroraStateCache.Action.NONE, null));

        AuroraOrchestrator.PollOutcome outcome =
                orchestrator.runNightPoll(TONIGHT, utc("2027-01-14T22:00"));

        assertThat(outcome).isEqualTo(new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.NONE, AuroraStateCache.Action.NONE));
        // Only the real-time path consults the state machine: the storm block no longer counts for
        // the lookahead, and nothing left tonight reaches MODERATE.
        verify(stateCache).evaluate(AlertLevel.MINOR);
        verifyNoMoreInteractions(stateCache);
        verifyNoInteractions(evaluationService, weatherTriage);
    }

    @Test
    @DisplayName("a night poll keeps a heads-up for a peak ten hours away: lookahead NOTIFY, real-time SUPPRESS")
    void runNightPoll_peakBeyondSixHours_realtimeAgreesWithLookahead() {
        when(noaaClient.fetchAll()).thenReturn(peakInTheSmallHours(3.0));
        when(stateCache.evaluate(AlertLevel.MODERATE)).thenReturn(
                evaluation(AuroraStateCache.Action.NOTIFY, AlertLevel.MODERATE),
                evaluation(AuroraStateCache.Action.SUPPRESS, AlertLevel.MODERATE));
        scoringReturns(properties.getBortleThreshold().getModerate(), AlertLevel.MODERATE);

        AuroraOrchestrator.PollOutcome outcome =
                orchestrator.runNightPoll(TONIGHT, utc("2027-01-14T17:45"));

        assertThat(outcome).isEqualTo(new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.NOTIFY, AuroraStateCache.Action.SUPPRESS));
        InOrder order = inOrder(stateCache);
        order.verify(stateCache).evaluate(AlertLevel.MODERATE);          // the lookahead
        order.verify(stateCache).updateTrigger(TriggerType.FORECAST_LOOKAHEAD, 5.67);
        order.verify(stateCache).evaluate(AlertLevel.MODERATE);          // the real-time path
        EvaluationTask.Aurora task = theClaudeTask();
        assertThat(task.triggerType()).isEqualTo(TriggerType.FORECAST_LOOKAHEAD);
        assertThat(task.tonightWindow()).isEqualTo(TONIGHT);
        assertThat(task.alertLevel()).isEqualTo(AlertLevel.MODERATE);
    }

    @Test
    @DisplayName("the real-time path escalates on the Kp for now, worded as happening now with that Kp")
    void runNightPoll_currentKpAboveForecast_realtimeEscalates() {
        SpaceWeatherData data = snapshot(List.of(reading("2027-01-14T18:00", 7.33)),
                List.of(block("2027-01-14T21:00", 5.33), block("2027-01-15T00:00", 5.00)), 3.0);
        when(noaaClient.fetchAll()).thenReturn(data);
        when(stateCache.evaluate(AlertLevel.MODERATE))
                .thenReturn(evaluation(AuroraStateCache.Action.SUPPRESS, AlertLevel.MODERATE));
        when(stateCache.evaluate(AlertLevel.STRONG))
                .thenReturn(evaluation(AuroraStateCache.Action.NOTIFY, AlertLevel.STRONG));
        scoringReturns(properties.getBortleThreshold().getStrong(), AlertLevel.STRONG);

        AuroraOrchestrator.PollOutcome outcome =
                orchestrator.runNightPoll(TONIGHT, utc("2027-01-14T22:00"));

        assertThat(outcome).isEqualTo(new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.SUPPRESS, AuroraStateCache.Action.NOTIFY));
        verify(stateCache).updateTrigger(TriggerType.REALTIME, 7.33);
        EvaluationTask.Aurora task = theClaudeTask();
        assertThat(task.triggerType()).isEqualTo(TriggerType.REALTIME);
        assertThat(task.tonightWindow()).isNull();
        assertThat(task.alertLevel()).isEqualTo(AlertLevel.STRONG);
    }

    @Test
    @DisplayName("an OVATION substorm on quiet Kp raises the real-time path to MODERATE and scores it")
    void runNightPoll_ovationSubstorm_realtimeNotifies() {
        when(noaaClient.fetchAll()).thenReturn(afterTheStorm(35.0));
        when(stateCache.evaluate(AlertLevel.MODERATE))
                .thenReturn(evaluation(AuroraStateCache.Action.NOTIFY, AlertLevel.MODERATE));
        scoringReturns(properties.getBortleThreshold().getModerate(), AlertLevel.MODERATE);

        AuroraOrchestrator.PollOutcome outcome =
                orchestrator.runNightPoll(TONIGHT, utc("2027-01-14T22:00"));

        assertThat(outcome).isEqualTo(new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.NONE, AuroraStateCache.Action.NOTIFY));
        // The Kp that goes with it is the Kp for now: 18:00-21:00's 4.00, just ended.
        verify(stateCache).updateTrigger(TriggerType.REALTIME, 4.00);
        assertThat(theClaudeTask().triggerType()).isEqualTo(TriggerType.REALTIME);
    }

    @Test
    @DisplayName("just after a storm block ends the real-time level holds, instead of dipping to a CLEAR")
    void runNightPoll_stormBlockJustEnded_realtimeHoldsTheLevel() {
        // 00:10 on the 15th. The 21:00-24:00 block read Kp 6.33 and ended ten minutes ago; its
        // reading is not out yet, and nothing ahead of it reaches 4.
        SpaceWeatherData data = snapshot(List.of(reading("2027-01-14T18:00", 3.00)),
                List.of(block("2027-01-14T21:00", 6.33), block("2027-01-15T00:00", 3.00),
                        block("2027-01-15T03:00", 2.67), block("2027-01-15T06:00", 2.33)),
                5.0);
        when(noaaClient.fetchAll()).thenReturn(data);
        when(stateCache.evaluate(AlertLevel.MODERATE))
                .thenReturn(evaluation(AuroraStateCache.Action.SUPPRESS, AlertLevel.MODERATE));

        AuroraOrchestrator.PollOutcome outcome =
                orchestrator.runNightPoll(TONIGHT, utc("2027-01-15T00:10"));

        assertThat(outcome).isEqualTo(new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.NONE, AuroraStateCache.Action.SUPPRESS));
        verify(stateCache).evaluate(AlertLevel.MODERATE);
        verifyNoMoreInteractions(stateCache);
    }

    @Test
    @DisplayName("a real-time CLEAR scores nothing")
    void runNightPoll_realtimeClear_scoresNothing() {
        SpaceWeatherData data = snapshot(List.of(reading("2027-01-15T00:00", 2.00)),
                List.of(block("2027-01-15T03:00", 2.33), block("2027-01-15T06:00", 2.00)), 3.0);
        when(noaaClient.fetchAll()).thenReturn(data);
        when(stateCache.evaluate(AlertLevel.QUIET))
                .thenReturn(evaluation(AuroraStateCache.Action.CLEAR, null));

        AuroraOrchestrator.PollOutcome outcome =
                orchestrator.runNightPoll(TONIGHT, utc("2027-01-15T04:00"));

        assertThat(outcome.realtime()).isEqualTo(AuroraStateCache.Action.CLEAR);
        verifyNoInteractions(locationRepository, weatherTriage, evaluationService);
    }

    @Test
    @DisplayName("a real-time NOTIFY gives overcast-rejected locations 1★ without asking Claude")
    void runNightPoll_realtimeNotifyAllOvercast_cachesOneStarWithoutClaude() {
        when(noaaClient.fetchAll()).thenReturn(afterTheStorm(35.0));
        when(stateCache.evaluate(AlertLevel.MODERATE))
                .thenReturn(evaluation(AuroraStateCache.Action.NOTIFY, AlertLevel.MODERATE));
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(
                properties.getBortleThreshold().getModerate()))
                .thenReturn(List.of(kielder));
        when(weatherTriage.triage(List.of(kielder))).thenReturn(new WeatherTriageService.TriageResult(
                List.of(), List.of(kielder), Map.of(kielder, 95)));

        orchestrator.runNightPoll(TONIGHT, utc("2027-01-14T22:00"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuroraForecastScore>> scores = ArgumentCaptor.forClass(List.class);
        verify(stateCache).updateScores(scores.capture());
        assertThat(scores.getValue()).singleElement().satisfies(score -> {
            assertThat(score.location()).isSameAs(kielder);
            assertThat(score.stars()).isEqualTo(1);
            assertThat(score.cloudPercent()).isEqualTo(95);
        });
        verify(stateCache).updateLocationCounts(1, 0);
        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("a failed Claude call still caches the overcast 1★ scores and the NOTIFY stands")
    void runNightPoll_claudeErrored_cachesOnlyTheRejected() {
        LocationEntity cloudy = LocationEntity.builder()
                .id(2L).name("Cloudy").lat(55.0).lon(-2.0).bortleClass(3).build();
        when(noaaClient.fetchAll()).thenReturn(afterTheStorm(35.0));
        when(stateCache.evaluate(AlertLevel.MODERATE))
                .thenReturn(evaluation(AuroraStateCache.Action.NOTIFY, AlertLevel.MODERATE));
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(
                properties.getBortleThreshold().getModerate()))
                .thenReturn(List.of(kielder, cloudy));
        when(weatherTriage.triage(List.of(kielder, cloudy)))
                .thenReturn(new WeatherTriageService.TriageResult(
                        List.of(kielder), List.of(cloudy), Map.of(kielder, 10, cloudy, 100)));
        when(modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION))
                .thenReturn(EvaluationModel.HAIKU);
        when(evaluationService.evaluateNow(any(EvaluationTask.Aurora.class),
                eq(BatchTriggerSource.SCHEDULED)))
                .thenReturn(new EvaluationResult.Errored("anthropic_529", "overloaded"));

        AuroraOrchestrator.PollOutcome outcome =
                orchestrator.runNightPoll(TONIGHT, utc("2027-01-14T22:00"));

        assertThat(outcome.realtime()).isEqualTo(AuroraStateCache.Action.NOTIFY);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuroraForecastScore>> scores = ArgumentCaptor.forClass(List.class);
        verify(stateCache).updateScores(scores.capture());
        assertThat(scores.getValue()).singleElement()
                .satisfies(score -> assertThat(score.location()).isSameAs(cloudy));
    }

    @Test
    @DisplayName("no Bortle-eligible locations: the NOTIFY caches an empty score list and asks nobody")
    void runNightPoll_noCandidates_cachesEmptyScores() {
        when(noaaClient.fetchAll()).thenReturn(afterTheStorm(35.0));
        when(stateCache.evaluate(AlertLevel.MODERATE))
                .thenReturn(evaluation(AuroraStateCache.Action.NOTIFY, AlertLevel.MODERATE));
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(
                properties.getBortleThreshold().getModerate()))
                .thenReturn(List.of());

        orchestrator.runNightPoll(TONIGHT, utc("2027-01-14T22:00"));

        verify(stateCache).updateScores(List.of());
        verifyNoInteractions(weatherTriage, evaluationService);
    }

    @Test
    @DisplayName("a failed NOAA fetch skips both paths and leaves the state machine alone")
    void runNightPoll_noaaFetchFails_skipsBothPaths() {
        when(noaaClient.fetchAll()).thenThrow(new RuntimeException("network error"));

        AuroraOrchestrator.PollOutcome outcome =
                orchestrator.runNightPoll(TONIGHT, utc("2027-01-14T22:00"));

        assertThat(outcome).isEqualTo(new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.NONE, AuroraStateCache.Action.NONE));
        verifyNoInteractions(stateCache);
        verify(noaaClient, never()).fetchKpForecast();
    }

    // -------------------------------------------------------------------------
    // runForecastLookahead — the daylight poll
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("daylight: nothing tonight reaches MODERATE, so the state machine is not consulted")
    void runForecastLookahead_belowThreshold_returnsNone() {
        when(noaaClient.fetchKpForecast()).thenReturn(List.of(
                block("2027-01-14T18:00", 4.67), block("2027-01-15T00:00", 3.33)));

        AuroraStateCache.Action action =
                orchestrator.runForecastLookahead(TONIGHT, utc("2027-01-14T12:00"));

        assertThat(action).isEqualTo(AuroraStateCache.Action.NONE);
        verifyNoInteractions(stateCache);
    }

    @Test
    @DisplayName("daylight: Kp 5.67 tonight NOTIFIES and scores for planning, with tonight's window")
    void runForecastLookahead_moderateTonight_notifiesAndScoresForPlanning() {
        SpaceWeatherData scoringData = peakInTheSmallHours(3.0);
        when(noaaClient.fetchKpForecast()).thenReturn(scoringData.kpForecast());
        when(stateCache.evaluate(AlertLevel.MODERATE))
                .thenReturn(evaluation(AuroraStateCache.Action.NOTIFY, AlertLevel.MODERATE));
        when(noaaClient.fetchAll()).thenReturn(scoringData);
        scoringReturns(properties.getBortleThreshold().getModerate(), AlertLevel.MODERATE);

        AuroraStateCache.Action action =
                orchestrator.runForecastLookahead(TONIGHT, utc("2027-01-14T12:00"));

        assertThat(action).isEqualTo(AuroraStateCache.Action.NOTIFY);
        verify(stateCache).updateTrigger(TriggerType.FORECAST_LOOKAHEAD, 5.67);
        EvaluationTask.Aurora task = theClaudeTask();
        assertThat(task.triggerType()).isEqualTo(TriggerType.FORECAST_LOOKAHEAD);
        assertThat(task.tonightWindow()).isEqualTo(TONIGHT);
        assertThat(task.spaceWeather()).isSameAs(scoringData);
        assertThat(task.viableLocations()).containsExactly(kielder);
    }

    @Test
    @DisplayName("daylight: an alert already active at this level is SUPPRESSED without refetching or scoring")
    void runForecastLookahead_alreadyActive_suppressesWithoutScoring() {
        when(noaaClient.fetchKpForecast()).thenReturn(List.of(block("2027-01-14T21:00", 5.33)));
        when(stateCache.evaluate(AlertLevel.MODERATE))
                .thenReturn(evaluation(AuroraStateCache.Action.SUPPRESS, AlertLevel.MODERATE));

        AuroraStateCache.Action action =
                orchestrator.runForecastLookahead(TONIGHT, utc("2027-01-14T12:00"));

        assertThat(action).isEqualTo(AuroraStateCache.Action.SUPPRESS);
        verify(noaaClient, never()).fetchAll();
        verifyNoInteractions(locationRepository, evaluationService);
    }

    @Test
    @DisplayName("daylight: a Kp 7 block running now but over by dusk raises nothing")
    void runForecastLookahead_stormOverBeforeDusk_returnsNone() {
        when(noaaClient.fetchKpForecast()).thenReturn(List.of(
                block("2027-01-14T12:00", 7.00), block("2027-01-14T18:00", 3.00)));

        AuroraStateCache.Action action =
                orchestrator.runForecastLookahead(TONIGHT, utc("2027-01-14T13:00"));

        assertThat(action).isEqualTo(AuroraStateCache.Action.NONE);
        verifyNoInteractions(stateCache);
    }

    @Test
    @DisplayName("daylight: Kp 8 tonight is STRONG and draws on the STRONG Bortle roster")
    void runForecastLookahead_kp8Tonight_notifiesStrong() {
        SpaceWeatherData scoringData = snapshot(List.of(reading("2027-01-14T09:00", 2.00)),
                List.of(block("2027-01-15T00:00", 8.00)), 0.0);
        when(noaaClient.fetchKpForecast()).thenReturn(scoringData.kpForecast());
        when(stateCache.evaluate(AlertLevel.STRONG))
                .thenReturn(evaluation(AuroraStateCache.Action.NOTIFY, AlertLevel.STRONG));
        when(noaaClient.fetchAll()).thenReturn(scoringData);
        scoringReturns(properties.getBortleThreshold().getStrong(), AlertLevel.STRONG);

        AuroraStateCache.Action action =
                orchestrator.runForecastLookahead(TONIGHT, utc("2027-01-14T12:00"));

        assertThat(action).isEqualTo(AuroraStateCache.Action.NOTIFY);
        assertThat(theClaudeTask().alertLevel()).isEqualTo(AlertLevel.STRONG);
    }

    @Test
    @DisplayName("daylight: a lowered Kp threshold makes Kp 4.67 MODERATE for the lookahead too")
    void runForecastLookahead_loweredThreshold_mapsLikeTheRealtimePath() {
        // The lookahead used to map through a fixed Kp 5 while the real-time path used the setting,
        // so at 4.5 the lookahead would have evaluated MINOR — a CLEAR — where the real-time path
        // evaluated MODERATE: the same flap, reversed.
        properties.getTriggers().setKpThreshold(4.5);
        when(noaaClient.fetchKpForecast()).thenReturn(List.of(block("2027-01-14T21:00", 4.67)));
        when(stateCache.evaluate(AlertLevel.MODERATE))
                .thenReturn(evaluation(AuroraStateCache.Action.SUPPRESS, AlertLevel.MODERATE));

        orchestrator.runForecastLookahead(TONIGHT, utc("2027-01-14T12:00"));

        verify(stateCache).evaluate(AlertLevel.MODERATE);
        verifyNoMoreInteractions(stateCache);
    }

    @Test
    @DisplayName("daylight: a failed forecast fetch skips the lookahead")
    void runForecastLookahead_fetchFails_returnsNone() {
        when(noaaClient.fetchKpForecast()).thenThrow(new RuntimeException("network error"));

        AuroraStateCache.Action action =
                orchestrator.runForecastLookahead(TONIGHT, utc("2027-01-14T12:00"));

        assertThat(action).isEqualTo(AuroraStateCache.Action.NONE);
        verifyNoInteractions(stateCache);
    }

    @Test
    @DisplayName("daylight: if the scoring fetch fails after a NOTIFY, nothing is scored")
    void runForecastLookahead_scoringFetchFails_scoresNothing() {
        when(noaaClient.fetchKpForecast()).thenReturn(List.of(block("2027-01-14T21:00", 5.33)));
        when(stateCache.evaluate(AlertLevel.MODERATE))
                .thenReturn(evaluation(AuroraStateCache.Action.NOTIFY, AlertLevel.MODERATE));
        when(noaaClient.fetchAll()).thenThrow(new RuntimeException("network error"));

        AuroraStateCache.Action action =
                orchestrator.runForecastLookahead(TONIGHT, utc("2027-01-14T12:00"));

        assertThat(action).isEqualTo(AuroraStateCache.Action.NONE);
        verify(stateCache, never()).updateTrigger(any(), anyDouble());
        verifyNoInteractions(locationRepository, evaluationService);
    }

    // -------------------------------------------------------------------------
    // deriveAlertLevel — the aurora batch job's gate (fixed horizon, orchestrator's clock)
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "Kp={0} → {1}")
    @CsvSource({
            "2.0,  QUIET",
            "3.9,  QUIET",
            "4.0,  MINOR",
            "4.9,  MINOR",
            "5.0,  MODERATE",
            "6.9,  MODERATE",
            "7.0,  STRONG",
            "9.0,  STRONG",
    })
    @DisplayName("deriveAlertLevel maps the latest reading to a level (OVATION 0, no forecast)")
    void deriveAlertLevel_kpMappings(double kp, AlertLevel expected) {
        SpaceWeatherData data = snapshot(List.of(reading("2027-01-14T18:00", kp)), List.of(), 0.0);
        assertThat(orchestrator.deriveAlertLevel(data)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "OVATION {0}% with Kp 3 → {1}")
    @CsvSource({
            "19.9, QUIET",
            "20.0, MODERATE",
            "25.0, MODERATE",
    })
    @DisplayName("deriveAlertLevel raises quiet Kp to MODERATE from the OVATION threshold")
    void deriveAlertLevel_ovationThreshold(double ovation, AlertLevel expected) {
        SpaceWeatherData data = snapshot(List.of(reading("2027-01-14T18:00", 3.0)), List.of(), ovation);
        assertThat(orchestrator.deriveAlertLevel(data)).isEqualTo(expected);
    }

    @Test
    @DisplayName("deriveAlertLevel counts a forecast block starting within six hours of its clock")
    void deriveAlertLevel_forecastWithinHorizon_counts() {
        SpaceWeatherData data = snapshot(List.of(reading("2027-01-14T18:00", 2.0)),
                List.of(block("2027-01-15T03:00", 7.5)), 0.0);   // starts 5 h after 22:00
        assertThat(orchestrator.deriveAlertLevel(data)).isEqualTo(AlertLevel.STRONG);
    }

    @Test
    @DisplayName("deriveAlertLevel ignores a forecast block starting beyond six hours of its clock")
    void deriveAlertLevel_forecastBeyondHorizon_ignored() {
        SpaceWeatherData data = snapshot(List.of(reading("2027-01-14T18:00", 2.0)),
                List.of(block("2027-01-15T06:00", 8.0)), 0.0);   // starts 8 h after 22:00
        assertThat(orchestrator.deriveAlertLevel(data)).isEqualTo(AlertLevel.QUIET);
    }

    @Test
    @DisplayName("deriveAlertLevel uses the configured Kp threshold, like both polling paths")
    void deriveAlertLevel_loweredThreshold_mapsKp467ToModerate() {
        properties.getTriggers().setKpThreshold(4.5);
        SpaceWeatherData data = snapshot(List.of(reading("2027-01-14T18:00", 4.67)), List.of(), 0.0);
        assertThat(orchestrator.deriveAlertLevel(data)).isEqualTo(AlertLevel.MODERATE);
    }

    @Test
    @DisplayName("deriveAlertLevel returns QUIET with no readings, no forecast and no OVATION")
    void deriveAlertLevel_noData_returnsQuiet() {
        SpaceWeatherData data = new SpaceWeatherData(List.of(), List.of(), null, List.of(), List.of());
        assertThat(orchestrator.deriveAlertLevel(data)).isEqualTo(AlertLevel.QUIET);
    }

    // -------------------------------------------------------------------------
    // PollOutcome
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a poll outcome is dark exactly when the real-time path ran")
    void pollOutcome_darkWhenRealtimeRan() {
        assertThat(new AuroraOrchestrator.PollOutcome(AuroraStateCache.Action.NONE, null).dark())
                .isFalse();
        assertThat(new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.NONE, AuroraStateCache.Action.NONE).dark())
                .isTrue();
    }

    @Test
    @DisplayName("a poll outcome always has a lookahead action")
    void pollOutcome_requiresLookahead() {
        assertThatThrownBy(() -> new AuroraOrchestrator.PollOutcome(null, AuroraStateCache.Action.NONE))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("lookahead");
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private static ZonedDateTime utc(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(UTC);
    }

    /** A 3-hour block of NOAA's Kp product, starting at {@code fromUtc}. */
    private static KpForecast block(String fromUtc, double kp) {
        ZonedDateTime from = utc(fromUtc);
        return new KpForecast(from, from.plusHours(3), kp);
    }

    /** A published Kp reading, stamped (as NOAA stamps it) with its block's start. */
    private static KpReading reading(String blockStartUtc, double kp) {
        return new KpReading(utc(blockStartUtc), kp);
    }

    private static SpaceWeatherData snapshot(List<KpReading> readings, List<KpForecast> blocks,
            double ovationPercent) {
        return new SpaceWeatherData(readings, blocks,
                new OvationReading(utc("2027-01-14T22:00"), ovationPercent, 55.0),
                List.of(), List.of());
    }

    /**
     * 22:00, after a storm: 15:00-18:00 read Kp 6 and is over, 18:00-21:00 read Kp 4, and nothing
     * still to come tonight reaches 4.
     */
    private static SpaceWeatherData afterTheStorm(double ovationPercent) {
        return snapshot(
                List.of(reading("2027-01-14T15:00", 6.00), reading("2027-01-14T18:00", 4.00)),
                List.of(block("2027-01-14T15:00", 6.00), block("2027-01-14T18:00", 4.00),
                        block("2027-01-14T21:00", 3.67), block("2027-01-15T00:00", 3.33),
                        block("2027-01-15T03:00", 3.00), block("2027-01-15T06:00", 2.67)),
                ovationPercent);
    }

    /**
     * 17:45: Kp 5.67 is predicted for 03:00-06:00, inside tonight but ten hours away. Everything
     * nearer stays under Kp 4.
     */
    private static SpaceWeatherData peakInTheSmallHours(double ovationPercent) {
        return snapshot(
                List.of(reading("2027-01-14T09:00", 2.33), reading("2027-01-14T12:00", 2.67)),
                List.of(block("2027-01-14T15:00", 3.00), block("2027-01-14T18:00", 3.33),
                        block("2027-01-14T21:00", 3.67), block("2027-01-15T00:00", 4.33),
                        block("2027-01-15T03:00", 5.67), block("2027-01-15T06:00", 4.67)),
                ovationPercent);
    }

    /**
     * A random NOAA product: every 3-hour block from midnight on the 14th to midnight on the 16th,
     * mostly quiet with storms scattered through, a random latest reading and a random OVATION.
     */
    private static SpaceWeatherData randomProduct(Random random) {
        List<KpForecast> blocks = new ArrayList<>();
        ZonedDateTime from = utc("2027-01-14T00:00");
        for (int i = 0; i < 16; i++) {
            blocks.add(new KpForecast(from, from.plusHours(3), randomKp(random)));
            from = from.plusHours(3);
        }
        List<KpReading> readings = List.of(reading("2027-01-14T18:00", randomKp(random)));
        return snapshot(readings, blocks, random.nextInt(41));
    }

    /** Kp in thirds, 0 to 9, with about one block in three alert-worthy. */
    private static double randomKp(Random random) {
        return random.nextInt(3) == 0 ? (15 + random.nextInt(13)) / 3.0 : random.nextInt(15) / 3.0;
    }

    /**
     * Every seven minutes from 15:30 to 08:00, which lands off every block edge, plus every block
     * edge, dusk and dawn exactly.
     */
    private static List<ZonedDateTime> sweepInstants() {
        List<ZonedDateTime> instants = new ArrayList<>();
        for (ZonedDateTime t = utc("2027-01-14T15:30"); t.isBefore(utc("2027-01-15T08:00"));
                t = t.plusMinutes(7)) {
            instants.add(t);
        }
        for (ZonedDateTime edge = utc("2027-01-14T15:00"); !edge.isAfter(utc("2027-01-15T09:00"));
                edge = edge.plusHours(3)) {
            instants.add(edge);
        }
        instants.add(TONIGHT.dusk());
        instants.add(TONIGHT.dawn());
        return instants;
    }

    private static AuroraStateCache.Evaluation evaluation(AuroraStateCache.Action action,
            AlertLevel level) {
        return new AuroraStateCache.Evaluation(action, level, null);
    }

    /** Kielder is eligible, clear, and Claude scores it 4★ at {@code scoredAt}. */
    private void scoringReturns(int bortleThreshold, AlertLevel scoredAt) {
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(bortleThreshold))
                .thenReturn(List.of(kielder));
        when(weatherTriage.triage(List.of(kielder))).thenReturn(new WeatherTriageService.TriageResult(
                List.of(kielder), List.of(), Map.of(kielder, 10)));
        when(modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION))
                .thenReturn(EvaluationModel.HAIKU);
        when(evaluationService.evaluateNow(any(EvaluationTask.Aurora.class),
                eq(BatchTriggerSource.SCHEDULED)))
                .thenReturn(new EvaluationResult.Scored(List.of(
                        new AuroraForecastScore(kielder, 4, scoredAt, 10, "Clear to the north", ""))));
    }

    /** The one synchronous Claude task this test's poll submitted. */
    private EvaluationTask.Aurora theClaudeTask() {
        ArgumentCaptor<EvaluationTask.Aurora> captor =
                ArgumentCaptor.forClass(EvaluationTask.Aurora.class);
        verify(evaluationService).evaluateNow(captor.capture(), eq(BatchTriggerSource.SCHEDULED));
        return captor.getValue();
    }
}
