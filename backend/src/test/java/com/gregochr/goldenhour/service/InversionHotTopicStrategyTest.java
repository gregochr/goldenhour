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
 * Unit tests for {@link InversionHotTopicStrategy}.
 *
 * <p>The detector reads the unified survivor surface ({@link SurvivorSignalReader}), not
 * {@code forecast_evaluation}, and fires only at the STRONG band (score &ge;
 * {@code STRONG_SCORE_INCLUSIVE} = 9). Expired mornings are dropped via {@link SolarEventFreshness}
 * (mocked here), and every remaining strong-inversion morning is enumerated in the pill.
 */
@ExtendWith(MockitoExtension.class)
class InversionHotTopicStrategyTest {

    // 2026-06-17 is a Wednesday; +2 = Friday.
    private static final LocalDate FROM = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = FROM.plusDays(3);

    @Mock
    private SurvivorSignalReader survivorSignalReader;

    @Mock
    private SolarEventFreshness freshness;

    private InversionHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new InversionHotTopicStrategy(survivorSignalReader, freshness);
    }

    /** Keeps each given morning ahead of the freshness cutoff. Stubbed per-test (only the tests that
     * reach the freshness filter need it) rather than leniently; the date + SUNRISE event are pinned,
     * the location varies per row and is not the discriminator (the filter is per-morning). */
    private void stubAhead(LocalDate... mornings) {
        for (LocalDate morning : mornings) {
            when(freshness.isAhead(any(LocationEntity.class), eq(morning), eq(TargetType.SUNRISE)))
                    .thenReturn(true);
        }
    }

    private static HotTopicFact factWithKey(HotTopic topic, String key) {
        return topic.facts().stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    /** A SUNRISE survivor composite carrying an inversion score and nothing else. */
    private static SurvivorSignals signal(LocalDate date, String regionName, int inversion) {
        return signalAt(date, regionName, inversion, TargetType.SUNRISE);
    }

    /** A survivor composite for a specific solar event, carrying an inversion score. */
    private static SurvivorSignals signalAt(LocalDate date, String regionName, int inversion,
            TargetType eventType) {
        LocationEntity location = new LocationEntity();
        if (regionName != null) {
            RegionEntity region = new RegionEntity();
            region.setName(regionName);
            location.setRegion(region);
        }
        return new SurvivorSignals(location, date, eventType,
                new SurvivorSignals.Scores(inversion, null, null),
                SurvivorSignals.Readings.EMPTY);
    }

    @Test
    @DisplayName("strong inversion survivor fires with priority 2 off the survivor surface")
    void detect_strongInversion_fires() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The North York Moors", 9)));
        stubAhead(FROM);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("INVERSION");
        assertThat(topic.priority()).isEqualTo(2);
        assertThat(topic.date()).isEqualTo(FROM);
        assertThat(topic.detail()).isEqualTo("Strong inversion likely at elevated locations");
        assertThat(topic.regions()).containsExactly("The North York Moors");
    }

    @Test
    @DisplayName("fact line shows the strength band; no fabricated inversion-top altitude")
    void detect_strongInversion_factLine() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The North York Moors", 9)));
        stubAhead(FROM);

        HotTopic topic = strategy.detect(FROM, TO).get(0);

        assertThat(factWithKey(topic, "inversion").value()).isEqualTo("9/10 · strong");
        assertThat(topic.facts()).noneMatch(f -> f.value() != null && f.value().contains(" m"));
        assertThat(topic.note())
                .isEqualTo("climb above it — the valleys fill with cloud, burning off after sunrise");
    }

    @Test
    @DisplayName("the strongest of a day's rows drives the score fact")
    void detect_representative_highestScore() {
        when(survivorSignalReader.read(FROM, TO)).thenReturn(List.of(
                signal(FROM, "The Lake District", 9),
                signal(FROM, "The Lake District", 10)));
        stubAhead(FROM);

        HotTopic topic = strategy.detect(FROM, TO).get(0);

        assertThat(factWithKey(topic, "inversion").value()).isEqualTo("10/10 · strong");
    }

    @Test
    @DisplayName("no survivor rows does not fire")
    void detect_noRows_doesNotFire() {
        when(survivorSignalReader.read(FROM, TO)).thenReturn(List.of());

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("boundary: score 9 fires (STRONG), score 8 does not (MODERATE)")
    void detect_strongBandBoundary() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The Lake District", 8)));
        assertThat(strategy.detect(FROM, TO)).isEmpty();

        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The Lake District", 9)));
        stubAhead(FROM);
        assertThat(strategy.detect(FROM, TO)).hasSize(1);
    }

    @Test
    @DisplayName("today's inversion is suppressed once its sunrise has passed")
    void detect_todayPastSunrise_suppressed() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The North York Moors", 9)));
        when(freshness.isAhead(any(LocationEntity.class), eq(FROM), eq(TargetType.SUNRISE)))
                .thenReturn(false);

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("past today's sunrise, the topic rolls forward to the next strong morning")
    void detect_todayPastSunrise_rollsForwardToFutureDay() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signal(FROM, "Today Region", 9),
                        signal(FROM.plusDays(1), "Tomorrow Region", 9)));
        when(freshness.isAhead(any(LocationEntity.class), eq(FROM), eq(TargetType.SUNRISE)))
                .thenReturn(false);
        stubAhead(FROM.plusDays(1));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(FROM.plusDays(1));
        assertThat(topics.get(0).detail()).isEqualTo("Strong inversion likely at elevated locations");
        assertThat(topics.get(0).regions()).containsExactly("Tomorrow Region");
    }

    @Test
    @DisplayName("emits one card per non-expired morning, each with that day's regions")
    void detect_multipleDays_onePerDate() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signal(FROM.plusDays(2), "Northumberland", 9),
                        signal(FROM, "The North York Moors", 9),
                        signal(FROM, "The Lake District", 10)));
        stubAhead(FROM, FROM.plusDays(2));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(2);
        assertThat(topics).extracting(HotTopic::date)
                .containsExactly(FROM, FROM.plusDays(2));
        assertThat(topics.get(0).regions())
                .containsExactly("The North York Moors", "The Lake District");
        assertThat(topics.get(1).regions()).containsExactly("Northumberland");
        // No spanning day phrase — the day is carried by each card's own date.
        assertThat(topics).noneMatch(t -> t.detail().contains("and Friday"));
    }

    @Test
    @DisplayName("sunset inversion rows are ignored — inversion is a dawn-only topic")
    void detect_sunsetRow_ignored() {
        // A SUNSET inversion row would pass the (stubbed-ahead) freshness check, but must be
        // dropped up front: a sea of clouds is a dawn event, so this-morning's inversion must not
        // linger into the evening on the strength of the still-future sunset.
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signalAt(FROM, "The North York Moors", 9, TargetType.SUNSET)));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("mixed sunrise + sunset rows: only the sunrise row drives the card")
    void detect_mixedRows_onlySunriseCounts() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signalAt(FROM, "Sunset Region", 9, TargetType.SUNSET),
                        signalAt(FROM, "Sunrise Region", 9, TargetType.SUNRISE)));
        stubAhead(FROM);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(FROM);
        assertThat(topics.get(0).regions()).containsExactly("Sunrise Region");
    }

    @Test
    @DisplayName("null region in a strong row is skipped")
    void detect_nullRegion_skipped() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signal(FROM, null, 9),
                        signal(FROM, "The North York Moors", 9)));
        stubAhead(FROM);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics.get(0).regions()).containsExactly("The North York Moors");
    }
}
