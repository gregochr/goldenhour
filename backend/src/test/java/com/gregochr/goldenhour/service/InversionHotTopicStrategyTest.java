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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
        // Default: every morning is still ahead. Expired-day tests override per date.
        lenient().when(freshness.isAhead(any(LocationEntity.class), any(), any()))
                .thenReturn(true);
        strategy = new InversionHotTopicStrategy(survivorSignalReader, freshness);
    }

    /** A survivor composite carrying an inversion score and nothing else. */
    private static SurvivorSignals signal(LocalDate date, String regionName, int inversion) {
        LocationEntity location = new LocationEntity();
        if (regionName != null) {
            RegionEntity region = new RegionEntity();
            region.setName(regionName);
            location.setRegion(region);
        }
        return new SurvivorSignals(location, date, TargetType.SUNRISE,
                new SurvivorSignals.Scores(inversion, null, null),
                SurvivorSignals.Readings.EMPTY);
    }

    @Test
    @DisplayName("strong inversion survivor fires with priority 2 off the survivor surface")
    void detect_strongInversion_fires() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The North York Moors", 9)));

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
        assertThat(strategy.detect(FROM, TO)).hasSize(1);
    }

    @Test
    @DisplayName("today's inversion is suppressed once its sunrise has passed")
    void detect_todayPastSunrise_suppressed() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The North York Moors", 9)));
        when(freshness.isAhead(any(LocationEntity.class), eq(FROM), any())).thenReturn(false);

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("past today's sunrise, the topic rolls forward to the next strong morning")
    void detect_todayPastSunrise_rollsForwardToFutureDay() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signal(FROM, "Today Region", 9),
                        signal(FROM.plusDays(1), "Tomorrow Region", 9)));
        when(freshness.isAhead(any(LocationEntity.class), eq(FROM), any())).thenReturn(false);

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
    @DisplayName("null region in a strong row is skipped")
    void detect_nullRegion_skipped() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signal(FROM, null, 9),
                        signal(FROM, "The North York Moors", 9)));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics.get(0).regions()).containsExactly("The North York Moors");
    }
}
