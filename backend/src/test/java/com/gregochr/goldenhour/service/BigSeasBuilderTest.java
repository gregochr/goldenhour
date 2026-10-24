package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CoastalStructure;
import com.gregochr.goldenhour.model.BigSeasCandidate;
import com.gregochr.goldenhour.model.BigSeasDay;
import com.gregochr.goldenhour.model.BigSeasDay.AlignedEvent;
import com.gregochr.goldenhour.model.BigSeasDay.GapSide;
import com.gregochr.goldenhour.model.BigSeasStructure;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.repository.MarineHighWaterRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BigSeasBuilder} with the real {@link SolarService} (sunrise and sunset are
 * read from it, so no solar value is hand-typed) and a fixed clock in December 2026, never "today".
 * The wall clock is Thursday 10 Dec 2026 12:00 UTC (GMT, so the UK date is the UTC date).
 */
@ExtendWith(MockitoExtension.class)
class BigSeasBuilderTest {

    private static final Instant NOW = Instant.parse("2026-12-10T12:00:00Z");
    private static final double LAT = 55.0;
    private static final double LON = -1.5;
    private static final LocalDate D10 = LocalDate.of(2026, 12, 10);
    private static final LocalDate D11 = LocalDate.of(2026, 12, 11);
    private static final LocalDate D12 = LocalDate.of(2026, 12, 12);
    private static final LocalDate D15 = LocalDate.of(2026, 12, 15);

    private final SolarService solar = new SolarService();
    private final BigSeasProperties properties = new BigSeasProperties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private MarineHighWaterRepository repository;

    private BigSeasBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new BigSeasBuilder(repository, solar, properties, clock);
    }

    private static List<BriefingDay> days(LocalDate... dates) {
        return java.util.Arrays.stream(dates).map(d -> new BriefingDay(d, List.of(), null)).toList();
    }

    private static List<BriefingDay> fiveDays() {
        return days(D10, D11, D12, LocalDate.of(2026, 12, 13), LocalDate.of(2026, 12, 14));
    }

    /** Stubs the one query with the exact arguments the builder must pass for days D10..D14. */
    private void rows(BigSeasCandidate... candidates) {
        when(repository.findBigSeasCandidates(NOW.minus(30, ChronoUnit.HOURS),
                ForecastHorizon.ukDayStartUtc(D10), ForecastHorizon.ukDayStartUtc(LocalDate.of(2026, 12, 15))))
                .thenReturn(List.of(candidates));
    }

    private static BigSeasCandidate cand(long id, String name, LocalDateTime hw, LocalDateTime start,
            LocalDateTime end, double wave) {
        return new BigSeasCandidate(id, name, CoastalStructure.PIER, name + " Pier", 90, LAT, LON, hw, start,
                end, wave, 10.0, 90);
    }

    /** A candidate whose window is its high water plus or minus 90 minutes. */
    private static BigSeasCandidate cand(long id, String name, LocalDateTime hw, double wave) {
        return cand(id, name, hw, hw.minusMinutes(90), hw.plusMinutes(90), wave);
    }

    private LocalDateTime sunrise(LocalDate d) {
        return solar.sunriseUtc(LAT, LON, d);
    }

    private LocalDateTime sunset(LocalDate d) {
        return solar.sunsetUtc(LAT, LON, d);
    }

    /** 11:00 UTC on 11 Dec: between sunrise (about 08:05) and sunset (about 15:40), so unaligned by 2 h+. */
    private static LocalDateTime midday(int plusMinutes) {
        return LocalDateTime.of(2026, 12, 11, 11, 0).plusMinutes(plusMinutes);
    }

    /** 19:00 UTC on 11 Dec: well after sunset (about 15:40), so a night high water with a night window. */
    private static LocalDateTime night(int plusMinutes) {
        return LocalDateTime.of(2026, 12, 11, 19, 0).plusMinutes(plusMinutes);
    }

    private static BigSeasCandidate candAt(long id, String name, double lon, LocalDateTime hw, LocalDateTime start,
            LocalDateTime end, double wave) {
        return new BigSeasCandidate(id, name, CoastalStructure.PIER, name + " Pier", 90, LAT, lon, hw, start,
                end, wave, 10.0, 90);
    }

    @Test
    @DisplayName("sanity: the midday fixtures are really clear of sunrise and sunset")
    void middayFixturesAreUnaligned() {
        assertThat(sunrise(D11)).isBefore(midday(-90).minusHours(1));
        assertThat(sunset(D11)).isAfter(midday(90).plusHours(2));
    }

    @Test
    @DisplayName("one aligned high water: the day carries its window, wave, gap in whole minutes and side")
    void singleAlignedStructure() {
        LocalDateTime hw = sunrise(D11).plusMinutes(20);
        rows(cand(1, "Roker", hw, 4.5));

        List<BigSeasDay> result = builder.build(fiveDays());

        assertThat(result).hasSize(1);
        BigSeasDay day = result.getFirst();
        assertThat(day.date()).isEqualTo(D11);
        assertThat(day.windowStart()).isEqualTo(hw.minusMinutes(90));
        assertThat(day.windowEnd()).isEqualTo(hw.plusMinutes(90));
        assertThat(day.hwTime()).isEqualTo(hw);
        assertThat(day.alignedEvent()).isEqualTo(AlignedEvent.SUNRISE);
        assertThat(day.gapMinutes()).isEqualTo(20L);
        assertThat(day.gapSide()).isEqualTo(GapSide.AFTER);
        assertThat(day.waveHeightM()).isEqualTo(4.5);
        assertThat(day.waveDirectionDeg()).isEqualTo(90);
        assertThat(day.wavePeriodS()).isEqualTo(10.0);
        assertThat(day.otherWindows()).isZero();
        assertThat(day.structures()).singleElement().satisfies(s -> {
            assertThat(s.locationId()).isEqualTo(1L);
            assertThat(s.label()).isEqualTo("Roker Pier");
            assertThat(s.structure()).isEqualTo(CoastalStructure.PIER);
            assertThat(s.facesDeg()).isEqualTo(90);
            assertThat(s.hwTime()).isEqualTo(hw);
            assertThat(s.aligned()).isTrue();
        });
    }

    @Test
    @DisplayName("the gap rounds to the NEAREST minute (89 s -> 1, 91 s -> 2), served positive with the side")
    void gapRoundsToNearestMinuteWithSide() {
        rows(cand(1, "A", sunrise(D11).minusSeconds(89), 4.0),
                cand(2, "B", sunset(D12).minusSeconds(91), 4.0));

        List<BigSeasDay> result = builder.build(fiveDays());

        assertThat(result).hasSize(2);
        assertThat(result.get(0).gapMinutes()).isEqualTo(1L);
        assertThat(result.get(0).gapSide()).isEqualTo(GapSide.BEFORE);
        assertThat(result.get(1).alignedEvent()).isEqualTo(AlignedEvent.SUNSET);
        assertThat(result.get(1).gapMinutes()).isEqualTo(2L);
        assertThat(result.get(1).gapSide()).isEqualTo(GapSide.BEFORE);
    }

    @Test
    @DisplayName("an aligned sunset is found whatever the location's solar event types are (Roker is sunrise-only)")
    void sunsetAlignmentIgnoresSolarEventTypes() {
        LocalDateTime hw = sunset(D11).plusMinutes(30);
        rows(cand(243, "Roker", hw, 4.0));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.alignedEvent()).isEqualTo(AlignedEvent.SUNSET);
        assertThat(day.gapMinutes()).isEqualTo(30L);
        assertThat(day.gapSide()).isEqualTo(GapSide.AFTER);
    }

    @Test
    @DisplayName("overlapping windows form one group: union window, both structures, max wave's direction and period")
    void overlappingWindowsMakeOneGroup() {
        BigSeasCandidate a = new BigSeasCandidate(1L, "A", CoastalStructure.PIER, "A Pier", 90, LAT, LON,
                midday(0), midday(-90), midday(90), 3.5, 9.0, 100);
        BigSeasCandidate b = new BigSeasCandidate(2L, "B", CoastalStructure.BREAKWATER, "B Wall", 90, LAT, LON,
                midday(60), midday(-30), midday(150), 5.0, 12.0, 70);
        rows(a, b);

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.windowStart()).isEqualTo(midday(-90));
        assertThat(day.windowEnd()).isEqualTo(midday(150));
        assertThat(day.structures()).extracting(BigSeasStructure::locationId).containsExactly(2L, 1L);
        assertThat(day.waveHeightM()).isEqualTo(5.0);
        assertThat(day.waveDirectionDeg()).isEqualTo(70);
        assertThat(day.wavePeriodS()).isEqualTo(12.0);
        assertThat(day.alignedEvent()).isNull();
        assertThat(day.gapMinutes()).isNull();
        assertThat(day.gapSide()).isNull();
        assertThat(day.hwTime()).as("unaligned anchor is the max-wave member").isEqualTo(midday(60));
        assertThat(day.otherWindows()).isZero();
    }

    @Test
    @DisplayName("chained windows (A meets B, B meets C, A misses C) are one group of three")
    void chainedWindowsAreOneGroup() {
        rows(cand(1, "A", midday(0), midday(-60), midday(60), 3.2),
                cand(2, "B", midday(100), midday(40), midday(140), 3.3),
                cand(3, "C", midday(200), midday(120), midday(260), 3.4));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.structures()).hasSize(3);
        assertThat(day.windowStart()).isEqualTo(midday(-60));
        assertThat(day.windowEnd()).isEqualTo(midday(260));
        assertThat(day.otherWindows()).isZero();
    }

    @Test
    @DisplayName("a window that starts after the running maximum end begins a new group; touching ends still join")
    void disjointWindowsMakeSeparateGroups() {
        rows(cand(1, "A", midday(0), midday(-60), midday(60), 3.2),
                cand(2, "B", midday(120), midday(60), midday(180), 3.3),
                cand(3, "C", midday(400), midday(341), midday(460), 3.4));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.otherWindows()).as("A+B touch at 60 and join; C is separate").isEqualTo(1);
        assertThat(day.structures()).extracting(BigSeasStructure::locationId).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("lead order: an aligned group beats a larger, higher group")
    void alignedGroupLeadsFirst() {
        LocalDateTime hw = sunrise(D11).plusMinutes(10);
        rows(cand(1, "Aligned", hw, 3.1),
                cand(2, "Big1", midday(150), 6.0), cand(3, "Big2", midday(160), 6.5));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.structures()).extracting(BigSeasStructure::locationId).containsExactly(1L);
        assertThat(day.alignedEvent()).isEqualTo(AlignedEvent.SUNRISE);
        assertThat(day.otherWindows()).isEqualTo(1);
    }

    @Test
    @DisplayName("lead order among unaligned groups: most structures, then highest wave, then earliest")
    void unalignedLeadOrder() {
        // Two structures at 3.5 m beat one at 6 m.
        // All night groups here (after sunset): the daylight key sits above these three, see daylight tests.
        rows(cand(1, "Solo", night(0), 6.0),
                cand(2, "PairA", night(200), 3.5), cand(3, "PairB", night(210), 3.5));
        assertThat(builder.build(fiveDays()).getFirst().structures())
                .extracting(BigSeasStructure::locationId).containsExactlyInAnyOrder(2L, 3L);

        // Equal counts: the higher wave leads.
        rows(cand(1, "Low", night(0), 4.0), cand(2, "High", night(200), 5.0));
        assertThat(builder.build(fiveDays()).getFirst().structures())
                .extracting(BigSeasStructure::locationId).containsExactly(2L);

        // Equal counts and waves: the earlier window leads.
        rows(cand(1, "Late", night(200), 4.0), cand(2, "Early", night(0), 4.0));
        BigSeasDay day = builder.build(fiveDays()).getFirst();
        assertThat(day.structures()).extracting(BigSeasStructure::locationId).containsExactly(2L);
        assertThat(day.otherWindows()).isEqualTo(1);
    }

    @Test
    @DisplayName("a structure that qualifies only in a non-lead group is not listed, and otherWindows counts the group")
    void nonLeadOnlyStructureIsNotListed() {
        rows(cand(1, "LeadA", midday(0), 5.0), cand(2, "LeadB", midday(20), 4.0),
                cand(3, "OtherOnly", midday(400), 6.0), cand(4, "OtherToo", midday(-300), 6.0));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.structures()).extracting(BigSeasStructure::locationId).containsExactly(1L, 2L);
        assertThat(day.otherWindows()).isEqualTo(2);
    }

    @Test
    @DisplayName("anchor: the aligned member with the smallest gap supplies hwTime, event and gap, "
            + "though a bigger wave exists")
    void anchorIsTheSmallestAlignedGap() {
        LocalDateTime sr = sunrise(D11);
        rows(cand(1, "Far", sr.plusMinutes(40), 6.0), cand(2, "Near", sr.minusMinutes(10), 3.5),
                cand(3, "Unaligned", sr.plusMinutes(150), 7.0));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.hwTime()).isEqualTo(sr.minusMinutes(10));
        assertThat(day.alignedEvent()).isEqualTo(AlignedEvent.SUNRISE);
        assertThat(day.gapMinutes()).isEqualTo(10L);
        assertThat(day.gapSide()).isEqualTo(GapSide.BEFORE);
        assertThat(day.waveHeightM()).as("the wave is the group maximum, not the anchor's").isEqualTo(7.0);
        assertThat(day.structures()).filteredOn(BigSeasStructure::aligned)
                .extracting(BigSeasStructure::locationId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(day.structures()).filteredOn(s -> !s.aligned())
                .extracting(BigSeasStructure::locationId).containsExactly(3L);
    }

    @Test
    @DisplayName("a past window is dropped before grouping, so a finished morning window does not lead today")
    void pastWindowIsDropped() {
        LocalDateTime nowUtc = LocalDateTime.of(2026, 12, 10, 12, 0);
        BigSeasCandidate over = cand(1, "Over", nowUtc.minusHours(2), nowUtc.minusHours(3),
                nowUtc.minusSeconds(1), 6.0);
        BigSeasCandidate ongoing = cand(2, "Ongoing", nowUtc.plusMinutes(30), nowUtc.minusMinutes(60),
                nowUtc, 4.0);
        rows(over, ongoing);

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.date()).isEqualTo(D10);
        assertThat(day.structures()).extracting(BigSeasStructure::locationId).containsExactly(2L);
        assertThat(day.waveHeightM()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("only past windows leaves no block at all")
    void onlyPastWindowsGivesNull() {
        LocalDateTime nowUtc = LocalDateTime.of(2026, 12, 10, 12, 0);
        rows(cand(1, "Over", nowUtc.minusHours(2), nowUtc.minusHours(3), nowUtc.minusSeconds(1), 6.0));

        assertThat(builder.build(fiveDays())).isNull();
    }

    @Test
    @DisplayName("the query is bound by the sample age property: a different age asks for a different cut-off")
    void sampleAgeBoundIsPassedToTheQuery() {
        properties.setMaxSampleAgeHours(12);
        when(repository.findBigSeasCandidates(NOW.minus(12, ChronoUnit.HOURS),
                ForecastHorizon.ukDayStartUtc(D10), ForecastHorizon.ukDayStartUtc(LocalDate.of(2026, 12, 15))))
                .thenReturn(List.of(cand(1, "A", midday(0), 4.0)));

        assertThat(builder.build(fiveDays())).hasSize(1);
    }

    @Test
    @DisplayName("rows that fail the rule are not counted: a small sea, a short period, an off-shore wind sea, nulls")
    void nonQualifyingRowsAreIgnored() {
        BigSeasCandidate small = cand(1, "Small", midday(0), 2.9);
        BigSeasCandidate short1 = new BigSeasCandidate(2L, "Short", CoastalStructure.PIER, "S", 90, LAT, LON,
                midday(0), midday(-90), midday(90), 5.0, 7.9, 90);
        BigSeasCandidate offshore = new BigSeasCandidate(3L, "Off", CoastalStructure.PIER, "O", 90, LAT, LON,
                midday(0), midday(-90), midday(90), 5.0, 10.0, 270);
        BigSeasCandidate noWave = new BigSeasCandidate(4L, "Null", CoastalStructure.PIER, "N", 90, LAT, LON,
                midday(0), midday(-90), midday(90), null, null, null);
        rows(small, short1, offshore, noWave);

        assertThat(builder.build(fiveDays())).isNull();
    }

    @Test
    @DisplayName("days outside the served list or beyond today+4 are dropped; surviving days are in date order")
    void daysAreIntersectedWithTheServedHorizon() {
        LocalDateTime d12 = LocalDateTime.of(2026, 12, 12, 11, 0);
        LocalDateTime d11 = LocalDateTime.of(2026, 12, 11, 11, 0);
        LocalDateTime d13 = LocalDateTime.of(2026, 12, 13, 11, 0);
        when(repository.findBigSeasCandidates(NOW.minus(30, ChronoUnit.HOURS),
                ForecastHorizon.ukDayStartUtc(D11), ForecastHorizon.ukDayStartUtc(LocalDate.of(2026, 12, 13))))
                .thenReturn(List.of(cand(1, "A", d12, 4.0), cand(2, "B", d11, 4.0),
                        cand(3, "NotServed", d13, 4.0)));

        List<BigSeasDay> result = builder.build(days(D12, D11, D15));

        assertThat(result).extracting(BigSeasDay::date).containsExactly(D11, D12);
    }

    @Test
    @DisplayName("a label-less structure falls back to the location name")
    void labelFallsBackToName() {
        rows(new BigSeasCandidate(5L, "Hartlepool Headland", CoastalStructure.BREAKWATER, null, 90, LAT, LON,
                midday(0), midday(-90), midday(90), 4.0, 10.0, 90));

        assertThat(builder.build(fiveDays()).getFirst().structures().getFirst().label())
                .isEqualTo("Hartlepool Headland");

        rows(new BigSeasCandidate(5L, "Hartlepool Headland", CoastalStructure.BREAKWATER, "  ", 90, LAT, LON,
                midday(0), midday(-90), midday(90), 4.0, 10.0, 90));

        assertThat(builder.build(fiveDays()).getFirst().structures().getFirst().label())
                .as("a blank label is no label")
                .isEqualTo("Hartlepool Headland");
    }

    @Test
    @DisplayName("no days, no served day in the horizon, or no candidates all serve no block")
    void nothingToServe() {
        assertThat(builder.build(null)).isNull();
        assertThat(builder.build(List.of())).isNull();
        assertThat(builder.build(days(LocalDate.of(2026, 12, 20)))).isNull();
        assertThat(builder.build(days(LocalDate.of(2026, 12, 9)))).isNull();
        rows();
        assertThat(builder.build(fiveDays())).isNull();
    }

    @Test
    @DisplayName("fail-soft: a repository failure returns null instead of throwing")
    void failSoft() {
        when(repository.findBigSeasCandidates(NOW.minus(30, ChronoUnit.HOURS),
                ForecastHorizon.ukDayStartUtc(D10), ForecastHorizon.ukDayStartUtc(LocalDate.of(2026, 12, 15))))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(builder.build(fiveDays())).isNull();
    }

    @Test
    @DisplayName("nested windows (A -60..+200 holds B 0..+60 and C +100..+160) are one group of three")
    void nestedWindowsAreOneGroup() {
        rows(cand(1, "A", midday(70), midday(-60), midday(200), 3.2),
                cand(2, "B", midday(30), midday(0), midday(60), 3.3),
                cand(3, "C", midday(130), midday(100), midday(160), 3.4));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.structures()).hasSize(3);
        assertThat(day.otherWindows()).isZero();
        assertThat(day.windowEnd()).isEqualTo(midday(200));
    }

    @Test
    @DisplayName("alignment is inclusive at both edges: a window starting at sunrise and one ending at sunset")
    void alignmentEdgesAreInclusive() {
        LocalDateTime sr = sunrise(D11);
        LocalDateTime ss = sunset(D12);
        rows(cand(1, "Dawn", sr.plusMinutes(90), sr, sr.plusMinutes(180), 4.0),
                cand(2, "Dusk", ss.minusMinutes(90), ss.minusMinutes(180), ss, 4.0));

        List<BigSeasDay> result = builder.build(fiveDays());

        assertThat(result.get(0).alignedEvent()).isEqualTo(AlignedEvent.SUNRISE);
        assertThat(result.get(1).alignedEvent()).isEqualTo(AlignedEvent.SUNSET);
    }

    @Test
    @DisplayName("lead order uses the group's MAXIMUM wave: (3.1, 6.0) later in the day beats (4.0, 4.5)")
    void leadOrderUsesGroupMaxWave() {
        // The last key (earliest start) is effectively redundant: groups are disjoint, so no two share a start.
        rows(cand(1, "LowA", midday(-60), 4.0), cand(2, "LowB", midday(-50), 4.5),
                cand(3, "HighA", midday(150), 3.1), cand(4, "HighB", midday(160), 6.0));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.structures()).extracting(BigSeasStructure::locationId).containsExactly(4L, 3L);
        assertThat(day.otherWindows()).isEqualTo(1);
    }

    @Test
    @DisplayName("two aligned members at equal gap (sunrise +-10 min): the bigger wave anchors")
    void equalAlignedGapBiggerWaveAnchors() {
        LocalDateTime sr = sunrise(D11);
        rows(cand(1, "Before", sr.minusMinutes(10), 3.5), cand(2, "After", sr.plusMinutes(10), 5.0));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.hwTime()).isEqualTo(sr.plusMinutes(10));
        assertThat(day.gapSide()).isEqualTo(GapSide.AFTER);
    }

    @Test
    @DisplayName("equal waves: the earlier high water is the max-wave member and is listed first")
    void equalWavesEarlierHighWaterWins() {
        BigSeasCandidate late = new BigSeasCandidate(1L, "Late", CoastalStructure.PIER, "Late", 90, LAT, LON,
                midday(30), midday(-60), midday(120), 4.0, 10.0, 80);
        BigSeasCandidate early = new BigSeasCandidate(9L, "Early", CoastalStructure.PIER, "Early", 90, LAT, LON,
                midday(0), midday(-90), midday(90), 4.0, 11.0, 100);
        rows(late, early);

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.structures()).extracting(BigSeasStructure::locationId).containsExactly(9L, 1L);
        assertThat(day.waveDirectionDeg()).isEqualTo(100);
        assertThat(day.wavePeriodS()).isEqualTo(11.0);
        assertThat(day.hwTime()).isEqualTo(midday(0));
    }

    @Test
    @DisplayName("a gap of exactly zero is served as 0 minutes, AFTER")
    void zeroGapIsAfter() {
        LocalDateTime sr = sunrise(D11);
        rows(cand(1, "On", sr, 4.0));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.gapMinutes()).isZero();
        assertThat(day.gapSide()).isEqualTo(GapSide.AFTER);
    }

    private void stubOct(Clock c, BigSeasCandidate... candidates) {
        when(repository.findBigSeasCandidates(c.instant().minus(30, ChronoUnit.HOURS),
                ForecastHorizon.ukDayStartUtc(LocalDate.of(2026, 10, 24)),
                ForecastHorizon.ukDayStartUtc(LocalDate.of(2026, 10, 29)))).thenReturn(List.of(candidates));
    }

    private static List<BriefingDay> octDays() {
        return days(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26),
                LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 28));
    }

    @Test
    @DisplayName("UK date across the clock change: 23:30Z on 24 Oct (BST) is 25 Oct, and 23:30Z on 25 Oct (GMT) "
            + "is still 25 Oct")
    void ukDateAcrossTheClockChange() {
        Clock bst = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC);
        LocalDateTime saturdayNight = LocalDateTime.of(2026, 10, 24, 23, 30);
        LocalDateTime sundayNight = LocalDateTime.of(2026, 10, 25, 23, 30);
        stubOct(bst, cand(1, "Sat", saturdayNight, 4.0), cand(2, "Sun", sundayNight, 5.0));

        List<BigSeasDay> result = new BigSeasBuilder(repository, solar, properties, bst).build(octDays());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(result.getFirst().otherWindows()).isEqualTo(1);
    }

    @Test
    @DisplayName("'now' is compared in UTC even when the clock's zone is Europe/London in BST")
    void nowIsComparedInUtc() {
        Clock london = Clock.fixed(Instant.parse("2026-10-24T22:30:00Z"), ZoneId.of("Europe/London"));
        // Still running at 22:30Z, but already over by the London wall clock (23:30).
        stubOct(london, cand(1, "Ongoing", LocalDateTime.of(2026, 10, 24, 22, 0),
                LocalDateTime.of(2026, 10, 24, 21, 0), LocalDateTime.of(2026, 10, 24, 22, 45), 4.0));

        List<BigSeasDay> result = new BigSeasBuilder(repository, solar, properties, london).build(octDays());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 24));
    }

    @Test
    @DisplayName("an unaligned midday group is a daylight high water, with the anchor's own sunrise and sunset")
    void unalignedMiddayGroupIsDaylightHighWater() {
        rows(cand(1, "Roker", midday(0), 4.0));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.alignedEvent()).isNull();
        assertThat(day.daylightHighWater()).isTrue();
        assertThat(day.daylightStart()).isEqualTo(sunrise(D11));
        assertThat(day.daylightEnd()).isEqualTo(sunset(D11));
    }

    @Test
    @DisplayName("an aligned day is not a daylight high water and serves no daylight bounds")
    void alignedDayIsNotDaylightHighWater() {
        rows(cand(1, "Roker", sunrise(D11).plusMinutes(20), 4.0));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.alignedEvent()).isEqualTo(AlignedEvent.SUNRISE);
        assertThat(day.daylightHighWater()).isFalse();
        assertThat(day.daylightStart()).isNull();
        assertThat(day.daylightEnd()).isNull();
    }

    @Test
    @DisplayName("a night group is not a daylight high water and serves no daylight bounds")
    void nightGroupIsNotDaylightHighWater() {
        rows(cand(1, "Roker", night(0), 4.0));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.alignedEvent()).isNull();
        assertThat(day.daylightHighWater()).isFalse();
        assertThat(day.daylightStart()).isNull();
        assertThat(day.daylightEnd()).isNull();
    }

    @Test
    @DisplayName("lead order: a daylight group now beats a night group with MORE structures and a bigger wave")
    void daylightGroupLeadsAheadOfLargerNightGroup() {
        rows(cand(1, "NightA", night(0), 6.0), cand(2, "NightB", night(10), 6.5),
                cand(3, "Day", midday(0), 3.2));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.structures()).extracting(BigSeasStructure::locationId).containsExactly(3L);
        assertThat(day.daylightHighWater()).isTrue();
        assertThat(day.waveHeightM()).isEqualTo(3.2);
        assertThat(day.otherWindows()).isEqualTo(1);
    }

    @Test
    @DisplayName("lead order: an aligned group still beats a daylight group")
    void alignedGroupStillBeatsDaylightGroup() {
        rows(cand(1, "Aligned", sunset(D11).minusMinutes(20), 3.1), cand(2, "Day", midday(0), 6.0));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.structures()).extracting(BigSeasStructure::locationId).containsExactly(1L);
        assertThat(day.daylightHighWater()).isFalse();
    }

    @Test
    @DisplayName("straddle: a member window starting before a neighbour's sunrise is not between the lights")
    void straddlingMemberWindowIsNotDaylight() {
        LocalDateTime eastSunrise = solar.sunriseUtc(LAT, LON, D11);
        LocalDateTime westSunrise = solar.sunriseUtc(LAT, -6.0, D11);
        assertThat(westSunrise).as("sanity: the western structure's sun rises later")
                .isAfter(eastSunrise.plusMinutes(10));
        // East's window opens after its own sunrise but before West's; West is the bigger wave and anchors.
        rows(candAt(1, "East", LON, eastSunrise.plusMinutes(100), eastSunrise.plusMinutes(10),
                        eastSunrise.plusMinutes(190), 4.0),
                candAt(2, "West", -6.0, westSunrise.plusMinutes(150), westSunrise.plusMinutes(30),
                        westSunrise.plusMinutes(270), 5.0));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.structures()).hasSize(2);
        assertThat(day.alignedEvent()).isNull();
        assertThat(day.daylightHighWater()).isFalse();
        assertThat(day.daylightStart()).isNull();
        assertThat(day.daylightEnd()).isNull();
    }

    @Test
    @DisplayName("the same pair with East's window opening after West's sunrise is daylight, bounds from the anchor")
    void pairInsideBothSunrisesIsDaylightWithTheAnchorsBounds() {
        LocalDateTime eastSunrise = solar.sunriseUtc(LAT, LON, D11);
        LocalDateTime westSunrise = solar.sunriseUtc(LAT, -6.0, D11);
        rows(candAt(1, "East", LON, westSunrise.plusMinutes(100), westSunrise.plusMinutes(10),
                        westSunrise.plusMinutes(190), 4.0),
                candAt(2, "West", -6.0, westSunrise.plusMinutes(150), westSunrise.plusMinutes(30),
                        westSunrise.plusMinutes(270), 5.0));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.daylightHighWater()).isTrue();
        assertThat(day.daylightStart()).as("the anchor (West, max wave) supplies the bounds")
                .isEqualTo(westSunrise).isNotEqualTo(eastSunrise);
        assertThat(day.daylightEnd()).isEqualTo(solar.sunsetUtc(LAT, -6.0, D11));
    }

    @Test
    @DisplayName("straddle at the sunset side: a member window ending after a neighbour's sunset is not daylight")
    void straddlingAtSunsetIsNotDaylight() {
        LocalDateTime eastSunset = solar.sunsetUtc(LAT, LON, D11);
        LocalDateTime westSunset = solar.sunsetUtc(LAT, -6.0, D11);
        assertThat(westSunset).isAfter(eastSunset.plusMinutes(10));
        // East's window closes before its own sunset but West's runs past East's sunset: union end is fine for
        // West's sun, not for East's - the earliest sunset governs.
        rows(candAt(1, "East", LON, eastSunset.minusMinutes(100), eastSunset.minusMinutes(190),
                        eastSunset.minusMinutes(10), 4.0),
                candAt(2, "West", -6.0, eastSunset.minusMinutes(20), eastSunset.minusMinutes(60),
                        eastSunset.plusMinutes(5), 5.0));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.structures()).hasSize(2);
        assertThat(day.alignedEvent()).as("West's window ends before West's own later sunset").isNull();
        assertThat(day.daylightHighWater()).isFalse();
    }

    @Test
    @DisplayName("a high water one minute inside each light is a daylight high water")
    void highWaterOneMinuteInsideEachLight() {
        LocalDateTime sr = sunrise(D11);
        rows(candAt(1, "Dawn", LON, sr.plusMinutes(1), sr.plusSeconds(1), sr.plusMinutes(120), 4.0));
        BigSeasDay dawn = builder.build(fiveDays()).getFirst();
        assertThat(dawn.alignedEvent()).isNull();
        assertThat(dawn.daylightHighWater()).isTrue();
        assertThat(dawn.daylightStart()).isEqualTo(sr);

        LocalDateTime ss = sunset(D11);
        rows(candAt(1, "Dusk", LON, ss.minusMinutes(1), ss.minusMinutes(120), ss.minusSeconds(1), 4.0));
        BigSeasDay dusk = builder.build(fiveDays()).getFirst();
        assertThat(dusk.alignedEvent()).isNull();
        assertThat(dusk.daylightHighWater()).isTrue();
        assertThat(dusk.daylightEnd()).isEqualTo(ss);
    }

    @Test
    @DisplayName("a window touching a light is aligned (inclusive), so it is never also a daylight high water")
    void windowTouchingALightIsAlignedNotDaylight() {
        LocalDateTime sr = sunrise(D11);
        rows(candAt(1, "Dawn", LON, sr.plusMinutes(1), sr, sr.plusMinutes(120), 4.0));

        BigSeasDay day = builder.build(fiveDays()).getFirst();

        assertThat(day.alignedEvent()).isEqualTo(AlignedEvent.SUNRISE);
        assertThat(day.daylightHighWater()).isFalse();
        assertThat(day.daylightStart()).isNull();
    }

    @Test
    @DisplayName("Sat 24 / Sun 25 Oct 2026 across the clock change: both midday high waters are daylight, "
            + "with UTC bounds (no BST shift)")
    void clockChangePairKeepsUtcDaylightBounds() {
        Clock bst = Clock.fixed(Instant.parse("2026-10-24T09:00:00Z"), ZoneOffset.UTC);
        LocalDate sat = LocalDate.of(2026, 10, 24);
        LocalDate sun = LocalDate.of(2026, 10, 25);
        LocalDateTime satHw = LocalDateTime.of(2026, 10, 24, 12, 0);
        LocalDateTime sunHw = LocalDateTime.of(2026, 10, 25, 12, 0);
        when(repository.findBigSeasCandidates(bst.instant().minus(30, ChronoUnit.HOURS),
                ForecastHorizon.ukDayStartUtc(sat), ForecastHorizon.ukDayStartUtc(LocalDate.of(2026, 10, 29))))
                .thenReturn(List.of(cand(1, "Sat", satHw, 4.0), cand(2, "Sun", sunHw, 4.0)));

        List<BigSeasDay> result = new BigSeasBuilder(repository, solar, properties, bst).build(octDays());

        assertThat(result).extracting(BigSeasDay::date).containsExactly(sat, sun);
        assertThat(result).allSatisfy(d -> assertThat(d.daylightHighWater()).isTrue());
        assertThat(result.get(0).daylightStart()).isEqualTo(solar.sunriseUtc(LAT, LON, sat));
        assertThat(result.get(0).daylightEnd()).isEqualTo(solar.sunsetUtc(LAT, LON, sat));
        assertThat(result.get(1).daylightStart()).isEqualTo(solar.sunriseUtc(LAT, LON, sun));
        assertThat(result.get(1).daylightEnd()).isEqualTo(solar.sunsetUtc(LAT, LON, sun));
        assertThat(result.get(1).daylightStart()).as("GMT day: sunrise about 06:5x UTC, not BST-shifted")
                .isAfter(LocalDateTime.of(2026, 10, 25, 6, 0)).isBefore(LocalDateTime.of(2026, 10, 25, 8, 0));
    }
}
