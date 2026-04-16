package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BluebellHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class BluebellHotTopicStrategyTest {

    /** A date within bluebell season. */
    private static final LocalDate IN_SEASON = LocalDate.of(2026, 4, 25);

    /** A date outside bluebell season. */
    private static final LocalDate OUT_OF_SEASON = LocalDate.of(2026, 6, 15);

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private ForecastEvaluationRepository evaluationRepository;

    private BluebellHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new BluebellHotTopicStrategy(locationRepository, evaluationRepository);
    }

    @Test
    @DisplayName("returns empty list when called outside bluebell season")
    void detect_outsideSeason_returnsEmpty() {
        List<HotTopic> topics = strategy.detect(OUT_OF_SEASON, OUT_OF_SEASON.plusDays(3));

        assertThat(topics).isEmpty();
        verifyNoInteractions(locationRepository, evaluationRepository);
    }

    @Test
    @DisplayName("returns empty list when no bluebell locations are configured")
    void detect_noLocations_returnsEmpty() {
        when(locationRepository.findBluebellLocations()).thenReturn(List.of());

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON.plusDays(2));

        assertThat(topics).isEmpty();
        verifyNoInteractions(evaluationRepository);
    }

    @Test
    @DisplayName("emits hot topic when best bluebell score >= 6 during season")
    void detect_goodConditions_emitsHotTopic() {
        RegionEntity region = new RegionEntity();
        region.setName("Lake District");

        LocationEntity location = LocationEntity.builder()
                .id(1L)
                .name("Rannerdale Knotts")
                .lat(54.5560)
                .lon(-3.2920)
                .locationType(Set.of(LocationType.BLUEBELL, LocationType.LANDSCAPE))
                .region(region)
                .enabled(true)
                .build();

        when(locationRepository.findBluebellLocations()).thenReturn(List.of(location));

        ForecastEvaluationEntity evaluation = new ForecastEvaluationEntity();
        evaluation.setTargetDate(IN_SEASON);
        evaluation.setBluebellScore(7);
        evaluation.setBluebellSummary("Misty and still");
        evaluation.setLocation(location);

        when(evaluationRepository.findBluebellEvaluations(
                eq(List.of(1L)), eq(IN_SEASON), eq(IN_SEASON)))
                .thenReturn(List.of(evaluation));

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("BLUEBELL");
        assertThat(topic.label()).isEqualTo("Bluebell conditions");
        assertThat(topic.date()).isEqualTo(IN_SEASON);
        assertThat(topic.filterAction()).isEqualTo("BLUEBELL");
        assertThat(topic.regions()).containsExactly("Lake District");
    }

    @Test
    @DisplayName("returns empty list when best bluebell score < 6")
    void detect_poorConditions_returnsEmpty() {
        LocationEntity location = LocationEntity.builder()
                .id(1L)
                .name("Test Location")
                .lat(54.0)
                .lon(-3.0)
                .enabled(true)
                .build();

        when(locationRepository.findBluebellLocations()).thenReturn(List.of(location));

        ForecastEvaluationEntity evaluation = new ForecastEvaluationEntity();
        evaluation.setTargetDate(IN_SEASON);
        evaluation.setBluebellScore(4);
        evaluation.setLocation(location);

        when(evaluationRepository.findBluebellEvaluations(
                eq(List.of(1L)), eq(IN_SEASON), eq(IN_SEASON)))
                .thenReturn(List.of(evaluation));

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("excellent conditions (score >= 8) get priority 1")
    void detect_excellentConditions_priority1() {
        LocationEntity location = LocationEntity.builder()
                .id(1L)
                .name("Rannerdale")
                .lat(54.5560)
                .lon(-3.2920)
                .enabled(true)
                .build();

        when(locationRepository.findBluebellLocations()).thenReturn(List.of(location));

        ForecastEvaluationEntity evaluation = new ForecastEvaluationEntity();
        evaluation.setTargetDate(IN_SEASON);
        evaluation.setBluebellScore(9);
        evaluation.setLocation(location);

        when(evaluationRepository.findBluebellEvaluations(
                eq(List.of(1L)), eq(IN_SEASON), eq(IN_SEASON)))
                .thenReturn(List.of(evaluation));

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).priority()).isEqualTo(1);
    }

    @Test
    @DisplayName("good conditions (score 6-7) get priority 3")
    void detect_goodConditions_priority3() {
        LocationEntity location = LocationEntity.builder()
                .id(1L)
                .name("Allen Banks")
                .lat(54.93)
                .lon(-2.24)
                .enabled(true)
                .build();

        when(locationRepository.findBluebellLocations()).thenReturn(List.of(location));

        ForecastEvaluationEntity evaluation = new ForecastEvaluationEntity();
        evaluation.setTargetDate(IN_SEASON);
        evaluation.setBluebellScore(6);
        evaluation.setLocation(location);

        when(evaluationRepository.findBluebellEvaluations(
                eq(List.of(1L)), eq(IN_SEASON), eq(IN_SEASON)))
                .thenReturn(List.of(evaluation));

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).priority()).isEqualTo(3);
    }

    @Test
    @DisplayName("queries the evaluation repository with exact location IDs and dates")
    void detect_queriesRepositoryWithLocationIds() {
        LocationEntity location = LocationEntity.builder()
                .id(42L)
                .name("Allen Banks")
                .lat(54.93)
                .lon(-2.24)
                .enabled(true)
                .build();

        when(locationRepository.findBluebellLocations()).thenReturn(List.of(location));
        when(evaluationRepository.findBluebellEvaluations(
                eq(List.of(42L)), eq(IN_SEASON), eq(IN_SEASON.plusDays(2))))
                .thenReturn(List.of());

        strategy.detect(IN_SEASON, IN_SEASON.plusDays(2));

        verify(evaluationRepository).findBluebellEvaluations(
                eq(List.of(42L)), eq(IN_SEASON), eq(IN_SEASON.plusDays(2)));
    }

    // ── HOT_TOPIC_THRESHOLD boundary ─────────────────────────────────────────

    @Test
    @DisplayName("score 5 does NOT emit a hot topic (threshold is >= 6)")
    void detect_score5_doesNotEmit() {
        LocationEntity location = LocationEntity.builder()
                .id(1L)
                .name("Test")
                .lat(54.0)
                .lon(-3.0)
                .enabled(true)
                .build();
        when(locationRepository.findBluebellLocations()).thenReturn(List.of(location));

        ForecastEvaluationEntity evaluation = new ForecastEvaluationEntity();
        evaluation.setTargetDate(IN_SEASON);
        evaluation.setBluebellScore(5);
        evaluation.setLocation(location);
        when(evaluationRepository.findBluebellEvaluations(
                eq(List.of(1L)), eq(IN_SEASON), eq(IN_SEASON)))
                .thenReturn(List.of(evaluation));

        assertThat(strategy.detect(IN_SEASON, IN_SEASON)).isEmpty();
    }

    // ── Priority boundary tests ───────────────────────────────────────────────

    @Test
    @DisplayName("score 7 gets priority 3 (boundary: < 8 threshold)")
    void detect_score7_priority3() {
        LocationEntity location = LocationEntity.builder()
                .id(1L)
                .name("Test")
                .lat(54.0)
                .lon(-3.0)
                .enabled(true)
                .build();
        when(locationRepository.findBluebellLocations()).thenReturn(List.of(location));

        ForecastEvaluationEntity evaluation = new ForecastEvaluationEntity();
        evaluation.setTargetDate(IN_SEASON);
        evaluation.setBluebellScore(7);
        evaluation.setLocation(location);
        when(evaluationRepository.findBluebellEvaluations(
                eq(List.of(1L)), eq(IN_SEASON), eq(IN_SEASON)))
                .thenReturn(List.of(evaluation));

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).priority()).isEqualTo(3);
    }

    @Test
    @DisplayName("score 8 gets priority 1 (boundary: >= 8 threshold)")
    void detect_score8_priority1() {
        LocationEntity location = LocationEntity.builder()
                .id(1L)
                .name("Test")
                .lat(54.0)
                .lon(-3.0)
                .enabled(true)
                .build();
        when(locationRepository.findBluebellLocations()).thenReturn(List.of(location));

        ForecastEvaluationEntity evaluation = new ForecastEvaluationEntity();
        evaluation.setTargetDate(IN_SEASON);
        evaluation.setBluebellScore(8);
        evaluation.setLocation(location);
        when(evaluationRepository.findBluebellEvaluations(
                eq(List.of(1L)), eq(IN_SEASON), eq(IN_SEASON)))
                .thenReturn(List.of(evaluation));

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).priority()).isEqualTo(1);
    }

    // ── MAX_REGIONS enforcement ───────────────────────────────────────────────

    @Test
    @DisplayName("region list is capped at 2 even when 3 qualifying regions exist")
    void detect_threeQualifyingRegions_cappedAt2() {
        RegionEntity r1 = new RegionEntity();
        r1.setName("Northumberland");
        RegionEntity r2 = new RegionEntity();
        r2.setName("Lake District");
        RegionEntity r3 = new RegionEntity();
        r3.setName("North York Moors");

        LocationEntity loc1 = LocationEntity.builder()
                .id(1L).name("L1").lat(55.0).lon(-1.9).region(r1).enabled(true).build();
        LocationEntity loc2 = LocationEntity.builder()
                .id(2L).name("L2").lat(54.5).lon(-3.1).region(r2).enabled(true).build();
        LocationEntity loc3 = LocationEntity.builder()
                .id(3L).name("L3").lat(54.3).lon(-1.0).region(r3).enabled(true).build();

        when(locationRepository.findBluebellLocations())
                .thenReturn(List.of(loc1, loc2, loc3));

        ForecastEvaluationEntity e1 = new ForecastEvaluationEntity();
        e1.setTargetDate(IN_SEASON);
        e1.setBluebellScore(8);
        e1.setLocation(loc1);
        ForecastEvaluationEntity e2 = new ForecastEvaluationEntity();
        e2.setTargetDate(IN_SEASON);
        e2.setBluebellScore(7);
        e2.setLocation(loc2);
        ForecastEvaluationEntity e3 = new ForecastEvaluationEntity();
        e3.setTargetDate(IN_SEASON);
        e3.setBluebellScore(6);
        e3.setLocation(loc3);

        when(evaluationRepository.findBluebellEvaluations(
                eq(List.of(1L, 2L, 3L)), eq(IN_SEASON), eq(IN_SEASON)))
                .thenReturn(List.of(e1, e2, e3));

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).regions()).hasSize(2);
    }

    @Test
    @DisplayName("region with highest score appears first in the list")
    void detect_multipleRegions_sortedByScoreDescending() {
        RegionEntity highRegion = new RegionEntity();
        highRegion.setName("Northumberland");
        RegionEntity lowRegion = new RegionEntity();
        lowRegion.setName("Lake District");

        LocationEntity loc1 = LocationEntity.builder()
                .id(1L).name("L1").lat(55.0).lon(-1.9).region(highRegion).enabled(true).build();
        LocationEntity loc2 = LocationEntity.builder()
                .id(2L).name("L2").lat(54.5).lon(-3.1).region(lowRegion).enabled(true).build();

        when(locationRepository.findBluebellLocations()).thenReturn(List.of(loc1, loc2));

        ForecastEvaluationEntity highScore = new ForecastEvaluationEntity();
        highScore.setTargetDate(IN_SEASON);
        highScore.setBluebellScore(9);
        highScore.setLocation(loc1);
        ForecastEvaluationEntity lowScore = new ForecastEvaluationEntity();
        lowScore.setTargetDate(IN_SEASON);
        lowScore.setBluebellScore(6);
        lowScore.setLocation(loc2);

        // deliberately supply in reverse order to confirm sorting is done by score, not input order
        when(evaluationRepository.findBluebellEvaluations(
                eq(List.of(1L, 2L)), eq(IN_SEASON), eq(IN_SEASON)))
                .thenReturn(List.of(lowScore, highScore));

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).regions().get(0)).isEqualTo("Northumberland");
    }

    // ── Day label tests ────────────────────────────────────────────────────────

    @Test
    @DisplayName("day label is 'today' when evaluation date equals fromDate")
    void detect_evaluationOnFromDate_detailContainsToday() {
        LocationEntity location = LocationEntity.builder()
                .id(1L)
                .name("Test")
                .lat(54.0)
                .lon(-3.0)
                .enabled(true)
                .build();
        when(locationRepository.findBluebellLocations()).thenReturn(List.of(location));

        ForecastEvaluationEntity evaluation = new ForecastEvaluationEntity();
        evaluation.setTargetDate(IN_SEASON);
        evaluation.setBluebellScore(7);
        evaluation.setBluebellSummary("Misty");
        evaluation.setLocation(location);
        when(evaluationRepository.findBluebellEvaluations(
                eq(List.of(1L)), eq(IN_SEASON), eq(IN_SEASON)))
                .thenReturn(List.of(evaluation));

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics.get(0).detail()).contains("today");
    }

    @Test
    @DisplayName("day label is 'tomorrow' when evaluation date is fromDate + 1")
    void detect_evaluationOnFromDatePlusOne_detailContainsTomorrow() {
        LocalDate tomorrow = IN_SEASON.plusDays(1);
        LocationEntity location = LocationEntity.builder()
                .id(1L)
                .name("Test")
                .lat(54.0)
                .lon(-3.0)
                .enabled(true)
                .build();
        when(locationRepository.findBluebellLocations()).thenReturn(List.of(location));

        ForecastEvaluationEntity evaluation = new ForecastEvaluationEntity();
        evaluation.setTargetDate(tomorrow);
        evaluation.setBluebellScore(7);
        evaluation.setLocation(location);
        when(evaluationRepository.findBluebellEvaluations(
                eq(List.of(1L)), eq(IN_SEASON), eq(tomorrow)))
                .thenReturn(List.of(evaluation));

        List<HotTopic> topics = strategy.detect(IN_SEASON, tomorrow);

        assertThat(topics.get(0).detail()).contains("tomorrow");
    }

    @Test
    @DisplayName("day label is full weekday name for dates beyond tomorrow")
    void detect_evaluationBeyondTomorrow_detailContainsWeekdayName() {
        // IN_SEASON = 2026-04-25 (Saturday), plusDays(2) = 2026-04-27 (Monday)
        LocalDate monday = IN_SEASON.plusDays(2);
        LocationEntity location = LocationEntity.builder()
                .id(1L)
                .name("Test")
                .lat(54.0)
                .lon(-3.0)
                .enabled(true)
                .build();
        when(locationRepository.findBluebellLocations()).thenReturn(List.of(location));

        ForecastEvaluationEntity evaluation = new ForecastEvaluationEntity();
        evaluation.setTargetDate(monday);
        evaluation.setBluebellScore(7);
        evaluation.setLocation(location);
        when(evaluationRepository.findBluebellEvaluations(
                eq(List.of(1L)), eq(IN_SEASON), eq(monday)))
                .thenReturn(List.of(evaluation));

        List<HotTopic> topics = strategy.detect(IN_SEASON, monday);

        assertThat(topics.get(0).detail()).contains("Monday");
    }

    // ── Exact repository argument verification ────────────────────────────────

    @Test
    @DisplayName("findBluebellEvaluations called with exact location IDs and exact date range")
    void detect_queriesRepositoryWithExactIdsAndDates() {
        LocationEntity location = LocationEntity.builder()
                .id(42L)
                .name("Allen Banks")
                .lat(54.93)
                .lon(-2.24)
                .enabled(true)
                .build();
        when(locationRepository.findBluebellLocations()).thenReturn(List.of(location));
        when(evaluationRepository.findBluebellEvaluations(
                eq(List.of(42L)), eq(IN_SEASON), eq(IN_SEASON.plusDays(3))))
                .thenReturn(List.of());

        strategy.detect(IN_SEASON, IN_SEASON.plusDays(3));

        verify(evaluationRepository).findBluebellEvaluations(
                eq(List.of(42L)), eq(IN_SEASON), eq(IN_SEASON.plusDays(3)));
    }
}
