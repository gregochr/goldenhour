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
 * Unit tests for {@link SnowTopsHotTopicStrategy}, including the freezing-level-versus-elevation
 * margin boundary.
 */
@ExtendWith(MockitoExtension.class)
class SnowTopsHotTopicStrategyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 15);
    private static final LocalDate TO = FROM.plusDays(3);
    private static final int MARGIN = SnowTopsHotTopicStrategy.FREEZING_LEVEL_MARGIN_METRES;

    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;

    private SnowTopsHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SnowTopsHotTopicStrategy(forecastEvaluationRepository);
    }

    @Test
    @DisplayName("white-tops row fires SNOW_TOPS at priority 3 with the 100 m margin")
    void detect_whiteTops_fires() {
        when(forecastEvaluationRepository.findSnowTopsDays(FROM, TO, MARGIN))
                .thenReturn(List.<Object[]>of(new Object[] {FROM, "The Lake District"}));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("SNOW_TOPS");
        assertThat(topic.priority()).isEqualTo(3);
        assertThat(topic.regions()).containsExactly("The Lake District");
        verify(forecastEvaluationRepository).findSnowTopsDays(FROM, TO, MARGIN);
    }

    @Test
    @DisplayName("no white-tops rows does not fire")
    void detect_noRows_doesNotFire() {
        when(forecastEvaluationRepository.findSnowTopsDays(FROM, TO, MARGIN))
                .thenReturn(List.<Object[]>of());

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    // ── isTopsWhite boundary: freezingLevel <= elevation - 100 ──

    @Test
    @DisplayName("freezing level exactly margin below summit is white (boundary)")
    void isTopsWhite_atMargin_true() {
        // 451 m summit (Cat Bells), freezing level at 351 m == elevation - 100
        assertThat(SnowTopsHotTopicStrategy.isTopsWhite(351.0, 451)).isTrue();
    }

    @Test
    @DisplayName("freezing level one metre above the margin is not white (boundary)")
    void isTopsWhite_justAboveMargin_false() {
        assertThat(SnowTopsHotTopicStrategy.isTopsWhite(352.0, 451)).isFalse();
    }

    @Test
    @DisplayName("low fell still fires when the freezing level drops far enough (no elevation floor)")
    void isTopsWhite_lowFellDeepFreeze_true() {
        // 235 m location, freezing level at sea level — no minimum-elevation floor applies
        assertThat(SnowTopsHotTopicStrategy.isTopsWhite(0.0, 235)).isTrue();
    }

    @Test
    @DisplayName("null freezing level or null elevation is not white")
    void isTopsWhite_nulls_false() {
        assertThat(SnowTopsHotTopicStrategy.isTopsWhite(null, 451)).isFalse();
        assertThat(SnowTopsHotTopicStrategy.isTopsWhite(351.0, null)).isFalse();
    }
}
