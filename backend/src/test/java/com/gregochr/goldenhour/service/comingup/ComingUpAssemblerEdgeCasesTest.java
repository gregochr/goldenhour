package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpFact;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.TideRunBuilder;
import com.gregochr.goldenhour.service.TideService;
import com.gregochr.goldenhour.service.TideSizeIndex;
import com.gregochr.solarutils.MoonriseMoonsetCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Boundary, ordering and fact-row edge cases of {@link ComingUpAssembler}, added to kill surviving
 * mutants: every fact-row builder's present/absent branches, the merge pairing rules, the
 * superlative and score-note thresholds, and the date fallbacks.
 */
@ExtendWith(MockitoExtension.class)
class ComingUpAssemblerEdgeCasesTest {

    // A far-future date, so a "reads the real clock" regression cannot match it (test standards §6).
    private static final LocalDate DAY = LocalDate.of(2027, 9, 10);
    private static final LocationEntity SEAHAM =
            LocationEntity.builder().id(1L).name("Seaham").lat(54.84).lon(-1.33).build();
    private static final List<LocationEntity> COASTAL_ROSTER = List.of(SEAHAM);
    private static final String NBSP = " ";
    private static final String SEP = " · ";

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private TideRunBuilder tideRunBuilder;

    @Mock
    private TideRunPeakHistory tideRunPeakHistory;

    @Mock
    private TideService tideService;

    private ComingUpScoringProperties properties;
    private DailyRangeHistory dailyRangeHistory;
    private ComingUpAssembler assembler;

    @BeforeEach
    void setUp() {
        properties = new ComingUpScoringProperties();
        dailyRangeHistory = new DailyRangeHistory(tideRunBuilder, mock(TideSizeIndex.class));
        assembler = new ComingUpAssembler(locationRepository, tideRunBuilder, tideRunPeakHistory,
                dailyRangeHistory, tideService, properties, new MoonriseMoonsetCalculator());
        when(locationRepository.findCoastalLocations()).thenReturn(COASTAL_ROSTER);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static AlmanacEvent event(LocalDate start, LocalDate end, String type, String title,
            Map<String, String> meta) {
        return new AlmanacEvent(start, end, AlmanacKind.ALMANAC, type, title, "detail", meta, List.of());
    }

    private static AlmanacEvent event(String type, Map<String, String> meta) {
        return event(DAY, DAY, type, "Title", meta);
    }

    private static AlmanacEvent withRegions(AlmanacEvent e, String... regions) {
        return new AlmanacEvent(e.startDate(), e.endDate(), e.kind(), e.type(), e.title(), e.detail(),
                e.meta(), List.of(regions));
    }

    private static List<String> factTexts(ComingUpEntry entry) {
        return entry.facts().stream()
                .map(f -> f.segments().stream().map(ComingUpFact.Segment::text).reduce("", String::concat))
                .toList();
    }

    private ComingUpEntry only(AlmanacEvent event) {
        ComingUpResponse response = assembler.assemble(DAY, List.of(event));
        assertThat(response.entries()).hasSize(1);
        return response.entries().getFirst();
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

    private void stubRange(LocalDate start, LocalDate end, double range) {
        when(tideRunBuilder.peakRange(eq(datesOf(start, end)), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, range)));
    }

    /** Stubs a 4.9 m peak at Seaham for exactly the run's own dates, with no stored statistics. */
    private void stubRunAtSeaham(LocalDate start, LocalDate end) {
        when(tideRunBuilder.peakRange(eq(datesOf(start, end)), eq(COASTAL_ROSTER)))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 4.9)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.empty());
    }

    // ── meteor fact rows ────────────────────────────────────────────────────

    @Test
    @DisplayName("meteor: radiant and best hours join into one row with a ' · ' between them, tones intact")
    void meteorRadiantAndBestHoursShareOneRow() {
        ComingUpEntry entry = only(event("meteor", Map.of("radiant", "Perseus", "bestHours", "after midnight")));

        assertThat(factTexts(entry)).containsExactly("radiates from the Perseus · best after midnight");
        assertThat(entry.facts().getFirst().segments()).extracting(ComingUpFact.Segment::tone)
                .containsExactly("base", "strong", "base", "base");
    }

    @Test
    @DisplayName("meteor: the metric comes from ukRate when present, else the ZHR; the low-radiant fact "
            + "names the catalogue rate; ukRate, lowRadiant and minor are stripped from meta")
    void meteorLowRadiantMetricFactAndStrippedKeys() {
        ComingUpEntry entry = only(event("meteor", Map.of("zhr", "50", "ukRate", "5", "lowRadiant", "true",
                "minor", "true", "radiant", "E")));

        assertThat(entry.metric()).isEqualTo("~5/hr");
        assertThat(factTexts(entry)).containsExactly("radiates from the E",
                "radiant low from the UK — catalogue rate ~50/hr");
        assertThat(entry.facts().get(1).segments()).extracting(ComingUpFact.Segment::tone)
                .containsExactly("accent");
        assertThat(entry.meta()).doesNotContainKeys("ukRate", "lowRadiant", "minor", "zhr");
        assertThat(only(event("meteor", Map.of("zhr", "150"))).metric()).isEqualTo("~150/hr");
        assertThat(factTexts(only(event("meteor", Map.of("zhr", "150", "lowRadiant", "false")))))
                .isEmpty();
    }

    @Test
    @DisplayName("meteor: a minor shower scores under the announce edge and states no frequency; a major "
            + "shower still clears it")
    void minorMeteorScoresBelowAnnounce() {
        ComingUpEntry minor = only(event("meteor", Map.of("zhr", "5", "minor", "true")));
        ComingUpEntry major = only(event("meteor", Map.of("zhr", "15")));

        assertThat(minor.bits()).isCloseTo(6.9, org.assertj.core.data.Offset.offset(0.05))
                .isLessThan(properties.getBands().getAnnounce());
        assertThat(major.bits()).isGreaterThan(properties.getBands().getAnnounce());
    }

    @Test
    @DisplayName("meteor: in one window a minor shower never takes the first-of-type prose; the first major does")
    void minorMeteorNeverTakesFirstOfType() {
        AlmanacEvent taurids = event(DAY, DAY, "meteor", "N. Taurids", Map.of("minor", "true"));
        AlmanacEvent leonids = event(DAY.plusDays(5), DAY.plusDays(5), "meteor", "Leonids", Map.of());
        AlmanacEvent geminids = event(DAY.plusDays(30), DAY.plusDays(30), "meteor", "Geminids", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(taurids, leonids, geminids));

        assertThat(response.entries()).extracting(ComingUpEntry::title)
                .containsExactly("N. Taurids", "Leonids", "Geminids");
        assertThat(response.entries().get(0).prose()).isNull();
        assertThat(response.entries().get(1).prose()).isEqualTo("detail");
        assertThat(response.entries().get(2).prose()).isNull();
    }

    @Test
    @DisplayName("meteor: radiant alone has no trailing separator")
    void meteorRadiantAlone() {
        assertThat(factTexts(only(event("meteor", Map.of("radiant", "Perseus")))))
                .containsExactly("radiates from the Perseus");
    }

    @Test
    @DisplayName("meteor: best hours alone has no leading separator")
    void meteorBestHoursAlone() {
        assertThat(factTexts(only(event("meteor", Map.of("bestHours", "after midnight")))))
                .containsExactly("best after midnight");
    }

    @Test
    @DisplayName("meteor: no radiant, hours, washout or regions means no fact rows and no metric")
    void meteorWithNothingHasNoFactsOrMetric() {
        ComingUpEntry entry = only(event("meteor", Map.of()));

        assertThat(entry.facts()).isEmpty();
        assertThat(entry.metric()).isNull();
    }

    @Test
    @DisplayName("meteor: washedOut=true adds the moon row naming the illumination; anything else adds none")
    void meteorWashedOutRow() {
        assertThat(factTexts(only(event("meteor", Map.of("washedOut", "true", "moonIllumination", "85%")))))
                .containsExactly("moon 85% lit — washes out all but the brightest");
        assertThat(only(event("meteor", Map.of("washedOut", "false", "moonIllumination", "85%"))).facts())
                .isEmpty();
    }

    @Test
    @DisplayName("meteor: a washed-out row with no or blank illumination reads 'bright moon', never a double space")
    void meteorWashedOutRowWithoutIllumination() {
        assertThat(factTexts(only(event("meteor", Map.of("washedOut", "true")))))
                .containsExactly("bright moon — washes out all but the brightest");
        assertThat(factTexts(only(event("meteor", Map.of("washedOut", "true", "moonIllumination", "  ")))))
                .containsExactly("bright moon — washes out all but the brightest");
    }

    @Test
    @DisplayName("meteor: a regional event gets the 'applies to' row after the others")
    void meteorRegionRow() {
        ComingUpEntry entry = only(withRegions(event("meteor", Map.of("radiant", "Perseus")),
                "North East", "Lake District"));

        assertThat(factTexts(entry)).containsExactly("radiates from the Perseus",
                "applies to North East, Lake District");
    }

    // ── solar eclipse fact rows ─────────────────────────────────────────────

    @Test
    @DisplayName("eclipse: coverage, maximum, rarity and location each become their own row, in that order")
    void eclipseFullFactRows() {
        ComingUpEntry entry = only(event("eclipse", Map.of("coverage", "62% covered",
                "maximum", "10:12 · sun 30° up SE", "rarity", "The first here in 20 years",
                "location", "Keswick")));

        assertThat(factTexts(entry)).containsExactly("62% covered · maximum 10:12 · sun 30° up SE",
                "The first here in 20 years", "figures for Keswick");
        assertThat(entry.facts().get(1).segments().getFirst().tone()).isEqualTo("accent");
        assertThat(entry.facts().get(2).segments()).extracting(ComingUpFact.Segment::tone)
                .containsExactly("base", "strong");
    }

    @Test
    @DisplayName("eclipse: maximum alone has no leading separator, coverage alone no trailing one")
    void eclipseCoverageOrMaximumAlone() {
        assertThat(factTexts(only(event("eclipse", Map.of("maximum", "10:12"))))).containsExactly("maximum 10:12");
        assertThat(factTexts(only(event("eclipse", Map.of("coverage", "62% covered")))))
                .containsExactly("62% covered");
    }

    @Test
    @DisplayName("eclipse: rarity alone and location alone each yield exactly one row; none yields none")
    void eclipseRarityAndLocationIndependently() {
        assertThat(factTexts(only(event("eclipse", Map.of("rarity", "Rare"))))).containsExactly("Rare");
        assertThat(factTexts(only(event("eclipse", Map.of("location", "Keswick")))))
                .containsExactly("figures for Keswick");
        ComingUpEntry bare = only(event("eclipse", Map.of()));
        assertThat(bare.facts()).isEmpty();
        assertThat(bare.metric()).isNull();
    }

    // ── lunar eclipse fact rows ─────────────────────────────────────────────

    @Test
    @DisplayName("lunar eclipse: magnitude+maximum, shadow, next and location rows, in that order")
    void lunarEclipseFullFactRows() {
        ComingUpEntry entry = only(event("lunar-eclipse", Map.of("magnitude", "93% in shadow",
                "maximum", "05:12", "shadow", "in shadow 03:33 → 06:52", "next", "Next: Wed 12 Jan 2028",
                "location", "Bamburgh")));

        assertThat(factTexts(entry)).containsExactly("93% in shadow · maximum 05:12",
                "in shadow 03:33 → 06:52", "Next: Wed 12 Jan 2028", "figures for Bamburgh");
        assertThat(entry.facts().get(1).segments().getFirst().tone()).isEqualTo("base");
        assertThat(entry.facts().get(2).segments().getFirst().tone()).isEqualTo("accent");
    }

    @Test
    @DisplayName("lunar eclipse: each key alone produces just its own row, with no stray separator")
    void lunarEclipseKeysAlone() {
        assertThat(factTexts(only(event("lunar-eclipse", Map.of("magnitude", "93% in shadow")))))
                .containsExactly("93% in shadow");
        assertThat(factTexts(only(event("lunar-eclipse", Map.of("maximum", "05:12")))))
                .containsExactly("maximum 05:12");
        assertThat(factTexts(only(event("lunar-eclipse", Map.of("shadow", "in shadow 03:33")))))
                .containsExactly("in shadow 03:33");
        assertThat(factTexts(only(event("lunar-eclipse", Map.of("next", "Next: 2028")))))
                .containsExactly("Next: 2028");
        assertThat(factTexts(only(event("lunar-eclipse", Map.of("location", "Bamburgh")))))
                .containsExactly("figures for Bamburgh");
        assertThat(only(event("lunar-eclipse", Map.of())).facts()).isEmpty();
    }

    // ── region scope rows on the other types ────────────────────────────────

    @Test
    @DisplayName("a regional NLC season, equinox and unrecognised type each gain the 'applies to' row")
    void regionRowOnNlcSolarAndUnknown() {
        assertThat(factTexts(only(withRegions(event("nlc-season", Map.of()), "Scotland"))))
                .containsExactly("applies to Scotland");
        assertThat(factTexts(only(withRegions(event("equinox", Map.of()), "Scotland"))))
                .containsExactly("falls " + fmt(DAY), "applies to Scotland");
        assertThat(factTexts(only(withRegions(event("mystery-topic", Map.of()), "Scotland"))))
                .containsExactly("applies to Scotland");
    }

    @Test
    @DisplayName("a regional standalone supermoon lists 'peaks' first, then the region")
    void regionRowOnSupermoon() {
        assertThat(factTexts(only(withRegions(event("supermoon", Map.of()), "Scotland"))))
                .containsExactly("peaks " + fmt(DAY), "applies to Scotland");
    }

    @Test
    @DisplayName("a regional tide run lists range, then the region")
    void regionRowOnTideRun() {
        stubRunAtSeaham(DAY, DAY.plusDays(3));

        ComingUpEntry entry = only(withRegions(event(DAY, DAY.plusDays(3), "spring-tide", "Spring",
                AlmanacEvent.metaOf("range", "4.9 m")), "North East"));

        assertThat(factTexts(entry)).containsExactly("range 4.9 m", "applies to North East");
    }

    @Test
    @DisplayName("a supermoon-hosted merged card keeps the supermoon's region row, ahead of the run's water rows")
    void regionRowOnSupermoonHostedMergedCard() {
        stubRunAtSeaham(DAY.plusDays(2), DAY.plusDays(2));
        AlmanacEvent run = event(DAY.plusDays(2), DAY.plusDays(2), "spring-tide", "Spring tide run",
                AlmanacEvent.metaOf("sunriseHw", "07:40", "sunriseHwGap", "-10",
                        "sunriseHwDate", DAY.plusDays(2).toString(), "sunriseHwAligned", "true"));
        AlmanacEvent moon = withRegions(event(DAY, DAY.plusDays(3), "supermoon", "Supermoon", Map.of()),
                "North East");

        ComingUpResponse response = assembler.assemble(DAY, List.of(run, moon));

        assertThat(response.entries()).hasSize(1);
        ComingUpEntry merged = response.entries().getFirst();
        assertThat(merged.type()).isEqualTo("supermoon");
        assertThat(factTexts(merged)).containsExactly("applies to North East",
                "sunrise" + SEP + "HW" + NBSP + "07:40, 10m" + NBSP + "before" + SEP + "Sun" + NBSP + "12");
    }

    // ── defaults and scores of single entries ───────────────────────────────

    @Test
    @DisplayName("an unrecognised type still gets the default 'See the plan' action on its start date")
    void unknownTypeGetsDefaultAction() {
        ComingUpEntry entry = only(event(DAY, DAY.plusDays(2), "mystery-topic", "Something", Map.of()));

        assertThat(entry.action().label()).isEqualTo("See the plan for " + fmt(DAY) + " →");
        assertThat(entry.action().kind()).isEqualTo("plan");
        assertThat(entry.action().date()).isEqualTo(DAY);
    }

    @Test
    @DisplayName("a supermoon scores its rarity PLUS the default magnitude: log2(60) + 1 = 6.9 bits")
    void supermoonBitsAddMagnitudeToRarity() {
        assertThat(only(event("supermoon", Map.of())).bits()).isEqualTo(6.9);
    }

    @Test
    @DisplayName("same-day entries of different types come out ordered by type")
    void sameDatesOrderByType() {
        ComingUpResponse response = assembler.assemble(DAY, List.of(
                event("meteor", Map.of()), event("equinox", Map.of()), event("eclipse", Map.of())));

        assertThat(response.entries()).extracting(ComingUpEntry::type)
                .containsExactly("eclipse", "equinox", "meteor");
    }

    @Test
    @DisplayName("entries sort by start, then by end date, then by type")
    void entriesSortByStartThenEnd() {
        ComingUpResponse response = assembler.assemble(DAY, List.of(
                event(DAY.plusDays(1), DAY.plusDays(1), "meteor", "Late", Map.of()),
                event(DAY, DAY.plusDays(5), "equinox", "Long", Map.of()),
                event(DAY, DAY.plusDays(2), "meteor", "Short", Map.of())));

        assertThat(response.entries()).extracting(ComingUpEntry::title)
                .containsExactly("Short", "Long", "Late");
    }

    // ── counts ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("counts split entries by kind: almanac entries are 'fixed', anything else 'forecast'")
    void countsSplitByKind() {
        AlmanacEvent forecast = new AlmanacEvent(DAY, DAY, AlmanacKind.FORECAST, "meteor", "Forecast",
                "detail", Map.of(), List.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(forecast,
                event("equinox", Map.of()), event("eclipse", Map.of())));

        assertThat(response.counts().fixed()).isEqualTo(2);
        assertThat(response.counts().forecast()).isEqualTo(1);
        assertThat(response.counts().byFamily()).containsEntry("night-sky", 1)
                .containsEntry("sun-moon", 1).containsEntry("eclipse", 1);
    }

    // ── peak date fallbacks ────────────────────────────────────────────────

    @Test
    @DisplayName("the action date is the peakDate, else figuresFrom, else the start; "
            + "an unparseable value falls back to the start")
    void peakDateFallbacks() {
        LocalDate start = DAY;
        LocalDate end = DAY.plusDays(4);
        LocalDate mid = DAY.plusDays(2);

        assertThat(only(event(start, end, "equinox", "E", Map.of("peakDate", mid.toString()))).action().date())
                .isEqualTo(mid);
        assertThat(only(event(start, end, "equinox", "E", Map.of("figuresFrom", mid.toString()))).action().date())
                .isEqualTo(mid);
        assertThat(only(event(start, end, "equinox", "E", Map.of())).action().date()).isEqualTo(start);
        assertThat(only(event(start, end, "equinox", "E", Map.of("peakDate", "garbage"))).action().date())
                .isEqualTo(start);
        assertThat(only(event(start, end, "equinox", "E", Map.of("figuresFrom", "garbage"))).action().date())
                .isEqualTo(start);
        // A present-but-unparseable peakDate never falls through to figuresFrom.
        assertThat(only(event(start, end, "equinox", "E",
                Map.of("peakDate", "garbage", "figuresFrom", mid.toString()))).action().date())
                .isEqualTo(start);
    }

    // ── score notes: the thresholds ─────────────────────────────────────────

    @Test
    @DisplayName("a default meteor shower clears the announce band on a statable rarity: 'about once a year'")
    void statableRarityScoreNoteStatesTheGap() {
        assertThat(only(event("meteor", Map.of())).scoreNote())
                .isEqualTo("It comes round about once a year, which is rare enough to flag on its own.");
    }

    @Test
    @DisplayName("an entry sitting exactly on the announce band gets a note; one just under it does not")
    void scoreNoteAnnounceBoundary() {
        // Gap 2 days -> rarity exactly 1.0 bit; with the 1.0 default magnitude the score is 2.0.
        properties.getRarity().setMeteorShowerMeanGapDays(2.0);

        properties.getBands().setAnnounce(2.0);
        ComingUpEntry onTheBand = only(event("meteor", Map.of("zhr", "20")));
        assertThat(onTheBand.bits()).isEqualTo(2.0);
        // rarity == magnitude is a tie, and a tie is carried by rarity (not "an unusually big one").
        assertThat(onTheBand.scoreNote())
                .isEqualTo("It comes round about once a week, which is rare enough to flag on its own.");

        properties.getBands().setAnnounce(2.1);
        assertThat(only(event("meteor", Map.of("zhr", "20"))).scoreNote()).isNull();
    }

    // ── tide-run superlatives and thresholds ────────────────────────────────

    @Test
    @DisplayName("equal ranges never out-do each other: a tie is not 'biggest so far' and not 'bigger later'")
    void equalRangesMakeNoSuperlative() {
        LocalDate a = DAY;
        LocalDate b = DAY.plusDays(14);
        LocalDate c = DAY.plusDays(28);
        stubRange(a, a.plusDays(3), 5.0);
        stubRange(b, b.plusDays(3), 5.0);
        stubRange(c, c.plusDays(3), 6.0);
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.empty());

        ComingUpResponse response = assembler.assemble(DAY, List.of(
                event(a, a.plusDays(3), "spring-tide", "Run", Map.of()),
                event(b, b.plusDays(3), "spring-tide", "Run", Map.of()),
                event(c, c.plusDays(3), "spring-tide", "Run", Map.of())));

        // A is first-biggest and the equal B does not count as bigger: its "until" is C's date.
        assertThat(response.entries().get(0).superlative()).isEqualTo("biggest until " + fmt(c));
        // B merely equals A, so it is not the biggest so far.
        assertThat(response.entries().get(1).superlative()).isNull();
        assertThat(response.entries().get(2).superlative()).isNull();
        assertThat(response.entries().get(0).threshold())
                .isEqualTo("The other 2 runs in this window ranged 5.0–6.0 m.");
    }

    @Test
    @DisplayName("with exactly two runs the threshold line is singular: 'The other 1 run'")
    void twoRunsThresholdIsSingular() {
        LocalDate a = DAY;
        LocalDate b = DAY.plusDays(14);
        stubRange(a, a.plusDays(3), 5.0);
        stubRange(b, b.plusDays(3), 6.0);
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.empty());

        ComingUpResponse response = assembler.assemble(DAY, List.of(
                event(a, a.plusDays(3), "spring-tide", "Run", Map.of()),
                event(b, b.plusDays(3), "spring-tide", "Run", Map.of())));

        assertThat(response.entries().get(0).threshold())
                .isEqualTo("The other 1 run in this window ranged 6.0–6.0 m.");
        assertThat(response.entries().get(1).threshold())
                .isEqualTo("The other 1 run in this window ranged 5.0–5.0 m.");
    }

    @Test
    @DisplayName("'biggest until' names the union start of a supermoon-hosted card, which begins before the run itself")
    void superlativeNamesUnionStartOfSupermoonHostedCard() {
        LocalDate a = DAY;
        LocalDate b = DAY.plusDays(14);
        stubRange(a, a.plusDays(1), 5.0);
        stubRange(b, b, 6.0);
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.empty());
        // A three-night supermoon is the longer span, so it hosts; the card starts 2 days before the run.
        AlmanacEvent moon = event(b.minusDays(2), b, "supermoon", "Supermoon", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(
                event(a, a.plusDays(1), "spring-tide", "Run", Map.of()),
                event(b, b, "spring-tide", "Run", Map.of()), moon));

        assertThat(response.entries()).hasSize(2);
        ComingUpEntry merged = response.entries().get(1);
        assertThat(merged.type()).isEqualTo("supermoon");
        assertThat(merged.startDate()).isEqualTo(b.minusDays(2));
        assertThat(response.entries().get(0).superlative()).isEqualTo("biggest until " + fmt(b.minusDays(2)));
    }

    // ── tide high-water rows: what makes a row appear ───────────────────────

    private List<String> tideRows(Map<String, String> extra) {
        stubRunAtSeaham(DAY, DAY.plusDays(3));
        Map<String, String> meta = new LinkedHashMap<>(AlmanacEvent.metaOf("range", "4.9 m", "location", "Seaham"));
        meta.putAll(extra);
        return factTexts(only(event(DAY, DAY.plusDays(3), "spring-tide", "Run", meta)));
    }

    @Test
    @DisplayName("a high-water row needs the clock, the gap and a parseable date: lose any one and it is omitted")
    void highWaterRowNeedsAllThreeParts() {
        String date = DAY.toString();
        List<String> onlyRange = List.of("range 4.9 m at Seaham");

        assertThat(tideRows(Map.of("sunriseHwGap", "-10", "sunriseHwDate", date))).isEqualTo(onlyRange);
        assertThat(tideRows(Map.of("sunriseHw", "07:40", "sunriseHwDate", date))).isEqualTo(onlyRange);
        assertThat(tideRows(Map.of("sunriseHw", "07:40", "sunriseHwGap", "-10"))).isEqualTo(onlyRange);
        assertThat(tideRows(Map.of("sunriseHw", "07:40", "sunriseHwGap", "abc", "sunriseHwDate", date)))
                .isEqualTo(onlyRange);
        assertThat(tideRows(Map.of("sunriseHw", "07:40", "sunriseHwGap", "-10", "sunriseHwDate", "not-a-date")))
                .isEqualTo(onlyRange);
        assertThat(tideRows(Map.of("sunsetHwGap", "-10", "sunsetHwDate", date))).isEqualTo(onlyRange);
    }

    @Test
    @DisplayName("a non-aligned row at a zero gap reads '0m after' and a partial run qualifies the miss")
    void zeroGapMissRowAndPartialQualifier() {
        String date = DAY.toString();

        assertThat(tideRows(Map.of("sunriseHw", "05:30", "sunriseHwGap", "0", "sunriseHwDate", date)))
                .containsExactly("range 4.9 m at Seaham", "sunrise" + SEP + "no high water within an hour" + SEP
                        + "nearest HW" + NBSP + "05:30, 0m" + NBSP + "after" + SEP + "Fri" + NBSP + "10");
        assertThat(tideRows(Map.of("sunsetHw", "20:30", "sunsetHwGap", "140", "sunsetHwDate", date,
                "eventWaterPartial", "true")))
                .containsExactly("range 4.9 m at Seaham", "sunset" + SEP + "no high water within an hour on the "
                        + "days measured" + SEP + "nearest HW" + NBSP + "20:30, 2h20" + NBSP + "after" + SEP
                        + "Fri" + NBSP + "10");
    }

    @Test
    @DisplayName("the partial-coverage note rides only when partialCoverage is exactly 'true'")
    void partialCoverageNote() {
        assertThat(tideRows(Map.of("partialCoverage", "true")))
                .containsExactly("range 4.9 m at Seaham", "figures cover only part of this run");
        assertThat(tideRows(Map.of("partialCoverage", "false"))).containsExactly("range 4.9 m at Seaham");
    }

    // ── merge pairing rules ────────────────────────────────────────────────

    @Test
    @DisplayName("a meteor shower that overlaps a supermoon is not a coincidence: two separate entries")
    void meteorNeverMergesWithSupermoon() {
        ComingUpResponse response = assembler.assemble(DAY, List.of(
                event(DAY, DAY.plusDays(2), "meteor", "Meteors", Map.of()),
                event(DAY.plusDays(1), DAY.plusDays(1), "supermoon", "Supermoon", Map.of())));

        assertThat(response.entries()).extracting(ComingUpEntry::type).containsExactly("meteor", "supermoon");
        assertThat(response.entries()).allSatisfy(e -> assertThat(e.coincidence()).isEmpty());
    }

    @Test
    @DisplayName("two overlapping supermoons are not a coincidence either")
    void twoSupermoonsNeverMerge() {
        ComingUpResponse response = assembler.assemble(DAY, List.of(
                event(DAY, DAY.plusDays(2), "supermoon", "Supermoon A", Map.of()),
                event(DAY.plusDays(1), DAY.plusDays(3), "supermoon", "Supermoon B", Map.of())));

        assertThat(response.entries()).extracting(ComingUpEntry::title)
                .containsExactly("Supermoon A", "Supermoon B");
        assertThat(response.entries()).allSatisfy(e -> assertThat(e.coincidence()).isEmpty());
    }

    @Test
    @DisplayName("one supermoon pairs with only one of two overlapping runs; the other stays standalone")
    void aSupermoonIsConsumedByTheFirstRunOnly() {
        stubRunAtSeaham(DAY, DAY.plusDays(2));
        stubRunAtSeaham(DAY.plusDays(2), DAY.plusDays(5));
        AlmanacEvent first = event(DAY, DAY.plusDays(2), "spring-tide", "First run", Map.of());
        AlmanacEvent second = event(DAY.plusDays(2), DAY.plusDays(5), "spring-tide", "Second run", Map.of());
        AlmanacEvent moon = event(DAY.plusDays(2), DAY.plusDays(2), "supermoon", "Supermoon", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(first, second, moon));

        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries().get(0).title()).isEqualTo("First run, on a supermoon");
        assertThat(response.entries().get(0).coincidence()).hasSize(2);
        assertThat(response.entries().get(1).title()).isEqualTo("Second run");
        assertThat(response.entries().get(1).coincidence()).isEmpty();
    }

    @Test
    @DisplayName("two Plan-owned partners never pair with each other, so neither leaks into the feed as a card")
    void twoPartnersNeverPairWithEachOther() {
        stubRunAtSeaham(DAY, DAY.plusDays(5));
        stubRunAtSeaham(DAY.plusDays(1), DAY.plusDays(2));
        AlmanacEvent run = event(DAY, DAY.plusDays(5), "spring-tide", "Spring tide run", Map.of());
        AlmanacEvent moon = event(DAY.plusDays(1), DAY.plusDays(1), "supermoon", "Supermoon", Map.of());
        // Each partner overlaps an eligible event of the complementary type, so both are staged...
        AlmanacEvent partnerRun = event(DAY.plusDays(1), DAY.plusDays(2), "spring-tide", "Partner run", Map.of());
        AlmanacEvent partnerMoon = event(DAY.plusDays(2), DAY.plusDays(2), "supermoon", "Partner moon", Map.of());

        ComingUpResponse response = assembler.assemble(DAY, List.of(run, moon),
                List.of(partnerRun, partnerMoon));

        // ...but only the eligible pair survives as a card.
        assertThat(response.entries()).hasSize(1);
        assertThat(response.entries().getFirst().title()).isEqualTo("Spring tide run, on a supermoon");
    }

    @Test
    @DisplayName("a merged card whose other topic has an empty title does not fail the feed")
    void emptyOtherTitleDoesNotBreakTheMerge() {
        stubRunAtSeaham(DAY, DAY.plusDays(3));

        ComingUpResponse response = assembler.assemble(DAY, List.of(
                event(DAY, DAY.plusDays(3), "spring-tide", "Spring tide run", Map.of()),
                event(DAY.plusDays(1), DAY.plusDays(1), "supermoon", "", Map.of())));

        assertThat(response.entries()).hasSize(1);
        assertThat(response.entries().getFirst().title()).isEqualTo("Spring tide run");
    }

    @Test
    @DisplayName("a merged card whose other topic has a whitespace-only title keeps the host title alone")
    void blankOtherTitleDoesNotLeaveADanglingArticle() {
        stubRunAtSeaham(DAY, DAY.plusDays(3));

        ComingUpResponse response = assembler.assemble(DAY, List.of(
                event(DAY, DAY.plusDays(3), "spring-tide", "Spring tide run", Map.of()),
                event(DAY.plusDays(1), DAY.plusDays(1), "supermoon", "   ", Map.of())));

        assertThat(response.entries()).hasSize(1);
        assertThat(response.entries().getFirst().title()).isEqualTo("Spring tide run");
    }

    // ── day ranges: the shoulder meta ───────────────────────────────────────

    @Test
    @DisplayName("a blank or absent shoulderDates value means no shoulder days are asked for or flagged")
    void blankShoulderDatesAreIgnored() {
        stubRunAtSeaham(DAY, DAY.plusDays(1));
        Map<LocalDate, Double> ranges = new LinkedHashMap<>();
        ranges.put(DAY, 4.0);
        ranges.put(DAY.plusDays(1), 4.4);
        when(tideRunBuilder.dailyRangesAt(eq(SEAHAM), eq(datesOf(DAY, DAY.plusDays(1))))).thenReturn(ranges);

        ComingUpEntry absent = only(event(DAY, DAY.plusDays(1), "spring-tide", "Run", Map.of()));
        ComingUpEntry blank = only(event(DAY, DAY.plusDays(1), "spring-tide", "Run",
                Map.of("shoulderDates", "   ")));

        assertThat(absent.dayRanges()).hasSize(2).allMatch(d -> !d.shoulder());
        assertThat(blank.dayRanges()).hasSize(2).allMatch(d -> !d.shoulder());
    }
}
