package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.repository.LocationRepository;
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
 * Unit tests for {@link MeteorHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class MeteorHotTopicStrategyTest {

    private static final LocalDate PERSEIDS_PEAK = LocalDate.of(2026, 8, 12);

    @Mock
    private LunarPhaseService lunarPhaseService;

    @Mock
    private LocationRepository locationRepository;

    private MeteorHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new MeteorHotTopicStrategy(lunarPhaseService, locationRepository);
    }

    @Test
    @DisplayName("shower peak in window with dark moon fires with priority 7 and shower name")
    void detect_peakInWindowDarkMoon_fires() {
        when(lunarPhaseService.getIlluminationFraction(PERSEIDS_PEAK)).thenReturn(0.2);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());

        List<HotTopic> topics = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13));

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("METEOR");
        assertThat(topic.priority()).isEqualTo(7);
        assertThat(topic.date()).isEqualTo(PERSEIDS_PEAK);
        assertThat(topic.detail()).contains("Perseids");

        // Perseids catalogue constants: ZHR ~100, radiant NE, best 01:00–04:00; moon 20% is dark.
        assertThat(factWithKey(topic, "ZHR").value()).isEqualTo("~100 at peak");
        HotTopicFact radiant = factWithKey(topic, "radiant");
        assertThat(radiant.value()).isEqualTo("best 01:00–04:00");
        assertThat(radiant.dir()).isEqualTo("NE");
        HotTopicFact moon = factWithKey(topic, "moon");
        assertThat(moon.value()).isEqualTo("20% · dark enough");
        assertThat(moon.optional()).isTrue();
    }

    @Test
    @DisplayName("a half-lit peak still under the gate reads 'some moonlight' in both headline and chip")
    void detect_someMoonlightPeak_headlineAndChipAgree() {
        when(lunarPhaseService.getIlluminationFraction(PERSEIDS_PEAK)).thenReturn(0.40);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());

        HotTopic topic = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13)).get(0);

        // The headline must not claim "dark moon" while the chip reports the sky is 40% lit.
        assertThat(topic.detail()).isEqualTo("Perseids peak — some moonlight, still worth a look");
        assertThat(factWithKey(topic, "moon").value()).isEqualTo("40% · some moonlight");
    }

    @Test
    @DisplayName("moon exactly at the 25% dark band boundary reads 'some moonlight'")
    void detect_moonAtDarkBandBoundary_someMoonlight() {
        when(lunarPhaseService.getIlluminationFraction(PERSEIDS_PEAK)).thenReturn(0.25);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());

        HotTopic topic = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13)).get(0);

        assertThat(topic.detail()).isEqualTo("Perseids peak — some moonlight, still worth a look");
        assertThat(factWithKey(topic, "moon").value()).isEqualTo("25% · some moonlight");
    }

    @Test
    @DisplayName("moon just below the 25% boundary reads 'dark moon' / 'dark enough'")
    void detect_moonJustBelowDarkBandBoundary_darkMoon() {
        when(lunarPhaseService.getIlluminationFraction(PERSEIDS_PEAK)).thenReturn(0.24);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());

        HotTopic topic = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13)).get(0);

        assertThat(topic.detail()).isEqualTo("Perseids peak — dark moon, good viewing");
        assertThat(factWithKey(topic, "moon").value()).isEqualTo("24% · dark enough");
    }

    @Test
    @DisplayName("illumination just below 50% fires (boundary)")
    void detect_illuminationJustBelowHalf_fires() {
        when(lunarPhaseService.getIlluminationFraction(PERSEIDS_PEAK)).thenReturn(0.49);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());

        assertThat(strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13))).hasSize(1);
    }

    @Test
    @DisplayName("illumination exactly 50% does not fire (boundary)")
    void detect_illuminationExactlyHalf_doesNotFire() {
        when(lunarPhaseService.getIlluminationFraction(PERSEIDS_PEAK)).thenReturn(0.5);

        assertThat(strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13))).isEmpty();
    }

    @Test
    @DisplayName("bright moon at peak does not fire")
    void detect_brightMoon_doesNotFire() {
        when(lunarPhaseService.getIlluminationFraction(PERSEIDS_PEAK)).thenReturn(0.9);

        assertThat(strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13))).isEmpty();
    }

    @Test
    @DisplayName("no shower peak in window does not fire")
    void detect_noPeakInWindow_doesNotFire() {
        assertThat(strategy.detect(LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 20))).isEmpty();
    }

    @Test
    @DisplayName("window straddling the year boundary finds the next-year Quadrantids peak")
    void detect_windowStraddlesYearBoundary_findsPeak() {
        LocalDate quadrantids = LocalDate.of(2027, 1, 3);
        when(lunarPhaseService.getIlluminationFraction(quadrantids)).thenReturn(0.1);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());

        List<HotTopic> topics = strategy.detect(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 3));

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(quadrantids);
        assertThat(topics.get(0).detail()).contains("Quadrantids");
    }

    private static HotTopicFact factWithKey(HotTopic topic, String key) {
        return topic.facts().stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    private List<LocationEntity> darkSkyLocation() {
        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        return List.of(LocationEntity.builder()
                .name("Kielder").bortleClass(2).region(region).build());
    }
}
