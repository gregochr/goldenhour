package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
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
 * Unit tests for {@link SupermoonHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class SupermoonHotTopicStrategyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO_DATE = TODAY.plusDays(3);

    @Mock
    private LunarPhaseService lunarPhaseService;

    @Mock
    private LocationRepository locationRepository;

    private SupermoonHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SupermoonHotTopicStrategy(lunarPhaseService, locationRepository);
    }

    @Test
    @DisplayName("full moon within 3 days of perigee fires with priority 5 and coastal regions")
    void detect_fullMoonNearPerigee_fires() {
        when(lunarPhaseService.isFullMoon(TODAY)).thenReturn(true);
        when(lunarPhaseService.daysFromNearestPerigee(TODAY)).thenReturn(2.0);
        when(locationRepository.findCoastalLocations()).thenReturn(coastalLocations());

        List<HotTopic> topics = strategy.detect(TODAY, TODAY);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("SUPERMOON");
        assertThat(topic.priority()).isEqualTo(5);
        assertThat(topic.date()).isEqualTo(TODAY);
        assertThat(topic.detail()).contains("today");
        assertThat(topic.regions()).containsExactly("The North Yorkshire Coast");
    }

    @Test
    @DisplayName("exactly 3 days from perigee still fires (boundary inclusive)")
    void detect_exactlyThreeDaysFromPerigee_fires() {
        when(lunarPhaseService.isFullMoon(TODAY)).thenReturn(true);
        when(lunarPhaseService.daysFromNearestPerigee(TODAY)).thenReturn(3.0);
        when(locationRepository.findCoastalLocations()).thenReturn(coastalLocations());

        assertThat(strategy.detect(TODAY, TODAY)).hasSize(1);
    }

    @Test
    @DisplayName("just beyond 3 days from perigee does not fire (boundary)")
    void detect_justBeyondPerigeeWindow_doesNotFire() {
        when(lunarPhaseService.isFullMoon(TODAY)).thenReturn(true);
        when(lunarPhaseService.daysFromNearestPerigee(TODAY)).thenReturn(3.01);

        assertThat(strategy.detect(TODAY, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("full moon not at perigee does not fire")
    void detect_fullMoonFarFromPerigee_doesNotFire() {
        when(lunarPhaseService.isFullMoon(TODAY)).thenReturn(true);
        when(lunarPhaseService.daysFromNearestPerigee(TODAY)).thenReturn(6.0);

        assertThat(strategy.detect(TODAY, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("not a full moon does not fire even at perigee")
    void detect_notFullMoon_doesNotFire() {
        when(lunarPhaseService.isFullMoon(TODAY)).thenReturn(false);

        assertThat(strategy.detect(TODAY, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("emits on the earliest qualifying day within the 4-day window")
    void detect_qualifyingDayMidWindow_datesToThatDay() {
        LocalDate tomorrow = TODAY.plusDays(1);
        when(lunarPhaseService.isFullMoon(TODAY)).thenReturn(false);
        when(lunarPhaseService.isFullMoon(tomorrow)).thenReturn(true);
        when(lunarPhaseService.daysFromNearestPerigee(tomorrow)).thenReturn(1.0);
        when(locationRepository.findCoastalLocations()).thenReturn(coastalLocations());

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(tomorrow);
        assertThat(topics.get(0).detail()).contains("tomorrow");
    }

    private List<LocationEntity> coastalLocations() {
        RegionEntity region = new RegionEntity();
        region.setName("The North Yorkshire Coast");
        return List.of(LocationEntity.builder().name("Saltwick Bay").region(region).build());
    }
}
