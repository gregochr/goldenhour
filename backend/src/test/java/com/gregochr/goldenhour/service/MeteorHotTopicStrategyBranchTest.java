package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.MeteorClarity;
import com.gregochr.goldenhour.repository.LocationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Branch tests for {@link MeteorHotTopicStrategy}: the earliest qualifying peak wins when two
 * showers sit in one window, and an inconsistent clarity count adds no clear-sky fact.
 */
@ExtendWith(MockitoExtension.class)
class MeteorHotTopicStrategyBranchTest {

    private static final LocalDate PERSEIDS_PEAK = LocalDate.of(2026, 8, 12);
    private static final LocalDate ORIONIDS_PEAK = LocalDate.of(2026, 10, 20);

    @Mock
    private MeteorMoonGate moonGate;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private MeteorClarityService meteorClarityService;

    private MeteorHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new MeteorHotTopicStrategy(moonGate, locationRepository, meteorClarityService,
                MeteorPeakCalendars.empty());
    }

    private static MeteorHotTopicStrategy.Shower shower(String name) {
        return MeteorHotTopicStrategy.SHOWERS.stream()
                .filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    private void stubMoon(String name, LocalDate night, int pct) {
        when(moonGate.assess(shower(name), night)).thenReturn(
                new MeteorMoonGate.MoonOverShower(pct, false, false, Optional.empty(), Optional.empty()));
    }

    private List<LocationEntity> darkSkyLocation() {
        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        return List.of(LocationEntity.builder().name("Kielder").bortleClass(2).region(region).build());
    }

    @Test
    @DisplayName("two qualifying peaks in one window: the earlier one is kept")
    void detect_twoPeaksInWindow_keepsEarliest() {
        stubMoon("Perseids", PERSEIDS_PEAK, 10);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue()).thenReturn(darkSkyLocation());
        when(meteorClarityService.getCached()).thenReturn(MeteorClarity.EMPTY);

        List<HotTopic> topics = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 10, 25));

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(PERSEIDS_PEAK);
        assertThat(topics.get(0).detail()).startsWith("Perseids peak");
        // The peaks are walked in date order, so the later Orionids night is never assessed.
        verify(moonGate, never()).assess(shower("Orionids"), ORIONIDS_PEAK);
    }

    @Test
    @DisplayName("a minor peak earlier in the window is skipped: the later Leonids are chosen, and the "
            + "minor night's moon is never read")
    void detect_minorThenMajor_choosesMajor() {
        LocalDate leonids = LocalDate.of(2026, 11, 17);
        stubMoon("Leonids", leonids, 10);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue()).thenReturn(darkSkyLocation());
        when(meteorClarityService.getCached()).thenReturn(MeteorClarity.EMPTY);

        List<HotTopic> topics = strategy.detect(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 20));

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).detail()).startsWith("Leonids peak");
        verify(moonGate, never()).assess(shower("N. Taurids"), LocalDate.of(2026, 11, 12));
    }

    @Test
    @DisplayName("clear count above the scanned total is inconsistent and adds no clear-sky fact")
    void detect_clearAboveTotal_omitsFact() {
        stubMoon("Perseids", PERSEIDS_PEAK, 10);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue()).thenReturn(darkSkyLocation());
        when(meteorClarityService.getCached()).thenReturn(
                new MeteorClarity(Map.of(PERSEIDS_PEAK, new MeteorClarity.NightClarity(31, 30))));

        HotTopic topic = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13)).get(0);

        assertThat(topic.facts()).noneMatch(f -> f.value() != null && f.value().startsWith("clear at"));
        assertThat(topic.facts()).hasSize(3);
    }
}
