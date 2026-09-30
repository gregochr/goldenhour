package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SlotAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.SlotAtmosphereRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link HotTopicAggregator}.
 *
 * <p>⚠️ Between #940 landing (commit c6e14cc8) and the owner's two-question rule (2026-09-29,
 * see {@code SlotSignalReader}'s class javadoc), this aggregator opened a shared
 * {@code SlotSignalReader.withStabilityWindow} around the whole strategies pass. That
 * dependency is gone entirely — this class no longer holds a {@code SlotSignalReader}
 * reference at all, and {@link #getHotTopics_aggregationIssuesNoStabilityOrDispositionQuery}
 * below proves it end to end through real strategies.
 */
@ExtendWith(MockitoExtension.class)
class HotTopicAggregatorTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 25);
    private static final LocalDate TO = LocalDate.of(2026, 4, 27);

    private HotTopicSimulationService simulationService;
    private final TravelDayService travelDayService = mock(TravelDayService.class);
    private final HotTopicEventEnricher eventEnricher = mock(HotTopicEventEnricher.class);

    @BeforeEach
    void setUp() {
        simulationService = new HotTopicSimulationService();
        // Passthrough — the enrichment of event type/time is exercised in its own test; here we
        // assert the aggregator's collect/filter/sort behaviour on the topics unchanged.
        when(eventEnricher.enrich(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("empty detector list returns empty topics")
    void getHotTopics_noDetectors_returnsEmpty() {
        HotTopicAggregator aggregator = new HotTopicAggregator(
                List.of(), simulationService, travelDayService, eventEnricher);

        List<HotTopic> topics = aggregator.getHotTopics(FROM, TO);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("aggregates topics from multiple detectors")
    void getHotTopics_multipleDetectors_aggregatesAll() {
        HotTopic topic1 = new HotTopic("BLUEBELL", "Bluebell conditions", "detail1",
                FROM, 3, "BLUEBELL", List.of(), null, null);
        HotTopic topic2 = new HotTopic("SPRING_TIDE", "Spring tide", "detail2",
                FROM.plusDays(1), 2, null, List.of(), null, null);

        HotTopicStrategy strategy1 = (from, to) -> List.of(topic1);
        HotTopicStrategy strategy2 = (from, to) -> List.of(topic2);

        HotTopicAggregator aggregator = new HotTopicAggregator(List.of(strategy1, strategy2),
                simulationService, travelDayService, eventEnricher);

        List<HotTopic> topics = aggregator.getHotTopics(FROM, TO);

        assertThat(topics).hasSize(2);
        assertThat(topics).contains(topic1, topic2);
    }

    @Test
    @DisplayName("suppresses topics dated on a travel day, keeps the rest")
    void getHotTopics_travelDay_suppressed() {
        HotTopic away = new HotTopic("SPRING_TIDE", "Spring tide", "away",
                FROM, 2, null, List.of(), null, null);
        HotTopic workable = new HotTopic("BLUEBELL", "Bluebell", "workable",
                FROM.plusDays(1), 3, null, List.of(), null, null);
        when(travelDayService.isTravelDay(FROM)).thenReturn(true);

        HotTopicStrategy strategy = (from, to) -> List.of(away, workable);
        HotTopicAggregator aggregator = new HotTopicAggregator(List.of(strategy),
                simulationService, travelDayService, eventEnricher);

        List<HotTopic> topics = aggregator.getHotTopics(FROM, TO);

        assertThat(topics).containsExactly(workable);
    }

    @Test
    @DisplayName("topics are sorted by priority ascending then date")
    void getHotTopics_multiplePriorities_sortedCorrectly() {
        HotTopic lowPriority = new HotTopic("A", "A", "a", FROM, 3, null, List.of(), null, null);
        HotTopic highPriority = new HotTopic("B", "B", "b", FROM, 1, null, List.of(), null, null);
        HotTopic medPriorityLaterDate = new HotTopic("C", "C", "c", FROM.plusDays(1), 2, null, List.of(), null, null);

        HotTopicStrategy strategy = (from, to) -> List.of(lowPriority, medPriorityLaterDate, highPriority);
        HotTopicAggregator aggregator = new HotTopicAggregator(
                List.of(strategy), simulationService, travelDayService, eventEnricher);

        List<HotTopic> topics = aggregator.getHotTopics(FROM, TO);

        assertThat(topics.get(0).type()).isEqualTo("B"); // priority 1
        assertThat(topics.get(1).type()).isEqualTo("C"); // priority 2
        assertThat(topics.get(2).type()).isEqualTo("A"); // priority 3
    }

    @Test
    @DisplayName("detector returning empty list is handled gracefully")
    void getHotTopics_detectorReturnsEmpty_noError() {
        HotTopicStrategy emptyStrategy = (from, to) -> List.of();
        HotTopicAggregator aggregator = new HotTopicAggregator(
                List.of(emptyStrategy), simulationService, travelDayService, eventEnricher);

        List<HotTopic> topics = aggregator.getHotTopics(FROM, TO);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("simulation enabled — returns simulated topics instead of running detectors")
    void getHotTopics_simulationEnabled_bypassesDetectors() {
        simulationService.setEnabled(true);
        simulationService.setTypeActive("BLUEBELL", true);

        HotTopicStrategy neverCalledStrategy = (from, to) -> {
            throw new IllegalStateException("strategy should not be called during simulation");
        };

        HotTopicAggregator aggregator = new HotTopicAggregator(
                List.of(neverCalledStrategy), simulationService, travelDayService, eventEnricher);

        List<HotTopic> topics = aggregator.getHotTopics(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).type()).isEqualTo("BLUEBELL");
    }

    @Test
    @DisplayName("simulation disabled — runs real detectors")
    void getHotTopics_simulationDisabled_usesRealDetectors() {
        simulationService.setTypeActive("BLUEBELL", true); // won't be used because disabled

        HotTopic realTopic = new HotTopic("SPRING_TIDE", "Spring tide", "real", FROM, 1, null, List.of(), null, null);
        HotTopicStrategy strategy = (from, to) -> List.of(realTopic);
        HotTopicAggregator aggregator = new HotTopicAggregator(
                List.of(strategy), simulationService, travelDayService, eventEnricher);

        List<HotTopic> topics = aggregator.getHotTopics(FROM, TO);

        assertThat(topics).containsExactly(realTopic);
    }

    // ── Verify strategy invocation ────────────────────────────────────────────

    @Mock
    private HotTopicStrategy mockStrategy1;

    @Mock
    private HotTopicStrategy mockStrategy2;

    @Test
    @DisplayName("each strategy receives the exact date range passed to the aggregator")
    void getHotTopics_invokesEachStrategyWithExactDates() {
        when(mockStrategy1.detect(FROM, TO)).thenReturn(List.of());
        when(mockStrategy2.detect(FROM, TO)).thenReturn(List.of());
        HotTopicAggregator aggregator = new HotTopicAggregator(List.of(mockStrategy1, mockStrategy2),
                simulationService, travelDayService, eventEnricher);

        aggregator.getHotTopics(FROM, TO);

        verify(mockStrategy1).detect(FROM, TO);
        verify(mockStrategy2).detect(FROM, TO);
    }

    @Test
    @DisplayName("simulation enabled — strategies are never invoked")
    void getHotTopics_simulationEnabled_strategiesNotInvoked() {
        simulationService.setEnabled(true);
        simulationService.setTypeActive("BLUEBELL", true);
        HotTopicAggregator aggregator = new HotTopicAggregator(
                List.of(mockStrategy1), simulationService, travelDayService, eventEnricher);

        aggregator.getHotTopics(FROM, TO);

        verifyNoInteractions(mockStrategy1);
    }

    // ── Owner decision (2026-09-29): no stability-skip or disposition query on this path ────

    @Mock
    private ForecastScoreRepository forecastScoreRepository;
    @Mock
    private SlotAtmosphereRepository slotAtmosphereRepository;
    @Mock
    private ForecastRunDispositionRepository forecastRunDispositionRepository;

    /**
     * Proves the count from the brief directly, through a real strategy and a real
     * {@link SlotSignalReader} — not a mock standing in for the claim. Before 2026-09-29 this
     * same shape of pass opened one shared {@code loadStabilitySkips} query (backed by
     * {@link ForecastRunDispositionRepository#findLatestStabilitySkipTimestamps}); now
     * {@code SlotSignalReader}'s only two collaborators are {@link ForecastScoreRepository} and
     * {@link SlotAtmosphereRepository}, and {@code HotTopicAggregator} itself holds neither an
     * {@code EvaluationViewService} nor a {@code SlotSignalReader} reference — so the
     * disposition repository, mocked here exactly as production wires it to other beans (e.g.
     * {@code EvaluationViewService} for the rating-retraction path this test does not exercise), is
     * verified to receive ZERO interactions across a full real aggregation.
     */
    @Test
    @DisplayName("one hot-topic aggregation issues no stability-skip or disposition query at all — "
            + "the repository behind that lookup is never touched, proven through a real strategy "
            + "and a real SlotSignalReader (owner decision, 2026-09-29)")
    void getHotTopics_aggregationIssuesNoStabilityOrDispositionQuery() {
        LocationEntity fell = new LocationEntity();
        fell.setId(1L);
        fell.setName("Great Gable");
        // Phase 2 (V158, owner decision 2026-09-30): InversionHotTopicStrategy now fires off the
        // calculator's own slot_atmosphere reading, not the forecast_score Claude echo — so
        // the strong-inversion row this test needs to make the strategy fire lives here.
        SlotAtmosphereEntity strongInversion = new SlotAtmosphereEntity();
        strongInversion.setLocation(fell);
        strongInversion.setEvaluationDate(FROM);
        strongInversion.setEventType(TargetType.SUNRISE);
        strongInversion.setInversionScore(9.0);
        // V158 round 4: a fresh row must be marked scored, or effectiveInversionScore() reads it
        // as pre-column and falls back to the (here, absent) forecast_score echo instead.
        strongInversion.setInversionScored(true);
        strongInversion.setEvaluatedAt(Instant.parse("2026-04-25T05:00:00Z"));
        lenient().when(forecastScoreRepository.findComponentsByType(
                        ForecastType.INVERSION.getId(), FROM, TO))
                .thenReturn(List.of());
        lenient().when(forecastScoreRepository.findComponentsByType(
                        ForecastType.BLUEBELL.getId(), FROM, TO))
                .thenReturn(List.of());
        lenient().when(slotAtmosphereRepository.findInDateRange(FROM, TO))
                .thenReturn(List.of(strongInversion));

        SolarEventFreshness freshness = mock(SolarEventFreshness.class);
        lenient().when(freshness.isAhead(fell, FROM, TargetType.SUNRISE)).thenReturn(true);
        SlotSignalReader realReader =
                new SlotSignalReader(forecastScoreRepository, slotAtmosphereRepository);
        InversionHotTopicStrategy realInversionStrategy =
                new InversionHotTopicStrategy(realReader, freshness);

        HotTopicAggregator aggregator = new HotTopicAggregator(
                List.of(realInversionStrategy), simulationService, travelDayService, eventEnricher);

        List<HotTopic> topics = aggregator.getHotTopics(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).type()).isEqualTo("INVERSION");
        verifyNoInteractions(forecastRunDispositionRepository);
    }
}
