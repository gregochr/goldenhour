package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.Verdict;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KingTideHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class KingTideHotTopicStrategyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 16);
    private static final LocalDate TO_DATE = TODAY.plusDays(3);

    @Mock
    private BriefingService briefingService;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;

    private KingTideHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new KingTideHotTopicStrategy(briefingService, locationRepository,
                forecastEvaluationRepository);
    }

    @Test
    @DisplayName("king tide today emits pill with priority 1 and 'today' label")
    void detect_kingTideToday_emitsPriority1() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland", "The North Yorkshire Coast");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("KING_TIDE");
        assertThat(topic.label()).isEqualTo("King tide");
        assertThat(topic.priority()).isEqualTo(1);
        assertThat(topic.date()).isEqualTo(TODAY);
        assertThat(topic.detail()).startsWith("King tide today");
        assertThat(topic.detail()).contains("2 coastal locations");
        assertThat(topic.regions()).containsExactly("Northumberland",
                "The North Yorkshire Coast");
        assertThat(topic.description()).contains("King tides");
        assertThat(topic.filterAction()).isNull();
    }

    @Test
    @DisplayName("spring tide (not king) emits nothing from this strategy")
    void detect_springTideNotKing_emitsNothing() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.SPRING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("regular tide emits nothing")
    void detect_regularTide_emitsNothing() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("king tide on T+2 emits pill with day-of-week label")
    void detect_kingTideInTwoDays_emitsWithDayName() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE));
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
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY.plusDays(1), "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).detail()).contains("tomorrow");
    }

    @Test
    @DisplayName("multiple king tide days — window from today, date set to first day")
    void detect_multipleKingTideDays_noAlignment_windowFromToday() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(1)))
                .thenReturn(List.of());
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
        assertThat(topics.get(0).detail()).contains("today and tomorrow");
    }

    @Test
    @DisplayName("multiple king tide days — today aligned, highlights today sunrise")
    void detect_multipleKingTideDays_todayAligned_highlightsTodaySunrise() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 3L}));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(1)))
                .thenReturn(List.of());
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
        assertThat(topics.get(0).detail()).contains("today and tomorrow");
        assertThat(topics.get(0).detail())
                .contains("3 tides aligned with today sunrise");
    }

    @Test
    @DisplayName("multiple king tide days — tomorrow aligned, window with alignment info")
    void detect_multipleKingTideDays_tomorrowAligned_windowWithAlignment() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(1)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNSET, 5L}));
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
        assertThat(topics.get(0).detail()).contains("today and tomorrow");
        assertThat(topics.get(0).detail())
                .contains("5 tides aligned with tomorrow sunset");
    }

    @Test
    @DisplayName("multiple king tide days — both aligned, best alignment highlighted")
    void detect_multipleKingTideDays_bothAligned_bestHighlighted() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 2L}));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(1)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNSET, 4L}));
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
        assertThat(topics.get(0).detail()).contains("today and tomorrow");
        assertThat(topics.get(0).detail())
                .contains("4 tides aligned with tomorrow sunset");
    }

    @Test
    @DisplayName("three consecutive king tides — window 'today through Saturday'")
    void detect_threeKingTideDays_thirdAligned_windowThroughSaturday() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(1)))
                .thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(2)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNSET, 6L}));
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
        assertThat(topics.get(0).detail())
                .contains("today through Saturday");
        assertThat(topics.get(0).detail())
                .contains("6 tides aligned with Saturday sunset");
    }

    @Test
    @DisplayName("best alignment info and expandedDetail reflect tomorrow counts")
    void detect_tomorrowAligned_detailReflectsTomorrowCounts() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(1)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 7L},
                        new Object[]{TargetType.SUNSET, 3L}));
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).date()).isEqualTo(TODAY);
        assertThat(topics.get(0).detail()).isEqualTo(
                "King tide today and tomorrow \u00b7 7 tides aligned with"
                        + " tomorrow sunrise \u00b7 1 coastal location");
        var metrics = topics.get(0).expandedDetail().tideMetrics();
        assertThat(metrics.sunriseAlignedCount()).isEqualTo(7);
        assertThat(metrics.sunsetAlignedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("two future king tides without alignment picks earliest future")
    void detect_twoFutureKingTides_noAlignment_picksEarliestFuture() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(1)))
                .thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(2)))
                .thenReturn(List.of());
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(1));
        assertThat(topics.get(0).detail()).contains("tomorrow");
    }

    // ── Boundary tests ────────────────────────────────────────────────────────

    @Test
    @DisplayName("king tide on toDate boundary (last day of window) is detected")
    void detect_kingTideOnLastDay_detected() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.KING_TIDE));
        stubCoastalLocations(TO_DATE, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TO_DATE);
    }

    @Test
    @DisplayName("single-day window with king tide emits pill")
    void detect_singleDayWindow_kingTide_emits() {
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(TODAY, LunarTideType.KING_TIDE)));
        stubCoastalLocations(TODAY, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TODAY);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
        assertThat(topics.get(0).detail()).contains("today");
    }

    @Test
    @DisplayName("single-day window with regular tide emits nothing")
    void detect_singleDayWindow_regularTide_emitsNothing() {
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(TODAY, LunarTideType.REGULAR_TIDE)));

        List<HotTopic> topics = strategy.detect(TODAY, TODAY);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("cached days null returns empty")
    void detect_cachedDaysNull_returnsEmpty() {
        when(briefingService.getCachedDays()).thenReturn(null);

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
        verifyNoInteractions(locationRepository);
        verifyNoInteractions(forecastEvaluationRepository);
    }

    // ── Region edge cases ────────────────────────────────────────────────────

    @Test
    @DisplayName("duplicate regions from multiple coastal locations are deduplicated")
    void detect_duplicateRegions_deduplicated() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
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
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
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
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
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
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));

        strategy.detect(TODAY, TO_DATE);

        verifyNoInteractions(locationRepository);
        verifyNoInteractions(forecastEvaluationRepository);
    }

    // ── expandedDetail tests ────────────────────────────────────────────────

    @Test
    @DisplayName("expandedDetail populated with regionGroups of coastal locations")
    void detect_expandedDetail_populatedWithRegionGroups() {
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(TODAY, LunarTideType.KING_TIDE, "Full Moon")));
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
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(TODAY, LunarTideType.KING_TIDE, "New Moon")));
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
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(TODAY, LunarTideType.KING_TIDE)));
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
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(TODAY, LunarTideType.KING_TIDE)));
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
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE));
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
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
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
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
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
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
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
    @DisplayName("detail line — both aligned, shows best event")
    void detect_bothAligned_detailShowsBestEvent() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 9L},
                        new Object[]{TargetType.SUNSET, 5L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "King tide today \u00b7 9 tides aligned with sunrise"
                        + " \u00b7 1 coastal location");
    }

    @Test
    @DisplayName("detail line — sunrise only aligned")
    void detect_sunriseOnlyAligned_detailShowsSunrise() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 9L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "King tide today \u00b7 9 tides aligned with sunrise"
                        + " \u00b7 1 coastal location");
    }

    @Test
    @DisplayName("detail line — no alignment shows fallback text")
    void detect_noAlignment_detailShowsFallback() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "King tide today \u00b7 no tide alignments \u2014 but exceptional"
                        + " coastal foreground \u00b7 1 coastal location");
    }

    @Test
    @DisplayName("detail line — tied alignment picks sunrise (enum order)")
    void detect_tiedAlignment_picksSunrise() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 1L},
                        new Object[]{TargetType.SUNSET, 1L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "King tide today \u00b7 1 tide aligned with sunrise"
                        + " \u00b7 1 coastal location");
    }

    @Test
    @DisplayName("detail line — sunset only aligned")
    void detect_sunsetOnlyAligned_detailShowsSunset() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNSET, 1L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "King tide today \u00b7 1 tide aligned with sunset"
                        + " \u00b7 1 coastal location");
    }

    @Test
    @DisplayName("detail line — sunset beats sunrise when higher count")
    void detect_sunsetHigherCount_detailShowsSunset() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 1L},
                        new Object[]{TargetType.SUNSET, 5L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "King tide today \u00b7 5 tides aligned with sunset"
                        + " \u00b7 1 coastal location");
    }

    @Test
    @DisplayName("detail line — single king tide tomorrow, alignment without day label")
    void detect_singleKingTideTomorrow_alignmentWithoutDayLabel() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY.plusDays(1), "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY.plusDays(1)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 3L},
                        new Object[]{TargetType.SUNSET, 2L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "King tide tomorrow \u00b7 3 tides aligned with sunrise"
                        + " \u00b7 1 coastal location");
    }

    // ── Statistical king tide detection ───────────────────────────────────────

    @Test
    @DisplayName("statistical king tide (isKingTide=true, lunarTideType=REGULAR) emits pill")
    void detect_statisticalKingTide_emitsPill() {
        BriefingSlot.TideInfo statisticalKing = new BriefingSlot.TideInfo(
                "HIGH", true, null, null, true, true, LunarTideType.REGULAR_TIDE,
                "Waxing Gibbous", false);
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDayWithTide(TODAY, statisticalKing)));
        stubCoastalLocations(TODAY, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).type()).isEqualTo("KING_TIDE");
    }

    // ── Unregioned slot detection ────────────────────────────────────────────

    @Test
    @DisplayName("king tide in unregioned slot is detected")
    void detect_kingTideInUnregionedSlot_detected() {
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDayWithUnregionedTide(TODAY, LunarTideType.KING_TIDE)));
        stubCoastalLocations(TODAY, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).type()).isEqualTo("KING_TIDE");
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("king tide only in unregioned — regioned slots have no king tide")
    void findKingTide_regionedRegularUnregionedKing_findsKingTide() {
        BriefingSlot.TideInfo kingTide = new BriefingSlot.TideInfo(
                "HIGH", true, null, null, true, true,
                LunarTideType.KING_TIDE, "Full Moon", true);
        BriefingSlot regularSlot = new BriefingSlot(
                "Coastal", null, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of(), null);
        BriefingSlot kingSlot = new BriefingSlot(
                "Orphan Coastal", null, Verdict.GO, null, kingTide,
                List.of(), null);
        BriefingRegion region = new BriefingRegion(
                "Northumberland", Verdict.GO, null, List.of(),
                List.of(regularSlot), null, null, null, null, null, null);
        BriefingEventSummary event = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of(kingSlot));
        BriefingDay day = new BriefingDay(TODAY, List.of(event));

        BriefingSlot.TideInfo result = KingTideHotTopicStrategy.findKingTide(day);

        assertThat(result).isNotNull();
        assertThat(result.lunarTideType()).isEqualTo(LunarTideType.KING_TIDE);
    }

    // ── Cached days outside detection window ──────────────────────────────────

    @Test
    @DisplayName("king tide outside detection window is ignored")
    void detect_kingTideOutsideWindow_ignored() {
        LocalDate beforeWindow = TODAY.minusDays(1);
        LocalDate afterWindow = TO_DATE.plusDays(1);
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(beforeWindow, LunarTideType.KING_TIDE),
                buildDay(TODAY, LunarTideType.REGULAR_TIDE),
                buildDay(afterWindow, LunarTideType.KING_TIDE)));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
        verifyNoInteractions(locationRepository);
    }

    @Test
    @DisplayName("king tide inside window detected despite extra days outside window")
    void detect_kingTideInsideWindowWithExtraDays_detected() {
        LocalDate beforeWindow = TODAY.minusDays(2);
        LocalDate afterWindow = TO_DATE.plusDays(2);
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(beforeWindow, LunarTideType.REGULAR_TIDE),
                buildDay(TODAY, LunarTideType.REGULAR_TIDE),
                buildDay(TODAY.plusDays(1), LunarTideType.KING_TIDE),
                buildDay(afterWindow, LunarTideType.REGULAR_TIDE)));
        stubCoastalLocations(TODAY.plusDays(1), "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(1));
    }

    // ── Sunset event detection ────────────────────────────────────────────────

    @Test
    @DisplayName("king tide found only in sunset event is detected")
    void detect_kingTideInSunsetEvent_detected() {
        BriefingSlot.TideInfo kingTide = new BriefingSlot.TideInfo(
                "HIGH", true, null, null, true, true,
                LunarTideType.KING_TIDE, "Full Moon", true);
        BriefingSlot regularSlot = new BriefingSlot(
                "Coastal", null, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of(), null);
        BriefingSlot kingSlot = new BriefingSlot(
                "Coastal", null, Verdict.GO, null, kingTide, List.of(), null);
        BriefingRegion sunriseRegion = new BriefingRegion(
                "Northumberland", Verdict.GO, null, List.of(),
                List.of(regularSlot), null, null, null, null, null, null);
        BriefingRegion sunsetRegion = new BriefingRegion(
                "Northumberland", Verdict.GO, null, List.of(),
                List.of(kingSlot), null, null, null, null, null, null);
        BriefingEventSummary sunrise = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(sunriseRegion), List.of());
        BriefingEventSummary sunset = new BriefingEventSummary(
                TargetType.SUNSET, List.of(sunsetRegion), List.of());
        BriefingDay day = new BriefingDay(TODAY, List.of(sunrise, sunset));

        when(briefingService.getCachedDays()).thenReturn(List.of(day));
        stubCoastalLocations(TODAY, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).type()).isEqualTo("KING_TIDE");
    }

    // ── Multi-day window exact detail text ──────────────────────────────────

    @Test
    @DisplayName("two-day window without alignment — exact detail, no alignment segment")
    void detect_twoDayWindow_noAlignment_exactDetail() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(1)))
                .thenReturn(List.of());
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "King tide today and tomorrow \u00b7 no tide alignments"
                        + " \u2014 but exceptional coastal foreground"
                        + " \u00b7 1 coastal location");
    }

    @Test
    @DisplayName("three-day window with alignment — exact detail includes 'through' and day label")
    void detect_threeDayWindow_alignment_exactDetail() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(1)))
                .thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(2)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 4L}));
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "King tide today through Saturday \u00b7 4 tides aligned"
                        + " with Saturday sunrise \u00b7 1 coastal location");
    }

    @Test
    @DisplayName("multi-day window alignment queries every candidate date")
    void detect_threeDayWindow_queriesAllDates() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(1)))
                .thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(2)))
                .thenReturn(List.of());
        stubLocationRepoOnly("Northumberland");

        strategy.detect(TODAY, TO_DATE);

        verify(forecastEvaluationRepository)
                .countTideAlignedByTargetType(TODAY);
        verify(forecastEvaluationRepository)
                .countTideAlignedByTargetType(TODAY.plusDays(1));
        verify(forecastEvaluationRepository)
                .countTideAlignedByTargetType(TODAY.plusDays(2));
    }

    @Test
    @DisplayName("three-day window — expandedDetail uses best alignment date's counts, not first")
    void detect_threeDayWindow_expandedDetailUsesBestAlignmentCounts() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 1L}));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(1)))
                .thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(2)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 5L},
                        new Object[]{TargetType.SUNSET, 3L}));
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        var metrics = topics.get(0).expandedDetail().tideMetrics();
        assertThat(metrics.sunriseAlignedCount()).isEqualTo(5);
        assertThat(metrics.sunsetAlignedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("coastal location count — plural with multiple locations")
    void detect_multipleCoastalLocations_pluralInDetail() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());

        RegionEntity r1 = new RegionEntity();
        r1.setName("Northumberland");
        RegionEntity r2 = new RegionEntity();
        r2.setName("The North Yorkshire Coast");
        List<LocationEntity> locations = List.of(
                LocationEntity.builder()
                        .id(1L).name("Craster").lat(55.47).lon(-1.59)
                        .tideType(Set.of(TideType.HIGH)).region(r1)
                        .enabled(true).build(),
                LocationEntity.builder()
                        .id(2L).name("Bamburgh").lat(55.61).lon(-1.71)
                        .tideType(Set.of(TideType.LOW)).region(r1)
                        .enabled(true).build(),
                LocationEntity.builder()
                        .id(3L).name("Whitby").lat(54.48).lon(-0.62)
                        .tideType(Set.of(TideType.HIGH)).region(r2)
                        .enabled(true).build());
        when(locationRepository.findCoastalLocations()).thenReturn(locations);

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "King tide today \u00b7 no tide alignments \u2014 but exceptional"
                        + " coastal foreground \u00b7 3 coastal locations");
    }

    // ── findBestAlignment unit tests ─────────────────────────────────────────

    @Test
    @DisplayName("findBestAlignment — empty alignments returns null")
    void findBestAlignment_emptyAlignments_returnsNull() {
        var alignments = new java.util.LinkedHashMap<LocalDate,
                java.util.Map<TargetType, Long>>();
        alignments.put(TODAY, new java.util.EnumMap<>(TargetType.class));

        assertThat(KingTideHotTopicStrategy.findBestAlignment(alignments))
                .isNull();
    }

    @Test
    @DisplayName("findBestAlignment — all zero counts returns null")
    void findBestAlignment_allZeroCounts_returnsNull() {
        var counts = new java.util.EnumMap<TargetType, Long>(
                TargetType.class);
        counts.put(TargetType.SUNRISE, 0L);
        counts.put(TargetType.SUNSET, 0L);
        var alignments = new java.util.LinkedHashMap<LocalDate,
                java.util.Map<TargetType, Long>>();
        alignments.put(TODAY, counts);

        assertThat(KingTideHotTopicStrategy.findBestAlignment(alignments))
                .isNull();
    }

    @Test
    @DisplayName("findBestAlignment — single date, single event returns it")
    void findBestAlignment_singleDateSingleEvent_returnsIt() {
        var counts = new java.util.EnumMap<TargetType, Long>(
                TargetType.class);
        counts.put(TargetType.SUNSET, 7L);
        var alignments = new java.util.LinkedHashMap<LocalDate,
                java.util.Map<TargetType, Long>>();
        alignments.put(TODAY, counts);

        var result = KingTideHotTopicStrategy
                .findBestAlignment(alignments);

        assertThat(result).isNotNull();
        assertThat(result.date()).isEqualTo(TODAY);
        assertThat(result.event()).isEqualTo(TargetType.SUNSET);
        assertThat(result.count()).isEqualTo(7L);
    }

    @Test
    @DisplayName("findBestAlignment — higher count on later date wins")
    void findBestAlignment_higherCountLaterDate_wins() {
        var day1 = new java.util.EnumMap<TargetType, Long>(
                TargetType.class);
        day1.put(TargetType.SUNRISE, 3L);
        var day2 = new java.util.EnumMap<TargetType, Long>(
                TargetType.class);
        day2.put(TargetType.SUNRISE, 8L);
        var alignments = new java.util.LinkedHashMap<LocalDate,
                java.util.Map<TargetType, Long>>();
        alignments.put(TODAY, day1);
        alignments.put(TODAY.plusDays(1), day2);

        var result = KingTideHotTopicStrategy
                .findBestAlignment(alignments);

        assertThat(result.date()).isEqualTo(TODAY.plusDays(1));
        assertThat(result.count()).isEqualTo(8L);
    }

    @Test
    @DisplayName("findBestAlignment — tied counts, earlier date wins")
    void findBestAlignment_tiedCounts_earlierDateWins() {
        var day1 = new java.util.EnumMap<TargetType, Long>(
                TargetType.class);
        day1.put(TargetType.SUNRISE, 5L);
        var day2 = new java.util.EnumMap<TargetType, Long>(
                TargetType.class);
        day2.put(TargetType.SUNSET, 5L);
        var alignments = new java.util.LinkedHashMap<LocalDate,
                java.util.Map<TargetType, Long>>();
        alignments.put(TODAY, day1);
        alignments.put(TODAY.plusDays(1), day2);

        var result = KingTideHotTopicStrategy
                .findBestAlignment(alignments);

        assertThat(result.date()).isEqualTo(TODAY);
        assertThat(result.event()).isEqualTo(TargetType.SUNRISE);
    }

    // ── formatDateRange unit tests ───────────────────────────────────────────

    @Test
    @DisplayName("formatDateRange — single date returns day label")
    void formatDateRange_singleDate_returnsDayLabel() {
        assertThat(KingTideHotTopicStrategy.formatDateRange(
                List.of(TODAY), TODAY)).isEqualTo("today");
        assertThat(KingTideHotTopicStrategy.formatDateRange(
                List.of(TODAY.plusDays(1)), TODAY)).isEqualTo("tomorrow");
        assertThat(KingTideHotTopicStrategy.formatDateRange(
                List.of(TODAY.plusDays(2)), TODAY)).isEqualTo("Saturday");
    }

    @Test
    @DisplayName("formatDateRange — two dates uses 'and'")
    void formatDateRange_twoDates_usesAnd() {
        assertThat(KingTideHotTopicStrategy.formatDateRange(
                List.of(TODAY, TODAY.plusDays(1)), TODAY))
                .isEqualTo("today and tomorrow");
        assertThat(KingTideHotTopicStrategy.formatDateRange(
                List.of(TODAY.plusDays(1), TODAY.plusDays(2)), TODAY))
                .isEqualTo("tomorrow and Saturday");
    }

    @Test
    @DisplayName("formatDateRange — three or more dates uses 'through'")
    void formatDateRange_threeDates_usesThrough() {
        assertThat(KingTideHotTopicStrategy.formatDateRange(
                List.of(TODAY, TODAY.plusDays(1), TODAY.plusDays(2)), TODAY))
                .isEqualTo("today through Saturday");
        assertThat(KingTideHotTopicStrategy.formatDateRange(
                List.of(TODAY, TODAY.plusDays(1), TODAY.plusDays(2),
                        TODAY.plusDays(3)),
                TODAY))
                .isEqualTo("today through Sunday");
    }

    // ── buildAlignmentInfo unit tests ────────────────────────────────────────

    @Test
    @DisplayName("buildAlignmentInfo — null best returns null")
    void buildAlignmentInfo_nullBest_returnsNull() {
        assertThat(KingTideHotTopicStrategy.buildAlignmentInfo(
                null, true, TODAY)).isNull();
    }

    @Test
    @DisplayName("buildAlignmentInfo — single day omits day label")
    void buildAlignmentInfo_singleDay_omitsDayLabel() {
        var best = new KingTideHotTopicStrategy.BestAlignment(
                TODAY, TargetType.SUNRISE, 3L);

        assertThat(KingTideHotTopicStrategy.buildAlignmentInfo(
                best, false, TODAY))
                .isEqualTo("3 tides aligned with sunrise");
    }

    @Test
    @DisplayName("buildAlignmentInfo — multi-day includes day label")
    void buildAlignmentInfo_multiDay_includesDayLabel() {
        var best = new KingTideHotTopicStrategy.BestAlignment(
                TODAY.plusDays(1), TargetType.SUNSET, 5L);

        assertThat(KingTideHotTopicStrategy.buildAlignmentInfo(
                best, true, TODAY))
                .isEqualTo("5 tides aligned with tomorrow sunset");
    }

    // ── buildAlignmentInfo count boundary ─────────────────────────────────

    @Test
    @DisplayName("buildAlignmentInfo — count=1 singular, single day")
    void buildAlignmentInfo_count1_singleDay_singular() {
        var best = new KingTideHotTopicStrategy.BestAlignment(
                TODAY, TargetType.SUNSET, 1L);

        assertThat(KingTideHotTopicStrategy.buildAlignmentInfo(
                best, false, TODAY))
                .isEqualTo("1 tide aligned with sunset");
    }

    @Test
    @DisplayName("buildAlignmentInfo — count=1 singular, multi-day")
    void buildAlignmentInfo_count1_multiDay_singular() {
        var best = new KingTideHotTopicStrategy.BestAlignment(
                TODAY.plusDays(1), TargetType.SUNRISE, 1L);

        assertThat(KingTideHotTopicStrategy.buildAlignmentInfo(
                best, true, TODAY))
                .isEqualTo("1 tide aligned with tomorrow sunrise");
    }

    @Test
    @DisplayName("buildAlignmentInfo — count=2 plural")
    void buildAlignmentInfo_count2_plural() {
        var best = new KingTideHotTopicStrategy.BestAlignment(
                TODAY, TargetType.SUNRISE, 2L);

        assertThat(KingTideHotTopicStrategy.buildAlignmentInfo(
                best, false, TODAY))
                .isEqualTo("2 tides aligned with sunrise");
    }

    // ── buildKingTideDetail direct unit tests ──────────────────────────────

    @Test
    @DisplayName("buildKingTideDetail — null alignment omits segment")
    void buildKingTideDetail_nullAlignment_omitsSegment() {
        assertThat(KingTideHotTopicStrategy.buildKingTideDetail(
                "today", null, 5))
                .isEqualTo("King tide today \u00b7 5 coastal locations");
    }

    @Test
    @DisplayName("buildKingTideDetail — non-null alignment included")
    void buildKingTideDetail_withAlignment_includesSegment() {
        assertThat(KingTideHotTopicStrategy.buildKingTideDetail(
                "today and tomorrow",
                "7 tides aligned with tomorrow sunrise", 12))
                .isEqualTo("King tide today and tomorrow"
                        + " \u00b7 7 tides aligned with tomorrow sunrise"
                        + " \u00b7 12 coastal locations");
    }

    @Test
    @DisplayName("buildKingTideDetail — singular coastal location")
    void buildKingTideDetail_singularCoastal() {
        assertThat(KingTideHotTopicStrategy.buildKingTideDetail(
                "today", "3 tides aligned with sunrise", 1))
                .isEqualTo("King tide today"
                        + " \u00b7 3 tides aligned with sunrise"
                        + " \u00b7 1 coastal location");
    }

    @Test
    @DisplayName("buildKingTideDetail — plural coastal locations")
    void buildKingTideDetail_pluralCoastal() {
        assertThat(KingTideHotTopicStrategy.buildKingTideDetail(
                "today", "3 tides aligned with sunrise", 2))
                .isEqualTo("King tide today"
                        + " \u00b7 3 tides aligned with sunrise"
                        + " \u00b7 2 coastal locations");
    }

    // ── formatCatch / formatCatchShort / formatLocationCount ─────────────

    @Test
    @DisplayName("formatCatch — singular and plural")
    void formatCatch_singularAndPlural() {
        assertThat(KingTideHotTopicStrategy.formatCatch(1, "sunrise"))
                .isEqualTo("1 location catches sunrise");
        assertThat(KingTideHotTopicStrategy.formatCatch(3, "sunset"))
                .isEqualTo("3 locations catch sunset");
    }

    @Test
    @DisplayName("formatCatchShort — singular and plural")
    void formatCatchShort_singularAndPlural() {
        assertThat(KingTideHotTopicStrategy.formatCatchShort(1, "sunrise"))
                .isEqualTo("1 catches sunrise");
        assertThat(KingTideHotTopicStrategy.formatCatchShort(4, "sunset"))
                .isEqualTo("4 catch sunset");
    }

    @Test
    @DisplayName("formatLocationCount — singular and plural")
    void formatLocationCount_singularAndPlural() {
        assertThat(KingTideHotTopicStrategy.formatLocationCount(1))
                .isEqualTo("1 location");
        assertThat(KingTideHotTopicStrategy.formatLocationCount(7))
                .isEqualTo("7 locations");
    }

    // ── findBestAlignment — zero mixed with positive on same date ────────

    @Test
    @DisplayName("findBestAlignment — zero sunrise, positive sunset on same date")
    void findBestAlignment_zeroAndPositiveOnSameDate() {
        var counts = new java.util.EnumMap<TargetType, Long>(
                TargetType.class);
        counts.put(TargetType.SUNRISE, 0L);
        counts.put(TargetType.SUNSET, 4L);
        var alignments = new java.util.LinkedHashMap<LocalDate,
                java.util.Map<TargetType, Long>>();
        alignments.put(TODAY, counts);

        var result = KingTideHotTopicStrategy
                .findBestAlignment(alignments);

        assertThat(result).isNotNull();
        assertThat(result.event()).isEqualTo(TargetType.SUNSET);
        assertThat(result.count()).isEqualTo(4L);
    }

    // ── buildExpandedDetail — empty tideType ────────────────────────────

    @Test
    @DisplayName("buildExpandedDetail — location with empty tideType set yields null preference")
    void buildExpandedDetail_emptyTideType_nullPreference() {
        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        LocationEntity loc = LocationEntity.builder()
                .id(1L).name("Mystery Cove").lat(55.0).lon(-1.5)
                .tideType(Set.of()).region(region).enabled(true).build();

        ExpandedHotTopicDetail detail = KingTideHotTopicStrategy
                .buildExpandedDetail(List.of(loc), "King tide",
                        "Full Moon", Map.of());

        assertThat(detail.regionGroups()).hasSize(1);
        assertThat(detail.regionGroups().get(0).locations().get(0)
                .tideLocationMetrics().tidePreference()).isNull();
    }

    // ── Alignment query skips non-king-tide dates ────────────────────────

    @Test
    @DisplayName("multi-day window only queries alignment for king tide dates")
    void detect_multiDay_onlyQueriesKingTideDates() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(2)))
                .thenReturn(List.of());
        stubLocationRepoOnly("Northumberland");

        strategy.detect(TODAY, TO_DATE);

        verify(forecastEvaluationRepository)
                .countTideAlignedByTargetType(TODAY);
        verify(forecastEvaluationRepository)
                .countTideAlignedByTargetType(TODAY.plusDays(2));
        // Regular tide dates must NOT be queried
        verify(forecastEvaluationRepository, never())
                .countTideAlignedByTargetType(TODAY.plusDays(1));
        verify(forecastEvaluationRepository, never())
                .countTideAlignedByTargetType(TODAY.plusDays(3));
    }

    // ── isKingTide negative — neither flag matches ──────────────────────

    @Test
    @DisplayName("findKingTide returns null when all slots have isKingTide=false and REGULAR_TIDE")
    void findKingTide_noKingTideFlags_returnsNull() {
        BriefingSlot.TideInfo notKing = new BriefingSlot.TideInfo(
                "HIGH", true, null, null, false, true,
                LunarTideType.REGULAR_TIDE, null, false);
        BriefingDay day = buildDayWithTide(TODAY, notKing);

        assertThat(KingTideHotTopicStrategy.findKingTide(day)).isNull();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private BriefingDay buildDay(LocalDate date, LunarTideType tideType) {
        String moonPhase = (tideType == LunarTideType.KING_TIDE
                || tideType == LunarTideType.SPRING_TIDE) ? "Full Moon" : null;
        return buildDay(date, tideType, moonPhase);
    }

    private BriefingDay buildDay(LocalDate date, LunarTideType tideType, String moonPhase) {
        BriefingSlot.TideInfo tideInfo;
        if (tideType == LunarTideType.KING_TIDE) {
            tideInfo = new BriefingSlot.TideInfo(
                    "HIGH", true, null, null, true, true,
                    LunarTideType.KING_TIDE, moonPhase, true);
        } else if (tideType == LunarTideType.SPRING_TIDE) {
            tideInfo = new BriefingSlot.TideInfo(
                    "HIGH", true, null, null, false, true,
                    LunarTideType.SPRING_TIDE, moonPhase, false);
        } else {
            tideInfo = BriefingSlot.TideInfo.NONE;
        }
        return buildDayWithTide(date, tideInfo);
    }

    private BriefingDay buildDayWithTide(LocalDate date, BriefingSlot.TideInfo tideInfo) {
        BriefingSlot slot = new BriefingSlot(
                "Coastal", null, Verdict.GO, null, tideInfo, List.of(), null);
        BriefingRegion region = new BriefingRegion(
                "Northumberland", Verdict.GO, null, List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary event = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        return new BriefingDay(date, List.of(event));
    }

    private List<BriefingDay> buildDays(LunarTideType d0, LunarTideType d1,
            LunarTideType d2, LunarTideType d3) {
        return List.of(
                buildDay(TODAY, d0),
                buildDay(TODAY.plusDays(1), d1),
                buildDay(TODAY.plusDays(2), d2),
                buildDay(TODAY.plusDays(3), d3));
    }

    private BriefingDay buildDayWithUnregionedTide(LocalDate date, LunarTideType tideType) {
        String moonPhase = (tideType == LunarTideType.KING_TIDE
                || tideType == LunarTideType.SPRING_TIDE) ? "Full Moon" : null;
        BriefingSlot.TideInfo tideInfo;
        if (tideType == LunarTideType.KING_TIDE) {
            tideInfo = new BriefingSlot.TideInfo(
                    "HIGH", true, null, null, true, true,
                    LunarTideType.KING_TIDE, moonPhase, true);
        } else {
            tideInfo = BriefingSlot.TideInfo.NONE;
        }
        BriefingSlot slot = new BriefingSlot(
                "Orphan Coastal", null, Verdict.GO, null, tideInfo, List.of(), null);
        BriefingEventSummary event = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(), List.of(slot));
        return new BriefingDay(date, List.of(event));
    }

    private void stubLocationRepoOnly(String... regionNames) {
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
