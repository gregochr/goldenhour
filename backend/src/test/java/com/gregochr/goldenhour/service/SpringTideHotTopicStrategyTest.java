package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TideType;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SpringTideHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class SpringTideHotTopicStrategyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 16);
    private static final LocalDate TO_DATE = TODAY.plusDays(3);

    @Mock
    private LunarPhaseService lunarPhaseService;

    @Mock
    private LocationRepository locationRepository;

    private SpringTideHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SpringTideHotTopicStrategy(lunarPhaseService, locationRepository);
    }

    @Test
    @DisplayName("spring tide today emits pill with priority 2 and 'today' label")
    void detect_springTideToday_emitsPriority2() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.SPRING_TIDE);
        stubCoastalLocations("The North Yorkshire Coast");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("SPRING_TIDE");
        assertThat(topic.label()).isEqualTo("Spring tide");
        assertThat(topic.priority()).isEqualTo(2);
        assertThat(topic.date()).isEqualTo(TODAY);
        assertThat(topic.detail()).contains("today");
        assertThat(topic.detail()).contains("Large tidal range");
        assertThat(topic.regions()).containsExactly("The North Yorkshire Coast");
        assertThat(topic.description()).contains("Spring tides");
        assertThat(topic.filterAction()).isNull();
    }

    @Test
    @DisplayName("king tide emits nothing from spring tide strategy (no duplication)")
    void detect_kingTide_emitsNothing() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(1)))
                .thenReturn(LunarTideType.KING_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(2)))
                .thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(3)))
                .thenReturn(LunarTideType.REGULAR_TIDE);

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("regular tide emits nothing")
    void detect_regularTide_emitsNothing() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(1)))
                .thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(2)))
                .thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(3)))
                .thenReturn(LunarTideType.REGULAR_TIDE);

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("spring tide on T+1 emits pill with 'tomorrow' label")
    void detect_springTideTomorrow_emitsWithTomorrowLabel() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(1)))
                .thenReturn(LunarTideType.SPRING_TIDE);
        stubCoastalLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).detail()).contains("tomorrow");
        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(1));
    }

    @Test
    @DisplayName("spring tide on T+2 emits pill with day-of-week label")
    void detect_springTideInTwoDays_emitsWithDayName() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(1)))
                .thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(2)))
                .thenReturn(LunarTideType.SPRING_TIDE);
        stubCoastalLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        // 2026-04-18 is a Saturday
        assertThat(topics.get(0).detail()).contains("Saturday");
    }

    @Test
    @DisplayName("only one pill emitted even when multiple spring tide days exist")
    void detect_multipleSpringTideDays_emitsOnlyFirst() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.SPRING_TIDE);
        stubCoastalLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("multiple coastal regions included in pill")
    void detect_multipleCoastalRegions_allIncluded() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.SPRING_TIDE);
        stubCoastalLocations("Northumberland", "The North Yorkshire Coast",
                "Tyne and Wear");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).regions()).containsExactly("Northumberland",
                "The North Yorkshire Coast", "Tyne and Wear");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubCoastalLocations(String... regionNames) {
        List<LocationEntity> locations = new java.util.ArrayList<>();
        for (int i = 0; i < regionNames.length; i++) {
            RegionEntity region = new RegionEntity();
            region.setName(regionNames[i]);
            locations.add(LocationEntity.builder()
                    .id((long) (i + 1))
                    .name("Coastal " + (i + 1))
                    .lat(55.0 - i)
                    .lon(-1.5)
                    .tideType(Set.of(TideType.HIGH))
                    .region(region)
                    .enabled(true)
                    .build());
        }
        when(locationRepository.findCoastalLocations()).thenReturn(locations);
    }
}
