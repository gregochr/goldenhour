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
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    private SolarEventFreshness freshness;

    @Mock
    private CoastalTideFactsBuilder coastalTideFactsBuilder;

    @Mock
    private TideRunBuilder tideRunBuilder;

    @Mock
    private LunarPhaseService lunarPhaseService;

    private SpringTideHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        // Default: every solar event is still ahead. Expiry tests override per date.
        lenient().when(freshness.isAhead(any(LocationEntity.class), any(), any()))
                .thenReturn(true);
        lenient().when(lunarPhaseService.nearestSyzygyIsPerigean(any()))
                .thenAnswer(inv -> fixtureSaysPerigean(inv.getArgument(0)));
        strategy = new SpringTideHotTopicStrategy(briefingService, locationRepository,
                freshness, coastalTideFactsBuilder, tideRunBuilder, lunarPhaseService);
    }

    /**
     * The lunar stub answers from the FIXTURE the test stubbed, rather than from a hand-kept list.
     *
     * <p>{@code classifyTide} returns KING_TIDE only when it judged that date's syzygy perigean, so
     * a fixture carrying a KING_TIDE slot has already said what {@code nearestSyzygyIsPerigean} must
     * say for the same date. Deriving it keeps the fixture the single source of that astronomy, and
     * means every existing test's intent survives the move from a per-date question to a per-run one.
     *
     * <p>Reading the stubbed days back at call time rather than hooking the day-builders is
     * deliberate: several tests construct a {@code BriefingDay} inline — an unregioned slot, a
     * second event summary — and never touch a builder, so a builder-side hook drifts.
     */
    private boolean fixtureSaysPerigean(LocalDate date) {
        List<BriefingDay> days = briefingService.getCachedDays();
        return days != null && days.stream()
                .filter(d -> date.equals(d.date()))
                .anyMatch(d -> KingTideHotTopicStrategy.findKingTide(d) != null);
    }

    @Test
    @DisplayName("spring tide today emits pill with priority 2, dated today")
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
        assertThat(topic.detail()).doesNotContain("today");
        assertThat(topic.detail()).contains("coastal location");
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
    @DisplayName("a king tide earlier in the window does not suppress a spring tide two days later")
    void detect_springTideWithKingTideElsewhereInWindow_notSuppressed() {
        // Asserted the opposite while the suppression was window-wide. A king tide on Monday and a
        // spring tide on Wednesday are two different tidal events; silencing the second because the
        // first shares a forecast window deleted a genuine card, and the rule the suppression
        // exists to hold — at most one tide topic per DAY — never asked for it.
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(TODAY, LunarTideType.KING_TIDE),
                buildDay(TODAY.plusDays(1), LunarTideType.REGULAR_TIDE),
                buildDay(TODAY.plusDays(2), LunarTideType.SPRING_TIDE),
                buildDay(TODAY.plusDays(3), LunarTideType.REGULAR_TIDE)));
        stubCoastalLocations(TODAY.plusDays(2), "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).type()).isEqualTo("SPRING_TIDE");
        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(2));
    }

    @Test
    @DisplayName("the king tide's own day still yields — at most one tide topic on any one date")
    void detect_kingAndSpringOnSameDay_kingWinsThatDate() {
        // The per-day invariant, stated where it is enforced. A day that is a king tide emits no
        // spring topic no matter how big its water is, so the two strategies can never both
        // produce a card for the same date.
        BriefingSlot.TideInfo kingWithSpringSizedWater = new BriefingSlot.TideInfo(
                "HIGH", true, null, null, true, true, LunarTideType.KING_TIDE,
                "New Moon", true);
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDayWithTide(TODAY, kingWithSpringSizedWater),
                buildDay(TODAY.plusDays(1), LunarTideType.SPRING_TIDE)));
        stubCoastalLocations(TODAY.plusDays(1), "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).singleElement()
                .satisfies(t -> assertThat(t.date()).isEqualTo(TODAY.plusDays(1)));
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
    @DisplayName("king tide on the last day of the window leaves the first day's spring tide alone")
    void detect_kingTideOnLastDay_doesNotSuppressSpringOnFirstDay() {
        // The same window-wide bug read backwards: a king tide three days AHEAD used to delete
        // today's spring card, so the pill vanished for an event that had not happened yet.
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.KING_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
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
    @DisplayName("spring tide on T+1 emits pill dated tomorrow, no day word in detail")
    void detect_springTideTomorrow_emitsWithTomorrowLabel() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.SPRING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY.plusDays(1), "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).detail()).doesNotContain("tomorrow");
        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(1));
    }

    @Test
    @DisplayName("spring tide on T+2 emits pill dated that day, no day word in detail")
    void detect_springTideInTwoDays_emitsWithDayName() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY.plusDays(2), "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(2));
        assertThat(topics.get(0).detail()).doesNotContain("Saturday");
    }

    @Test
    @DisplayName("one card per day when multiple spring tide days exist")
    void detect_multipleSpringTideDays_onePerDate() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.SPRING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(2);
        assertThat(topics).extracting(HotTopic::date)
                .containsExactly(TODAY, TODAY.plusDays(1));
        assertThat(topics).noneMatch(t -> t.detail().contains("today and tomorrow"));
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
        verifyNoInteractions(tideRunBuilder);
    }

    // ── Region edge cases ────────────────────────────────────────────────────

    @Test
    @DisplayName("duplicate regions from multiple coastal locations are deduplicated")
    void detect_duplicateRegions_deduplicated() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));

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
        verifyNoInteractions(tideRunBuilder);
    }

    @Test
    @DisplayName("king tide days are skipped — no location query")
    void detect_kingTideDays_skippedNoLocationQuery() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.KING_TIDE, LunarTideType.KING_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));

        strategy.detect(TODAY, TO_DATE);

        verifyNoInteractions(locationRepository);
        verifyNoInteractions(tideRunBuilder);
    }

    // ── expandedDetail tests ────────────────────────────────────────────────

    @Test
    @DisplayName("expandedDetail populated with regionGroups of coastal locations")
    void detect_expandedDetail_populatedWithRegionGroups() {
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDay(TODAY, LunarTideType.SPRING_TIDE, "New Moon")));

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

        stubRun(Map.of(springTideDate, runDay("sunrise")));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        var metrics = topics.get(0).expandedDetail().tideMetrics();
        assertThat(metrics.tidalClassification()).isEqualTo("Spring tide");
        assertThat(metrics.sunriseAlignedCount()).isEqualTo(12);
        assertThat(metrics.sunsetAlignedCount()).isEqualTo(0);
    }

    // ── Detail line copy tests ──────────────────────────────────────────────

    @Test
    @DisplayName("detail line — both sunrise and sunset aligned")
    void detect_bothAligned_detailShowsBothCounts() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        stubRun(Map.of(TODAY, runDay("sunrise")));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "tide aligned with sunrise at 1 of 1 coastal location");
    }

    @Test
    @DisplayName("detail line — no alignment")
    void detect_noAlignment_detailShowsFallback() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        stubRun(Map.of(TODAY, runDay(null)));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "no sunrise or sunset"
                        + " alignment, but good coastal foreground \u00b7 1 coastal location");
    }

    @Test
    @DisplayName("detail line — singular location uses 'catches' not 'catch'")
    void detect_singularLocation_usesCorrectGrammar() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        stubRun(Map.of(TODAY, runDay("sunrise")));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "tide aligned with sunrise at 1 of 1 coastal location");
    }

    @Test
    @DisplayName("detail line — sunrise only aligned")
    void detect_sunriseOnlyAligned_detailShowsSunrise() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        stubRun(Map.of(TODAY, runDay("sunrise")));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "tide aligned with sunrise at 1 of 1 coastal location");
    }

    @Test
    @DisplayName("detail line — sunset only aligned")
    void detect_sunsetOnlyAligned_detailShowsSunset() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY, "Northumberland");
        stubRun(Map.of(TODAY, runDay("sunset")));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).detail()).isEqualTo(
                "tide aligned with sunset at 1 of 1 coastal location");
    }

    @Test
    @DisplayName("detail line — a T+2 spring tide card names its own alignment")
    void detect_alignedWithSaturdayLabel_detailEndsWithDayName() {
        when(briefingService.getCachedDays()).thenReturn(buildDays(
                LunarTideType.REGULAR_TIDE, LunarTideType.REGULAR_TIDE,
                LunarTideType.SPRING_TIDE, LunarTideType.REGULAR_TIDE));
        stubCoastalLocations(TODAY.plusDays(2), "Northumberland");
        stubRun(Map.of(TODAY.plusDays(2), runDay("sunrise")));

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(2));
        assertThat(topics.get(0).detail()).isEqualTo(
                "tide aligned with sunrise at 1 of 1 coastal location");
    }

    // ── Statistical spring tide detection ─────────────────────────────────────

    @Test
    @DisplayName("spring-sized water outside the syzygy window still emits a pill — the age of "
            + "the tide puts the biggest water a day or two after the new moon")
    void detect_heightAboveThresholdWithoutSyzygy_emitsPill() {
        // THIS IS THE REGRESSION TEST. It asserted the exact opposite between 2026-08-13 and
        // 2026-08-14, and that inversion is what emptied the Plan tab's tide pill: the lunar window
        // is 2–3 dates wide, a port's biggest water lags syzygy by a day or two, and against the
        // August 2026 new moon (12 Aug 17:38 UTC) the run measured 12–17 Aug and peaked on the
        // 14th — a date the lunar test puts 1.77 days from syzygy and therefore REGULAR_TIDE.
        //
        // The inversion was defended on the grounds that a port can clear its threshold "for
        // reasons that are weather rather than astronomy". It cannot: tide_extreme is populated
        // from WorldTides' extremes endpoint, which is a harmonic prediction with no surge in it.
        // The height arm is an astronomical test taken at the coastline instead of at the moon.
        BriefingSlot.TideInfo bigButNotSyzygy = new BriefingSlot.TideInfo(
                "HIGH", true, null, null, false, true, LunarTideType.REGULAR_TIDE,
                "Waxing Crescent", false);
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDayWithTide(TODAY, bigButNotSyzygy)));
        stubCoastalLocations(TODAY, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).type()).isEqualTo("SPRING_TIDE");
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("water below the spring threshold with a regular moon emits nothing — the height "
            + "arm is a threshold, not a pass")
    void detect_heightBelowThresholdWithoutSyzygy_emitsNoPill() {
        // The boundary the test above does not cover: restoring the height arm must not make every
        // coastal day a spring tide. Same fixture, threshold flag off.
        BriefingSlot.TideInfo ordinaryWater = new BriefingSlot.TideInfo(
                "HIGH", true, null, null, false, false, LunarTideType.REGULAR_TIDE,
                "Waxing Crescent", false);
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDayWithTide(TODAY, ordinaryWater)));

        assertThat(strategy.detect(TODAY, TO_DATE)).isEmpty();
        verifyNoInteractions(locationRepository);
    }

    @Test
    @DisplayName("water above P95 with a regular moon emits SPRING, not KING and not silence")
    void detect_heightAboveP95WithoutSyzygy_emitsSpringNotKing() {
        // The hole between the two strategies, and the one that actually bit. King is lunar-only
        // and must stay so — its copy describes the moon's closest approach, so a merely-large
        // tide labelled "king" is a false claim about a cause. But if spring is ALSO lunar-only,
        // the biggest water of the cycle falls between the two rules and the reader is told
        // nothing at all on the very day the foreshore is at its widest.
        //
        // "Spring" claims only that the water is big, which is precisely what P95 measures.
        BriefingSlot.TideInfo p95WaterRegularMoon = new BriefingSlot.TideInfo(
                "HIGH", true, null, null, true, true, LunarTideType.REGULAR_TIDE,
                "Full Moon", false);
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDayWithTide(TODAY, p95WaterRegularMoon)));
        stubCoastalLocations(TODAY, "Northumberland");

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).singleElement()
                .satisfies(t -> assertThat(t.type()).isEqualTo("SPRING_TIDE"));
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

    // ── The two axes, varied independently ───────────────────────────────────

    /**
     * The full truth table of {@code isSpringNotKing}, one row per (lunar × height) combination.
     *
     * <p><b>This test exists because its absence shipped a regression.</b> Every other fixture in
     * this file comes from {@link #buildDay}, which until now derived the height flags <em>from</em>
     * the lunar type — so the two arms of {@code (lunar == SPRING || heightAboveSpringThreshold)}
     * moved together and neither could be observed alone. Deleting the height arm was invisible to
     * 35 of 38 tests, and when it was deleted the suite stayed green while the Plan tab's tide pill
     * disappeared for a week (#514).
     *
     * <p>A truth table is the smallest thing that cannot have that blind spot: every operand is
     * false in some row and true in another, so <b>deleting either arm must fail a row</b>. Rows 2
     * and 3 are the two that a single-arm implementation gets wrong, in opposite directions.
     *
     * <p>King is deliberately absent from the table — it is a third state rather than a fourth
     * combination, and {@code detect_kingAndSpringOnSameDay_kingWinsThatDate} pins it.
     */
    @ParameterizedTest(name = "lunar={0}, aboveSpringThreshold={1} → pill={2}")
    @CsvSource({
        // The moon says so and the water agrees — the ordinary spring tide.
        "SPRING_TIDE,  true,  true",
        // The moon says so but this port's water has not built yet: the run's leading edge, and the
        // row that fails if the LUNAR arm is deleted.
        "SPRING_TIDE,  false, true",
        // The water says so but syzygy has passed: the age of the tide, the run's peak, and the row
        // that fails if the HEIGHT arm is deleted. This is 14 Aug 2026 — the reported bug.
        "REGULAR_TIDE, true,  true",
        // Neither — an ordinary neap day, and the row that fails if the predicate becomes a pass.
        "REGULAR_TIDE, false, false",
    })
    void isSpringNotKing_bothAxesAreObservableIndependently(
            LunarTideType lunar, boolean aboveSpringThreshold, boolean expectPill) {
        when(briefingService.getCachedDays()).thenReturn(List.of(
                buildDayWithAxes(TODAY, lunar, "New Moon", aboveSpringThreshold, false)));
        if (expectPill) {
            stubCoastalLocations(TODAY, "Northumberland");
        }

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(expectPill ? 1 : 0);
        if (expectPill) {
            assertThat(topics.get(0).type()).isEqualTo("SPRING_TIDE");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * A day carrying the given lunar classification and <b>no</b> spring-sized water.
     *
     * <p><b>The height flags are deliberately false here, and that is the whole point of this
     * helper.</b> It used to set them <em>from</em> the lunar type — KING got both, SPRING got
     * {@code heightAboveSpringThreshold}, REGULAR got {@code TideInfo.NONE} — which made the two
     * axes perfectly correlated across every fixture in this file. {@code isSpringNotKing} reads
     * {@code (lunar == SPRING || heightAboveSpringThreshold)}, so under that correlation the two
     * arms were indistinguishable: deleting the height arm was a no-op to 35 of 38 tests, and when
     * someone did delete it the suite stayed green while the Plan tab's tide pill vanished for a
     * week. The height arm was <em>dead input</em> — a parameter the caller could not reach.
     *
     * <p>With the flags pinned false, a day built here exercises the <b>lunar arm alone</b>, which
     * is what every caller of this helper is actually testing. To vary the other axis use
     * {@link #buildDayWithAxes}; the full truth table is pinned by {@code axesAreIndependent}.
     *
     * @param date     the briefing date
     * @param tideType the lunar classification
     * @return a briefing day whose only spring/king signal is lunar
     */
    private BriefingDay buildDay(LocalDate date, LunarTideType tideType) {
        String moonPhase = (tideType == LunarTideType.KING_TIDE
                || tideType == LunarTideType.SPRING_TIDE) ? "Full Moon" : null;
        return buildDay(date, tideType, moonPhase);
    }

    private BriefingDay buildDay(LocalDate date, LunarTideType tideType, String moonPhase) {
        return buildDayWithAxes(date, tideType, moonPhase, false, false);
    }

    /**
     * A day whose two tide axes are set <b>independently</b> — the lunar classification and the two
     * measured-height flags.
     *
     * <p>This is the helper a test should reach for whenever the behaviour under test could depend
     * on the height axis. Every argument is explicit precisely so that no caller inherits a hidden
     * correlation; see {@link #buildDay} for the regression that made this necessary.
     *
     * @param date                the briefing date
     * @param tideType            the lunar classification
     * @param moonPhase           the moon phase name, or null
     * @param aboveSpringThreshold whether the water cleared this port's spring threshold
     * @param aboveP95            whether the water cleared this port's P95
     * @return a briefing day carrying exactly those two axes
     */
    private BriefingDay buildDayWithAxes(LocalDate date, LunarTideType tideType, String moonPhase,
            boolean aboveSpringThreshold, boolean aboveP95) {
        if (tideType == LunarTideType.REGULAR_TIDE && !aboveSpringThreshold && !aboveP95) {
            // The genuinely empty case: no lunar event and no notable water.
            return buildDayWithTide(date, BriefingSlot.TideInfo.NONE);
        }
        return buildDayWithTide(date, new BriefingSlot.TideInfo(
                "HIGH", true, null, null, aboveP95, aboveSpringThreshold,
                tideType, moonPhase, tideType == LunarTideType.KING_TIDE));
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
    }

    /**
     * Stubs the run builder so each named date carries a run row. The detail line's alignment
     * segment is read from these rows — the same source as the chart beneath it.
     *
     * @param run run rows by date
     */
    private void stubRun(Map<LocalDate, TideRunDay> run) {
        when(tideRunBuilder.build(any(), any(), eq(false))).thenReturn(run);
    }

    /**
     * A run row aligned with the given solar event, or unaligned when {@code alignedEvent} is null.
     *
     * @param alignedEvent {@code "sunrise"}, {@code "sunset"}, or null for an unaligned day
     * @return the run row
     */
    private static TideRunDay runDay(String alignedEvent) {
        return new TideRunDay(
                "SPRING RUN", 1, 1, "THU 16", "St. Mary's Lighthouse",
                "4.2 m", "+0.4", null, null, null,
                "05:37", "20:38", null, List.of(),
                alignedEvent == null
                        ? "no clear tide/sun alignment"
                        : "LW 05:44 \u00b7 7m after " + alignedEvent,
                // bestAligned mirrors aligned: this is a one-day run, so an aligned day is
                // trivially the run's best and is the card that takes the accent.
                alignedEvent != null, alignedEvent != null, alignedEvent,
                // Non-null with alignedEvent, as the builder guarantees.
                alignedEvent == null ? null : "LW 05:44 · 7m after " + alignedEvent,
                new TideRunDay.RosterAlignment(
                        "sunrise".equals(alignedEvent) ? 12 : 0,
                        "sunset".equals(alignedEvent) ? 12 : 0,
                        61),
                // sunriseWater/sunsetWater: not this fixture's subject.
                null, null,
                false, null);
    }
}
