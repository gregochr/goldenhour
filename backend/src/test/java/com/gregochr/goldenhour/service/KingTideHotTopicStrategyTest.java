package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KingTideHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class KingTideHotTopicStrategyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 16);
    private static final LocalDate TO_DATE = TODAY.plusDays(3);

    @Mock
    private LunarPhaseService lunarPhaseService;

    @Mock
    private LocationRepository locationRepository;

    private KingTideHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new KingTideHotTopicStrategy(lunarPhaseService, locationRepository);
    }

    @Test
    @DisplayName("king tide today emits pill with priority 1 and 'today' label")
    void detect_kingTideToday_emitsPriority1() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations("Northumberland", "The North Yorkshire Coast");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("KING_TIDE");
        assertThat(topic.label()).isEqualTo("King tide");
        assertThat(topic.priority()).isEqualTo(1);
        assertThat(topic.date()).isEqualTo(TODAY);
        assertThat(topic.detail()).contains("today");
        assertThat(topic.detail()).contains("Rare extreme tidal range");
        assertThat(topic.regions()).containsExactly("Northumberland",
                "The North Yorkshire Coast");
        assertThat(topic.description()).contains("King tides");
        assertThat(topic.filterAction()).isNull();
    }

    @Test
    @DisplayName("spring tide (not king) emits nothing from this strategy")
    void detect_springTideNotKing_emitsNothing() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.SPRING_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(1)))
                .thenReturn(LunarTideType.SPRING_TIDE);
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
    @DisplayName("king tide on T+2 emits pill with day-of-week label")
    void detect_kingTideInTwoDays_emitsWithDayName() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(1)))
                .thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(2)))
                .thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.date()).isEqualTo(TODAY.plusDays(2));
        // 2026-04-18 is a Saturday
        assertThat(topic.detail()).contains("Saturday");
    }

    @Test
    @DisplayName("king tide on T+1 emits pill with 'tomorrow' label")
    void detect_kingTideTomorrow_emitsWithTomorrowLabel() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(1)))
                .thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).detail()).contains("tomorrow");
    }

    @Test
    @DisplayName("only one pill emitted even when multiple king tide days exist")
    void detect_multipleKingTideDays_emitsOnlyFirst() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
    }

    // ── Early return / loop boundary ─────────────────────────────────────────

    @Test
    @DisplayName("stops scanning after first king tide — does not call classifyTide for later days")
    void detect_kingTideOnFirstDay_stopsScanning() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        when(lunarPhaseService.getMoonPhase(TODAY)).thenReturn("Full Moon");
        stubCoastalLocations("Northumberland");

        strategy.detect(TODAY, TO_DATE);

        verify(lunarPhaseService).classifyTide(TODAY);
        verify(lunarPhaseService).getMoonPhase(TODAY);
        verifyNoMoreInteractions(lunarPhaseService);
    }

    @Test
    @DisplayName("king tide on toDate boundary (last day of window) is detected")
    void detect_kingTideOnLastDay_detected() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(1)))
                .thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(2)))
                .thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TO_DATE))
                .thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TO_DATE);
    }

    @Test
    @DisplayName("single-day window with king tide emits pill")
    void detect_singleDayWindow_kingTide_emits() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TODAY);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
        assertThat(topics.get(0).detail()).contains("today");
    }

    @Test
    @DisplayName("single-day window with regular tide emits nothing")
    void detect_singleDayWindow_regularTide_emitsNothing() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.REGULAR_TIDE);

        List<HotTopic> topics = strategy.detect(TODAY, TODAY);

        assertThat(topics).isEmpty();
    }

    // ── Region edge cases ────────────────────────────────────────────────────

    @Test
    @DisplayName("duplicate regions from multiple coastal locations are deduplicated")
    void detect_duplicateRegions_deduplicated() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);

        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");

        LocationEntity loc1 = LocationEntity.builder()
                .id(1L).name("Craster").lat(55.47).lon(-1.59)
                .tideType(Set.of(TideType.HIGH)).region(region).enabled(true).build();
        LocationEntity loc2 = LocationEntity.builder()
                .id(2L).name("Bamburgh").lat(55.61).lon(-1.71)
                .tideType(Set.of(TideType.LOW)).region(region).enabled(true).build();

        when(locationRepository.findCoastalLocations()).thenReturn(List.of(loc1, loc2));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).regions()).containsExactly("Northumberland");
    }

    @Test
    @DisplayName("locations with null region are filtered out")
    void detect_nullRegion_filteredOut() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);

        RegionEntity validRegion = new RegionEntity();
        validRegion.setName("Northumberland");

        LocationEntity withRegion = LocationEntity.builder()
                .id(1L).name("Craster").lat(55.47).lon(-1.59)
                .tideType(Set.of(TideType.HIGH)).region(validRegion).enabled(true).build();
        LocationEntity noRegion = LocationEntity.builder()
                .id(2L).name("Orphan Cove").lat(54.0).lon(-1.0)
                .tideType(Set.of(TideType.HIGH)).region(null).enabled(true).build();

        when(locationRepository.findCoastalLocations())
                .thenReturn(List.of(withRegion, noRegion));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).regions()).containsExactly("Northumberland");
    }

    @Test
    @DisplayName("no coastal locations produces empty regions list")
    void detect_noCoastalLocations_emptyRegions() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        when(locationRepository.findCoastalLocations()).thenReturn(List.of());

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).regions()).isEmpty();
    }

    // ── Interaction verification ─────────────────────────────────────────────

    @Test
    @DisplayName("does not query location repository when no king tide found")
    void detect_noKingTide_noLocationQuery() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(1)))
                .thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(2)))
                .thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(3)))
                .thenReturn(LunarTideType.REGULAR_TIDE);

        strategy.detect(TODAY, TO_DATE);

        verifyNoInteractions(locationRepository);
    }

    // ── expandedDetail tests ────────────────────────────────────────────────

    @Test
    @DisplayName("expandedDetail populated with regionGroups of coastal locations")
    void detect_expandedDetail_populatedWithRegionGroups() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        when(lunarPhaseService.getMoonPhase(TODAY)).thenReturn("Full Moon");

        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        LocationEntity loc = LocationEntity.builder()
                .id(1L).name("Craster").lat(55.47).lon(-1.59)
                .tideType(Set.of(TideType.HIGH)).region(region).enabled(true).build();
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(loc));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        ExpandedHotTopicDetail detail = topics.get(0).expandedDetail();
        assertThat(detail).isNotNull();
        assertThat(detail.regionGroups()).hasSize(1);
        assertThat(detail.regionGroups().get(0).regionName()).isEqualTo("Northumberland");
        assertThat(detail.regionGroups().get(0).locations()).hasSize(1);
        assertThat(detail.regionGroups().get(0).locations().get(0).locationName())
                .isEqualTo("Craster");
        assertThat(detail.regionGroups().get(0).locations().get(0).locationType())
                .isEqualTo("Coastal");
    }

    @Test
    @DisplayName("tideMetrics has correct classification and lunar phase")
    void detect_expandedDetail_tideMetricsCorrect() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        when(lunarPhaseService.getMoonPhase(TODAY)).thenReturn("New Moon");
        stubCoastalLocations("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        var metrics = topics.get(0).expandedDetail().tideMetrics();
        assertThat(metrics.tidalClassification()).isEqualTo("King tide");
        assertThat(metrics.lunarPhase()).isEqualTo("New Moon");
        assertThat(metrics.coastalLocationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("locations sorted alphabetically within regions")
    void detect_expandedDetail_locationsSortedAlphabetically() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        when(lunarPhaseService.getMoonPhase(TODAY)).thenReturn("Full Moon");

        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        LocationEntity loc1 = LocationEntity.builder()
                .id(1L).name("Craster").lat(55.47).lon(-1.59)
                .tideType(Set.of(TideType.HIGH)).region(region).enabled(true).build();
        LocationEntity loc2 = LocationEntity.builder()
                .id(2L).name("Bamburgh").lat(55.61).lon(-1.71)
                .tideType(Set.of(TideType.LOW)).region(region).enabled(true).build();
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(loc1, loc2));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        var locations = topics.get(0).expandedDetail().regionGroups().get(0).locations();
        assertThat(locations).hasSize(2);
        assertThat(locations.get(0).locationName()).isEqualTo("Bamburgh");
        assertThat(locations.get(1).locationName()).isEqualTo("Craster");
    }

    @Test
    @DisplayName("regions sorted alphabetically in expandedDetail")
    void detect_expandedDetail_regionsSortedAlphabetically() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        when(lunarPhaseService.getMoonPhase(TODAY)).thenReturn("Full Moon");

        RegionEntity r1 = new RegionEntity();
        r1.setName("The North Yorkshire Coast");
        RegionEntity r2 = new RegionEntity();
        r2.setName("Northumberland");

        LocationEntity loc1 = LocationEntity.builder()
                .id(1L).name("Whitby").lat(54.48).lon(-0.62)
                .tideType(Set.of(TideType.HIGH)).region(r1).enabled(true).build();
        LocationEntity loc2 = LocationEntity.builder()
                .id(2L).name("Craster").lat(55.47).lon(-1.59)
                .tideType(Set.of(TideType.HIGH)).region(r2).enabled(true).build();
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(loc1, loc2));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        var groups = topics.get(0).expandedDetail().regionGroups();
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).regionName()).isEqualTo("Northumberland");
        assertThat(groups.get(1).regionName()).isEqualTo("The North Yorkshire Coast");
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
