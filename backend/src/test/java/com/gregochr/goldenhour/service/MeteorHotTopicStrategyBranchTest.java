package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.MeteorClarity;
import com.gregochr.goldenhour.repository.LocationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
    private static final LocalDate ORIONIDS_PEAK = LocalDate.of(2026, 10, 21);

    @Mock
    private LunarPhaseService lunarPhaseService;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private MeteorClarityService meteorClarityService;

    private MeteorHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new MeteorHotTopicStrategy(lunarPhaseService, locationRepository, meteorClarityService);
    }

    private List<LocationEntity> darkSkyLocation() {
        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        return List.of(LocationEntity.builder().name("Kielder").bortleClass(2).region(region).build());
    }

    @Test
    @DisplayName("two qualifying peaks in one window: the earlier one is kept")
    void detect_twoPeaksInWindow_keepsEarliest() {
        when(lunarPhaseService.getIlluminationFraction(PERSEIDS_PEAK)).thenReturn(0.1);
        when(lunarPhaseService.getIlluminationFraction(ORIONIDS_PEAK)).thenReturn(0.1);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue()).thenReturn(darkSkyLocation());
        when(meteorClarityService.getCached()).thenReturn(MeteorClarity.EMPTY);

        List<HotTopic> topics = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 10, 25));

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(PERSEIDS_PEAK);
        assertThat(topics.get(0).detail()).startsWith("Perseids peak");
    }

    @Test
    @DisplayName("clear count above the scanned total is inconsistent and adds no clear-sky fact")
    void detect_clearAboveTotal_omitsFact() {
        when(lunarPhaseService.getIlluminationFraction(PERSEIDS_PEAK)).thenReturn(0.1);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue()).thenReturn(darkSkyLocation());
        when(meteorClarityService.getCached()).thenReturn(
                new MeteorClarity(Map.of(PERSEIDS_PEAK, new MeteorClarity.NightClarity(31, 30))));

        HotTopic topic = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13)).get(0);

        assertThat(topic.facts()).noneMatch(f -> f.value() != null && f.value().startsWith("clear at"));
        assertThat(topic.facts()).hasSize(3);
    }
}
