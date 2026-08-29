package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.AlmanacSource;
import com.gregochr.goldenhour.service.EclipseAlmanacSource;
import com.gregochr.goldenhour.service.LunarPhaseService;
import com.gregochr.goldenhour.service.MeteorAlmanacSource;
import com.gregochr.goldenhour.service.NlcClarityService;
import com.gregochr.goldenhour.service.NlcSeasonAlmanacSource;
import com.gregochr.goldenhour.service.PlanHorizon;
import com.gregochr.goldenhour.service.SolarAlignmentAlmanacSource;
import com.gregochr.goldenhour.service.SupermoonAlmanacSource;
import com.gregochr.goldenhour.service.TideRunBuilder;
import com.gregochr.goldenhour.service.TideService;
import com.gregochr.solarutils.LunarCalculator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pre-ship badge census plan D4/§11.13 gates P5 on — kept as a regression fixture so a later
 * topic addition, rarity retune or band move re-checks the arrival rate instead of silently
 * changing it.
 *
 * <p><b>What it measures.</b> One synthetic year of daily feed assemblies (1 Sep 2026 – 31 Aug
 * 2027), through the REAL ephemeris sources and the real {@link ComingUpAssembler}, counting
 * badge-firing arrivals exactly the way the client's {@code comingUpArrivals.js} does: an entry
 * "arrives" on its served {@code enteredWindow} date, and it fires the badge iff it is
 * {@code kind == ALMANAC}, not {@code interim}, and its served (rounded) {@code bits} clear the
 * served band edges, lower-inclusive. The design's target (README §3): roughly <b>10 badges a
 * year, of which one or two clear the interrupt contour</b>.
 *
 * <p><b>What the placeholders measured, and what was set from it.</b> Under the pre-census edges
 * (announce 7.5 / interrupt 9.5) the year fired 11 badges of which SEVEN were interrupts — every
 * annual-rate topic (5 showers at 9.5, the NLC season boundary at 9.5) sat exactly on the
 * interrupt contour, ~4× the design's "one or two". With every v1 magnitude pinned at the 1.0
 * default and no deterministic rate rarer than annual except the eclipse catalogue, the score
 * ladder is discrete: supermoon 6.9 · solar turning point 8.5 · shower/NLC 9.5 · eclipse 11.6.
 * The census therefore moves ONE edge: <b>interrupt 9.5 → 10.0</b> — above "annual rarity +
 * typical magnitude" (9.5) and below the eclipse (11.6) — leaving announce at 7.5 (the 6.9→8.5
 * rung gap gives no reason to move within it). Result: 11 badge arrivals/year, 1 interrupt. No
 * topic is special-cased (plan §11.13's instruction); the whole change is which rungs of the
 * ladder fire which band. A mature (non-cold-start) tide-run magnitude, though never exercised by
 * this census (see below), is separately checkable against {@code interrupt}: {@link
 * SurpriseScore#magnitudeFromHistory}'s Laplace-corrected {@code log2(n+1)} scores a record run,
 * so clearing 10.0 needs a {@code n ≥ 69}-observation run-peak history at that port — 9 past the
 * {@code coldStartMinObservations = 60} floor, roughly 4–5 months of accrued runs past first
 * maturity — not the coarser "once-in-200-runs" figure an earlier draft of this note gave.
 *
 * <p><b>What is deliberately out of the numerator, and the one open question that leaves.</b> Tide
 * runs (interim under cold start — D4's "list, don't badge"; {@code TideAlmanacSource} is not
 * wired in here, and a fixture comment below records why that omission cannot change the counts
 * TODAY) and FORECAST-kind entries (never badge, design §6; none exist at first ship anyway, D9).
 * ⚠️ <b>The tide-run omission is valid only while every port is cold-started, and nothing pins
 * that it stays that way.</b> §11.13 also names a design-bundle contradiction this census does
 * NOT resolve: the design says "a spring tide run entering at day 90 is silence" while its own
 * worked example scores one 8.2 = Announced. That contradiction is resolved TODAY only by the
 * separate {@code interim} exclusion (P2's own addition, unrelated to which edge {@code
 * interrupt} sits at) — not by this census, which never scores a real tide run at all. Once any
 * port's stored run-peak history crosses {@code coldStartMinObservations} (D4: ~2.5 years of
 * history, the 12-month backfill yields ~25 — a long way off, not the 90-day horizon of the
 * recurrent-topic constants below), its tide-run entries stop being {@code interim} and start
 * competing for a band on their own real magnitude — a scenario this census has never measured
 * and the band edges above were never checked against. Revisit then: wire
 * {@code TideAlmanacSource} into this census with a matured port's real history and re-run it,
 * the same instinct that already motivates revisiting the recurrent-topic (dust/inversion)
 * constants after P7's {@code topic_daily_log} accrues ~90 days of rows — this is that same kind
 * of check, on a much longer clock.
 */
class ComingUpAnnualBadgeCensusTest {

    /** First build date of the census year. */
    private static final LocalDate CENSUS_START = LocalDate.of(2026, 9, 1);

    /** Last build date of the census year, inclusive. */
    private static final LocalDate CENSUS_END = LocalDate.of(2027, 8, 31);

    /** The feed horizon the census assembles at — the served default, never a shorter request. */
    private static final int DAYS = 90;

    private static ComingUpScoringProperties properties;
    private static List<ComingUpEntry> badgeArrivals;
    private static Map<String, Integer> arrivalsByType;

    /**
     * Assembles the whole census year once — every per-band assertion below reads this one pass,
     * because the counts are one measurement, not several.
     *
     * <p>The sources are the real ephemeris implementations over a real
     * {@link LunarPhaseService}; {@code TideAlmanacSource} is the one source not wired in, and
     * its absence cannot change the numerator: every tide-run entry the assembler can produce
     * without a mature (≥60-observation) run-peak history is served {@code interim} and excluded
     * from badging by the same rule the client applies — and at first ship no port has one
     * (plan §1: the 12-month backfill yields ~25). The eclipse source runs against an empty
     * roster, which degrades to a dates-only entry (its own documented path) with its rarity and
     * kind intact — the two fields the badge reads.
     */
    @BeforeAll
    static void runCensus() {
        properties = new ComingUpScoringProperties();

        LunarPhaseService lunar = new LunarPhaseService(new LunarCalculator());
        LocationRepository locationRepository = mock(LocationRepository.class);
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        when(locationRepository.findCoastalLocations()).thenReturn(List.of());
        NlcClarityService nlcClarity = new NlcClarityService(null, null, null);

        List<AlmanacSource> sources = List.of(
                new MeteorAlmanacSource(lunar),
                new SupermoonAlmanacSource(lunar),
                new SolarAlignmentAlmanacSource(),
                new NlcSeasonAlmanacSource(nlcClarity),
                new EclipseAlmanacSource(locationRepository));

        ComingUpAssembler assembler = new ComingUpAssembler(locationRepository,
                mock(TideRunBuilder.class), mock(TideRunPeakHistory.class),
                mock(TideService.class), properties);

        badgeArrivals = new ArrayList<>();
        arrivalsByType = new LinkedHashMap<>();
        double announce = properties.getBands().getAnnounce();

        for (LocalDate build = CENSUS_START; !build.isAfter(CENSUS_END);
                build = build.plusDays(1)) {
            LocalDate to = build.plusDays(DAYS - 1L);
            List<AlmanacEvent> all = new ArrayList<>();
            for (AlmanacSource source : sources) {
                all.addAll(source.events(build, to));
            }
            // Sorted, matching AlmanacService.build()'s own ".stream().sorted().toList()" —
            // ComingUpAssembler.markFirstOfType marks the FIRST Staged object it encounters as
            // "first of type" (a prose-only concern, not scored), so an unsorted list here would
            // let source-registration order rather than calendar order decide which occurrence
            // gets the explanatory prose — never load-bearing for the census's own bits/interim/
            // kind assertions below, but worth matching exactly rather than leaving as a fidelity
            // gap against "the real ComingUpAssembler, assembled the way production assembles it".
            all = all.stream().sorted().toList();
            // The same eligibility filter AlmanacService.assemble applies (plan D1).
            LocalDate cutoff = PlanHorizon.lastPlanDate(build);
            List<AlmanacEvent> eligible = all.stream()
                    .filter(event -> event.endDate().isAfter(cutoff))
                    .toList();
            ComingUpResponse response = assembler.assemble(build, eligible);

            for (ComingUpEntry entry : response.entries()) {
                // An entry "arrives" on the one build whose date equals its served enteredWindow —
                // the same day the client's isNew comparison first turns true for it (D3).
                if (!build.equals(entry.enteredWindow())) {
                    continue;
                }
                arrivalsByType.merge(entry.type(), 1, Integer::sum);
                if (entry.kind() == AlmanacKind.ALMANAC && !entry.interim()
                        && entry.bits() != null && entry.bits() >= announce) {
                    badgeArrivals.add(entry);
                }
            }
        }
    }

    @Test
    @DisplayName("the year's badge-firing arrivals sit at the design's ~10/year target")
    void badgeRateIsNearTheDesignTarget() {
        // 5 named showers + 4 solar turning points + the NLC season boundary + one catalogued
        // eclipse (2 Aug 2027, arriving 5 May 2027). 11 against the design's "roughly 10" is the
        // honest nearest reachable on this inventory: the next rung down (announce past 8.5) drops
        // to 7/year, and the next rung up (admitting supermoons at 6.9) rises to ~14.
        assertThat(badgeArrivals).hasSize(11);
    }

    @Test
    @DisplayName("one or two arrivals a year clear the interrupt contour — not every annual topic")
    void interruptRateIsOneOrTwo() {
        double interrupt = properties.getBands().getInterrupt();
        List<ComingUpEntry> interrupts = badgeArrivals.stream()
                .filter(entry -> entry.bits() >= interrupt)
                .toList();
        // The eclipse alone. Under the placeholder edge (9.5) this list held SEVEN entries —
        // every shower and the NLC boundary — which is the over-fire §11.13 predicted and the
        // reason the shipped edge is 10.0.
        assertThat(interrupts).extracting(ComingUpEntry::type).containsExactly("eclipse");
    }

    @Test
    @DisplayName("the badge arrivals break down by type exactly as censused — a change here is a "
            + "re-census, not a tweak")
    void badgeArrivalsByType() {
        Map<String, Long> byType = badgeArrivals.stream()
                .collect(java.util.stream.Collectors.groupingBy(ComingUpEntry::type,
                        java.util.stream.Collectors.counting()));
        assertThat(byType).containsOnly(
                Map.entry("meteor", 5L),
                Map.entry("equinox", 2L),
                Map.entry("solstice", 2L),
                Map.entry("nlc-season", 1L),
                Map.entry("eclipse", 1L));
    }

    @Test
    @DisplayName("supermoons arrive but sit below the announce edge — they list, never badge")
    void supermoonsArriveWithoutBadging() {
        // The ladder's bottom rung doing its job: rarity log2(60) + the 1.0 default ≈ 6.9 < 7.5.
        // If the supermoon rarity gap is ever re-derived from the ephemeris (~3–4/year ≈ 7.5–7.9
        // bits), this test is the tripwire that forces the announce edge to be re-censused with it.
        assertThat(arrivalsByType.getOrDefault("supermoon", 0)).isGreaterThanOrEqualTo(2);
        assertThat(badgeArrivals).extracting(ComingUpEntry::type).doesNotContain("supermoon");
    }
}
