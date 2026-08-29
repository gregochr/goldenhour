package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.TideRunBuilder;
import com.gregochr.goldenhour.service.TideService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ComingUpAssembler}. */
@ExtendWith(MockitoExtension.class)
class ComingUpAssemblerTest {

    private static final LocalDate DAY = LocalDate.of(2027, 9, 10);
    private static final LocationEntity SEAHAM =
            LocationEntity.builder().id(1L).name("Seaham").lat(54.84).lon(-1.33).build();
    private static final List<LocationEntity> COASTAL_ROSTER = List.of(SEAHAM);

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private TideRunBuilder tideRunBuilder;

    @Mock
    private TideRunPeakHistory tideRunPeakHistory;

    @Mock
    private TideService tideService;

    private ComingUpAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ComingUpAssembler(locationRepository, tideRunBuilder, tideRunPeakHistory,
                tideService, new ComingUpScoringProperties());
        // Empty by default; individual tide-run tests override.
        when(locationRepository.findCoastalLocations()).thenReturn(COASTAL_ROSTER);
    }

    private static AlmanacEvent event(LocalDate start, LocalDate end, String type, String title,
            Map<String, String> meta) {
        return new AlmanacEvent(start, end, AlmanacKind.ALMANAC, type, title, "detail", meta, List.of());
    }

    // ── every ALMANAC entry gets non-null bits (plan D4) ────────────────────

    @Test
    @DisplayName("a meteor entry scores rarity (annual) + the default median magnitude")
    void meteorEntryScoresRarityPlusDefaultMagnitude() {
        AlmanacEvent shower = event(DAY, DAY, "meteor", "Perseids",
                Map.of("zhr", "20", "radiant", "Perseus", "bestHours", "after midnight"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(shower));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.bits()).isNotNull();
        assertThat(entry.bits()).isCloseTo(9.51, org.assertj.core.data.Offset.offset(0.05));
        assertThat(entry.family()).isEqualTo("night-sky");
        assertThat(entry.metric()).isEqualTo("~20/hr");
        assertThat(entry.kindTag()).isEqualTo("Almanac");
        assertThat(entry.action().kind()).isEqualTo("dark-sky-spots");
        // Non-tide types default the median magnitude, which is "by definition typical" (D4),
        // never provisional — interim must default false, not true, or the badge can never fire.
        assertThat(entry.interim()).isFalse();
        assertThat(entry.meta()).doesNotContainKeys("zhr", "radiant", "bestHours");
    }

    @Test
    @DisplayName("equinox/solstice score rarity (twice-yearly) + default magnitude")
    void solarTurningPointScoresTwiceYearlyRarity() {
        AlmanacEvent equinox = event(DAY, DAY, "equinox", "Autumn equinox",
                Map.of("peakDate", DAY.toString()));

        ComingUpResponse response = assembler.assemble(DAY, List.of(equinox));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.bits()).isCloseTo(8.51, org.assertj.core.data.Offset.offset(0.05));
        assertThat(entry.family()).isEqualTo("sun-moon");
        assertThat(entry.action().kind()).isEqualTo("plan");
    }

    @Test
    @DisplayName("an eclipse entry scores the rarest of the six types and lands in family 'eclipse'")
    void eclipseEntryIsRareAndItsOwnFamily() {
        AlmanacEvent eclipse = event(DAY, DAY, "eclipse", "Partial solar eclipse",
                Map.of("coverage", "62% covered", "maximum", "10:12 · sun 30° up SE",
                        "location", "Keswick"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(eclipse));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.family()).isEqualTo("eclipse");
        assertThat(entry.metric()).isEqualTo("62%");
        assertThat(entry.bits()).isGreaterThan(10.0);
        assertThat(entry.interim()).isFalse();
        assertThat(entry.meta()).doesNotContainKeys("coverage", "maximum", "location");
    }

    @Test
    @DisplayName("an unrecognised type degrades to a specific, plausible score rather than throwing")
    void unrecognisedTypeDoesNotThrow() {
        AlmanacEvent mystery = event(DAY, DAY, "mystery-topic", "Something new", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(mystery));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.family()).isEqualTo("night-sky");
        assertThat(entry.bits()).isCloseTo(
                SurpriseScore.rarity(182.625) + SurpriseScore.DEFAULT_MAGNITUDE_BITS,
                org.assertj.core.data.Offset.offset(0.06));
        // The 1.0 default magnitude is "by definition typical" (plan D4), not provisional — only
        // the tide branches that never reach a mature distribution are interim.
        assertThat(entry.interim()).isFalse();
    }

    // ── source isolation: a coastal-roster failure must not blank the feed ──

    @Test
    @DisplayName("a coastal-roster fetch failure degrades tide-run scoring rather than 500ing the "
            + "whole feed — the same per-source isolation AlmanacService.build() already gives "
            + "every AlmanacSource, extended to this read which sits outside that protection")
    void coastalRosterFailure_degradesRatherThanThrows() {
        reset(locationRepository);
        when(locationRepository.findCoastalLocations()).thenThrow(new RuntimeException("db down"));

        AlmanacEvent meteor = event(DAY, DAY, "meteor", "Perseids", Map.of());
        AlmanacEvent tideRun = event(DAY.plusDays(5), DAY.plusDays(8), "spring-tide",
                "Spring tide run", AlmanacEvent.metaOf("range", "4.6 m"));

        ComingUpResponse response;
        try {
            response = assembler.assemble(DAY, List.of(meteor, tideRun));
        } catch (RuntimeException e) {
            throw new AssertionError("a coastal-roster failure must not propagate out of assemble()", e);
        }

        // The ephemeris entry that never touches a coastal location is unaffected.
        assertThat(response.entries()).hasSize(2);
        ComingUpEntry meteorEntry = response.entries().stream()
                .filter(e -> e.type().equals("meteor")).findFirst().orElseThrow();
        assertThat(meteorEntry.bits()).isNotNull();

        // The tide run degrades exactly like "no derivable peak" — interim, default magnitude,
        // no tide field — rather than the request failing.
        ComingUpEntry tideEntry = response.entries().stream()
                .filter(e -> e.type().equals("spring-tide")).findFirst().orElseThrow();
        assertThat(tideEntry.interim()).isTrue();
        assertThat(tideEntry.tide()).isNull();
        assertThat(tideEntry.bits()).isNotNull();
    }

    @Test
    @DisplayName("assemble() never throws for a coastal-roster failure, whatever entries are in "
            + "the window")
    void coastalRosterFailure_neverThrows() {
        reset(locationRepository);
        when(locationRepository.findCoastalLocations()).thenThrow(new RuntimeException("db down"));
        AlmanacEvent tideRun = event(DAY, DAY.plusDays(3), "king-tide", "King tide run", Map.of());

        assertThatCode(() -> assembler.assemble(DAY, List.of(tideRun))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an NLC season entry clipped at both ends of the window carries both flags as facts")
    void nlcSeasonClippedAtBothEndsGetsBothFacts() {
        AlmanacEvent nlc = event(DAY, DAY.plusDays(60), "nlc-season", "Noctilucent cloud season",
                Map.of("startsBeforeWindow", "true", "endsAfterWindow", "true"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(nlc));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.family()).isEqualTo("night-sky");
        assertThat(entry.action().kind()).isEqualTo("dark-sky-spots");
        assertThat(entry.bits()).isCloseTo(
                SurpriseScore.rarity(365.25) + SurpriseScore.DEFAULT_MAGNITUDE_BITS,
                org.assertj.core.data.Offset.offset(0.06));
        List<String> factText = entry.facts().stream()
                .flatMap(f -> f.segments().stream())
                .map(com.gregochr.goldenhour.model.comingup.ComingUpFact.Segment::text)
                .toList();
        assertThat(factText).contains("already running", "continues past this window");
        assertThat(entry.meta()).doesNotContainKeys("startsBeforeWindow", "endsAfterWindow");
    }

    @Test
    @DisplayName("a supermoon with no coinciding tide run is its own entry, with its own facts and "
            + "a dark-sky-spots action")
    void standaloneSupermoonGetsItsOwnFactsAndAction() {
        AlmanacEvent supermoon = event(DAY, DAY.plusDays(2), "supermoon", "Supermoon",
                Map.of("peakDate", DAY.plusDays(1).toString()));

        ComingUpResponse response = assembler.assemble(DAY, List.of(supermoon));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.type()).isEqualTo("supermoon");
        assertThat(entry.family()).isEqualTo("sun-moon");
        assertThat(entry.coincidence()).isEmpty();
        assertThat(entry.action().kind()).isEqualTo("dark-sky-spots");
        assertThat(entry.action().date()).isEqualTo(DAY.plusDays(1));
        List<String> factText = entry.facts().stream()
                .flatMap(f -> f.segments().stream())
                .map(com.gregochr.goldenhour.model.comingup.ComingUpFact.Segment::text)
                .toList();
        assertThat(String.join("", factText)).contains("peaks");
        assertThat(entry.meta()).doesNotContainKey("peakDate");
    }

    @Test
    @DisplayName("a high-band entry gets a server-authored scoreNote naming whichever component "
            + "carried the score")
    void highBandEntryGetsAScoreNote() {
        // Eclipse rarity alone (~10.6 bits) clears the announce band (7.5) on rarity, not magnitude.
        AlmanacEvent eclipse = event(DAY, DAY, "eclipse", "Partial solar eclipse", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(eclipse));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.bits()).isGreaterThanOrEqualTo(7.5);
        assertThat(entry.scoreNote()).isNotNull().contains("Rarity");
    }

    @Test
    @DisplayName("an entry below the announce band gets no scoreNote")
    void belowAnnounceBandGetsNoScoreNote() {
        AlmanacEvent supermoon = event(DAY, DAY, "supermoon", "Supermoon", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(supermoon));

        assertThat(response.entries().getFirst().bits()).isLessThan(7.5);
        assertThat(response.entries().getFirst().scoreNote()).isNull();
    }

    // ── id, kindTag, region-scope fact ──────────────────────────────────────

    @Test
    @DisplayName("id is deterministic: type:startDate:endDate")
    void idIsDeterministic() {
        AlmanacEvent shower = event(DAY, DAY, "meteor", "Perseids", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(shower));

        assertThat(response.entries().getFirst().id()).isEqualTo("meteor:" + DAY + ":" + DAY);
    }

    @Test
    @DisplayName("a region-scoped event gets a fact row naming its regions — dead in production "
            + "today (no source populates regions yet) but exercised directly here")
    void regionScopedEventGetsAFactRow() {
        AlmanacEvent regional = new AlmanacEvent(DAY, DAY, AlmanacKind.ALMANAC, "equinox",
                "Autumn equinox", "detail", Map.of(), List.of("Dales & Lakes"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(regional));

        assertThat(response.entries().getFirst().facts())
                .anySatisfy(fact -> assertThat(fact.segments())
                        .anySatisfy(seg -> assertThat(seg.text()).contains("Dales & Lakes")));
    }

    // ── first-of-type prose ("say the definition once") ─────────────────────

    @Test
    @DisplayName("only the first occurrence of a type in the window gets prose")
    void onlyFirstOfTypeGetsProse() {
        AlmanacEvent first = event(DAY, DAY, "meteor", "Perseids", Map.of());
        AlmanacEvent second = event(DAY.plusDays(30), DAY.plusDays(30), "meteor", "Orionids", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(first, second));

        assertThat(response.entries().get(0).prose()).isEqualTo("detail");
        assertThat(response.entries().get(1).prose()).isNull();
    }

    // ── counts ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("counts reflect the assembled entries, split fixed/forecast and by family")
    void countsReflectAssembledEntries() {
        AlmanacEvent shower = event(DAY, DAY, "meteor", "Perseids", Map.of());
        AlmanacEvent eclipse = event(DAY.plusDays(1), DAY.plusDays(1), "eclipse", "Eclipse", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(shower, eclipse));

        assertThat(response.counts().fixed()).isEqualTo(2);
        assertThat(response.counts().forecast()).isZero();
        assertThat(response.counts().byFamily()).containsEntry("night-sky", 1)
                .containsEntry("eclipse", 1);
    }

    @Test
    @DisplayName("bands are served from the scoring properties")
    void bandsAreServed() {
        ComingUpResponse response = assembler.assemble(DAY, List.of());

        assertThat(response.bands().list()).isEqualTo(5.0);
        assertThat(response.bands().announce()).isEqualTo(7.5);
        // 10.0, not the pre-census 9.5 — P5's census moved this edge (ComingUpScoringProperties'
        // own Javadoc records why: at 9.5 every annual-rate almanac topic interrupted).
        assertThat(response.bands().interrupt()).isEqualTo(10.0);
    }

    // ── tide runs: magnitude, cold start, king-as-big-spring, tide field ────

    @Test
    @DisplayName("a spring-tide run with no derivable peak still gets non-null bits — the default "
            + "median magnitude, per D4's 'every ALMANAC entry gets non-null bits'")
    void tideRunWithNoPeakStillScores() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER))).thenReturn(Optional.empty());
        AlmanacEvent run = event(DAY, DAY.plusDays(3), "spring-tide", "Spring tide run", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(run));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.bits()).isNotNull();
        assertThat(entry.bits()).isCloseTo(4.89, org.assertj.core.data.Offset.offset(0.05));
        assertThat(entry.tide()).isNull();
        assertThat(entry.interim()).as("unmeasurable — no magnitude claim at all").isTrue();
    }

    @Test
    @DisplayName("a tide run at a port with no TideStats gets the default magnitude — 'no magnitude "
            + "claim at all' (plan D4), same treatment as a topic with no distribution")
    void tideRunWithNoStatsGetsDefaultMagnitude() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 4.6)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.empty());
        AlmanacEvent run = event(DAY, DAY.plusDays(3), "spring-tide", "Spring tide run",
                AlmanacEvent.metaOf("range", "4.6 m"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(run));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.bits()).isCloseTo(4.89, org.assertj.core.data.Offset.offset(0.05));
        assertThat(entry.tide()).isNull();
        assertThat(entry.metric()).isEqualTo("4.6 m");
        assertThat(entry.interim()).as("no TideStats — no magnitude claim at all").isTrue();
        // "range" was consumed into metric AND facts (tideFacts reads it unconditionally whenever
        // present), so dropping it is safe even though tide.delta was never built.
        assertThat(entry.meta()).doesNotContainKey("range");
    }

    @Test
    @DisplayName("rangeAnomaly is NOT dropped from meta when tide.delta could not be built — unlike "
            + "range/alignment/location, nothing else in the payload carries that fact, so dropping "
            + "it unconditionally would lose it with no typed-field replacement")
    void rangeAnomalyIsPreservedWhenTideFieldFailsToBuild() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 4.6)));
        // TideStats present (so magnitude IS computed, interim should be false), but with no
        // avgRangeMetres — the one condition under which s.tide stays null despite stats being
        // present, per enrichTide's own gating.
        when(tideService.getTideStats(SEAHAM.getId()))
                .thenReturn(Optional.of(statsWithoutAvgRange()));
        when(tideRunPeakHistory.peakRanges(any(), any(), any(), any()))
                .thenReturn(List.of(1.0, 2.0, 3.0));
        AlmanacEvent run = event(DAY, DAY.plusDays(3), "spring-tide", "Spring tide run",
                AlmanacEvent.metaOf("range", "4.6 m", "rangeAnomaly", "+1.3"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(run));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.tide()).isNull();
        assertThat(entry.meta()).containsEntry("rangeAnomaly", "+1.3");
    }

    @Test
    @DisplayName("a mature run-peak history scores an exact percentile magnitude, and populates "
            + "the tide{range,delta,phase} field with delta from the port's own avgRangeMetres")
    void tideRunWithMatureHistoryScoresExactPercentile() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 5.2)));
        when(tideService.getTideStats(SEAHAM.getId()))
                .thenReturn(Optional.of(stats("3.3")));
        // 100 observations 1..100 — 5.2 lands at the very bottom, so it should score near the
        // median, not a high magnitude.
        java.util.List<Double> history = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            history.add((double) i);
        }
        when(tideRunPeakHistory.peakRanges(eq(SEAHAM), eq(COASTAL_ROSTER), eq(DAY), eq(DAY)))
                .thenReturn(history);
        AlmanacEvent run = event(DAY, DAY.plusDays(3), "spring-tide", "Spring tide run",
                AlmanacEvent.metaOf("range", "5.2 m", "alignment", "HW 09:08 · 58m after sunrise",
                        "rangeAnomaly", "+1.9", "location", "Seaham"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(run));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.tide()).isNotNull();
        assertThat(entry.tide().range()).isEqualTo(5.2);
        assertThat(entry.tide().delta()).isEqualTo(1.9, org.assertj.core.data.Offset.offset(0.001));
        assertThat(entry.tide().phase()).isEqualTo("HW");
        assertThat(entry.interim()).isFalse();
        // Every one of these is now represented elsewhere in the payload (tide.range/delta,
        // metric, or facts), so — unlike the no-tide-field case above — dropping them all is safe.
        assertThat(entry.meta()).doesNotContainKeys("range", "alignment", "rangeAnomaly", "location");
    }

    @Test
    @DisplayName("the tide's phase is LW when the entry's own alignment fact names low water")
    void tidePhaseFollowsTheAlignmentFact() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 3.6)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.of(stats("3.3")));
        when(tideRunPeakHistory.peakRanges(any(), any(), any(), any())).thenReturn(List.of());
        AlmanacEvent run = event(DAY, DAY, "spring-tide", "Spring tide run",
                AlmanacEvent.metaOf("range", "3.6 m", "alignment", "LW 05:44 · 34m after sunrise"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(run));

        assertThat(response.entries().getFirst().tide().phase()).isEqualTo("LW");
    }

    @Test
    @DisplayName("king runs use the spring rarity (3.9 bits), never a separate near-annual rate — "
            + "a king tide is a big spring, not a rarer event of its own (plan D4)")
    void kingRunUsesSpringRarity() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER))).thenReturn(Optional.empty());
        AlmanacEvent king = event(DAY, DAY.plusDays(3), "king-tide", "King tide run", Map.of());
        AlmanacEvent spring = event(DAY.plusDays(60), DAY.plusDays(63), "spring-tide",
                "Spring tide run", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(king, spring));

        double kingBits = response.entries().get(0).bits();
        double springBits = response.entries().get(1).bits();
        assertThat(kingBits).isCloseTo(springBits, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("a cold-start run (thin history) is marked interim via a bucketed magnitude — a "
            + "median run does not score at or above the p95 bucket")
    void coldStartTideRunUsesBucketedMagnitude() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 5.0)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.of(stats("3.3")));
        // Ten observations, well under the 60-observation floor, median 5.0.
        when(tideRunPeakHistory.peakRanges(eq(SEAHAM), eq(COASTAL_ROSTER), eq(DAY), eq(DAY)))
                .thenReturn(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0));
        AlmanacEvent run = event(DAY, DAY.plusDays(3), "spring-tide", "Spring tide run",
                AlmanacEvent.metaOf("range", "5.0 m"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(run));

        double springRarity = SurpriseScore.rarity(14.8);
        ComingUpEntry entry = response.entries().getFirst();
        double bits = entry.bits();
        assertThat(entry.interim()).as("cold start must never be reported as a mature score").isTrue();
        // median bucket (1.0), not p95 (4.3) — a ten-point sample cannot support that claim.
        assertThat(bits).isCloseTo(springRarity + 1.0, org.assertj.core.data.Offset.offset(0.05));
    }

    // ── superlative and threshold across tide runs in one window ────────────

    @Test
    @DisplayName("the smaller of two tide runs gets 'biggest until <date>'; the bigger, later run "
            + "gets none — and both get a threshold line naming the other's range")
    void superlativeAndThresholdAcrossTideRuns() {
        when(tideRunBuilder.peakRange(eq(datesOf(DAY, DAY.plusDays(3))), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 4.0)));
        LocalDate secondStart = DAY.plusDays(14);
        when(tideRunBuilder.peakRange(eq(datesOf(secondStart, secondStart.plusDays(3))), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 5.0)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.empty());

        AlmanacEvent smaller = event(DAY, DAY.plusDays(3), "spring-tide", "Spring tide run", Map.of());
        AlmanacEvent bigger = event(secondStart, secondStart.plusDays(3), "spring-tide",
                "Spring tide run", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(smaller, bigger));

        ComingUpEntry first = response.entries().get(0);
        ComingUpEntry second = response.entries().get(1);
        assertThat(first.superlative()).isEqualTo("biggest until " + fmt(secondStart));
        assertThat(second.superlative()).isNull();
        assertThat(first.threshold()).contains("5.0");
        assertThat(second.threshold()).contains("4.0");
    }

    @Test
    @DisplayName("a run with a bigger EARLIER run in the same window never claims 'biggest until "
            + "X' — the pre-fix code only checked later runs, so a smaller middle run could still "
            + "print a falsifiable false claim")
    void superlative_notClaimedWhenABiggerRunCameEarlier() {
        LocalDate aStart = DAY;
        LocalDate bStart = DAY.plusDays(14);
        LocalDate cStart = DAY.plusDays(28);
        when(tideRunBuilder.peakRange(eq(datesOf(aStart, aStart.plusDays(3))), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 5.0)));
        when(tideRunBuilder.peakRange(eq(datesOf(bStart, bStart.plusDays(3))), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 3.0)));
        when(tideRunBuilder.peakRange(eq(datesOf(cStart, cStart.plusDays(3))), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 6.0)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.empty());

        AlmanacEvent a = event(aStart, aStart.plusDays(3), "spring-tide", "Spring tide run", Map.of());
        AlmanacEvent b = event(bStart, bStart.plusDays(3), "spring-tide", "Spring tide run", Map.of());
        AlmanacEvent c = event(cStart, cStart.plusDays(3), "spring-tide", "Spring tide run", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(a, b, c));

        ComingUpEntry entryA = response.entries().get(0);
        ComingUpEntry entryB = response.entries().get(1);
        ComingUpEntry entryC = response.entries().get(2);
        // A (5.0) is biggest-so-far, and C (6.0) is bigger and later -> A claims "biggest until C".
        assertThat(entryA.superlative()).isEqualTo("biggest until " + fmt(cStart));
        // B (3.0) is NOT biggest-so-far — A already beat it — so no claim, even though C is both
        // later and bigger than B. The pre-fix code compared B only against C and got this wrong.
        assertThat(entryB.superlative()).isNull();
        // C is the overall biggest with nothing later bigger, so it makes no claim either.
        assertThat(entryC.superlative()).isNull();
    }

    @Test
    @DisplayName("the biggest run in a window still sets the comparison set for its neighbours even "
            + "after being absorbed into a supermoon coincidence — superlatives/thresholds must be "
            + "computed BEFORE the merge, not after, or the merged-away run's range silently drops "
            + "out of every other run's comparison")
    void biggestRunMergedAway_stillSetsNeighboursComparisonSet() {
        LocalDate aStart = DAY;
        LocalDate bStart = DAY.plusDays(14);
        LocalDate cStart = DAY.plusDays(28);
        when(tideRunBuilder.peakRange(eq(datesOf(aStart, aStart.plusDays(3))), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 4.0)));
        // B is the biggest of the three, and overlaps a supermoon — which outscores every tide run
        // here on default magnitude alone (~6.9 vs ~4.9 bits), so B is the one that gets merged away.
        when(tideRunBuilder.peakRange(eq(datesOf(bStart, bStart.plusDays(3))), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 6.0)));
        when(tideRunBuilder.peakRange(eq(datesOf(cStart, cStart.plusDays(3))), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 5.0)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.empty());

        AlmanacEvent a = event(aStart, aStart.plusDays(3), "spring-tide", "Spring tide run", Map.of());
        AlmanacEvent b = event(bStart, bStart.plusDays(3), "spring-tide", "Spring tide run", Map.of());
        AlmanacEvent c = event(cStart, cStart.plusDays(3), "spring-tide", "Spring tide run", Map.of());
        AlmanacEvent supermoon = event(bStart, bStart, "supermoon", "Supermoon",
                Map.of("peakDate", bStart.toString()));

        ComingUpResponse response = assembler.assemble(DAY, List.of(a, b, c, supermoon));

        assertThat(response.entries()).hasSize(3);
        ComingUpEntry entryA = response.entries().get(0);
        ComingUpEntry merged = response.entries().get(1);
        ComingUpEntry entryC = response.entries().get(2);

        assertThat(merged.type()).isEqualTo("supermoon");
        assertThat(merged.coincidence()).hasSize(1);

        // A still knows B (6.0, merged away) is the next bigger run — not silently invisible.
        assertThat(entryA.superlative()).isEqualTo("biggest until " + fmt(bStart));
        assertThat(entryA.threshold()).contains("6.0").contains("5.0");
        // C's threshold still names B's true range (6.0), the run's own comparison set unaffected
        // by B having been folded into the supermoon's coincidence line.
        assertThat(entryC.threshold()).contains("4.0").contains("6.0");
        assertThat(entryC.superlative()).isNull();
    }

    // ── coincidence merge (plan D10) ─────────────────────────────────────

    @Test
    @DisplayName("a tide run and a supermoon sharing dates merge into one entry, scored as the max "
            + "of the two, with the loser folded into coincidence and a joinNote naming the winner")
    void tideRunAndSupermoonMerge() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER))).thenReturn(Optional.empty());
        AlmanacEvent tideRun = event(DAY, DAY.plusDays(3), "king-tide", "King tide run", Map.of());
        AlmanacEvent supermoon = event(DAY.plusDays(1), DAY.plusDays(1), "supermoon", "Supermoon",
                Map.of("peakDate", DAY.plusDays(1).toString()));

        ComingUpResponse response = assembler.assemble(DAY, List.of(tideRun, supermoon));

        assertThat(response.entries()).hasSize(1);
        ComingUpEntry merged = response.entries().getFirst();
        // Supermoon (rarity ~5.9 + 1.0 ≈ 6.9) beats the king run's default-magnitude score
        // (rarity ~3.9 + 1.0 ≈ 4.9), so the supermoon carries the merged entry.
        assertThat(merged.type()).isEqualTo("supermoon");
        assertThat(merged.coincidence()).hasSize(1);
        assertThat(merged.coincidence().getFirst().family()).isEqualTo("coastal");
        assertThat(merged.coincidence().getFirst().name()).isEqualTo("King tide run");
        assertThat(merged.joinNote()).contains("maximum").contains("Supermoon");
    }

    @Test
    @DisplayName("a supermoon that sorts BEFORE its overlapping tide run still merges into one "
            + "entry, not a standalone supermoon plus a second merged copy — the merge must not "
            + "depend on which of the pair AlmanacService.build()'s date sort happens to place "
            + "first, since a supermoon's span commonly starts before the tide run it overlaps")
    void supermoonSortingBeforeItsTideRun_stillMergesToOneEntry() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER))).thenReturn(Optional.empty());
        // Supermoon starts a day before the tide run (and so sorts first in a date-ordered list)
        // but still overlaps it.
        AlmanacEvent supermoon = event(DAY.minusDays(1), DAY.plusDays(1), "supermoon", "Supermoon",
                Map.of("peakDate", DAY.toString()));
        AlmanacEvent tideRun = event(DAY, DAY.plusDays(3), "king-tide", "King tide run", Map.of());

        // Passed in the exact order a date-sorted feed would hand them: supermoon first.
        ComingUpResponse response = assembler.assemble(DAY, List.of(supermoon, tideRun));

        assertThat(response.entries()).hasSize(1);
        ComingUpEntry merged = response.entries().getFirst();
        assertThat(merged.type()).isEqualTo("supermoon");
        assertThat(merged.coincidence()).hasSize(1);
        assertThat(merged.coincidence().getFirst().name()).isEqualTo("King tide run");
    }

    @Test
    @DisplayName("when the tide run outscores the supermoon, the TIDE carries the merged entry — "
            + "the max rule can land on either side, not just the higher-rarity one")
    void tideRunOutscoresSupermoon_tideCarriesTheMerge() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 6.0)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.of(stats("3.3")));
        // 100 observations, 1..100 — a peak of 6.0 (the biggest on record) scores a high exact
        // magnitude, comfortably beating a supermoon's default ~6.9-bit score.
        List<Double> history = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            history.add((double) i / 20.0);
        }
        when(tideRunPeakHistory.peakRanges(eq(SEAHAM), eq(COASTAL_ROSTER), eq(DAY), eq(DAY)))
                .thenReturn(history);
        AlmanacEvent tideRun = event(DAY, DAY.plusDays(3), "king-tide", "King tide run",
                AlmanacEvent.metaOf("range", "6.0 m"));
        AlmanacEvent supermoon = event(DAY.plusDays(1), DAY.plusDays(1), "supermoon", "Supermoon",
                Map.of("peakDate", DAY.plusDays(1).toString()));

        ComingUpResponse response = assembler.assemble(DAY, List.of(tideRun, supermoon));

        assertThat(response.entries()).hasSize(1);
        ComingUpEntry merged = response.entries().getFirst();
        assertThat(merged.type()).isEqualTo("king-tide");
        assertThat(merged.coincidence()).hasSize(1);
        assertThat(merged.coincidence().getFirst().name()).isEqualTo("Supermoon");
        assertThat(merged.coincidence().getFirst().family()).isEqualTo("sun-moon");
        assertThat(merged.joinNote()).contains("King tide run");
    }

    @Test
    @DisplayName("a tide run and a supermoon on non-overlapping dates stay two separate entries")
    void nonOverlappingTideAndSupermoonStaySeparate() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER))).thenReturn(Optional.empty());
        AlmanacEvent tideRun = event(DAY, DAY.plusDays(3), "spring-tide", "Spring tide run", Map.of());
        AlmanacEvent supermoon = event(DAY.plusDays(30), DAY.plusDays(30), "supermoon", "Supermoon",
                Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(tideRun, supermoon));

        assertThat(response.entries()).hasSize(2);
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static TideStats stats(String avgRange) {
        return new TideStats(null, null, null, null, 100L, new BigDecimal(avgRange),
                null, null, null, 0L, null, null, null, 0L);
    }

    /** {@code TideStats} present (so magnitude IS scored) but with no {@code avgRangeMetres} — the
     * one condition under which {@code tide.delta} cannot be computed despite stats resolving. */
    private static TideStats statsWithoutAvgRange() {
        return new TideStats(null, null, null, null, 100L, null,
                null, null, null, 0L, null, null, null, 0L);
    }

    private static List<LocalDate> datesOf(LocalDate start, LocalDate end) {
        List<LocalDate> dates = new java.util.ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            dates.add(d);
        }
        return dates;
    }

    private static String fmt(LocalDate date) {
        return java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.UK).format(date);
    }
}
