package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StormSurgeHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class StormSurgeHotTopicStrategyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = FROM.plusDays(3);

    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;

    private StormSurgeHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new StormSurgeHotTopicStrategy(forecastEvaluationRepository);
    }

    @Test
    @DisplayName("high surge risk row fires with priority 1 and queries HIGH only")
    void detect_highRisk_fires() {
        when(forecastEvaluationRepository.findSurgeDaysByRiskLevel(FROM, TO, "HIGH"))
                .thenReturn(List.<Object[]>of(new Object[] {FROM, "Northumberland"}));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("STORM_SURGE");
        assertThat(topic.priority()).isEqualTo(1);
        assertThat(topic.date()).isEqualTo(FROM);
        assertThat(topic.regions()).containsExactly("Northumberland");
        // Boundary: only HIGH is queried — MODERATE rows are never matched.
        verify(forecastEvaluationRepository).findSurgeDaysByRiskLevel(FROM, TO, "HIGH");
    }

    @Test
    @DisplayName("no high surge rows does not fire")
    void detect_noHighRows_doesNotFire() {
        when(forecastEvaluationRepository.findSurgeDaysByRiskLevel(FROM, TO, "HIGH"))
                .thenReturn(List.<Object[]>of());

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("multiple rows: dates to the earliest day, collects distinct regions")
    void detect_multipleRows_earliestDateDistinctRegions() {
        LocalDate later = FROM.plusDays(1);
        when(forecastEvaluationRepository.findSurgeDaysByRiskLevel(FROM, TO, "HIGH"))
                .thenReturn(List.<Object[]>of(
                        new Object[] {FROM, "Northumberland"},
                        new Object[] {later, "The North Yorkshire Coast"}));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(FROM);
        assertThat(topics.get(0).regions())
                .containsExactly("Northumberland", "The North Yorkshire Coast");
    }
}
