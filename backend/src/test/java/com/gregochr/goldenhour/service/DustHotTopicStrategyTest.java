package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DustHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class DustHotTopicStrategyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = FROM.plusDays(3);

    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;

    private DustHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new DustHotTopicStrategy(forecastEvaluationRepository);
    }

    @Test
    @DisplayName("dust-enhanced row fires with priority 3 and the badge thresholds")
    void detect_dustEnhanced_fires() {
        when(forecastEvaluationRepository.findDustDays(FROM, TO,
                new BigDecimal("0.3"), new BigDecimal("50"), new BigDecimal("35")))
                .thenReturn(List.<Object[]>of(new Object[] {FROM, "The North Yorkshire Coast"}));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("DUST");
        assertThat(topic.priority()).isEqualTo(3);
        assertThat(topic.regions()).containsExactly("The North Yorkshire Coast");
        // Locks that the query uses exactly the dust badge thresholds.
        verify(forecastEvaluationRepository).findDustDays(FROM, TO,
                new BigDecimal("0.3"), new BigDecimal("50"), new BigDecimal("35"));
    }

    @Test
    @DisplayName("no dust-enhanced rows does not fire")
    void detect_noRows_doesNotFire() {
        when(forecastEvaluationRepository.findDustDays(FROM, TO,
                new BigDecimal("0.3"), new BigDecimal("50"), new BigDecimal("35")))
                .thenReturn(List.<Object[]>of());

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    // ── Dust badge consistency lock: isDustEnhanced mirrors the frontend proxy ──
    //    (AOD > 0.3 OR dust > 50) AND (PM2.5 < 35 OR PM2.5 absent)

    @Test
    @DisplayName("elevated AOD with low PM2.5 is dust-enhanced")
    void isDustEnhanced_highAodLowPm25_true() {
        assertThat(DustHotTopicStrategy.isDustEnhanced(
                new BigDecimal("0.31"), null, new BigDecimal("10"))).isTrue();
    }

    @Test
    @DisplayName("AOD exactly at 0.3 threshold is not elevated (boundary)")
    void isDustEnhanced_aodAtThreshold_false() {
        assertThat(DustHotTopicStrategy.isDustEnhanced(
                new BigDecimal("0.3"), null, new BigDecimal("10"))).isFalse();
    }

    @Test
    @DisplayName("elevated surface dust with absent PM2.5 is dust-enhanced")
    void isDustEnhanced_highDustNullPm25_true() {
        assertThat(DustHotTopicStrategy.isDustEnhanced(
                null, new BigDecimal("51"), null)).isTrue();
    }

    @Test
    @DisplayName("surface dust exactly at 50 threshold is not elevated (boundary)")
    void isDustEnhanced_dustAtThreshold_false() {
        assertThat(DustHotTopicStrategy.isDustEnhanced(
                null, new BigDecimal("50"), null)).isFalse();
    }

    @Test
    @DisplayName("PM2.5 exactly at 35 rules out dust (boundary)")
    void isDustEnhanced_pm25AtThreshold_false() {
        assertThat(DustHotTopicStrategy.isDustEnhanced(
                new BigDecimal("0.5"), null, new BigDecimal("35"))).isFalse();
    }

    @Test
    @DisplayName("PM2.5 just below 35 with elevated AOD is dust-enhanced (boundary)")
    void isDustEnhanced_pm25JustBelowThreshold_true() {
        assertThat(DustHotTopicStrategy.isDustEnhanced(
                new BigDecimal("0.5"), null, new BigDecimal("34.9"))).isTrue();
    }

    @Test
    @DisplayName("no aerosol data is not dust-enhanced")
    void isDustEnhanced_allNull_false() {
        assertThat(DustHotTopicStrategy.isDustEnhanced(null, null, null)).isFalse();
    }
}
