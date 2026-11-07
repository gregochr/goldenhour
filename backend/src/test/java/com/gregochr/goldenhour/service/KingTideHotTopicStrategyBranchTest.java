package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.TideRunDay;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.LocationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Branch tests for {@link KingTideHotTopicStrategy}: the coastal science attached to a king card, run-row
 * helpers for missing rosters and single-location rosters, tidal-slot discovery, and drill-down tide
 * preferences.
 */
@ExtendWith(MockitoExtension.class)
class KingTideHotTopicStrategyBranchTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 16);

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
    private TideRunExtent tideRunExtent;
    @Mock
    private LunarPhaseService lunarPhaseService;

    private KingTideHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new KingTideHotTopicStrategy(briefingService, locationRepository, freshness,
                coastalTideFactsBuilder, tideRunBuilder, tideRunExtent, lunarPhaseService);
    }

    private static BriefingSlot.TideInfo kingTide() {
        return new BriefingSlot.TideInfo("HIGH", true, null, null, true, true, LunarTideType.KING_TIDE,
                "Full Moon", true);
    }

    private static BriefingDay dayWith(LocalDate date, BriefingSlot.TideInfo regionTide,
            BriefingSlot.TideInfo unregionedTide) {
        BriefingSlot regionSlot = new BriefingSlot("Coastal", null, Verdict.GO, null, regionTide, List.of());
        BriefingRegion region = new BriefingRegion("Northumberland", Verdict.GO, null, List.of(),
                List.of(regionSlot), null, null, null, null, null, null);
        List<BriefingSlot> unregioned = unregionedTide == null ? List.of()
                : List.of(new BriefingSlot("Orphan", null, Verdict.GO, null, unregionedTide, List.of()));
        return new BriefingDay(date, List.of(
                new BriefingEventSummary(TargetType.SUNRISE, List.of(region), unregioned)));
    }

    private static TideRunDay runDay(String alignedEvent, TideRunDay.RosterAlignment roster) {
        return new TideRunDay("KING RUN", 1, 1, "THU 16", "St. Mary's Lighthouse", "4.2 m", null, "2.5 m",
                "05:37", "20:38", null, List.of(), "verdict", alignedEvent != null, alignedEvent, roster, false,
                null);
    }

    private static LocationEntity location(long id, String name, Set<TideType> tideTypes) {
        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        return LocationEntity.builder().id(id).name(name).lat(55.0).lon(-1.5).tideType(tideTypes)
                .region(region).enabled(true).build();
    }

    @Test
    @DisplayName("a king card carries the coastal science's facts and note when the builder produces them")
    void detect_attachesCoastalScience() {
        BriefingDay day = dayWith(TODAY, kingTide(), null);
        LocationEntity craster = location(1L, "Craster", Set.of(TideType.HIGH));
        List<LocationEntity> coastal = List.of(craster);
        HotTopicFact fact = HotTopicFact.metric("range", "6.1 m");
        when(briefingService.getCachedDays()).thenReturn(List.of(day));
        when(lunarPhaseService.nearestSyzygyIsPerigean(TODAY)).thenReturn(true);
        when(locationRepository.findCoastalLocations()).thenReturn(coastal);
        when(freshness.isAhead(craster, TODAY, TargetType.SUNRISE)).thenReturn(true);
        when(freshness.isAhead(craster, TODAY, TargetType.SUNSET)).thenReturn(true);
        when(tideRunBuilder.build(List.of(TODAY), coastal, true)).thenReturn(Map.of());
        when(coastalTideFactsBuilder.buildKing(day, coastal))
                .thenReturn(new CoastalTideFactsBuilder.CoastalScience(List.of(fact), "look low"));

        List<HotTopic> topics = strategy.detect(TODAY, TODAY);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).facts()).containsExactly(fact);
        assertThat(topics.get(0).note()).isEqualTo("look low");
    }

    @Test
    @DisplayName("rosterCounts of a run row with no roster is empty")
    void rosterCounts_nullRoster_empty() {
        TideRunDay noRoster = runDay("sunrise", null);

        assertThat(KingTideHotTopicStrategy.rosterCounts(noRoster, Set.of(TargetType.SUNRISE))).isEmpty();
    }

    @Test
    @DisplayName("rosterCounts keeps only the still-ahead events")
    void rosterCounts_dropsExpiredEvents() {
        TideRunDay day = runDay("sunrise", new TideRunDay.RosterAlignment(47, 12, 61));

        assertThat(KingTideHotTopicStrategy.rosterCounts(day, Set.of(TargetType.SUNRISE)))
                .containsOnly(Map.entry(TargetType.SUNRISE, 47L));
        assertThat(KingTideHotTopicStrategy.rosterCounts(day, Set.of(TargetType.SUNSET)))
                .containsOnly(Map.entry(TargetType.SUNSET, 12L));
        assertThat(KingTideHotTopicStrategy.rosterCounts(day, Set.of())).isEmpty();
    }

    @Test
    @DisplayName("rosterScope is empty with no roster, no coastal locations or nobody aligned")
    void rosterScope_emptyCases() {
        assertThat(KingTideHotTopicStrategy.rosterScope(runDay("sunrise", null), 5)).isEmpty();
        assertThat(KingTideHotTopicStrategy.rosterScope(
                runDay("sunrise", new TideRunDay.RosterAlignment(3, 0, 5)), 0)).isEmpty();
        assertThat(KingTideHotTopicStrategy.rosterScope(
                runDay("sunrise", new TideRunDay.RosterAlignment(0, 4, 5)), 5)).isEmpty();
    }

    @Test
    @DisplayName("rosterScope is singular for one coastal location and is capped at the roster size")
    void rosterScope_singularAndCapped() {
        assertThat(KingTideHotTopicStrategy.rosterScope(
                runDay("sunrise", new TideRunDay.RosterAlignment(1, 0, 1)), 1))
                .isEqualTo(" at 1 of 1 coastal location");
        assertThat(KingTideHotTopicStrategy.rosterScope(
                runDay("sunrise", new TideRunDay.RosterAlignment(47, 0, 61)), 5))
                .isEqualTo(" at 5 of 5 coastal locations");
    }

    @Test
    @DisplayName("an aligned day with an empty scope states the alignment without a roster size")
    void alignmentInfo_emptyScope_doesNotCarryRosterSize() {
        KingTideHotTopicStrategy.Alignment alignment = KingTideHotTopicStrategy.alignmentInfo(
                runDay("sunset", new TideRunDay.RosterAlignment(0, 0, 5)), Set.of(TargetType.SUNSET),
                "unaligned", "passed", 5);

        assertThat(alignment.text()).isEqualTo("tide aligned with sunset");
        assertThat(alignment.carriesRosterSize()).isFalse();
    }

    @Test
    @DisplayName("an aligned day with a scope carries the roster size")
    void alignmentInfo_withScope_carriesRosterSize() {
        KingTideHotTopicStrategy.Alignment alignment = KingTideHotTopicStrategy.alignmentInfo(
                runDay("sunset", new TideRunDay.RosterAlignment(0, 4, 5)), Set.of(TargetType.SUNSET),
                "unaligned", "passed", 5);

        assertThat(alignment.text()).isEqualTo("tide aligned with sunset at 4 of 5 coastal locations");
        assertThat(alignment.carriesRosterSize()).isTrue();
    }

    @Test
    @DisplayName("findTidalSlot ignores a non-tidal unregioned slot and finds a later tidal one")
    void findTidalSlot_unregionedNotTidal() {
        BriefingSlot.TideInfo regular = new BriefingSlot.TideInfo("HIGH", true, null, null, false, false,
                LunarTideType.REGULAR_TIDE, null, false);
        BriefingDay none = dayWith(TODAY, regular, regular);
        BriefingDay found = dayWith(TODAY, regular, kingTide());

        assertThat(KingTideHotTopicStrategy.findTidalSlot(none)).isNull();
        assertThat(KingTideHotTopicStrategy.findTidalSlot(found)).isEqualTo(kingTide());
    }

    @Test
    @DisplayName("isTidal is false for no tide and true on the lunar axis or on the water axis alone")
    void isTidal_axes() {
        BriefingSlot.TideInfo springThresholdOnly = new BriefingSlot.TideInfo("HIGH", true, null, null, false,
                true, LunarTideType.REGULAR_TIDE, null, false);
        BriefingSlot.TideInfo p95Only = new BriefingSlot.TideInfo("HIGH", true, null, null, true, false,
                LunarTideType.REGULAR_TIDE, null, false);
        BriefingSlot.TideInfo springLunar = new BriefingSlot.TideInfo("HIGH", true, null, null, false, false,
                LunarTideType.SPRING_TIDE, null, false);

        assertThat(KingTideHotTopicStrategy.isTidal(null)).isFalse();
        assertThat(KingTideHotTopicStrategy.isTidal(springThresholdOnly)).isTrue();
        assertThat(KingTideHotTopicStrategy.isTidal(p95Only)).isFalse();
        assertThat(KingTideHotTopicStrategy.isTidal(springLunar)).isTrue();
        assertThat(KingTideHotTopicStrategy.isTidal(kingTide())).isTrue();
    }

    @Test
    @DisplayName("the drill-down lists a location's tide preference, or none when it has none")
    void buildExpandedDetail_tidePreference() {
        LocationEntity high = location(1L, "Craster", Set.of(TideType.HIGH));
        LocationEntity empty = location(2L, "Bamburgh", Set.of());
        LocationEntity unset = location(3L, "Alnmouth", null);

        ExpandedHotTopicDetail detail = KingTideHotTopicStrategy.buildExpandedDetail(
                List.of(high, empty, unset), "King tide", "Full Moon", Map.of());

        List<ExpandedHotTopicDetail.LocationEntry> entries = detail.regionGroups().get(0).locations();
        assertThat(entries).extracting(ExpandedHotTopicDetail.LocationEntry::locationName)
                .containsExactly("Alnmouth", "Bamburgh", "Craster");
        assertThat(entries).extracting(e -> e.tideLocationMetrics().tidePreference())
                .containsExactly(null, null, "HIGH");
    }
}
