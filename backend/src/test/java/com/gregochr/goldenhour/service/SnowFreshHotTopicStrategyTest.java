package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private SolarEventFreshness freshness;

    private SnowFreshHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SnowFreshHotTopicStrategy(survivorSignalReader, freshness);
    }

    /** Keeps each given day's SUNRISE event ahead of the freshness cutoff. Stubbed per-test (only
     * the tests that emit a topic reach the freshness filter), with the date + SUNRISE event pinned;
     * the location varies per row and is not the discriminator (the filter is per-day). */
    private void stubAhead(LocalDate... days) {
        for (LocalDate day : days) {
            when(freshness.isAhead(any(LocationEntity.class), eq(day), eq(TargetType.SUNRISE)))
                    .thenReturn(true);
        }
    }

    private static HotTopicFact factWithKey(HotTopic topic, String key) {
        return topic.facts().stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    /** A survivor composite carrying snow depth and humidity (no freezing level or temperature). */
    private static SurvivorSignals signal(LocalDate date, String regionName,
            Double snowDepthMetres, Integer humidity) {
        return signal(date, regionName, snowDepthMetres, humidity, null, null);
    }

    /** A survivor composite carrying snow depth, humidity, freezing level and 2 m temperature. */
    private static SurvivorSignals signal(LocalDate date, String regionName, Double snowDepthMetres,
            Integer humidity, Double freezingLevelMetres, Double temperatureCelsius) {
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        LocationEntity location = new LocationEntity();
        location.setRegion(region);
        SurvivorSignals.Readings readings = new SurvivorSignals.Readings(
                null, null, null, null, snowDepthMetres, freezingLevelMetres, humidity,
                null, null, null, temperatureCelsius);
        return new SurvivorSignals(location, date, TargetType.SUNRISE,
                SurvivorSignals.Scores.EMPTY, readings);
    }

    @Test
    @DisplayName("snow lying without mist fires the plain SNOW_FRESH topic at priority 2")
    void detect_snowLyingNoMist_firesFresh() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The Lake District", 0.05, 70)));
        stubAhead(FROM);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("SNOW_FRESH");
        assertThat(topic.priority()).isEqualTo(2);
        assertThat(topic.regions()).containsExactly("The Lake District");
    }

    @Test
    @DisplayName("fresh-snow fact line shows depth and the snow line (freezing level)")
    void detect_fresh_factLine() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The Lake District", 0.08, 70, 340.0, 1.0)));
        stubAhead(FROM);

        HotTopic topic = strategy.detect(FROM, TO).get(0);

        assertThat(factWithKey(topic, "depth").value()).isEqualTo("8 cm");
        assertThat(factWithKey(topic, "snow line").value()).isEqualTo("~340 m");
        assertThat(topic.note()).isEqualTo("clean at first light, before wind and footprints");
    }

    @Test
    @DisplayName("the deepest snowy row is the representative for depth")
    void detect_fresh_representativeDeepest() {
        when(survivorSignalReader.read(FROM, TO)).thenReturn(List.of(
                signal(FROM, "The Lake District", 0.05, 70),
                signal(FROM, "The Lake District", 0.12, 70)));
        stubAhead(FROM);

        HotTopic topic = strategy.detect(FROM, TO).get(0);

        assertThat(factWithKey(topic, "depth").value()).isEqualTo("12 cm");
    }

    @Test
    @DisplayName("snow lying with mist upgrades to SNOW_MIST at priority 1")
    void detect_snowLyingWithMist_firesMistVariant() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signal(FROM, "The Lake District", 0.05, 70),
                        signal(FROM, "The North York Moors", 0.10, 95)));
        stubAhead(FROM);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("SNOW_MIST");
        assertThat(topic.priority()).isEqualTo(1);
        assertThat(topic.regions())
                .containsExactly("The Lake District", "The North York Moors");
    }

    @Test
    @DisplayName("misty snow with unknown/above-zero air stays plain 'Fresh snow with mist'")
    void detect_mist_aboveZero_plainMist() {
        // Representative is the deepest misty row (0.10 m, 95%), air +1 °C → no hoar-frost claim.
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The North York Moors", 0.10, 95, 200.0, 1.0)));
        stubAhead(FROM);

        HotTopic topic = strategy.detect(FROM, TO).get(0);

        assertThat(topic.type()).isEqualTo("SNOW_MIST");
        assertThat(topic.label()).isEqualTo("Fresh snow with mist");
        assertThat(factWithKey(topic, "depth").value()).isEqualTo("10 cm");
        assertThat(factWithKey(topic, "mist").value()).isEqualTo("humidity 95%");
        assertThat(topic.facts()).noneMatch(f -> "air".equals(f.key()));
        assertThat(topic.note()).isEqualTo("mist over lying snow — soft, flat light");
        // Plain mist keeps the general copy — no hoar-frost explanation is claimed.
        assertThat(topic.description()).doesNotContain("Hoar frost");
    }

    @Test
    @DisplayName("sub-zero misty snow becomes 'Snow mist & hoar frost' with the freezing-air fact")
    void detect_mist_subZero_hoarFrost() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The Lake District", 0.08, 96, 150.0, -2.0)));
        stubAhead(FROM);

        HotTopic topic = strategy.detect(FROM, TO).get(0);

        assertThat(topic.type()).isEqualTo("SNOW_MIST");
        assertThat(topic.label()).isEqualTo("Snow mist & hoar frost");
        assertThat(topic.detail()).startsWith("Freezing fog over lying snow — hoar frost");
        assertThat(factWithKey(topic, "depth").value()).isEqualTo("8 cm");
        assertThat(factWithKey(topic, "air").value()).isEqualTo("-2 °C · hoar frost likely");
        assertThat(factWithKey(topic, "mist").value()).isEqualTo("humidity 96%");
        assertThat(topic.note())
                .isEqualTo("densest before dawn, lifts mid-morning — rime on every branch");
        // The (i) "science" tooltip explains what hoar frost is (surfaced in the UI on the pill).
        assertThat(topic.description())
                .startsWith("Hoar frost is the feathery white ice");
    }

    @Test
    @DisplayName("air exactly 0 °C is not sub-zero — stays plain mist (boundary)")
    void detect_mist_zeroCelsius_notHoarFrost() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The Lake District", 0.08, 96, 150.0, 0.0)));
        stubAhead(FROM);

        HotTopic topic = strategy.detect(FROM, TO).get(0);

        assertThat(topic.label()).isEqualTo("Fresh snow with mist");
        assertThat(topic.facts()).noneMatch(f -> "air".equals(f.key()));
    }

    @Test
    @DisplayName("a barely sub-zero reading shows '-1 °C', never a self-contradictory '0 °C · hoar frost'")
    void detect_mist_nearZeroSubZero_neverShowsZero() {
        // -0.3 °C is genuinely sub-zero (gate fires) but rounds to 0 — the chip must not read "0 °C".
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The Lake District", 0.08, 96, 150.0, -0.3)));
        stubAhead(FROM);

        HotTopic topic = strategy.detect(FROM, TO).get(0);

        assertThat(topic.label()).isEqualTo("Snow mist & hoar frost");
        assertThat(factWithKey(topic, "air").value()).isEqualTo("-1 °C · hoar frost likely");
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

    @Test
    @DisplayName("a snow row whose solar event has passed is dropped")
    void detect_expiredEvent_dropped() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The Lake District", 0.05, 70)));
        when(freshness.isAhead(any(LocationEntity.class), eq(FROM), eq(TargetType.SUNRISE)))
                .thenReturn(false);

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("enumerates every non-expired snow day; dates to the earliest")
    void detect_multipleDays_enumeratesAll() {
        when(survivorSignalReader.read(FROM, TO)).thenReturn(List.of(
                signal(FROM.plusDays(1), "The North York Moors", 0.05, 70),
                signal(FROM, "The Lake District", 0.05, 70)));
        stubAhead(FROM, FROM.plusDays(1));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(FROM);
        assertThat(topics.get(0).detail()).endsWith("today and tomorrow");
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
