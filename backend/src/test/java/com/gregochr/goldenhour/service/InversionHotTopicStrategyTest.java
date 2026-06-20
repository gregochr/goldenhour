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
 * Unit tests for {@link InversionHotTopicStrategy}.
 *
 * <p>The detector reads the unified survivor surface ({@link SurvivorSignalReader}), not
 * {@code forecast_evaluation}, and fires only at the STRONG band (score &ge;
 * {@code STRONG_SCORE_INCLUSIVE} = 9). The boundary is tested explicitly: 8 (MODERATE) never fires,
 * 9 does.
 */
@ExtendWith(MockitoExtension.class)
class InversionHotTopicStrategyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = FROM.plusDays(3);

    @Mock
    private SurvivorSignalReader survivorSignalReader;

    private InversionHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new InversionHotTopicStrategy(survivorSignalReader);
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
        // A MODERATE day (8) alongside no STRONG row → no topic.
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The Lake District", 8)));
        assertThat(strategy.detect(FROM, TO)).isEmpty();

        // The same location at 9 → fires.
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The Lake District", 9)));
        assertThat(strategy.detect(FROM, TO)).hasSize(1);
    }

    @Test
    @DisplayName("MODERATE rows mixed with a STRONG row: only the STRONG one drives the topic")
    void detect_moderateMixedWithStrong_onlyStrongFires() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signal(FROM, "Moderate-only Region", 8),
                        signal(FROM.plusDays(1), "Strong Region", 10)));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        // Dated to the STRONG day, and only the STRONG region is listed — the MODERATE row is dropped.
        assertThat(topics.get(0).date()).isEqualTo(FROM.plusDays(1));
        assertThat(topics.get(0).regions()).containsExactly("Strong Region");
    }

    @Test
    @DisplayName("multiple strong rows: dates to the earliest day, collects distinct regions")
    void detect_multipleRows_earliestDateDistinctRegions() {
        LocalDate later = FROM.plusDays(2);
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signal(later, "Northumberland", 9),
                        signal(FROM, "The North York Moors", 9),
                        signal(FROM, "The Lake District", 10),
                        signal(FROM, "The North York Moors", 10)));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(FROM);
        // Distinct regions, earliest-day regions first (stable sort by date), no duplicates.
        assertThat(topics.get(0).regions())
                .containsExactly("The North York Moors", "The Lake District", "Northumberland");
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
