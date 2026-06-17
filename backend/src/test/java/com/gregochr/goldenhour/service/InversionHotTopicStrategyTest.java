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
 * Unit tests for {@link InversionHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class InversionHotTopicStrategyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = FROM.plusDays(3);

    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;

    private InversionHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new InversionHotTopicStrategy(forecastEvaluationRepository);
    }

    @Test
    @DisplayName("strong inversion row fires with priority 2 and queries STRONG only")
    void detect_strongInversion_fires() {
        when(forecastEvaluationRepository.findInversionDaysByPotential(FROM, TO, "STRONG"))
                .thenReturn(List.<Object[]>of(new Object[] {FROM, "The North York Moors"}));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("INVERSION");
        assertThat(topic.priority()).isEqualTo(2);
        assertThat(topic.date()).isEqualTo(FROM);
        assertThat(topic.regions()).containsExactly("The North York Moors");
        // Boundary: only STRONG is queried — MODERATE rows are never matched.
        verify(forecastEvaluationRepository).findInversionDaysByPotential(FROM, TO, "STRONG");
    }

    @Test
    @DisplayName("no strong inversion rows does not fire")
    void detect_noStrongRows_doesNotFire() {
        when(forecastEvaluationRepository.findInversionDaysByPotential(FROM, TO, "STRONG"))
                .thenReturn(List.<Object[]>of());

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("multiple rows: dates to the earliest day, collects distinct regions")
    void detect_multipleRows_earliestDateDistinctRegions() {
        LocalDate later = FROM.plusDays(2);
        when(forecastEvaluationRepository.findInversionDaysByPotential(FROM, TO, "STRONG"))
                .thenReturn(List.<Object[]>of(
                        new Object[] {FROM, "The North York Moors"},
                        new Object[] {FROM, "The Lake District"},
                        new Object[] {later, "Northumberland"}));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(FROM);
        assertThat(topics.get(0).regions())
                .containsExactly("The North York Moors", "The Lake District", "Northumberland");
    }

    @Test
    @DisplayName("null region in a row is skipped")
    void detect_nullRegion_skipped() {
        when(forecastEvaluationRepository.findInversionDaysByPotential(FROM, TO, "STRONG"))
                .thenReturn(List.<Object[]>of(
                        new Object[] {FROM, null},
                        new Object[] {FROM, "The North York Moors"}));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics.get(0).regions()).containsExactly("The North York Moors");
    }
}
