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
import com.gregochr.goldenhour.model.TideRunDay;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private SolarEventFreshness freshness;

    @Mock
    private CoastalTideFactsBuilder coastalTideFactsBuilder;

    @Mock
    private TideRunBuilder tideRunBuilder;

    private KingTideHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        // Default: every solar event is still ahead. Expiry tests override per date.
        lenient().when(freshness.isAhead(any(LocationEntity.class), any(), any()))
                .thenReturn(true);
        strategy = new KingTideHotTopicStrategy(briefingService, locationRepository,
                forecastEvaluationRepository, freshness, coastalTideFactsBuilder,
                tideRunBuilder);
    }

    @Test
    @DisplayName("king tide today emits pill with priority 1, dated today")
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
        assertThat(topic.detail()).doesNotContain("today");
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
        assertThat(topic.detail()).doesNotContain("Saturday");
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
        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(1));
        assertThat(topics.get(0).detail()).doesNotContain("tomorrow");
    }

    @Test
    @DisplayName("multiple king tide days — one card per day, each with its own date")
    void detect_multipleKingTideDays_perDateCards() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY)).thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(1))).thenReturn(List.of());
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(2);
        assertThat(topics).extracting(HotTopic::date)
                .containsExactly(TODAY, TODAY.plusDays(1));
        assertThat(topics).noneMatch(t -> t.detail().contains("today and tomorrow"));
    }

    @Test
    @DisplayName("king tide days — the aligned day's card names its own alignment, no day word")
    void detect_multipleKingTideDays_todayAligned_perDate() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubRun(Map.of(TODAY, runDay("sunrise"), TODAY.plusDays(1), runDay(null)));
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(2);
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
        assertThat(topics.get(0).detail()).contains("tide aligned with sunrise");
        assertThat(topics.get(0).detail()).doesNotContain("today");
        assertThat(topics.get(1).date()).isEqualTo(TODAY.plusDays(1));
        assertThat(topics.get(1).detail()).contains("no tide alignment");
    }

    @Test
    @DisplayName("king tide days — tomorrow's card carries tomorrow's alignment")
    void detect_multipleKingTideDays_tomorrowAligned_perDate() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubRun(Map.of(TODAY, runDay(null), TODAY.plusDays(1), runDay("sunset")));
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(2);
        assertThat(topics.get(1).date()).isEqualTo(TODAY.plusDays(1));
        assertThat(topics.get(1).detail()).contains("tide aligned with sunset");
    }

    @Test
    @DisplayName("king tide days — each card highlights its own day's best alignment")
    void detect_multipleKingTideDays_bothAligned_perDate() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubRun(Map.of(TODAY, runDay("sunrise"), TODAY.plusDays(1), runDay("sunset")));
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(2);
        assertThat(topics.get(0).detail()).contains("tide aligned with sunrise");
        assertThat(topics.get(1).detail()).contains("tide aligned with sunset");
    }

    @Test
    @DisplayName("three consecutive king tides — three cards, the third carries its alignment")
    void detect_threeKingTideDays_perDate() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE));
        stubRun(Map.of(
                TODAY, runDay(null),
                TODAY.plusDays(1), runDay(null),
                TODAY.plusDays(2), runDay("sunset")));
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(3);
        assertThat(topics.get(2).date()).isEqualTo(TODAY.plusDays(2));
        assertThat(topics.get(2).detail()).contains("tide aligned with sunset");
        assertThat(topics).noneMatch(t -> t.detail().contains("through"));
    }

    @Test
    @DisplayName("expandedDetail keeps that day's per-location counts; the detail states its tide")
    void detect_perDate_detailReflectsThatDaysCounts() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY)).thenReturn(List.of());
        when(forecastEvaluationRepository
                .countTideAlignedByTargetType(TODAY.plusDays(1)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 7L},
                        new Object[]{TargetType.SUNSET, 3L}));
        stubRun(Map.of(TODAY, runDay(null), TODAY.plusDays(1), runDay("sunrise")));
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(2);
        assertThat(topics.get(1).date()).isEqualTo(TODAY.plusDays(1));
        // The headline is the tide's own geometry — one number of locations, not a count of them.
        assertThat(topics.get(1).detail()).isEqualTo(
                "tide aligned with sunrise · 1 coastal location");
        // The drill-down still answers the other question: how many locations want this water.
        var metrics = topics.get(1).expandedDetail().tideMetrics();
        assertThat(metrics.sunriseAlignedCount()).isEqualTo(7);
        assertThat(metrics.sunsetAlignedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("two future king tides without alignment emit one card each")
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

        assertThat(topics).hasSize(2);
        assertThat(topics).extracting(HotTopic::date)
                .containsExactly(TODAY.plusDays(1), TODAY.plusDays(2));
        assertThat(topics).noneMatch(t -> t.detail().contains("tomorrow"));
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
        assertThat(topics.get(0).detail()).contains("coastal location");
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
    @DisplayName("detail line — aligned with sunrise names the event, no count")
    void detect_alignedWithSunrise_detailNamesEvent() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        stubRun(Map.of(TODAY, runDay("sunrise")));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "tide aligned with sunrise · 1 coastal location");
    }

    @Test
    @DisplayName("detail line — aligned with sunset names sunset")
    void detect_alignedWithSunset_detailNamesEvent() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        stubRun(Map.of(TODAY, runDay("sunset")));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "tide aligned with sunset · 1 coastal location");
    }

    @Test
    @DisplayName("detail line — unaligned day still offers the foreground")
    void detect_noAlignment_detailShowsFallback() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        stubRun(Map.of(TODAY, runDay(null)));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "no tide alignment — but exceptional"
                        + " coastal foreground · 1 coastal location");
    }

    @Test
    @DisplayName("detail line — the headline cannot contradict the chart it sits above")
    void detect_alignedRunRow_zeroDatabaseCounts_stillReadsAligned() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        // stubCoastalLocations reports ZERO aligned rows in forecast_evaluation for this date.
        // The regression this change exists for: the chart's geometry says the high water lands
        // 39 minutes before sunrise, while that flag reports nobody aligned — because it asks
        // whether each location's own tide-type preference matched, in a differently sized
        // window, and cannot tell "nobody aligned" from "no rows were written". The card used to
        // print the count's answer directly above the chart's, contradicting itself.
        stubCoastalLocations(TODAY, "Northumberland");
        stubRun(Map.of(TODAY, runDay("sunrise")));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).contains("tide aligned with sunrise");
        assertThat(topics.get(0).tideRun().aligned()).isTrue();
    }

    @Test
    @DisplayName("detail line — no derivable curve claims nothing either way")
    void detect_noRunRow_detailOmitsAlignmentSegment() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        stubRun(Map.of());

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        // Silence, not a denial: with no geometry there is nothing to say about the tide's
        // timing, and "no tide alignment" would be a positive claim built from missing data.
        assertThat(topics.get(0).detail()).isEqualTo("1 coastal location");
    }

    @Test
    @DisplayName("detail line — an alignment that already happened is not denied, it is dated")
    void detect_alignedWithExpiredEvent_readsAsPassed() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        stubRun(Map.of(TODAY, runDay("sunrise")));
        when(freshness.isAhead(any(LocationEntity.class), eq(TODAY), eq(TargetType.SUNRISE)))
                .thenReturn(false);

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        // Not "no tide alignment": the chart on this same card still draws HW 39 minutes before
        // sunrise, with the editorial line that renders only on an aligned day. Denying it would
        // restore the exact contradiction this whole change removes, one branch further along.
        assertThat(topics.get(0).detail()).contains("tide alignment already passed");
        assertThat(topics.get(0).detail()).doesNotContain("no tide alignment");
    }

    @Test
    @DisplayName("detail line — single king tide tomorrow, alignment without day label")
    void detect_singleKingTideTomorrow_alignmentWithoutDayLabel() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY.plusDays(1), "Northumberland");
        stubRun(Map.of(TODAY.plusDays(1), runDay("sunrise")));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "tide aligned with sunrise · 1 coastal location");
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
    @DisplayName("two-day window without alignment — one card per day, exact detail")
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
        stubRun(Map.of(TODAY, runDay(null), TODAY.plusDays(1), runDay(null)));
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(2);
        assertThat(topics.get(0).detail()).isEqualTo(
                "no tide alignment — but exceptional coastal foreground"
                        + " · 1 coastal location");
        assertThat(topics.get(1).detail()).isEqualTo(topics.get(0).detail());
    }

    @Test
    @DisplayName("three-day window with alignment — the aligned card carries its own tide")
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
                .thenReturn(List.of());
        stubRun(Map.of(
                TODAY, runDay(null),
                TODAY.plusDays(1), runDay(null),
                TODAY.plusDays(2), runDay("sunrise")));
        stubLocationRepoOnly("Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(3);
        assertThat(topics.get(2).date()).isEqualTo(TODAY.plusDays(2));
        assertThat(topics.get(2).detail()).isEqualTo(
                "tide aligned with sunrise · 1 coastal location");
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
    @DisplayName("three-day window — each card's expandedDetail uses its own date's counts")
    void detect_threeDayWindow_expandedDetailUsesPerDateCounts() {
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

        // Each card's expandedDetail reflects its own day's counts — the T+2 card carries 5/3.
        assertThat(topics).hasSize(3);
        assertThat(topics.get(2).date()).isEqualTo(TODAY.plusDays(2));
        var metrics = topics.get(2).expandedDetail().tideMetrics();
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
        stubRun(Map.of(TODAY, runDay(null)));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "no tide alignment \u2014 but exceptional"
                        + " coastal foreground \u00b7 3 coastal locations");
    }

    // ── alignmentInfo unit tests ─────────────────────────────────────────────

    @Test
    @DisplayName("alignmentInfo — a null run row says nothing rather than denying alignment")
    void alignmentInfo_nullRunRow_returnsNull() {
        assertThat(KingTideHotTopicStrategy.alignmentInfo(
                null, Set.of(TargetType.SUNRISE), "no tide alignment", "passed"))
                .isNull();
    }

    @Test
    @DisplayName("alignmentInfo — unaligned day returns the caller's wording")
    void alignmentInfo_unalignedDay_returnsCallerWording() {
        assertThat(KingTideHotTopicStrategy.alignmentInfo(
                runDay(null), Set.of(TargetType.SUNRISE, TargetType.SUNSET), "nothing doing",
                "passed"))
                .isEqualTo("nothing doing");
    }

    @Test
    @DisplayName("alignmentInfo — a spring caller's null wording drops the segment entirely")
    void alignmentInfo_unalignedDay_nullWording_returnsNull() {
        assertThat(KingTideHotTopicStrategy.alignmentInfo(
                runDay(null), Set.of(TargetType.SUNRISE), null, "passed"))
                .isNull();
    }

    @Test
    @DisplayName("alignmentInfo — names the event the tide actually aligned with")
    void alignmentInfo_alignedDay_namesEvent() {
        assertThat(KingTideHotTopicStrategy.alignmentInfo(
                runDay("sunset"), Set.of(TargetType.SUNRISE, TargetType.SUNSET), "unaligned",
                "passed"))
                .isEqualTo("tide aligned with sunset");
    }

    @Test
    @DisplayName("alignmentInfo — an expired alignment gets its own wording, never the denial")
    void alignmentInfo_alignedWithExpiredEvent_returnsPassed() {
        // Sunrise has been and gone, so this is no longer a reason to go — but it is also not the
        // same statement as "the water missed the light", and the chart beneath still draws it.
        assertThat(KingTideHotTopicStrategy.alignmentInfo(
                runDay("sunrise"), Set.of(TargetType.SUNSET), "unaligned", "already passed"))
                .isEqualTo("already passed");
    }


    // ── buildKingTideDetail direct unit tests ──────────────────

    @Test
    @DisplayName("buildKingTideDetail — null alignment shows only the count")
    void buildKingTideDetail_nullAlignment_omitsSegment() {
        assertThat(KingTideHotTopicStrategy.buildKingTideDetail(null, 5))
                .isEqualTo("5 coastal locations");
    }

    @Test
    @DisplayName("buildKingTideDetail — non-null alignment leads the line")
    void buildKingTideDetail_withAlignment_includesSegment() {
        assertThat(KingTideHotTopicStrategy.buildKingTideDetail(
                "tide aligned with sunrise", 12))
                .isEqualTo("tide aligned with sunrise \u00b7 12 coastal locations");
    }

    @Test
    @DisplayName("buildKingTideDetail — singular coastal location")
    void buildKingTideDetail_singularCoastal() {
        assertThat(KingTideHotTopicStrategy.buildKingTideDetail(
                "tide aligned with sunrise", 1))
                .isEqualTo("tide aligned with sunrise \u00b7 1 coastal location");
    }

    @Test
    @DisplayName("buildKingTideDetail — plural coastal locations")
    void buildKingTideDetail_pluralCoastal() {
        assertThat(KingTideHotTopicStrategy.buildKingTideDetail(
                "tide aligned with sunrise", 2))
                .isEqualTo("tide aligned with sunrise \u00b7 2 coastal locations");
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

    // ── non-expired solar-event filtering ──────────────────────────────

    @Test
    @DisplayName("king tide whose solar events have all passed is suppressed")
    void detect_allEventsExpired_suppressed() {
        when(briefingService.getCachedDays())
                .thenReturn(List.of(buildDay(TODAY, LunarTideType.KING_TIDE)));
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(coastal()));
        when(freshness.isAhead(any(LocationEntity.class), eq(TODAY), any())).thenReturn(false);

        assertThat(strategy.detect(TODAY, TO_DATE)).isEmpty();
    }

    @Test
    @DisplayName("king tide rolls forward to tomorrow when today's events have passed")
    void detect_todayExpired_rollsToTomorrow() {
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(TODAY, LunarTideType.KING_TIDE),
                buildDay(TODAY.plusDays(1), LunarTideType.KING_TIDE)));
        when(freshness.isAhead(any(LocationEntity.class), eq(TODAY), any())).thenReturn(false);
        stubCoastalLocations(TODAY.plusDays(1), "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(1));
        // Today's passed events are not queried for alignment.
        verify(forecastEvaluationRepository, never())
                .countTideAlignedByTargetType(TODAY);
    }

    @Test
    @DisplayName("maskExpired keeps only non-expired event types")
    void maskExpired_dropsExpiredEventTypes() {
        Map<TargetType, Long> counts = Map.of(TargetType.SUNRISE, 2L, TargetType.SUNSET, 3L);

        Map<TargetType, Long> masked = KingTideHotTopicStrategy.maskExpired(
                counts, Set.of(TargetType.SUNSET));

        assertThat(masked).containsOnly(Map.entry(TargetType.SUNSET, 3L));
    }

    private LocationEntity coastal() {
        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        return LocationEntity.builder()
                .id(1L).name("Coastal").lat(55.0).lon(-1.5)
                .tideType(Set.of(TideType.HIGH)).region(region).enabled(true).build();
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

    /**
     * Stubs the run builder so each named date carries a run row. The detail line's alignment
     * segment is read from these rows, so a test that wants an aligned card states it here — the
     * same place the chart's alignment comes from, which is the point of the change.
     *
     * @param run run rows by date
     */
    private void stubRun(Map<LocalDate, TideRunDay> run) {
        when(tideRunBuilder.build(any(), any(), eq(true))).thenReturn(run);
    }

    /**
     * A run row aligned with the given solar event, or unaligned when {@code alignedEvent} is null.
     *
     * @param alignedEvent {@code "sunrise"}, {@code "sunset"}, or null for an unaligned day
     * @return the run row
     */
    private static TideRunDay runDay(String alignedEvent) {
        return new TideRunDay(
                "KING RUN", 1, 1, "THU 16", "St. Mary's Lighthouse",
                "4.2 m", null, "2.5 m", "+0.5 m over spring", "0.4 m off the record",
                "05:37", "20:38", null, List.of(),
                alignedEvent == null
                        ? "no clear tide/sun alignment"
                        : "HW 04:58 · 39m before " + alignedEvent,
                alignedEvent != null, alignedEvent, false, null);
    }
}
