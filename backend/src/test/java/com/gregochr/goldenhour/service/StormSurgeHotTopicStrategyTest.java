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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StormSurgeHotTopicStrategy} — reads the survivor surface and fires at
 * the HIGH band only.
 */
@ExtendWith(MockitoExtension.class)
class StormSurgeHotTopicStrategyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = FROM.plusDays(3);

    @Mock
    private SurvivorSignalReader survivorSignalReader;

    @Mock
    private StormSurgeFactsBuilder stormSurgeFactsBuilder;

    private StormSurgeHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        lenient().when(stormSurgeFactsBuilder.attach(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        strategy = new StormSurgeHotTopicStrategy(survivorSignalReader, stormSurgeFactsBuilder);
    }

    /** A survivor composite carrying only a surge risk-level reading. */
    private static SurvivorSignals signal(LocalDate date, String regionName, String riskLevel) {
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        LocationEntity location = new LocationEntity();
        location.setRegion(region);
        SurvivorSignals.Readings readings = new SurvivorSignals.Readings(
                null, null, null, riskLevel, null, null, null, null, null, null);
        return new SurvivorSignals(location, date, TargetType.SUNSET,
                SurvivorSignals.Scores.EMPTY, readings);
    }

    @Test
    @DisplayName("high surge-risk survivor fires with priority 1 off the survivor surface")
    void detect_highRisk_fires() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "Northumberland", "HIGH")));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("STORM_SURGE");
        assertThat(topic.priority()).isEqualTo(1);
        assertThat(topic.date()).isEqualTo(FROM);
        assertThat(topic.regions()).containsExactly("Northumberland");
    }

    @Test
    @DisplayName("boundary: a MODERATE survivor does not fire — HIGH only")
    void detect_moderate_doesNotFire() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "Northumberland", "MODERATE")));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("no survivor rows does not fire")
    void detect_noRows_doesNotFire() {
        when(survivorSignalReader.read(FROM, TO)).thenReturn(List.of());

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("multiple HIGH rows: one card per day, each with that day's regions")
    void detect_multipleRows_onePerDate() {
        LocalDate later = FROM.plusDays(1);
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signal(FROM, "Northumberland", "HIGH"),
                        signal(later, "The North Yorkshire Coast", "HIGH")));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(2);
        assertThat(topics).extracting(HotTopic::date).containsExactly(FROM, later);
        assertThat(topics.get(0).regions()).containsExactly("Northumberland");
        assertThat(topics.get(1).regions()).containsExactly("The North Yorkshire Coast");
    }
}
