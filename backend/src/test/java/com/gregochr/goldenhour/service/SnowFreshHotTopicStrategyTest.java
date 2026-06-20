package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SurvivorSignals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SnowFreshHotTopicStrategy}, including the SNOW_MIST variant and the
 * snow-depth / humidity threshold boundaries. Reads the survivor surface.
 */
@ExtendWith(MockitoExtension.class)
class SnowFreshHotTopicStrategyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 15);
    private static final LocalDate TO = FROM.plusDays(3);

    @Mock
    private SurvivorSignalReader survivorSignalReader;

    private SnowFreshHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SnowFreshHotTopicStrategy(survivorSignalReader);
    }

    /** A survivor composite carrying snow depth and humidity readings. */
    private static SurvivorSignals signal(LocalDate date, String regionName,
            Double snowDepthMetres, Integer humidity) {
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        LocationEntity location = new LocationEntity();
        location.setRegion(region);
        SurvivorSignals.Readings readings = new SurvivorSignals.Readings(
                null, null, null, null, snowDepthMetres, null, humidity);
        return new SurvivorSignals(location, date, TargetType.SUNRISE,
                SurvivorSignals.Scores.EMPTY, readings);
    }

    @Test
    @DisplayName("snow lying without mist fires the plain SNOW_FRESH topic at priority 2")
    void detect_snowLyingNoMist_firesFresh() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The Lake District", 0.05, 70)));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("SNOW_FRESH");
        assertThat(topic.priority()).isEqualTo(2);
        assertThat(topic.regions()).containsExactly("The Lake District");
    }

    @Test
    @DisplayName("snow lying with mist upgrades to SNOW_MIST at priority 1")
    void detect_snowLyingWithMist_firesMistVariant() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signal(FROM, "The Lake District", 0.05, 70),
                        signal(FROM, "The North York Moors", 0.10, 95)));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("SNOW_MIST");
        assertThat(topic.priority()).isEqualTo(1);
        assertThat(topic.regions())
                .containsExactly("The Lake District", "The North York Moors");
    }

    @Test
    @DisplayName("a below-threshold dusting does not fire — the depth filter, not just empty")
    void detect_belowThreshold_doesNotFire() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The Lake District", 0.01, 70)));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("no survivor rows does not fire")
    void detect_noRows_doesNotFire() {
        when(survivorSignalReader.read(FROM, TO)).thenReturn(List.of());

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    // ── isFreshSnow boundary: depth >= 0.02 m ──

    @Test
    @DisplayName("depth exactly at 0.02 m threshold is fresh snow (boundary)")
    void isFreshSnow_atThreshold_true() {
        assertThat(SnowFreshHotTopicStrategy.isFreshSnow(0.02)).isTrue();
    }

    @Test
    @DisplayName("depth just below 0.02 m is not fresh snow (boundary)")
    void isFreshSnow_justBelowThreshold_false() {
        assertThat(SnowFreshHotTopicStrategy.isFreshSnow(0.019)).isFalse();
    }

    @Test
    @DisplayName("null depth is not fresh snow")
    void isFreshSnow_null_false() {
        assertThat(SnowFreshHotTopicStrategy.isFreshSnow(null)).isFalse();
    }

    // ── isMisty boundary: humidity > 90 (the briefing's HUMIDITY_MARGINAL) ──

    @Test
    @DisplayName("humidity exactly at 90 is not misty (boundary)")
    void isMisty_atThreshold_false() {
        assertThat(SnowFreshHotTopicStrategy.isMisty(BriefingVerdictEvaluator.HUMIDITY_MARGINAL))
                .isFalse();
    }

    @Test
    @DisplayName("humidity just above 90 is misty (boundary)")
    void isMisty_justAboveThreshold_true() {
        assertThat(SnowFreshHotTopicStrategy.isMisty(
                BriefingVerdictEvaluator.HUMIDITY_MARGINAL + 1)).isTrue();
    }

    @Test
    @DisplayName("null humidity is not misty")
    void isMisty_null_false() {
        assertThat(SnowFreshHotTopicStrategy.isMisty(null)).isFalse();
    }
}
