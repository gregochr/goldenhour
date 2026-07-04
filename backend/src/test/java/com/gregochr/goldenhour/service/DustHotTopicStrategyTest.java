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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DustHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class DustHotTopicStrategyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = FROM.plusDays(3);

    @Mock
    private SurvivorSignalReader survivorSignalReader;

    @Mock
    private SolarEventFreshness freshness;

    private DustHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        // Default: every solar event is still ahead. Expiry tests override per date.
        lenient().when(freshness.isAhead(any(LocationEntity.class), any(), any()))
                .thenReturn(true);
        strategy = new DustHotTopicStrategy(survivorSignalReader, freshness);
    }

    /** A survivor composite carrying only aerosol readings (the dust proxy inputs). */
    private static SurvivorSignals signal(LocalDate date, String regionName,
            String aod, String dust, String pm25) {
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        LocationEntity location = new LocationEntity();
        location.setRegion(region);
        SurvivorSignals.Readings readings = new SurvivorSignals.Readings(
                aod == null ? null : new BigDecimal(aod),
                dust == null ? null : new BigDecimal(dust),
                pm25 == null ? null : new BigDecimal(pm25),
                null, null, null, null);
        return new SurvivorSignals(location, date, TargetType.SUNSET,
                SurvivorSignals.Scores.EMPTY, readings);
    }

    @Test
    @DisplayName("dust-enhanced survivor fires with priority 3 off the survivor surface")
    void detect_dustEnhanced_fires() {
        when(survivorSignalReader.read(FROM, TO)).thenReturn(List.of(
                signal(FROM, "The North Yorkshire Coast", "0.42", "60", "10")));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("DUST");
        assertThat(topic.priority()).isEqualTo(3);
        assertThat(topic.regions()).containsExactly("The North Yorkshire Coast");
    }

    @Test
    @DisplayName("a non-dusty survivor (PM2.5 too high) does not fire — the proxy filters, not just empty")
    void detect_smokyHighPm25_doesNotFire() {
        when(survivorSignalReader.read(FROM, TO)).thenReturn(List.of(
                signal(FROM, "The North Yorkshire Coast", "0.42", "60", "40")));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("no survivor readings does not fire")
    void detect_noRows_doesNotFire() {
        when(survivorSignalReader.read(FROM, TO)).thenReturn(List.of());

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("a dust row whose solar event has passed is dropped")
    void detect_expiredEvent_dropped() {
        when(survivorSignalReader.read(FROM, TO)).thenReturn(List.of(
                signal(FROM, "The North Yorkshire Coast", "0.42", "60", "10")));
        when(freshness.isAhead(any(LocationEntity.class), eq(FROM), any())).thenReturn(false);

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("emits one card per non-expired dust day, each with that day's regions")
    void detect_multipleDays_onePerDate() {
        when(survivorSignalReader.read(FROM, TO)).thenReturn(List.of(
                signal(FROM.plusDays(2), "Northumberland", "0.42", "60", "10"),
                signal(FROM, "The North Yorkshire Coast", "0.42", "60", "10")));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(2);
        assertThat(topics).extracting(HotTopic::date)
                .containsExactly(FROM, FROM.plusDays(2));
        assertThat(topics.get(0).regions()).containsExactly("The North Yorkshire Coast");
        assertThat(topics.get(1).regions()).containsExactly("Northumberland");
        assertThat(topics).noneMatch(t -> t.detail().contains("and Friday"));
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
