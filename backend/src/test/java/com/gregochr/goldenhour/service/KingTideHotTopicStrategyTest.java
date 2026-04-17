package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
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

    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;

    private KingTideHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new KingTideHotTopicStrategy(lunarPhaseService, locationRepository,
                forecastEvaluationRepository);
    }

    @Test
    @DisplayName("king tide today emits pill with priority 1 and 'today' label")
    void detect_kingTideToday_emitsPriority1() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations(TODAY, "Northumberland", "The North Yorkshire Coast");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("KING_TIDE");
        assertThat(topic.label()).isEqualTo("King tide");
        assertThat(topic.priority()).isEqualTo(1);
        assertThat(topic.date()).isEqualTo(TODAY);
        assertThat(topic.detail()).contains("today");
        assertThat(topic.detail()).contains("Rare king tide");
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
        stubCoastalLocations(TODAY.plusDays(2), "Northumberland");

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
        stubCoastalLocations(TODAY.plusDays(1), "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).detail()).contains("tomorrow");
    }

    @Test
    @DisplayName("only one pill emitted even when multiple king tide days exist")
    void detect_multipleKingTideDays_emitsOnlyFirst() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations(TODAY, "Northumberland");

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
        stubCoastalLocations(TODAY, "Northumberland");

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
        stubCoastalLocations(TO_DATE, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TO_DATE);
    }

    @Test
    @DisplayName("single-day window with king tide emits pill")
    void detect_singleDayWindow_kingTide_emits() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations(TODAY, "Northumberland");

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
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());

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
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());

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
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());

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
        verifyNoInteractions(forecastEvaluationRepository);
    }

    // ── expandedDetail tests ────────────────────────────────────────────────

    @Test
    @DisplayName("expandedDetail populated with regionGroups of coastal locations")
    void detect_expandedDetail_populatedWithRegionGroups() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        when(lunarPhaseService.getMoonPhase(TODAY)).thenReturn("Full Moon");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());

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
    @DisplayName("tideMetrics has correct classification, lunar phase, and alignment counts")
    void detect_expandedDetail_tideMetricsCorrect() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        when(lunarPhaseService.getMoonPhase(TODAY)).thenReturn("New Moon");
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 3L},
                        new Object[]{TargetType.SUNSET, 2L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        var metrics = topics.get(0).expandedDetail().tideMetrics();
        assertThat(metrics.tidalClassification()).isEqualTo("King tide");
        assertThat(metrics.lunarPhase()).isEqualTo("New Moon");
        assertThat(metrics.sunriseAlignedCount()).isEqualTo(3);
        assertThat(metrics.sunsetAlignedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("locations sorted alphabetically within regions")
    void detect_expandedDetail_locationsSortedAlphabetically() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        when(lunarPhaseService.getMoonPhase(TODAY)).thenReturn("Full Moon");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());

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
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());

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

    // ── Alignment count tests ─────────────────────────────────────────────

    @Test
    @DisplayName("alignment query is called with the king tide date, not fromDate")
    void detect_kingTideOnT2_queriesAlignmentForCorrectDate() {
        LocalDate kingTideDate = TODAY.plusDays(2);
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(1)))
                .thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(kingTideDate))
                .thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations(kingTideDate, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(kingTideDate))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 5L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        verify(forecastEvaluationRepository).countTideAlignedByTargetType(kingTideDate);
        assertThat(topics.get(0).expandedDetail().tideMetrics().sunriseAlignedCount())
                .isEqualTo(5);
        assertThat(topics.get(0).expandedDetail().tideMetrics().sunsetAlignedCount())
                .isEqualTo(0);
    }

    @Test
    @DisplayName("only sunrise alignment — sunset count is zero")
    void detect_onlySunriseAligned_sunsetCountIsZero() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 7L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        var metrics = topics.get(0).expandedDetail().tideMetrics();
        assertThat(metrics.sunriseAlignedCount()).isEqualTo(7);
        assertThat(metrics.sunsetAlignedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("only sunset alignment — sunrise count is zero")
    void detect_onlySunsetAligned_sunriseCountIsZero() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNSET, 4L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        var metrics = topics.get(0).expandedDetail().tideMetrics();
        assertThat(metrics.sunriseAlignedCount()).isEqualTo(0);
        assertThat(metrics.sunsetAlignedCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("no aligned locations — both counts are zero")
    void detect_noAlignedLocations_bothCountsZero() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        var metrics = topics.get(0).expandedDetail().tideMetrics();
        assertThat(metrics.sunriseAlignedCount()).isEqualTo(0);
        assertThat(metrics.sunsetAlignedCount()).isEqualTo(0);
    }

    // ── Detail line copy tests ──────────────────────────────────────────────

    @Test
    @DisplayName("detail line — both sunrise and sunset aligned")
    void detect_bothAligned_detailShowsBothCounts() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 9L},
                        new Object[]{TargetType.SUNSET, 5L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "Rare king tide \u2014 9 locations catch sunrise,"
                        + " 5 catch sunset today");
    }

    @Test
    @DisplayName("detail line — sunrise only aligned")
    void detect_sunriseOnlyAligned_detailShowsSunrise() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 9L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "Rare king tide \u2014 9 locations aligned with sunrise today");
    }

    @Test
    @DisplayName("detail line — no alignment")
    void detect_noAlignment_detailShowsFallback() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "Rare king tide today \u2014 no sunrise or sunset"
                        + " alignment, but exceptional coastal foreground");
    }

    @Test
    @DisplayName("detail line — singular location uses 'catches' not 'catch'")
    void detect_singularLocation_usesCorrectGrammar() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 1L},
                        new Object[]{TargetType.SUNSET, 1L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "Rare king tide \u2014 1 location catches sunrise,"
                        + " 1 catches sunset today");
    }

    @Test
    @DisplayName("detail line — sunset only aligned with singular")
    void detect_sunsetOnlySingular_detailShowsSunset() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNSET, 1L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "Rare king tide \u2014 1 location aligned with sunset today");
    }

    @Test
    @DisplayName("detail line — mixed singular sunrise, plural sunset")
    void detect_mixedSingularPlural_detailShowsCorrectGrammar() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 1L},
                        new Object[]{TargetType.SUNSET, 5L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "Rare king tide \u2014 1 location catches sunrise,"
                        + " 5 catch sunset today");
    }

    @Test
    @DisplayName("detail line — alignment with tomorrow label ends with 'tomorrow'")
    void detect_alignedWithTomorrowLabel_detailEndsWithTomorrow() {
        when(lunarPhaseService.classifyTide(TODAY)).thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.classifyTide(TODAY.plusDays(1)))
                .thenReturn(LunarTideType.KING_TIDE);
        stubCoastalLocations(TODAY.plusDays(1), "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY.plusDays(1)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 3L},
                        new Object[]{TargetType.SUNSET, 2L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "Rare king tide \u2014 3 locations catch sunrise,"
                        + " 2 catch sunset tomorrow");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubCoastalLocations(LocalDate tideDate, String... regionNames) {
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
        when(forecastEvaluationRepository.countTideAlignedByTargetType(tideDate))
                .thenReturn(List.of());
    }
}
