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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BluebellHotTopicDetector}.
 */
@ExtendWith(MockitoExtension.class)
class BluebellHotTopicDetectorTest {

    /** A date within bluebell season. */
    private static final LocalDate IN_SEASON = LocalDate.of(2026, 4, 25);

    /** A date outside bluebell season. */
    private static final LocalDate OUT_OF_SEASON = LocalDate.of(2026, 6, 15);

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private ForecastEvaluationRepository evaluationRepository;

    private BluebellHotTopicDetector detector;

    @BeforeEach
    void setUp() {
        detector = new BluebellHotTopicDetector(locationRepository, evaluationRepository);
    }

    @Test
    @DisplayName("returns empty list when called outside bluebell season")
    void detect_outsideSeason_returnsEmpty() {
        List<HotTopic> topics = detector.detect(OUT_OF_SEASON, OUT_OF_SEASON.plusDays(3));

        assertThat(topics).isEmpty();
        verifyNoInteractions(locationRepository, evaluationRepository);
    }

    @Test
    @DisplayName("returns empty list when no bluebell locations are configured")
    void detect_noLocations_returnsEmpty() {
        when(locationRepository.findBluebellLocations()).thenReturn(List.of());

        List<HotTopic> topics = detector.detect(IN_SEASON, IN_SEASON.plusDays(2));

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

        when(evaluationRepository.findBluebellEvaluations(any(), any(), any()))
                .thenReturn(List.of(evaluation));

        List<HotTopic> topics = detector.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("BLUEBELL");
        assertThat(topic.label()).isEqualTo("Bluebell conditions");
        assertThat(topic.date()).isEqualTo(IN_SEASON);
        assertThat(topic.filterAction()).isEqualTo("BLUEBELL");
        assertThat(topic.regions()).contains("Lake District");
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

        when(evaluationRepository.findBluebellEvaluations(any(), any(), any()))
                .thenReturn(List.of(evaluation));

        List<HotTopic> topics = detector.detect(IN_SEASON, IN_SEASON);

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

        when(evaluationRepository.findBluebellEvaluations(any(), any(), any()))
                .thenReturn(List.of(evaluation));

        List<HotTopic> topics = detector.detect(IN_SEASON, IN_SEASON);

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

        when(evaluationRepository.findBluebellEvaluations(any(), any(), any()))
                .thenReturn(List.of(evaluation));

        List<HotTopic> topics = detector.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).priority()).isEqualTo(3);
    }

    @Test
    @DisplayName("queries the evaluation repository with correct location IDs")
    void detect_queriesRepositoryWithLocationIds() {
        LocationEntity location = LocationEntity.builder()
                .id(42L)
                .name("Allen Banks")
                .lat(54.93)
                .lon(-2.24)
                .enabled(true)
                .build();

        when(locationRepository.findBluebellLocations()).thenReturn(List.of(location));
        when(evaluationRepository.findBluebellEvaluations(any(), any(), any()))
                .thenReturn(List.of());

        detector.detect(IN_SEASON, IN_SEASON.plusDays(2));

        verify(evaluationRepository).findBluebellEvaluations(
                org.mockito.ArgumentMatchers.argThat(ids -> ids.contains(42L)),
                any(), any());
    }
}
