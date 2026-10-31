package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.model.comingup.ComingUpCoincidenceLine;
import com.gregochr.goldenhour.model.comingup.ComingUpDayMark;
import com.gregochr.goldenhour.model.comingup.ComingUpDayRange;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpFact;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.TideRunBuilder;
import com.gregochr.goldenhour.service.TideService;
import com.gregochr.goldenhour.service.TideSizeIndex;
import com.gregochr.solarutils.MoonriseMoonset;
import com.gregochr.solarutils.MoonriseMoonsetCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
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

    private DailyRangeHistory dailyRangeHistory;

    @Mock
    private TideService tideService;

    private ComingUpAssembler assembler;

    @BeforeEach
    void setUp() {
        dailyRangeHistory = new DailyRangeHistory(tideRunBuilder, mock(TideSizeIndex.class));
        assembler = new ComingUpAssembler(locationRepository, tideRunBuilder, tideRunPeakHistory,
                dailyRangeHistory, tideService, new ComingUpScoringProperties(), new MoonriseMoonsetCalculator());
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
    @DisplayName("a lunar eclipse entry clears the interrupt band and shares family 'eclipse' with the solar one")
    void lunarEclipseEntryClearsInterruptAndSharesFamily() {
        // log2(900) + 1.0 ≈ 10.81 — the plan's own worked figure (§1 row 12).
        AlmanacEvent lunar = event(DAY, DAY, "lunar-eclipse", "Deep partial lunar eclipse",
                Map.of("magnitude", "93% in shadow", "maximum", "05:12 · moon WSW 241°, 8° up",
                        "shadow", "in shadow 03:33 → sets 06:16", "seen", "seen from 41 of 57 sites",
                        "next", "Next from the UK: a partial eclipse, Wed 12 Jan 2028",
                        "since", "September 2025", "location", "Bamburgh"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(lunar));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.family()).isEqualTo("eclipse");
        assertThat(entry.metric()).isEqualTo("93%");
        assertThat(entry.bits()).isCloseTo(10.8, org.assertj.core.data.Offset.offset(0.05));
        assertThat(entry.interim()).isFalse();
        // "sets " appears in the shadow meta, so the representative sets while still eclipsed.
        assertThat(entry.superlative()).isEqualTo("sets in shadow");
        assertThat(entry.aside())
                .isEqualTo("No filter needed — bracket, the shadow is ~10 stops under the lit edge");
        // "since" wins over markScoreNotes' generic rarity-carried phrasing.
        assertThat(entry.scoreNote()).isEqualTo("The first visible from here since September 2025.");
        assertThat(entry.meta()).doesNotContainKeys(
                "magnitude", "maximum", "shadow", "next", "since", "location");
        // "seen" is not consumed by any fact row and survives as a passthrough figure.
        assertThat(entry.meta()).containsEntry("seen", "seen from 41 of 57 sites");
    }

    @Test
    @DisplayName("a lunar eclipse whose representative does not set in shadow carries no superlative")
    void lunarEclipseNotSettingInShadowHasNoSuperlative() {
        AlmanacEvent lunar = event(DAY, DAY, "lunar-eclipse", "Partial lunar eclipse",
                Map.of("shadow", "in shadow 03:33 → 06:52"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(lunar));

        assertThat(response.entries().getFirst().superlative()).isNull();
    }

    @Test
    @DisplayName("a lunar eclipse with no 'since' fact falls through to markScoreNotes' generic rarity phrasing")
    void lunarEclipseWithNoSinceFallsThroughToGenericScoreNote() {
        // No "since" key at all — the catalogue's earliest entry, which has nothing earlier.
        AlmanacEvent lunar = event(DAY, DAY, "lunar-eclipse", "Total lunar eclipse", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(lunar));

        ComingUpEntry entry = response.entries().getFirst();
        // gapWord(rarity(900)) → gapWordFromDays(900), which falls in the ≤1200 band: "every two
        // to three years" (the plan's own worked figure — lunar-eclipse-plan.md §1 row 12).
        assertThat(entry.scoreNote()).isEqualTo(
                "It is rare enough to flag on its own.");
        assertThat(entry.aside())
                .isEqualTo("No filter needed — bracket, the shadow is ~10 stops under the lit edge");
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
    @DisplayName("a high-band entry gets a server-authored, plain-language scoreNote naming the "
            + "rarity-carried case — no bits figure, the mean gap read back as a calendar phrase")
    void highBandEntryGetsAScoreNote() {
        // Eclipse rarity alone (~10.6 bits, from the 1500-day config gap) clears the announce band
        // (7.5) on rarity, not magnitude — 1500 days ≈ 4.1 years, so gapWord reads "every 4 years"
        // rather than overclaiming the "two to three years" band a lunar eclipse's 900-day gap gets.
        AlmanacEvent eclipse = event(DAY, DAY, "eclipse", "Partial solar eclipse", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(eclipse));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.bits()).isGreaterThanOrEqualTo(7.5);
        assertThat(entry.scoreNote()).isEqualTo(
                "It is rare enough to flag on its own.");
        assertThat(entry.scoreNote()).doesNotContain("bits").doesNotContain("Rarity")
                .doesNotContain("once").doesNotContain("years");
    }

    @Test
    @DisplayName("an entry below the announce band gets no scoreNote")
    void belowAnnounceBandGetsNoScoreNote() {
        AlmanacEvent supermoon = event(DAY, DAY, "supermoon", "Supermoon", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(supermoon));

        assertThat(response.entries().getFirst().bits()).isLessThan(7.5);
        assertThat(response.entries().getFirst().scoreNote()).isNull();
    }

    @Test
    @DisplayName("a magnitude-carried scoreNote names the entry's own figure against the usual one "
            + "from its history, once the magnitude was computed from a mature distribution")
    void magnitudeCarriedScoreNoteNamesFigureAgainstUsual() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 6.0)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.of(stats("3.3")));
        // 100 observations, 0.05..5.0 — a range of 6.0 clears every one of them, so magnitude
        // (~6.66 bits) beats the spring/king rarity (~3.9 bits) and carries the score.
        List<Double> history = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            history.add((double) i / 20.0);
        }
        when(tideRunPeakHistory.peakRanges(eq(SEAHAM), eq(COASTAL_ROSTER), eq(DAY), eq(DAY)))
                .thenReturn(history);
        AlmanacEvent run = event(DAY, DAY, "king-tide", "King tide run",
                AlmanacEvent.metaOf("range", "6.0 m"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(run));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.bits()).isGreaterThanOrEqualTo(7.5);
        assertThat(entry.interim()).isFalse();
        // Median of 0.05..5.0 in steps of 0.05 (nearest-rank, 50th percentile of 100 values) is
        // the 50th value: 2.5.
        assertThat(entry.scoreNote()).isEqualTo("An unusually big one — 6.0 m against the usual 2.5 m.");
    }

    @Test
    @DisplayName("a cold-start magnitude-carried scoreNote falls back to the no-figure wording — a "
            + "bucketed reference names no real distribution to call \"usual\"")
    void coldStartMagnitudeCarriedScoreNoteFallsBackWithNoFigure() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 11.0)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.of(stats("3.3")));
        // 10 observations (well under the 60-observation cold-start floor) — 11.0 clears all of
        // them, bucketing to p97Bits (5.1), still enough to beat the spring/king rarity (~3.9).
        List<Double> history = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
        when(tideRunPeakHistory.peakRanges(eq(SEAHAM), eq(COASTAL_ROSTER), eq(DAY), eq(DAY)))
                .thenReturn(history);
        AlmanacEvent run = event(DAY, DAY, "king-tide", "King tide run",
                AlmanacEvent.metaOf("range", "11.0 m"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(run));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.bits()).isGreaterThanOrEqualTo(7.5);
        assertThat(entry.interim()).isTrue();
        assertThat(entry.scoreNote()).isEqualTo("An unusually big one carries it, not rarity.");
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
                AlmanacEvent.metaOf("range", "5.2 m", "sunriseHw", "09:08", "sunriseHwGap", "58",
                        "sunriseHwDate", DAY.toString(), "sunriseHwAligned", "true",
                        "sunsetHw", "19:50", "sunsetHwGap", "-20", "sunsetHwDate", DAY.toString(),
                        "sunsetHwAligned", "true",
                        "eventWaterPartial", "true", "rangeAnomaly", "+1.9", "location", "Seaham"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(run));

        ComingUpEntry entry = response.entries().getFirst();
        assertThat(entry.tide()).isNotNull();
        assertThat(entry.tide().range()).isEqualTo(5.2);
        assertThat(entry.tide().delta()).isEqualTo(1.9, org.assertj.core.data.Offset.offset(0.001));
        assertThat(entry.tide().phase()).isEqualTo("HW");
        assertThat(entry.interim()).isFalse();
        // Every one of these is now represented elsewhere in the payload (tide.range/delta,
        // metric, or facts), so — unlike the no-tide-field case above — dropping them all is safe.
        assertThat(entry.meta()).doesNotContainKeys("range", "rangeAnomaly", "location", "sunriseHw",
                "sunriseHwGap", "sunriseHwDate", "sunriseHwAligned", "sunsetHw", "sunsetHwGap",
                "sunsetHwDate", "sunsetHwAligned", "eventWaterPartial");
    }

    @Test
    @DisplayName("the tide's phase is always HW: the card's rows name high water only")
    void tidePhaseIsAlwaysHighWater() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 3.6)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.of(stats("3.3")));
        when(tideRunPeakHistory.peakRanges(any(), any(), any(), any())).thenReturn(List.of());
        AlmanacEvent run = event(DAY, DAY, "spring-tide", "Spring tide run",
                // The retired single-extremum key, LW-shaped: the old phaseOf read it and said "LW".
                AlmanacEvent.metaOf("range", "3.6 m", "alignment", "LW 05:44 · 34m after sunrise"));

        ComingUpResponse response = assembler.assemble(DAY, List.of(run));

        assertThat(response.entries().getFirst().tide().phase()).isEqualTo("HW");
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
            + "after being merged with a supermoon — superlatives/thresholds must be computed BEFORE "
            + "the merge, and the run that hosts the merged card keeps its OWN superlative and "
            + "threshold")
    void biggestRunMerged_stillSetsNeighboursComparisonSet() {
        LocalDate aStart = DAY;
        LocalDate bStart = DAY.plusDays(14);
        LocalDate cStart = DAY.plusDays(28);
        when(tideRunBuilder.peakRange(eq(datesOf(aStart, aStart.plusDays(3))), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 4.0)));
        // B overlaps a supermoon, which outscores every tide run here on default magnitude alone
        // (~6.9 vs ~4.9 bits) — yet B's four-day span is longer, so B hosts the merged card.
        when(tideRunBuilder.peakRange(eq(datesOf(bStart, bStart.plusDays(3))), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 6.0)));
        when(tideRunBuilder.peakRange(eq(datesOf(cStart, cStart.plusDays(3))), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 7.0)));
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

        assertThat(merged.type()).isEqualTo("spring-tide");
        assertThat(merged.coincidence()).hasSize(2);

        // A still knows B (6.0, now the merged card) is the next bigger run.
        assertThat(entryA.superlative()).isEqualTo("biggest until " + fmt(bStart));
        assertThat(entryA.threshold()).contains("6.0").contains("7.0");
        // B hosts, so its own superlative and threshold now appear on the merged card.
        assertThat(merged.superlative()).isEqualTo("biggest until " + fmt(cStart));
        assertThat(merged.threshold()).contains("4.0").contains("7.0");
        assertThat(entryC.threshold()).contains("4.0").contains("6.0");
        assertThat(entryC.superlative()).isNull();
    }

    // ── coincidence merge (plan D10; shape: supermoon-tide-run-plan.md) ──────

    // Oct 2026: 25 Sun, 26 Mon, 27 Tue, 28 Wed, 29 Thu, 30 Fri. UK clocks go back on the 25th, so
    // the 26th is GMT and a UTC instant reads the same on the London clock.
    private static final LocalDate BUILT = LocalDate.of(2026, 10, 20);

    private static LocalDate oct(int day) {
        return LocalDate.of(2026, 10, day);
    }

    private static LocalDate nov(int day) {
        return LocalDate.of(2026, 11, day);
    }

    /** A run's meta as TideAlmanacSource writes it for a run with a true peak. */
    private static Map<String, String> runMeta(LocalDate peak, LocalDate alignedOn) {
        return AlmanacEvent.metaOf("range", "4.9 m",
                "sunsetHw", alignedOn == null ? null : "15:48",
                "sunsetHwGap", alignedOn == null ? null : "-52",
                "sunsetHwDate", alignedOn == null ? null : alignedOn.toString(),
                "sunsetHwAligned", alignedOn == null ? null : "true",
                "peakDate", peak.toString(), "location", "Seaham");
    }

    private static AlmanacEvent supermoon(LocalDate start, LocalDate end, LocalDate peak) {
        return event(start, end, "supermoon", "Supermoon", Map.of("peakDate", peak.toString()));
    }

    /** Stubs one derivable peak at Seaham with no stored statistics (interim, default magnitude). */
    private void stubRunAtSeaham() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 4.9)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.empty());
    }

    private static final java.time.ZoneId LONDON = java.time.ZoneId.of("Europe/London");

    /** A moonrise at the given UTC clock (these dates are GMT, so the London clock reads the same). */
    private static MoonriseMoonset risesAt(int year, int month, int day, int hour, int minute) {
        return new MoonriseMoonset(Optional.of(ZonedDateTime.of(year, month, day, hour, minute, 0, 0,
                java.time.ZoneOffset.UTC)), Optional.empty());
    }

    /** The served non-breaking-space form of a facts label: breaks only around " · " and " – ". */
    private static String nb(String label) {
        return label.replace(" – ", "\u0001").replace(" · ", "\u0002").replace(' ', ' ')
                .replace("\u0001", " – ").replace("\u0002", " · ");
    }

    private ComingUpAssembler assemblerWith(MoonriseMoonsetCalculator calculator) {
        return new ComingUpAssembler(locationRepository, tideRunBuilder, tideRunPeakHistory,
                dailyRangeHistory, tideService, new ComingUpScoringProperties(), calculator);
    }

    private static final String NBSP = "\u00a0";

    /** The sunset row of {@link #runMeta} on Tue 27 Oct, aligned, with its non-breaking spaces. */
    private static final String SUNSET_ROW_ON_27 =
            "sunset\u00a0· HW" + NBSP + "15:48, 52m" + NBSP + "before\u00a0· Tue" + NBSP + "27";

    private static List<String> factTexts(ComingUpEntry entry) {
        return entry.facts().stream()
                .map(f -> f.segments().stream().map(ComingUpFact.Segment::text)
                        .reduce("", String::concat))
                .toList();
    }

    @Test
    @DisplayName("§2.3: a supermoon that wins on bits inside a longer spring run leaves the RUN hosting "
            + "the union span, with both lines, both marks, the run's door and the composite title")
    void supermoonWinsOnBits_runStillHostsTheUnionSpan() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                runMeta(oct(27), oct(27)));

        MoonriseMoonsetCalculator calculator = mock(MoonriseMoonsetCalculator.class);
        when(calculator.calculate(oct(26), 54.84, -1.33, LONDON)).thenReturn(risesAt(2026, 10, 26, 17, 22));

        ComingUpResponse response = assemblerWith(calculator).assemble(BUILT,
                List.of(run, supermoon(oct(26), oct(26), oct(26))));

        assertThat(response.entries()).hasSize(1);
        ComingUpEntry merged = response.entries().getFirst();
        assertThat(merged.type()).isEqualTo("spring-tide");
        assertThat(merged.family()).isEqualTo("coastal");
        assertThat(merged.id()).isEqualTo("spring-tide:2026-10-25:2026-10-30");
        assertThat(merged.startDate()).isEqualTo(oct(25));
        assertThat(merged.endDate()).isEqualTo(oct(30));
        assertThat(merged.title()).isEqualTo("Spring tide run, on a supermoon");
        assertThat(merged.action().kind()).isEqualTo("coastal-spots");
        assertThat(merged.action().date()).isEqualTo(oct(27));
        assertThat(merged.action().label()).isEqualTo("Show coastal spots for 27 Oct →");
        assertThat(merged.metric()).isEqualTo("4.9 m");

        assertThat(merged.coincidence()).hasSize(2);
        assertThat(merged.coincidence().get(0).family()).isEqualTo("coastal");
        assertThat(merged.coincidence().get(0).name()).isEqualTo("Spring tide run");
        assertThat(merged.coincidence().get(0).type()).isEqualTo("spring-tide");
        assertThat(merged.coincidence().get(0).factsLabel())
                .isEqualTo(nb("Sun 25 – Fri 30 Oct · biggest Tue 27 · 4.9 m · HW 15:48"));
        assertThat(merged.coincidence().get(1).family()).isEqualTo("sun-moon");
        assertThat(merged.coincidence().get(1).name()).isEqualTo("Supermoon");
        assertThat(merged.coincidence().get(1).type()).isEqualTo("supermoon");
        assertThat(merged.coincidence().get(1).factsLabel()).isEqualTo(nb("Mon 26 Oct · moonrise 17:22"));

        assertThat(merged.joinNote()).isEqualTo("Same cause, two effects. The moon is closest to Earth, "
                + "which both enlarges it and stretches the tide. The moon is full on the 26th and the "
                + "tide peaks a day later, on the 27th. Counted as one event, not two.");
        assertThat(merged.dayMarks()).containsExactly(
                new ComingUpDayMark(oct(26), "full moon", "sun-moon"),
                new ComingUpDayMark(oct(27), "peak", "coastal"));
        // The run's range and event rows stay (each row carries its own date); the supermoon's
        // "peaks" does not.
        assertThat(factTexts(merged)).containsExactly("range 4.9 m at Seaham",
                SUNSET_ROW_ON_27);
        assertThat(merged.joinNote()).doesNotContain("bits");
    }

    @Test
    @DisplayName("R10: the score provenance stays with the bits WINNER — the supermoon's bits, interim "
            + "flag and enteredWindow — never the union start, which would back-date the arrival")
    void scoreProvenanceStaysWithTheWinner() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                runMeta(oct(27), oct(27)));
        AlmanacEvent moon = supermoon(oct(26), oct(26), oct(26));

        ComingUpEntry merged = assembler.assemble(BUILT, List.of(run, moon)).entries().getFirst();
        ComingUpEntry moonAlone = assembler.assemble(BUILT, List.of(moon)).entries().getFirst();

        assertThat(merged.bits()).isEqualTo(moonAlone.bits());
        // The run alone is interim (no stored statistics); the winning supermoon is not.
        assertThat(merged.interim()).isFalse();
        assertThat(merged.enteredWindow()).isEqualTo(moonAlone.enteredWindow());
        assertThat(merged.enteredWindow()).isEqualTo(oct(26).minusDays(AlmanacService.DEFAULT_DAYS - 1L));
    }

    @Test
    @DisplayName("a king run with a two-night supermoon: both nights marked, the peak marked on its own day")
    void kingRunWithTwoNightSupermoon() {
        stubRunAtSeaham();
        AlmanacEvent run = event(nov(23), nov(28), "king-tide", "King tide run", runMeta(nov(26), nov(26)));
        MoonriseMoonsetCalculator calculator = mock(MoonriseMoonsetCalculator.class);
        // The supermoon's peak night is the 25th (nearest perigee), not its first night.
        when(calculator.calculate(nov(25), 54.84, -1.33, LONDON)).thenReturn(risesAt(2026, 11, 25, 15, 30));

        ComingUpEntry merged = assemblerWith(calculator).assemble(BUILT,
                List.of(run, supermoon(nov(24), nov(25), nov(25)))).entries().getFirst();

        assertThat(merged.type()).isEqualTo("king-tide");
        assertThat(merged.title()).isEqualTo("King tide run, on a supermoon");
        assertThat(merged.id()).isEqualTo("king-tide:2026-11-23:2026-11-28");
        assertThat(merged.dayMarks()).containsExactly(
                new ComingUpDayMark(nov(24), "full moon", "sun-moon"),
                new ComingUpDayMark(nov(25), "full moon", "sun-moon"),
                new ComingUpDayMark(nov(26), "peak", "coastal"));
        assertThat(merged.joinNote()).contains("The moon is full on the 24th and 25th and the tide peaks "
                + "a day later, on the 26th.");
        // The multi-night line names the night the moonrise is for.
        assertThat(merged.coincidence().get(1).factsLabel())
                .isEqualTo(nb("Tue 24 – Wed 25 Nov · moonrise 15:30 on the 25th"));
    }

    @Test
    @DisplayName("when the tide peaks on a moon night the host's mark comes first and the note says same day")
    void peakOnAMoonNight_hostMarkFirst() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                runMeta(oct(26), oct(26)));

        ComingUpEntry merged = assembler.assemble(BUILT,
                List.of(run, supermoon(oct(26), oct(26), oct(26)))).entries().getFirst();

        assertThat(merged.dayMarks()).containsExactly(
                new ComingUpDayMark(oct(26), "peak", "coastal"),
                new ComingUpDayMark(oct(26), "full moon", "sun-moon"));
        assertThat(merged.joinNote()).contains("The moon is full and the tide peaks on the same day, "
                + "the 26th. Counted as one event, not two.");
    }

    @Test
    @DisplayName("a one-day run has no true peak: the supermoon hosts on the span tie, marks the day full "
            + "moon only, and the note says nothing about a peak")
    void oneDayRunHasNoTruePeak() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(26), oct(26), "spring-tide", "Spring tide run",
                AlmanacEvent.metaOf("range", "4.9 m", "figuresFrom", oct(26).toString(),
                        "partialCoverage", "true", "location", "Seaham"));

        ComingUpEntry merged = assembler.assemble(BUILT,
                List.of(run, supermoon(oct(26), oct(26), oct(26)))).entries().getFirst();

        // Equal spans: the bits winner (the supermoon) hosts — R2's accepted consequence.
        assertThat(merged.type()).isEqualTo("supermoon");
        assertThat(merged.title()).isEqualTo("Supermoon, on a spring tide run");
        assertThat(merged.action().kind()).isEqualTo("dark-sky-spots");
        assertThat(merged.id()).isEqualTo("supermoon:2026-10-26:2026-10-26");
        assertThat(merged.dayMarks()).containsExactly(new ComingUpDayMark(oct(26), "full moon", "sun-moon"));
        assertThat(merged.joinNote()).isEqualTo("Same cause, two effects. The moon is closest to Earth, "
                + "which both enlarges it and stretches the tide. Counted as one event, not two.");
        // The supermoon's own "peaks" fact is not served on a merged entry.
        assertThat(factTexts(merged)).isEmpty();
        // Host first: the supermoon's line leads, the run's line follows with its own figures.
        assertThat(merged.coincidence().get(1).factsLabel()).isEqualTo(nb("Mon 26 Oct · 4.9 m on Mon 26"));
    }

    @Test
    @DisplayName("a supermoon starting a day before a run that wins on bits: the union starts on the "
            + "moon's night, the run hosts and its OWN enteredWindow stands")
    void runWinsOnBitsWithSupermoonBeforeIt() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 6.0)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.of(stats("3.3")));
        List<Double> history = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            history.add((double) i / 20.0);
        }
        when(tideRunPeakHistory.peakRanges(eq(SEAHAM), eq(COASTAL_ROSTER), eq(BUILT), eq(nov(25))))
                .thenReturn(history);
        AlmanacEvent run = event(nov(25), nov(29), "king-tide", "King tide run", runMeta(nov(27), nov(27)));

        ComingUpEntry merged = assembler.assemble(BUILT,
                List.of(supermoon(nov(24), nov(25), nov(24)), run)).entries().getFirst();

        assertThat(merged.type()).isEqualTo("king-tide");
        assertThat(merged.startDate()).isEqualTo(nov(24));
        assertThat(merged.endDate()).isEqualTo(nov(29));
        assertThat(merged.id()).isEqualTo("king-tide:2026-11-24:2026-11-29");
        // The run won on bits, so the arrival is the run's own day, not the union start.
        assertThat(merged.enteredWindow()).isEqualTo(nov(25).minusDays(AlmanacService.DEFAULT_DAYS - 1L));
        assertThat(merged.interim()).isFalse();
        assertThat(merged.dayMarks()).containsExactly(
                new ComingUpDayMark(nov(24), "full moon", "sun-moon"),
                new ComingUpDayMark(nov(25), "full moon", "sun-moon"),
                new ComingUpDayMark(nov(27), "peak", "coastal"));
        assertThat(merged.joinNote()).contains("tide peaks 2 days later, on the 27th.");
    }

    @Test
    @DisplayName("a merged run with no true peak (partial coverage, two or more days) marks the moon "
            + "nights only — never a peak anywhere")
    void partialCoverageRun_marksMoonNightsOnly() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(25), oct(29), "spring-tide", "Spring tide run",
                AlmanacEvent.metaOf("range", "4.9 m", "figuresFrom", oct(26).toString(),
                        "partialCoverage", "true", "location", "Seaham"));

        ComingUpEntry merged = assembler.assemble(BUILT,
                List.of(run, supermoon(oct(26), oct(27), oct(26)))).entries().getFirst();

        assertThat(merged.type()).isEqualTo("spring-tide");
        assertThat(merged.dayMarks()).extracting(ComingUpDayMark::label)
                .containsExactly("full moon", "full moon");
        assertThat(merged.joinNote()).doesNotContain("peaks");
        assertThat(merged.coincidence().getFirst().factsLabel())
                .isEqualTo(nb("Sun 25 – Thu 29 Oct · 4.9 m on Mon 26"));
        assertThat(factTexts(merged)).contains("figures cover only part of this run");
    }

    @Test
    @DisplayName("the alignment clock is printed beside the peak only when the alignment is on the peak day")
    void alignmentClockOnlyWhenOnThePeakDay() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                runMeta(oct(27), oct(28)));

        ComingUpEntry merged = assembler.assemble(BUILT,
                List.of(run, supermoon(oct(26), oct(26), oct(26)))).entries().getFirst();

        assertThat(merged.coincidence().getFirst().factsLabel())
                .isEqualTo(nb("Sun 25 – Fri 30 Oct · biggest Tue 27 · 4.9 m"));
        assertThat(factTexts(merged)).contains(
                "sunset\u00a0· HW" + NBSP + "15:48, 52m" + NBSP + "before\u00a0· Wed" + NBSP + "28");
    }

    /** A run's meta with explicit sunrise and sunset high-water keys (null args are omitted). */
    private static Map<String, String> eventMeta(String sunriseClock, String sunriseGap,
            LocalDate sunriseDate, boolean sunriseAligned, String sunsetClock, String sunsetGap,
            LocalDate sunsetDate, boolean sunsetAligned, boolean partial) {
        return AlmanacEvent.metaOf("range", "4.9 m", "peakDate", oct(27).toString(),
                "location", "Seaham",
                "sunriseHw", sunriseClock, "sunriseHwGap", sunriseGap,
                "sunriseHwDate", sunriseDate == null ? null : sunriseDate.toString(),
                "sunriseHwAligned", sunriseAligned ? "true" : null,
                "sunsetHw", sunsetClock, "sunsetHwGap", sunsetGap,
                "sunsetHwDate", sunsetDate == null ? null : sunsetDate.toString(),
                "sunsetHwAligned", sunsetAligned ? "true" : null,
                "eventWaterPartial", partial ? "true" : null);
    }

    private List<String> runFacts(Map<String, String> meta) {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run", meta);
        return factTexts(assembler.assemble(BUILT, List.of(run)).entries().getFirst());
    }

    @Test
    @DisplayName("W1: both event rows are always dated; aligned rows say how far, sunrise first")
    void bothEventRowsAreDated() {
        List<String> facts = runFacts(eventMeta("07:40", "-10", oct(28), true,
                "15:48", "-52", oct(27), true, false));

        assertThat(facts).containsExactly("range 4.9 m at Seaham",
                "sunrise\u00a0· HW" + NBSP + "07:40, 10m" + NBSP + "before\u00a0· Wed" + NBSP + "28",
                SUNSET_ROW_ON_27);
        // The old single "tide ..." row and the no-alignment row are gone.
        assertThat(facts).noneMatch(f -> f.startsWith("tide ") || f.contains("no water lands"));
        assertThat(facts.get(1)).contains(" ");
    }

    @Test
    @DisplayName("W1: a high water within five minutes of the event reads 'at sunrise'")
    void atTheEvent() {
        List<String> facts = runFacts(eventMeta("07:42", "3", oct(28), true, null, null, null,
                false, false));

        assertThat(facts).contains(
                "sunrise\u00a0· HW" + NBSP + "07:42, at" + NBSP + "sunrise\u00a0· Wed" + NBSP + "28");
    }

    @Test
    @DisplayName("W1: no high water within the hour names the nearest one; partial runs say 'on the "
            + "days measured'; an event with no high water has no row")
    void missRowsAndOmission() {
        List<String> facts = runFacts(eventMeta("05:30", "-140", oct(28), false,
                null, null, null, false, false));
        assertThat(facts).contains("sunrise\u00a0· no high water within an hour\u00a0· nearest HW"
                + NBSP + "05:30, 2h20" + NBSP + "before\u00a0· Wed" + NBSP + "28");
        assertThat(facts).noneMatch(f -> f.startsWith("sunset"));

        List<String> partial = runFacts(eventMeta("05:30", "-140", oct(28), false,
                "19:00", "130", oct(29), false, true));
        assertThat(partial).contains("sunrise\u00a0· no high water within an hour on the days"
                + " measured\u00a0· nearest HW" + NBSP + "05:30, 2h20" + NBSP + "before\u00a0· Wed" + NBSP + "28");
        assertThat(partial).contains("sunset\u00a0· no high water within an hour on the days"
                + " measured\u00a0· nearest HW" + NBSP + "19:00, 2h10" + NBSP + "after\u00a0· Thu" + NBSP + "29");
    }

    @Test
    @DisplayName("W1: the aligned row's clock clause is the accent segment; a miss row has none")
    void segmentTones() {
        stubRunAtSeaham();
        AlmanacEvent aligned = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                eventMeta("07:40", "-10", oct(28), true, null, null, null, false, false));
        AlmanacEvent miss = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                eventMeta("05:30", "-140", oct(28), false, null, null, null, false, false));

        List<ComingUpFact> alignedFacts = assembler.assemble(BUILT, List.of(aligned)).entries()
                .getFirst().facts();
        List<ComingUpFact> missFacts = assembler.assemble(BUILT, List.of(miss)).entries()
                .getFirst().facts();

        assertThat(alignedFacts.get(1).segments())
                .extracting(ComingUpFact.Segment::text, ComingUpFact.Segment::tone)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("sunrise\u00a0· ", "base"),
                        org.assertj.core.api.Assertions.tuple(
                                "HW" + NBSP + "07:40, 10m" + NBSP + "before", "accent"),
                        org.assertj.core.api.Assertions.tuple(NBSP + "· Wed" + NBSP + "28", "base"));
        assertThat(missFacts.get(1).segments())
                .extracting(ComingUpFact.Segment::tone).containsOnly("base");
    }

    @Test
    @DisplayName("W1: a supermoon-hosted merged card (longer moon span) still shows both dated rows, "
            + "in order, and not the range fact")
    void supermoonHostedCardKeepsTheEventRows() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(26), oct(27), "spring-tide", "Spring tide run",
                eventMeta("07:40", "-10", oct(28), true, "15:48", "-52", oct(27), true, false));

        ComingUpEntry merged = assembler.assemble(BUILT,
                List.of(run, supermoon(oct(24), oct(30), oct(26)))).entries().getFirst();

        assertThat(merged.type()).isEqualTo("supermoon");
        assertThat(factTexts(merged)).containsExactly(
                "sunrise · HW" + NBSP + "07:40, 10m" + NBSP + "before · Wed" + NBSP + "28",
                SUNSET_ROW_ON_27);
    }

    @Test
    @DisplayName("W1: a supermoon that wins a span tie hosts and still shows both dated rows")
    void supermoonWinningASpanTieKeepsTheEventRows() {
        stubRunAtSeaham();
        Map<String, String> meta = new java.util.HashMap<>(eventMeta("07:40", "-10", oct(26), true,
                "15:48", "-52", oct(26), true, false));
        meta.remove("peakDate");
        meta.put("figuresFrom", oct(26).toString());
        AlmanacEvent run = event(oct(26), oct(26), "spring-tide", "Spring tide run", meta);

        ComingUpEntry merged = assembler.assemble(BUILT,
                List.of(run, supermoon(oct(26), oct(26), oct(26)))).entries().getFirst();

        assertThat(merged.type()).isEqualTo("supermoon");
        assertThat(factTexts(merged)).containsExactly(
                "sunrise · HW" + NBSP + "07:40, 10m" + NBSP + "before · Mon" + NBSP + "26",
                "sunset · HW" + NBSP + "15:48, 52m" + NBSP + "before · Mon" + NBSP + "26");
    }

    @Test
    @DisplayName("W1: exactly five minutes reads 'at sunrise'; exactly an hour reads '1h00 before'")
    void offsetBoundaries() {
        assertThat(runFacts(eventMeta("07:45", "5", oct(28), true, null, null, null, false, false)))
                .contains("sunrise\u00a0· HW" + NBSP + "07:45, at" + NBSP + "sunrise\u00a0· Wed" + NBSP + "28");
        assertThat(runFacts(eventMeta("06:45", "-60", oct(28), true, null, null, null, false, false)))
                .contains("sunrise\u00a0· HW" + NBSP + "06:45, 1h00" + NBSP + "before\u00a0· Wed" + NBSP + "28");
        assertThat(runFacts(eventMeta("07:46", "6", oct(28), true, null, null, null, false, false)))
                .contains("sunrise\u00a0· HW" + NBSP + "07:46, 6m" + NBSP + "after\u00a0· Wed" + NBSP + "28");
    }

    @Test
    @DisplayName("W1: the event rows are identical on a merged card (no ' on the <ordinal>' suffix)")
    void mergedAndUnmergedShowTheSameRows() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                eventMeta("07:40", "-10", oct(28), true, "15:48", "-52", oct(27), true, false));

        ComingUpEntry merged = assembler.assemble(BUILT,
                List.of(run, supermoon(oct(26), oct(26), oct(26)))).entries().getFirst();
        ComingUpEntry alone = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(factTexts(merged)).isEqualTo(factTexts(alone));
        assertThat(factTexts(merged)).noneMatch(f -> f.contains(" on the "));
    }

    @Test
    @DisplayName("W1: the coincidence clock reads the aligned sunset row on the peak day, else the "
            + "sunrise row, else none")
    void coincidenceClockReadsTheEventRows() {
        stubRunAtSeaham();
        AlmanacEvent bothOnPeak = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                eventMeta("07:40", "-10", oct(27), true, "15:48", "-52", oct(27), true, false));
        AlmanacEvent sunriseOnly = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                eventMeta("07:40", "-10", oct(27), true, "15:48", "-52", oct(28), true, false));
        AlmanacEvent neither = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                eventMeta("07:40", "-10", oct(27), false, "15:48", "-52", oct(28), true, false));
        AlmanacEvent moon = supermoon(oct(26), oct(26), oct(26));

        assertThat(assembler.assemble(BUILT, List.of(bothOnPeak, moon)).entries().getFirst()
                .coincidence().getFirst().factsLabel())
                .isEqualTo(nb("Sun 25 – Fri 30 Oct · biggest Tue 27 · 4.9 m · HW 15:48"));
        assertThat(assembler.assemble(BUILT, List.of(sunriseOnly, moon)).entries().getFirst()
                .coincidence().getFirst().factsLabel())
                .isEqualTo(nb("Sun 25 – Fri 30 Oct · biggest Tue 27 · 4.9 m · HW 07:40"));
        assertThat(assembler.assemble(BUILT, List.of(neither, moon)).entries().getFirst()
                .coincidence().getFirst().factsLabel())
                .isEqualTo(nb("Sun 25 – Fri 30 Oct · biggest Tue 27 · 4.9 m"));
    }

    @Test
    @DisplayName("B2: only the sunrise and sunset high-water rows carry kind EVENT_WATER; the range "
            + "row and every other row carry none")
    void eventWaterRowsAreTaggedAndNothingElse() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                eventMeta("07:40", "-10", oct(28), true, "15:48", "-52", oct(27), true, false));

        List<ComingUpFact> facts = assembler.assemble(BUILT, List.of(run)).entries().getFirst().facts();

        assertThat(facts).extracting(ComingUpFact::kind).containsExactly(
                null, ComingUpFact.Kind.EVENT_WATER, ComingUpFact.Kind.EVENT_WATER);
    }

    @Test
    @DisplayName("B2: a supermoon-hosted merged card tags the run's event rows too, and not its own")
    void mergedCardEventRowsAreTagged() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                eventMeta("07:40", "-10", oct(28), true, "15:48", "-52", oct(27), true, false));
        AlmanacEvent moon = supermoon(oct(20), oct(30), oct(26));

        ComingUpEntry merged = assembler.assemble(BUILT, List.of(run, moon)).entries().getFirst();

        assertThat(merged.facts()).extracting(ComingUpFact::kind)
                .filteredOn(kind -> kind == ComingUpFact.Kind.EVENT_WATER).hasSize(2);
        assertThat(merged.facts()).filteredOn(f -> f.kind() == null)
                .noneMatch(f -> factText(f).startsWith("sunrise") || factText(f).startsWith("sunset"));
    }

    @Test
    @DisplayName("B2: liteFactsLabel is the factsLabel without the aligned clock; the Pro factsLabel is "
            + "unchanged; a line with no clock has equal labels")
    void liteFactsLabelDropsOnlyTheClock() {
        stubRunAtSeaham();
        AlmanacEvent aligned = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                eventMeta("07:40", "-10", oct(27), true, "15:48", "-52", oct(27), true, false));
        AlmanacEvent moon = supermoon(oct(26), oct(26), oct(26));

        ComingUpCoincidenceLine tideLine = assembler.assemble(BUILT, List.of(aligned, moon))
                .entries().getFirst().coincidence().getFirst();
        assertThat(tideLine.factsLabel())
                .isEqualTo(nb("Sun 25 – Fri 30 Oct · biggest Tue 27 · 4.9 m · HW 15:48"));
        assertThat(tideLine.liteFactsLabel())
                .isEqualTo(nb("Sun 25 – Fri 30 Oct · biggest Tue 27 · 4.9 m"));

        AlmanacEvent neither = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                eventMeta("07:40", "-10", oct(27), false, "15:48", "-52", oct(28), true, false));
        ComingUpCoincidenceLine noClock = assembler.assemble(BUILT, List.of(neither, moon))
                .entries().getFirst().coincidence().getFirst();
        assertThat(noClock.liteFactsLabel()).isEqualTo(noClock.factsLabel());

        ComingUpCoincidenceLine moonLine = assembler.assemble(BUILT, List.of(aligned, moon))
                .entries().getFirst().coincidence().get(1);
        assertThat(moonLine.liteFactsLabel()).isEqualTo(moonLine.factsLabel());
    }

    private static String factText(ComingUpFact fact) {
        return fact.segments().stream().map(ComingUpFact.Segment::text)
                .collect(java.util.stream.Collectors.joining());
    }

    @Test
    @DisplayName("the join note counts days from the NEAREST moon night, in both directions, "
            + "and lists three nights")
    void joinNoteRelativeWordings() {
        stubRunAtSeaham();
        AlmanacEvent later = event(oct(20), oct(29), "spring-tide", "Spring tide run",
                runMeta(oct(27), null));
        assertThat(assembler.assemble(BUILT, List.of(later, supermoon(oct(23), oct(25), oct(24))))
                .entries().getFirst().joinNote())
                .contains("full on the 23rd, 24th and 25th and the tide peaks 2 days later, on the 27th.");

        AlmanacEvent earlier = event(oct(20), oct(29), "spring-tide", "Spring tide run",
                runMeta(oct(21), null));
        assertThat(assembler.assemble(BUILT, List.of(earlier, supermoon(oct(23), oct(25), oct(24))))
                .entries().getFirst().joinNote())
                .contains("tide peaks 2 days earlier, on the 21st.");

        AlmanacEvent justBefore = event(oct(20), oct(29), "spring-tide", "Spring tide run",
                runMeta(oct(22), null));
        assertThat(assembler.assemble(BUILT, List.of(justBefore, supermoon(oct(23), oct(25), oct(24))))
                .entries().getFirst().joinNote())
                .contains("tide peaks a day earlier, on the 22nd.");
    }

    @Test
    @DisplayName("moonrise is read at the run's representative on the moon night, converted to the "
            + "London clock from the instant, and omitted when the moon does not rise")
    void moonriseAtTheRepresentative() {
        stubRunAtSeaham();
        MoonriseMoonsetCalculator calculator = mock(MoonriseMoonsetCalculator.class);
        // 13:22 in New York on the 26th is 17:22 UTC — the clock must come from the instant.
        ZonedDateTime rise = ZonedDateTime.of(2026, 10, 26, 13, 22, 0, 0,
                java.time.ZoneId.of("America/New_York"));
        when(calculator.calculate(oct(26), 54.84, -1.33, java.time.ZoneId.of("Europe/London")))
                .thenReturn(new MoonriseMoonset(Optional.of(rise), Optional.empty()));
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                runMeta(oct(27), oct(27)));

        ComingUpEntry merged = assemblerWith(calculator).assemble(BUILT,
                List.of(run, supermoon(oct(26), oct(26), oct(26)))).entries().getFirst();
        assertThat(merged.coincidence().get(1).factsLabel()).isEqualTo(nb("Mon 26 Oct · moonrise 17:22"));

        when(calculator.calculate(oct(26), 54.84, -1.33, java.time.ZoneId.of("Europe/London")))
                .thenReturn(new MoonriseMoonset(Optional.empty(), Optional.empty()));
        ComingUpEntry noRise = assemblerWith(calculator).assemble(BUILT,
                List.of(run, supermoon(oct(26), oct(26), oct(26)))).entries().getFirst();
        assertThat(noRise.coincidence().get(1).factsLabel()).isEqualTo(nb("Mon 26 Oct"));
    }

    @Test
    @DisplayName("with no representative (no derivable peak) the moonrise is never asked for")
    void noRepresentative_noMoonrise() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER))).thenReturn(Optional.empty());
        MoonriseMoonsetCalculator calculator = mock(MoonriseMoonsetCalculator.class);
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run", Map.of());

        ComingUpEntry merged = assemblerWith(calculator).assemble(BUILT,
                List.of(run, supermoon(oct(26), oct(26), oct(26)))).entries().getFirst();

        assertThat(merged.coincidence().get(1).factsLabel()).isEqualTo(nb("Mon 26 Oct"));
        assertThat(merged.coincidence().getFirst().factsLabel()).isEqualTo(nb("Sun 25 – Fri 30 Oct"));
        verifyNoInteractions(calculator);
    }

    @Test
    @DisplayName("R2: on a span tie the bits winner hosts; on a bits tie too the tide run hosts")
    void hostTies() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(26), oct(27), "spring-tide", "Spring tide run", runMeta(oct(26), null));
        AlmanacEvent moon = supermoon(oct(26), oct(27), oct(26));
        // Higher rarity for the supermoon: it wins the tie.
        assertThat(assembler.assemble(BUILT, List.of(run, moon)).entries().getFirst().type())
                .isEqualTo("supermoon");

        ComingUpScoringProperties tied = new ComingUpScoringProperties();
        tied.getRarity().setSpringTideMeanGapDays(tied.getRarity().getSupermoonMeanGapDays());
        ComingUpAssembler tiedAssembler = new ComingUpAssembler(locationRepository, tideRunBuilder,
                tideRunPeakHistory, dailyRangeHistory, tideService, tied, new MoonriseMoonsetCalculator());
        assertThat(tiedAssembler.assemble(BUILT, List.of(moon, run)).entries().getFirst().type())
                .isEqualTo("spring-tide");
    }

    @Test
    @DisplayName("a merged entry counts under every family it holds; fixed and forecast count it once")
    void mergedEntryCountsUnderEachFamily() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run", runMeta(oct(27), null));
        AlmanacEvent meteor = event(nov(10), nov(10), "meteor", "Leonids", Map.of());

        var counts = assembler.assemble(BUILT, List.of(run, supermoon(oct(26), oct(26), oct(26)), meteor))
                .counts();

        assertThat(counts.byFamily()).containsEntry("coastal", 1).containsEntry("sun-moon", 1)
                .containsEntry("night-sky", 1);
        assertThat(counts.fixed()).isEqualTo(2);
        assertThat(counts.forecast()).isZero();
    }

    private void stubRanges(LocalDate start, LocalDate end, Object... dateAndRange) {
        java.util.Map<LocalDate, Double> ranges = new java.util.LinkedHashMap<>();
        for (int i = 0; i < dateAndRange.length; i += 2) {
            ranges.put((LocalDate) dateAndRange[i], (Double) dateAndRange[i + 1]);
        }
        when(tideRunBuilder.dailyRangesAt(eq(SEAHAM), eq(datesOf(start, end)))).thenReturn(ranges);
    }

    @Test
    @DisplayName("S2: an unmerged run serves one 1-dp range per derivable day, ascending, peak true on "
            + "the true peak day only")
    void unmergedRunServesDayRanges() {
        stubRunAtSeaham();
        stubRanges(oct(25), oct(30), oct(25), 4.34, oct(26), 4.66, oct(27), 4.9, oct(28), 4.51,
                oct(29), 4.0, oct(30), 3.54);
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run", runMeta(oct(27), null));

        ComingUpEntry entry = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(entry.dayRanges()).containsExactly(
                new ComingUpDayRange(oct(25), 4.3, false, null, false),
                new ComingUpDayRange(oct(26), 4.7, false, null, false),
                new ComingUpDayRange(oct(27), 4.9, true, null, false),
                new ComingUpDayRange(oct(28), 4.5, false, null, false),
                new ComingUpDayRange(oct(29), 4.0, false, null, false),
                new ComingUpDayRange(oct(30), 3.5, false, null, false));
        assertThat(entry.dayMarks()).isEmpty();
    }

    @Test
    @DisplayName("S2: a missing middle day is absent from the served ranges, never synthesised")
    void missingMiddleDayIsAbsentFromDayRanges() {
        stubRunAtSeaham();
        stubRanges(oct(25), oct(27), oct(25), 4.3, oct(27), 4.9);
        AlmanacEvent run = event(oct(25), oct(27), "spring-tide", "Spring tide run", runMeta(oct(27), null));

        ComingUpEntry entry = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(entry.dayRanges()).extracting(ComingUpDayRange::date).containsExactly(oct(25), oct(27));
    }

    @Test
    @DisplayName("S2: fewer than two derivable days serves no ranges")
    void fewerThanTwoDerivableDaysServesNoRanges() {
        stubRunAtSeaham();
        stubRanges(oct(25), oct(27), oct(26), 4.9);
        AlmanacEvent run = event(oct(25), oct(27), "spring-tide", "Spring tide run", runMeta(oct(26), null));

        assertThat(assembler.assemble(BUILT, List.of(run)).entries().getFirst().dayRanges()).isEmpty();
    }

    @Test
    @DisplayName("S2: with no representative (no derivable peak) there are no ranges and none are fetched")
    void noRepresentativeServesNoRanges() {
        when(tideRunBuilder.peakRange(anyList(), eq(COASTAL_ROSTER))).thenReturn(Optional.empty());
        AlmanacEvent run = event(oct(25), oct(27), "spring-tide", "Spring tide run", runMeta(oct(26), null));

        assertThat(assembler.assemble(BUILT, List.of(run)).entries().getFirst().dayRanges()).isEmpty();
        verify(tideRunBuilder, never()).dailyRangesAt(any(), anyList());
    }

    @Test
    @DisplayName("S2: a failing range read degrades — the entry is still served with its metric and "
            + "tide, just without dayRanges")
    void failingRangeReadDegradesToNoDayRanges() {
        stubRunAtSeaham();
        when(tideRunBuilder.dailyRangesAt(any(), anyList())).thenThrow(new IllegalStateException("db down"));
        AlmanacEvent run = event(oct(25), oct(27), "spring-tide", "Spring tide run", runMeta(oct(26), null));

        ComingUpEntry entry = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(entry.dayRanges()).isEmpty();
        assertThat(entry.metric()).isEqualTo("4.9 m");
        assertThat(entry.type()).isEqualTo("spring-tide");
    }

    @Test
    @DisplayName("S2: ranges round exactly as the headline metric is spelt (%.1f), e.g. 8.966 - 1.516")
    void dayRangesRoundLikeTheMetric() {
        stubRunAtSeaham();
        double nearlyHalf = 8.966 - 1.516;
        stubRanges(oct(25), oct(27), oct(25), 4.35, oct(26), nearlyHalf);
        AlmanacEvent run = event(oct(25), oct(27), "spring-tide", "Spring tide run", runMeta(oct(26), null));

        ComingUpEntry entry = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(entry.dayRanges()).extracting(ComingUpDayRange::rangeMetres)
                .containsExactly(Double.parseDouble(String.format(java.util.Locale.UK, "%.1f", 4.35)),
                        Double.parseDouble(String.format(java.util.Locale.UK, "%.1f", nearlyHalf)));
        assertThat(entry.dayRanges().get(1).rangeMetres()).isEqualTo(7.4);
    }

    @Test
    @DisplayName("S2 (defensive guard — production emits partialCoverage only for a one-day run, which "
            + "has no ranges): a fixture with no peakDate crowns no day, peak is false everywhere")
    void partialCoverageRunHasNoPeakDay() {
        stubRunAtSeaham();
        stubRanges(oct(25), oct(30), oct(27), 4.9, oct(28), 4.5, oct(29), 4.0);
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                AlmanacEvent.metaOf("range", "4.9 m", "figuresFrom", oct(27).toString(),
                        "partialCoverage", "true", "location", "Seaham"));

        ComingUpEntry entry = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(entry.dayRanges()).hasSize(3).noneMatch(ComingUpDayRange::peak);
    }

    @Test
    @DisplayName("S2: a merged entry carries the run's ranges when the run hosts")
    void mergedEntryCarriesRunRangesWhenRunHosts() {
        stubRunAtSeaham();
        stubRanges(oct(25), oct(30), oct(25), 4.3, oct(26), 4.6, oct(27), 4.9);
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run", runMeta(oct(27), null));

        ComingUpEntry merged = assembler.assemble(BUILT, List.of(run, supermoon(oct(26), oct(26), oct(26))))
                .entries().getFirst();

        assertThat(merged.type()).isEqualTo("spring-tide");
        assertThat(merged.dayMarks()).isNotEmpty();
        assertThat(merged.dayRanges()).extracting(ComingUpDayRange::date)
                .containsExactly(oct(25), oct(26), oct(27));
        assertThat(merged.dayRanges()).extracting(ComingUpDayRange::peak).containsExactly(false, false, true);
    }

    @Test
    @DisplayName("S2: a merged entry carries the run's ranges when the supermoon hosts, restricted to the run")
    void mergedEntryCarriesRunRangesWhenSupermoonHosts() {
        stubRunAtSeaham();
        stubRanges(oct(26), oct(27), oct(26), 4.2, oct(27), 4.9);
        AlmanacEvent run = event(oct(26), oct(27), "spring-tide", "Spring tide run", runMeta(oct(27), null));
        AlmanacEvent moon = supermoon(oct(24), oct(30), oct(26));

        ComingUpEntry merged = assembler.assemble(BUILT, List.of(run, moon)).entries().getFirst();

        assertThat(merged.type()).isEqualTo("supermoon");
        assertThat(merged.startDate()).isEqualTo(oct(24));
        assertThat(merged.dayRanges()).containsExactly(
                new ComingUpDayRange(oct(26), 4.2, false, null, false),
                new ComingUpDayRange(oct(27), 4.9, true, null, false));
    }

    @Test
    @DisplayName("unmerged entries are exactly as before: own title, id, action, no coincidence, no dayMarks")
    void unmergedEntriesKeepTheirShape() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run", runMeta(oct(27), oct(27)));
        AlmanacEvent moon = supermoon(nov(24), nov(24), nov(24));

        ComingUpResponse response = assembler.assemble(BUILT, List.of(moon, run));

        ComingUpEntry runEntry = response.entries().get(0);
        assertThat(runEntry.title()).isEqualTo("Spring tide run");
        assertThat(runEntry.id()).isEqualTo("spring-tide:2026-10-25:2026-10-30");
        assertThat(runEntry.startDate()).isEqualTo(oct(25));
        assertThat(runEntry.action().label()).isEqualTo("Show coastal spots for 27 Oct →");
        assertThat(runEntry.coincidence()).isEmpty();
        assertThat(runEntry.dayMarks()).isEmpty();
        assertThat(runEntry.joinNote()).isNull();
        // Unmerged or merged, the event row carries its date.
        assertThat(factTexts(runEntry)).containsExactly("range 4.9 m at Seaham", SUNSET_ROW_ON_27);

        ComingUpEntry moonEntry = response.entries().get(1);
        assertThat(moonEntry.title()).isEqualTo("Supermoon");
        assertThat(moonEntry.id()).isEqualTo("supermoon:2026-11-24:2026-11-24");
        assertThat(moonEntry.action().kind()).isEqualTo("dark-sky-spots");
        assertThat(moonEntry.coincidence()).isEmpty();
        assertThat(moonEntry.dayMarks()).isEmpty();
        assertThat(factTexts(moonEntry)).containsExactly("peaks 24 Nov");
    }

    // ── Plan-owned partners (supermoon-tide-run-plan.md §4.7) ────────────

    @Test
    @DisplayName("§4.7: a supermoon inside Plan's window, supplied as a partner only, is marked on the "
            + "eligible run that overlaps it — one merged card, not a run with no mark")
    void planWindowSupermoonMergesIntoAnEligibleRun() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run", runMeta(oct(27), oct(27)));

        ComingUpResponse response = assembler.assemble(BUILT, List.of(run),
                List.of(supermoon(oct(26), oct(26), oct(26))));

        assertThat(response.entries()).hasSize(1);
        assertThat(response.entries().getFirst().type()).isEqualTo("spring-tide");
        assertThat(response.entries().getFirst().dayMarks()).contains(
                new ComingUpDayMark(oct(26), "full moon", "sun-moon"));
    }

    @Test
    @DisplayName("§4.7: a Plan-window run partnered with an eligible supermoon merges, the longer run hosting")
    void planWindowRunMergesIntoAnEligibleSupermoon() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(23), oct(26), "spring-tide", "Spring tide run", runMeta(oct(24), oct(24)));

        ComingUpResponse response = assembler.assemble(BUILT, List.of(supermoon(oct(26), oct(27), oct(26))),
                List.of(run));

        assertThat(response.entries()).hasSize(1);
        ComingUpEntry merged = response.entries().getFirst();
        assertThat(merged.type()).isEqualTo("spring-tide");
        assertThat(merged.startDate()).isEqualTo(oct(23));
        assertThat(merged.endDate()).isEqualTo(oct(27));
    }

    @Test
    @DisplayName("§4.7: an unpaired partner-only event is dropped; two partner-only events never pair; "
            + "and a partner-only run never joins the superlative comparison set")
    void partnersOnlyAreNeverEntriesOfTheirOwn() {
        when(tideRunBuilder.peakRange(eq(datesOf(oct(25), oct(30))), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 4.0)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.empty());
        AlmanacEvent eligible = event(oct(25), oct(30), "spring-tide", "Spring tide run", Map.of());
        AlmanacEvent planRun = event(oct(1), oct(4), "spring-tide", "Spring tide run", Map.of());
        AlmanacEvent planMoon = supermoon(oct(2), oct(2), oct(2));
        AlmanacEvent loneMoon = supermoon(oct(10), oct(10), oct(10));

        ComingUpResponse response = assembler.assemble(BUILT, List.of(eligible),
                List.of(planRun, planMoon, loneMoon));

        // planRun and planMoon overlap but are both partner-only: neither pairs, neither is listed.
        assertThat(response.entries()).hasSize(1);
        ComingUpEntry only = response.entries().getFirst();
        assertThat(only.id()).isEqualTo("spring-tide:2026-10-25:2026-10-30");
        // The Plan-window run is not in the comparison set: no threshold, no superlative.
        assertThat(only.threshold()).isNull();
        assertThat(only.superlative()).isNull();
        // And, unpaired, it was never enriched: no repository read for its dates.
        verify(tideRunBuilder, never()).peakRange(eq(datesOf(oct(1), oct(4))), eq(COASTAL_ROSTER));
    }

    @Test
    @DisplayName("§4.7: a paired partner whose enrichment throws is dropped with a warning — the "
            + "eligible event is still served, unmerged, and the build succeeds")
    void aPartnerWhoseEnrichmentThrowsNeverFailsTheFeed() {
        when(tideRunBuilder.peakRange(eq(datesOf(oct(23), oct(26))), eq(COASTAL_ROSTER)))
                .thenThrow(new IllegalStateException("db down"));
        AlmanacEvent planRun = event(oct(23), oct(26), "spring-tide", "Spring tide run", runMeta(oct(24), oct(24)));

        ComingUpResponse response = assembler.assemble(BUILT,
                List.of(supermoon(oct(26), oct(27), oct(26))), List.of(planRun));

        assertThat(response.entries()).hasSize(1);
        assertThat(response.entries().getFirst().type()).isEqualTo("supermoon");
        assertThat(response.entries().getFirst().coincidence()).isEmpty();
    }

    @Test
    @DisplayName("first-of-type prose goes to the EARLIEST card of its type even when that card is hosted "
            + "by a Plan-owned partner, which is staged after every eligible event")
    void proseGoesToTheEarliestCardEvenWhenAPartnerHostsIt() {
        stubRunAtSeaham();
        AlmanacEvent planRun = event(oct(23), oct(26), "spring-tide", "Spring tide run", runMeta(oct(24), oct(24)));
        AlmanacEvent laterRun = event(nov(5), nov(8), "spring-tide", "Spring tide run", runMeta(nov(6), nov(6)));

        ComingUpResponse response = assembler.assemble(BUILT,
                List.of(supermoon(oct(26), oct(27), oct(26)), laterRun), List.of(planRun));

        assertThat(response.entries()).hasSize(2);
        ComingUpEntry merged = response.entries().get(0);
        ComingUpEntry later = response.entries().get(1);
        assertThat(merged.startDate()).isEqualTo(oct(23));
        assertThat(merged.prose()).isEqualTo("detail");
        assertThat(later.prose()).isNull();
    }

    @Test
    @DisplayName("a partner-only event that can never pair is not even staged: its enrichment (which "
            + "reads the scoring knobs) never runs, so a failure there cannot fail the feed")
    void nonPairablePartnerIsNeverStaged() {
        ComingUpScoringProperties properties = mock(ComingUpScoringProperties.class);
        when(properties.getBands()).thenReturn(new ComingUpScoringProperties().getBands());
        ComingUpAssembler guarded = new ComingUpAssembler(locationRepository, tideRunBuilder,
                tideRunPeakHistory, dailyRangeHistory, tideService, properties, new MoonriseMoonsetCalculator());

        ComingUpResponse response = guarded.assemble(BUILT, List.of(), List.of(
                event(oct(21), oct(21), "meteor", "Perseids", Map.of()),
                event(oct(22), oct(22), "lunar-eclipse", "Eclipse", Map.of())));

        assertThat(response.entries()).isEmpty();
        verify(properties, never()).getRarity();
    }

    @Test
    @DisplayName("'biggest until X' names the date the later run's CARD shows — the union start when "
            + "that run is merged with a supermoon that begins a day earlier")
    void superlativeNamesTheMergedCardsDate() {
        when(tideRunBuilder.peakRange(eq(datesOf(nov(1), nov(4))), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 4.0)));
        when(tideRunBuilder.peakRange(eq(datesOf(nov(20), nov(23))), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 6.0)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.empty());
        AlmanacEvent a = event(nov(1), nov(4), "spring-tide", "Spring tide run", Map.of());
        AlmanacEvent b = event(nov(20), nov(23), "spring-tide", "Spring tide run", Map.of());

        ComingUpResponse response = assembler.assemble(BUILT, List.of(a, b, supermoon(nov(19), nov(20), nov(19))));

        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries().get(1).startDate()).isEqualTo(nov(19));
        assertThat(response.entries().get(0).superlative()).isEqualTo("biggest until 19 Nov");
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

    // ── R1: rank against the year, one average per card ─────────────────────────

    private static List<LocalDate> yearBefore(LocalDate built) {
        List<LocalDate> dates = new java.util.ArrayList<>();
        for (int back = 365; back >= 1; back--) {
            dates.add(built.minusDays(back));
        }
        return dates;
    }

    private void stubYearOf(java.util.Map<LocalDate, Double> year) {
        doReturn(year).when(tideRunBuilder).dailyRangesAt(eq(SEAHAM), eq(yearBefore(BUILT)));
    }

    /** Stubs the year read: {@code bigDays} days at 5.0, {@code nearDays} at 4.9, the rest at 3.4. */
    private void stubYear(int days, int bigDays, int nearDays) {
        java.util.Map<LocalDate, Double> year = new java.util.LinkedHashMap<>();
        List<LocalDate> dates = yearBefore(BUILT);
        for (int i = 0; i < days; i++) {
            double v = i < bigDays ? 5.0 : i < bigDays + nearDays ? 4.9 : 3.4;
            year.put(dates.get(i), v);
        }
        stubYearOf(year);
    }

    @Test
    @DisplayName("R1: every day carries its top-N% and the run the year's average; the run's delta is "
            + "measured against that average, not TideStats")
    void daysRankedAgainstTheYearAndDeltaUsesTheYearAverage() {
        stubRunAtSeaham();
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.of(stats("2.0")));
        stubRanges(oct(25), oct(27), oct(25), 4.3, oct(26), 4.9, oct(27), 5.2);
        stubYear(365, 5, 15);
        AlmanacEvent run = event(oct(25), oct(27), "spring-tide", "Spring tide run", runMeta(oct(27), null));

        ComingUpEntry entry = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        // 5.2 is above every sample day -> top 1%; 4.9 and 4.3 are matched by 20 of 365 -> ceil(5.48) = 6
        assertThat(entry.dayRanges()).extracting(ComingUpDayRange::rangePercentile).containsExactly(6, 6, 1);
        // mean = (5 * 5.0 + 15 * 4.9 + 345 * 3.4) / 365 = 3.48 -> 3.5
        assertThat(entry.rangeAverageMetres()).isEqualTo(3.5);
        // delta = displayed headline range 4.9 - 3.5, not 4.9 - 2.0 from TideStats
        assertThat(entry.tide().delta()).isEqualTo(1.4);
    }

    @Test
    @DisplayName("R1: ranking is in display space - 40 samples of 6.05 - 1.20 (4.85, displays 4.9) count as "
            + "at least a 4.9 day")
    void rankingUsesDisplayedValues() {
        stubRunAtSeaham();
        stubRanges(oct(25), oct(26), oct(25), 3.5, oct(26), 4.9);
        java.util.Map<LocalDate, Double> year = new java.util.LinkedHashMap<>();
        List<LocalDate> dates = yearBefore(BUILT);
        for (int i = 0; i < dates.size(); i++) {
            year.put(dates.get(i), i < 40 ? 6.05 - 1.20 : 3.0);
        }
        stubYearOf(year);
        AlmanacEvent run = event(oct(25), oct(26), "spring-tide", "Spring tide run", runMeta(oct(26), null));

        ComingUpEntry entry = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        // 40 of 365 -> ceil(10.96) = 11; a raw comparison (4.85 < 4.9) would have found none -> 1
        assertThat(entry.dayRanges().get(1).rangePercentile()).isEqualTo(11);
        assertThat(entry.dayRanges().get(0).rangePercentile()).isEqualTo(11);
    }

    @Test
    @DisplayName("R1: a year sample of 299 days serves neither a percentile nor an average; 300 serves both")
    void thinYearSampleServesNothing() {
        stubRunAtSeaham();
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.of(stats("3.3")));
        stubRanges(oct(25), oct(26), oct(25), 4.0, oct(26), 4.9);
        stubYear(299, 0, 0);
        AlmanacEvent run = event(oct(25), oct(26), "spring-tide", "Spring tide run", runMeta(oct(26), null));

        ComingUpEntry thin = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(thin.rangeAverageMetres()).isNull();
        assertThat(thin.dayRanges()).extracting(ComingUpDayRange::rangePercentile).containsOnlyNulls();
        // the TideStats figure still drives the delta
        assertThat(thin.tide().delta()).isEqualTo(1.6);

        dailyRangeHistory.evict();
        stubYear(300, 0, 0);
        ComingUpEntry enough = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(enough.rangeAverageMetres()).isEqualTo(3.4);
        assertThat(enough.dayRanges()).extracting(ComingUpDayRange::rangePercentile).containsExactly(1, 1);
    }

    @Test
    @DisplayName("R1: a run with no day ranges reads no year at all")
    void noDayRangesSkipsTheYearRead() {
        stubRunAtSeaham();
        AlmanacEvent run = event(oct(25), oct(27), "spring-tide", "Spring tide run", runMeta(oct(26), null));

        ComingUpEntry entry = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(entry.rangeAverageMetres()).isNull();
        verify(tideRunBuilder, never()).dailyRangesAt(any(), eq(yearBefore(BUILT)));
    }

    @Test
    @DisplayName("R1: a failing year read degrades to no percentile and no average, and is not cached")
    void failingYearReadDegradesAndIsNotCached() {
        stubRunAtSeaham();
        stubRanges(oct(25), oct(26), oct(25), 4.0, oct(26), 4.9);
        doThrow(new IllegalStateException("db down")).when(tideRunBuilder)
                .dailyRangesAt(eq(SEAHAM), eq(yearBefore(BUILT)));
        AlmanacEvent run = event(oct(25), oct(26), "spring-tide", "Spring tide run", runMeta(oct(26), null));

        ComingUpEntry failed = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(failed.rangeAverageMetres()).isNull();
        assertThat(failed.dayRanges()).hasSize(2);
        assertThat(failed.dayRanges()).extracting(ComingUpDayRange::rangePercentile).containsOnlyNulls();

        stubYear(365, 0, 0);
        ComingUpEntry recovered = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(recovered.rangeAverageMetres()).isEqualTo(3.4);
    }

    @Test
    @DisplayName("R1: a merged entry carries the ranked days and the average whichever topic hosts, and the "
            + "tide coincidence line reads 'top N% of the year' in place of the metres")
    void mergedEntryCarriesRankAndCoincidenceLineReadsIt() {
        stubRunAtSeaham();
        stubRanges(oct(25), oct(30), oct(25), 4.3, oct(26), 4.6, oct(27), 5.2);
        stubYear(365, 5, 15);
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run", runMeta(oct(27), oct(27)));

        ComingUpEntry runHosts = assembler.assemble(BUILT, List.of(run, supermoon(oct(26), oct(26), oct(26))))
                .entries().getFirst();

        assertThat(runHosts.type()).isEqualTo("spring-tide");
        assertThat(runHosts.rangeAverageMetres()).isEqualTo(3.5);
        assertThat(runHosts.dayRanges()).extracting(ComingUpDayRange::rangePercentile).containsExactly(6, 6, 1);
        assertThat(runHosts.coincidence().get(0).factsLabel())
                .isEqualTo(nb("Sun 25 – Fri 30 Oct · biggest Tue 27 · top 1% of the year · HW 15:48"));

        ComingUpEntry moonHosts = assembler.assemble(BUILT, List.of(run, supermoon(oct(24), oct(30), oct(26))))
                .entries().getFirst();

        assertThat(moonHosts.type()).isEqualTo("supermoon");
        assertThat(moonHosts.rangeAverageMetres()).isEqualTo(3.5);
        assertThat(moonHosts.dayRanges()).extracting(ComingUpDayRange::rangePercentile)
                .containsExactly(6, 6, 1);
        assertThat(moonHosts.coincidence()).extracting(c -> c.factsLabel())
                .anyMatch(l -> l.contains(nb("top 1% of the year")));
    }

    // ── P3: shoulder days beside a run (tide-source-integrity-plan.md) ──────────

    private static Map<String, String> withShoulders(Map<String, String> meta, LocalDate... shoulders) {
        Map<String, String> out = new java.util.HashMap<>(meta);
        out.put("shoulderDates", java.util.Arrays.stream(shoulders).map(LocalDate::toString)
                .collect(java.util.stream.Collectors.joining(",")));
        return out;
    }

    private void stubRangesOver(List<LocalDate> wanted, Object... dateAndRange) {
        java.util.Map<LocalDate, Double> ranges = new java.util.LinkedHashMap<>();
        for (int i = 0; i < dateAndRange.length; i += 2) {
            ranges.put((LocalDate) dateAndRange[i], (Double) dateAndRange[i + 1]);
        }
        when(tideRunBuilder.dailyRangesAt(eq(SEAHAM), eq(wanted))).thenReturn(ranges);
    }

    @Test
    @DisplayName("P3: shoulders are served flagged, ranked like the rest, never the peak, and the run's "
            + "own dates and headline are untouched even when a shoulder is the tallest bar")
    void shouldersAreServedFlaggedAndNeverPeak() {
        stubRunAtSeaham();
        stubRangesOver(datesOf(oct(24), oct(28)), oct(24), 5.4, oct(25), 4.3, oct(26), 4.6, oct(27), 4.9,
                oct(28), 4.4);
        AlmanacEvent run = event(oct(25), oct(27), "spring-tide", "Spring tide run",
                withShoulders(runMeta(oct(27), null), oct(24), oct(28)));

        ComingUpEntry entry = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(entry.startDate()).isEqualTo(oct(25));
        assertThat(entry.endDate()).isEqualTo(oct(27));
        assertThat(entry.dayRanges()).extracting(ComingUpDayRange::date)
                .containsExactly(oct(24), oct(25), oct(26), oct(27), oct(28));
        assertThat(entry.dayRanges()).extracting(ComingUpDayRange::shoulder)
                .containsExactly(true, false, false, false, true);
        assertThat(entry.dayRanges()).extracting(ComingUpDayRange::peak)
                .containsExactly(false, false, false, true, false);
        // The shoulder (5.4 m) is taller than the run's peak (4.9 m): accepted, and the peak label
        // stays the run's.
        assertThat(entry.dayRanges().getFirst().rangeMetres()).isGreaterThan(4.9);
    }

    @Test
    @DisplayName("P3: shoulderDates is build-time only — it is never served in meta")
    void shoulderDatesAreNotServed() {
        stubRunAtSeaham();
        stubRangesOver(datesOf(oct(24), oct(28)), oct(24), 4.4, oct(25), 4.3, oct(26), 4.6, oct(27), 4.9,
                oct(28), 4.4);
        AlmanacEvent run = event(oct(25), oct(27), "spring-tide", "Spring tide run",
                withShoulders(runMeta(oct(27), null), oct(24), oct(28)));

        ComingUpEntry entry = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(entry.meta()).doesNotContainKey("shoulderDates");
    }

    @Test
    @DisplayName("P3: a shoulder the representative cannot derive is dropped, not invented")
    void anUnderivableShoulderIsDropped() {
        stubRunAtSeaham();
        stubRangesOver(datesOf(oct(24), oct(28)), oct(25), 4.3, oct(26), 4.6, oct(27), 4.9, oct(28), 4.4);
        AlmanacEvent run = event(oct(25), oct(27), "spring-tide", "Spring tide run",
                withShoulders(runMeta(oct(27), null), oct(24), oct(28)));

        ComingUpEntry entry = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(entry.dayRanges()).extracting(ComingUpDayRange::date)
                .containsExactly(oct(25), oct(26), oct(27), oct(28));
        assertThat(entry.dayRanges()).extracting(ComingUpDayRange::shoulder)
                .containsExactly(false, false, false, true);
    }

    @Test
    @DisplayName("P3: the fewer-than-two rule counts run days, so shoulders cannot make up the numbers")
    void shouldersDoNotSatisfyTheTwoDayRule() {
        stubRunAtSeaham();
        stubRangesOver(datesOf(oct(26), oct(29)), oct(26), 4.5, oct(27), 4.9, oct(29), 4.4);
        AlmanacEvent run = event(oct(27), oct(28), "spring-tide", "Spring tide run",
                withShoulders(runMeta(oct(27), null), oct(26), oct(29)));

        ComingUpEntry entry = assembler.assemble(BUILT, List.of(run)).entries().getFirst();

        assertThat(entry.dayRanges()).isEmpty();
    }

    @Test
    @DisplayName("P3: ranking keeps the shoulder flag, and the coincidence rank label reads the run's peak "
            + "even when a shoulder ranks higher")
    void rankingKeepsTheFlagAndPeakRankIgnoresShoulders() {
        stubRunAtSeaham();
        stubRangesOver(datesOf(oct(24), oct(27)), oct(24), 5.4, oct(25), 4.3, oct(26), 4.6, oct(27), 4.9);
        stubYear(365, 5, 15);
        AlmanacEvent run = event(oct(25), oct(27), "spring-tide", "Spring tide run",
                withShoulders(runMeta(oct(27), oct(27)), oct(24)));

        ComingUpEntry entry = assembler.assemble(BUILT, List.of(run, supermoon(oct(26), oct(26), oct(26))))
                .entries().getFirst();

        assertThat(entry.dayRanges()).extracting(ComingUpDayRange::shoulder)
                .containsExactly(true, false, false, false);
        assertThat(entry.dayRanges()).extracting(ComingUpDayRange::rangePercentile)
                .containsExactly(1, 6, 6, 6);
        assertThat(entry.coincidence().get(0).factsLabel()).contains(nb("top 6% of the year"));
    }

    @Test
    @DisplayName("P3: a merged entry keeps the flags whether the shoulder lies outside or inside the "
            + "union span")
    void mergeKeepsTheShoulderFlags() {
        stubRunAtSeaham();
        stubRangesOver(datesOf(oct(25), oct(28)), oct(25), 4.4, oct(26), 4.2, oct(27), 4.9, oct(28), 4.4);
        AlmanacEvent run = event(oct(26), oct(27), "spring-tide", "Spring tide run",
                withShoulders(runMeta(oct(27), null), oct(25), oct(28)));

        // Supermoon hosts a wider span: both shoulders lie inside the union.
        ComingUpEntry inside = assembler.assemble(BUILT, List.of(run, supermoon(oct(24), oct(30), oct(26))))
                .entries().getFirst();
        assertThat(inside.type()).isEqualTo("supermoon");
        assertThat(inside.dayRanges()).extracting(ComingUpDayRange::shoulder)
                .containsExactly(true, false, false, true);

        // The run hosts a narrower union: both shoulders lie outside it.
        ComingUpEntry outside = assembler.assemble(BUILT, List.of(run, supermoon(oct(27), oct(27), oct(27))))
                .entries().getFirst();
        assertThat(outside.startDate()).isEqualTo(oct(26));
        assertThat(outside.endDate()).isEqualTo(oct(27));
        assertThat(outside.dayRanges()).extracting(ComingUpDayRange::shoulder)
                .containsExactly(true, false, false, true);
    }

    @Test
    @DisplayName("R1: the partial-coverage branch keeps its metres even when percentiles are served")
    void partialCoverageBranchKeepsItsMetres() {
        stubRunAtSeaham();
        stubRanges(oct(25), oct(30), oct(27), 4.9, oct(28), 4.5, oct(29), 4.0);
        stubYear(365, 5, 15);
        AlmanacEvent partial = event(oct(25), oct(30), "spring-tide", "Spring tide run",
                AlmanacEvent.metaOf("range", "4.9 m", "figuresFrom", oct(27).toString(),
                        "partialCoverage", "true", "location", "Seaham"));

        ComingUpEntry merged = assembler.assemble(BUILT, List.of(partial, supermoon(oct(26), oct(26), oct(26))))
                .entries().getFirst();

        assertThat(merged.dayRanges()).extracting(ComingUpDayRange::rangePercentile).isNotEmpty()
                .doesNotContainNull();
        assertThat(merged.coincidence().get(0).factsLabel()).contains(nb("4.9 m on Tue 27"))
                .doesNotContain("of the year");
    }

    @Test
    @DisplayName("R1: with no percentile the coincidence line keeps the metres")
    void coincidenceLineKeepsMetresWithoutAPercentile() {
        stubRunAtSeaham();
        stubRanges(oct(25), oct(30), oct(27), 4.9, oct(28), 4.5, oct(29), 4.0);
        AlmanacEvent run = event(oct(25), oct(30), "spring-tide", "Spring tide run", runMeta(oct(27), null));

        ComingUpEntry merged = assembler.assemble(BUILT, List.of(run, supermoon(oct(26), oct(26), oct(26))))
                .entries().getFirst();

        assertThat(merged.coincidence().get(0).factsLabel()).contains(nb("biggest Tue 27 · 4.9 m"))
                .doesNotContain("of the year");
    }
}
