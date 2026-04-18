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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SpringTideHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class SpringTideHotTopicStrategyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 16);
    private static final LocalDate TO_DATE = TODAY.plusDays(3);

    @Mock
    private BriefingService briefingService;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;

    private SpringTideHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SpringTideHotTopicStrategy(briefingService, locationRepository,
                forecastEvaluationRepository);
    }

    @Test
    @DisplayName("spring tide today emits pill with priority 2 and 'today' label")
    void detect_springTideToday_emitsPriority2() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "The North Yorkshire Coast");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("SPRING_TIDE");
        assertThat(topic.label()).isEqualTo("Spring tide");
        assertThat(topic.priority()).isEqualTo(2);
        assertThat(topic.date()).isEqualTo(TODAY);
        assertThat(topic.detail()).contains("today");
        assertThat(topic.detail()).contains("Spring tide");
        assertThat(topic.regions()).containsExactly("The North Yorkshire Coast");
        assertThat(topic.description()).contains("Spring tides");
        assertThat(topic.filterAction()).isNull();
    }

    @Test
    @DisplayName("king tide emits nothing from spring tide strategy (no duplication)")
    void detect_kingTide_emitsNothing() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("spring tide suppressed when king tide exists elsewhere in window")
    void detect_springTideWithKingTideElsewhereInWindow_suppressed() {
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(TODAY, LunarTideType.KING_TIDE),
                buildDay(TODAY.plusDays(1), LunarTideType.REGULAR_TIDE),
                buildDay(TODAY.plusDays(2), LunarTideType.SPRING_TIDE),
                buildDay(TODAY.plusDays(3), LunarTideType.REGULAR_TIDE)));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
        verifyNoInteractions(locationRepository);
        verifyNoInteractions(forecastEvaluationRepository);
    }

    @Test
    @DisplayName("king tide outside window does not suppress spring tide inside")
    void detect_kingTideOutsideWindow_doesNotSuppressSpringTide() {
        LocalDate beforeWindow = TODAY.minusDays(1);
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(beforeWindow, LunarTideType.KING_TIDE),
                buildDay(TODAY, LunarTideType.SPRING_TIDE),
                buildDay(TODAY.plusDays(1), LunarTideType.REGULAR_TIDE)));
        stubCoastalLocations(TODAY, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).type()).isEqualTo("SPRING_TIDE");
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("king tide on last day of window suppresses spring tide on first")
    void detect_kingTideOnLastDay_suppressesSpringOnFirstDay() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.KING_TIDE));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
        verifyNoInteractions(locationRepository);
        verifyNoInteractions(forecastEvaluationRepository);
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
    @DisplayName("spring tide on T+1 emits pill with 'tomorrow' label")
    void detect_springTideTomorrow_emitsWithTomorrowLabel() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.SPRING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY.plusDays(1), "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).detail()).contains("tomorrow");
        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(1));
    }

    @Test
    @DisplayName("spring tide on T+2 emits pill with day-of-week label")
    void detect_springTideInTwoDays_emitsWithDayName() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY.plusDays(2), "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        // 2026-04-18 is a Saturday
        assertThat(topics.get(0).detail()).contains("Saturday");
    }

    @Test
    @DisplayName("only one pill emitted even when multiple spring tide days exist")
    void detect_multipleSpringTideDays_emitsOnlyFirst() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.SPRING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("multiple coastal regions included in pill")
    void detect_multipleCoastalRegions_allIncluded() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland", "The North Yorkshire Coast",
                "Tyne and Wear");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).regions()).containsExactly("Northumberland",
                "The North Yorkshire Coast", "Tyne and Wear");
    }

    // ── Boundary tests ────────────────────────────────────────────────────────

    @Test
    @DisplayName("spring tide on toDate boundary (last day of window) is detected")
    void detect_springTideOnLastDay_detected() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.SPRING_TIDE));
        stubCoastalLocations(TO_DATE, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TO_DATE);
    }

    @Test
    @DisplayName("single-day window with spring tide emits pill")
    void detect_singleDayWindow_springTide_emits() {
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(TODAY, LunarTideType.SPRING_TIDE)));
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
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
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
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
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
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
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
    @DisplayName("does not query location repository when no spring tide found")
    void detect_noSpringTide_noLocationQuery() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));

        strategy.detect(TODAY, TO_DATE);

        verifyNoInteractions(locationRepository);
        verifyNoInteractions(forecastEvaluationRepository);
    }

    @Test
    @DisplayName("king tide days are skipped — no location query")
    void detect_kingTideDays_skippedNoLocationQuery() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
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
                buildDay(TODAY, LunarTideType.SPRING_TIDE, "New Moon")));
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
        assertThat(detail.tideMetrics().tidalClassification()).isEqualTo("Spring tide");
        assertThat(detail.tideMetrics().lunarPhase()).isEqualTo("New Moon");
        assertThat(detail.regionGroups()).hasSize(1);
        assertThat(detail.regionGroups().get(0).locations().get(0).locationType())
                .isEqualTo("Coastal");
    }

    @Test
    @DisplayName("tideLocationMetrics has correct tidePreference")
    void detect_expandedDetail_tideLocationMetricsCorrect() {
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(TODAY, LunarTideType.SPRING_TIDE, "Full Moon")));
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());

        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        LocationEntity loc = LocationEntity.builder()
                .id(1L).name("Bamburgh").lat(55.61).lon(-1.71)
                .tideType(Set.of(TideType.LOW)).region(region).enabled(true).build();
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(loc));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        var tideMetrics = topics.get(0).expandedDetail()
                .regionGroups().get(0).locations().get(0).tideLocationMetrics();
        assertThat(tideMetrics.tidePreference()).isEqualTo("LOW");
    }

    // ── Alignment count tests ─────────────────────────────────────────────

    @Test
    @DisplayName("alignment query is called with the spring tide date, not fromDate")
    void detect_springTideOnT1_queriesAlignmentForCorrectDate() {
        LocalDate springTideDate = TODAY.plusDays(1);
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.SPRING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(springTideDate, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(springTideDate))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 4L},
                        new Object[]{TargetType.SUNSET, 3L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        verify(forecastEvaluationRepository).countTideAlignedByTargetType(springTideDate);
        var metrics = topics.get(0).expandedDetail().tideMetrics();
        assertThat(metrics.tidalClassification()).isEqualTo("Spring tide");
        assertThat(metrics.sunriseAlignedCount()).isEqualTo(4);
        assertThat(metrics.sunsetAlignedCount()).isEqualTo(3);
    }

    // ── Detail line copy tests ──────────────────────────────────────────────

    @Test
    @DisplayName("detail line — both sunrise and sunset aligned")
    void detect_bothAligned_detailShowsBothCounts() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 3L},
                        new Object[]{TargetType.SUNSET, 2L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "Spring tide \u2014 3 locations catch sunrise,"
                        + " 2 catch sunset today");
    }

    @Test
    @DisplayName("detail line — no alignment")
    void detect_noAlignment_detailShowsFallback() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.of());

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "Spring tide today \u2014 no sunrise or sunset"
                        + " alignment, but good coastal foreground");
    }

    @Test
    @DisplayName("detail line — singular location uses 'catches' not 'catch'")
    void detect_singularLocation_usesCorrectGrammar() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 1L},
                        new Object[]{TargetType.SUNSET, 3L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "Spring tide \u2014 1 location catches sunrise,"
                        + " 3 catch sunset today");
    }

    @Test
    @DisplayName("detail line — sunrise only aligned")
    void detect_sunriseOnlyAligned_detailShowsSunrise() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 4L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "Spring tide \u2014 4 locations aligned with sunrise today");
    }

    @Test
    @DisplayName("detail line — sunset only aligned")
    void detect_sunsetOnlyAligned_detailShowsSunset() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNSET, 2L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "Spring tide \u2014 2 locations aligned with sunset today");
    }

    @Test
    @DisplayName("detail line — alignment with Saturday label ends with day name")
    void detect_alignedWithSaturdayLabel_detailEndsWithDayName() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY.plusDays(2), "Northumberland");
        when(forecastEvaluationRepository.countTideAlignedByTargetType(TODAY.plusDays(2)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{TargetType.SUNRISE, 3L},
                        new Object[]{TargetType.SUNSET, 2L}));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        // 2026-04-18 is a Saturday
        assertThat(topics.get(0).detail()).isEqualTo(
                "Spring tide \u2014 3 locations catch sunrise,"
                        + " 2 catch sunset Saturday");
    }

    // ── Statistical spring tide detection ─────────────────────────────────────

    @Test
    @DisplayName("statistical spring tide (isSpringTide=true, lunarTideType=REGULAR) emits pill")
    void detect_statisticalSpringTide_emitsPill() {
        BriefingSlot.TideInfo statisticalSpring = new BriefingSlot.TideInfo(
                "HIGH", true, null, null, false, true, LunarTideType.REGULAR_TIDE,
                "Waxing Crescent", false);
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDayWithTide(TODAY, statisticalSpring)));
        stubCoastalLocations(TODAY, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).type()).isEqualTo("SPRING_TIDE");
    }

    @Test
    @DisplayName("statistical king tide does not emit spring tide pill")
    void detect_statisticalKingTide_noSpringPill() {
        BriefingSlot.TideInfo statisticalKing = new BriefingSlot.TideInfo(
                "HIGH", true, null, null, true, true, LunarTideType.REGULAR_TIDE,
                "Full Moon", false);
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDayWithTide(TODAY, statisticalKing)));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
    }

    // ── Unregioned slot detection ────────────────────────────────────────────

    @Test
    @DisplayName("spring tide in unregioned slot is detected")
    void detect_springTideInUnregionedSlot_detected() {
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDayWithUnregionedTide(TODAY, LunarTideType.SPRING_TIDE)));
        stubCoastalLocations(TODAY, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).type()).isEqualTo("SPRING_TIDE");
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("spring tide only in unregioned — regioned slots have regular tide")
    void findSpringTide_regionedRegularUnregionedSpring_findsSpringTide() {
        BriefingSlot.TideInfo springTide = new BriefingSlot.TideInfo(
                "HIGH", true, null, null, false, true,
                LunarTideType.SPRING_TIDE, "New Moon", false);
        BriefingSlot regularSlot = new BriefingSlot(
                "Coastal", null, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of(), null);
        BriefingSlot springSlot = new BriefingSlot(
                "Orphan Coastal", null, Verdict.GO, null, springTide,
                List.of(), null);
        BriefingRegion region = new BriefingRegion(
                "Northumberland", Verdict.GO, null, List.of(),
                List.of(regularSlot), null, null, null, null, null, null);
        BriefingEventSummary event = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of(springSlot));
        BriefingDay day = new BriefingDay(TODAY, List.of(event));

        BriefingSlot.TideInfo result = SpringTideHotTopicStrategy.findSpringTide(day);

        assertThat(result).isNotNull();
        assertThat(result.lunarTideType()).isEqualTo(LunarTideType.SPRING_TIDE);
    }

    @Test
    @DisplayName("king tide in unregioned slot does not emit spring pill")
    void findSpringTide_unregionedKingTide_returnsNull() {
        BriefingSlot.TideInfo kingTide = new BriefingSlot.TideInfo(
                "HIGH", true, null, null, true, true,
                LunarTideType.KING_TIDE, "Full Moon", true);
        BriefingSlot kingSlot = new BriefingSlot(
                "Orphan Coastal", null, Verdict.GO, null, kingTide,
                List.of(), null);
        BriefingEventSummary event = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(), List.of(kingSlot));
        BriefingDay day = new BriefingDay(TODAY, List.of(event));

        BriefingSlot.TideInfo result = SpringTideHotTopicStrategy.findSpringTide(day);

        assertThat(result).isNull();
    }

    // ── Cached days outside detection window ──────────────────────────────────

    @Test
    @DisplayName("spring tide outside detection window is ignored")
    void detect_springTideOutsideWindow_ignored() {
        LocalDate beforeWindow = TODAY.minusDays(1);
        LocalDate afterWindow = TO_DATE.plusDays(1);
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(beforeWindow, LunarTideType.SPRING_TIDE),
                buildDay(TODAY, LunarTideType.REGULAR_TIDE),
                buildDay(afterWindow, LunarTideType.SPRING_TIDE)));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).isEmpty();
        verifyNoInteractions(locationRepository);
    }

    @Test
    @DisplayName("spring tide inside window detected despite extra days outside window")
    void detect_springTideInsideWindowWithExtraDays_detected() {
        LocalDate beforeWindow = TODAY.minusDays(2);
        LocalDate afterWindow = TO_DATE.plusDays(2);
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(beforeWindow, LunarTideType.REGULAR_TIDE),
                buildDay(TODAY, LunarTideType.REGULAR_TIDE),
                buildDay(TODAY.plusDays(1), LunarTideType.SPRING_TIDE),
                buildDay(afterWindow, LunarTideType.REGULAR_TIDE)));
        stubCoastalLocations(TODAY.plusDays(1), "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(1));
    }

    // ── Sunset event detection ────────────────────────────────────────────────

    @Test
    @DisplayName("spring tide found only in sunset event is detected")
    void detect_springTideInSunsetEvent_detected() {
        BriefingSlot.TideInfo springTide = new BriefingSlot.TideInfo(
                "HIGH", true, null, null, false, true,
                LunarTideType.SPRING_TIDE, "New Moon", false);
        BriefingSlot regularSlot = new BriefingSlot(
                "Coastal", null, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of(), null);
        BriefingSlot springSlot = new BriefingSlot(
                "Coastal", null, Verdict.GO, null, springTide, List.of(), null);
        BriefingRegion sunriseRegion = new BriefingRegion(
                "Northumberland", Verdict.GO, null, List.of(),
                List.of(regularSlot), null, null, null, null, null, null);
        BriefingRegion sunsetRegion = new BriefingRegion(
                "Northumberland", Verdict.GO, null, List.of(),
                List.of(springSlot), null, null, null, null, null, null);
        BriefingEventSummary sunrise = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(sunriseRegion), List.of());
        BriefingEventSummary sunset = new BriefingEventSummary(
                TargetType.SUNSET, List.of(sunsetRegion), List.of());
        BriefingDay day = new BriefingDay(TODAY, List.of(sunrise, sunset));

        when(briefingService.getCachedDays()).thenReturn(List.of(day));
        stubCoastalLocations(TODAY, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).type()).isEqualTo("SPRING_TIDE");
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
        if (tideType == LunarTideType.SPRING_TIDE) {
            tideInfo = new BriefingSlot.TideInfo(
                    "HIGH", true, null, null, false, true,
                    LunarTideType.SPRING_TIDE, moonPhase, false);
        } else {
            tideInfo = BriefingSlot.TideInfo.NONE;
        }
        BriefingSlot slot = new BriefingSlot(
                "Orphan Coastal", null, Verdict.GO, null, tideInfo, List.of(), null);
        BriefingEventSummary event = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(), List.of(slot));
        return new BriefingDay(date, List.of(event));
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
