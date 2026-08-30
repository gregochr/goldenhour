package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.model.TideRunDay;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TideRunBuilder}.
 *
 * <p>The run rows exist to answer one question — <em>does a water land near a solar event?</em> — so
 * what is pinned here is the arithmetic behind that answer: which location the run is drawn for, how
 * a day's range and anomaly are derived, when a day counts as aligned, and which of the three
 * verdict forms a day earns. Times are fixed rather than relative so the assertions read as the
 * strings a photographer sees.
 *
 * <p><b>Which water a day is about is decided by the sun, not by the run's lunar label.</b> Several
 * tests here pair a run type with the opposite water on purpose — a spring run named by its high
 * water, a king run named by its low — because the defect being pinned was the reverse: the label
 * chose the water, so a spring run at a roster shooting high water was reported unaligned on exactly
 * the mornings it was not.
 *
 * <p>Dates sit in <b>January</b>, where Europe/London is UTC, so a stored UTC extreme and its local
 * clock time coincide and each assertion reads unambiguously. The BST offset is exercised separately.
 */
@ExtendWith(MockitoExtension.class)
class TideRunBuilderTest {

    private static final LocalDate DAY_1 = LocalDate.of(2026, 1, 27);
    private static final LocalDate DAY_2 = LocalDate.of(2026, 1, 28);

    /** Winter sunrise/sunset for the fixture coast — 08:10 and 16:30, UTC == local in January. */
    private static final LocalDateTime SUNRISE = DAY_1.atTime(8, 10);
    private static final LocalDateTime SUNSET = DAY_1.atTime(16, 30);

    private static final long ID_WHITBY = 1L;
    private static final long ID_SEAHAM = 2L;

    @Mock
    private TideExtremeRepository tideExtremeRepository;

    @Mock
    private MarineWaveRepository marineWaveRepository;

    @Mock
    private TideService tideService;

    @Mock
    private SolarService solarService;

    private TideRunBuilder builder;

    @BeforeEach
    void setUp() {
        // No anchor configured: every existing test exercises pure biggest-range selection, which
        // is the documented fallback and remains the behaviour wherever the anchor is absent.
        builder = new TideRunBuilder(tideExtremeRepository, marineWaveRepository,
                tideService, solarService, "");
    }

    /** A builder configured to draw runs for the named location. */
    private TideRunBuilder anchoredTo(String anchor) {
        return new TideRunBuilder(tideExtremeRepository, marineWaveRepository,
                tideService, solarService, anchor);
    }

    // ── representative selection ──────────────────────────────────────────────

    @Test
    @DisplayName("draws the run for the location with the biggest single-day range")
    void build_picksBiggestRangeLocation() {
        // Seaham swings 4.6 m against Whitby's 2.0 m, so the curve is Seaham's — and stays Seaham's
        // for every day, or a reader comparing two days would be comparing two coastlines.
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_WHITBY, DAY_1,
                low("04:30", 1.0), high("10:40", 3.0), low("16:50", 1.0)));
        extremes.addAll(day(ID_SEAHAM, DAY_1,
                low("05:00", 0.4), high("11:10", 5.0), low("17:20", 0.4)));
        stubExtremes(extremes);

        Map<LocalDate, TideRunDay> run = builder.build(List.of(DAY_1),
                List.of(whitby(), seaham()), false);

        assertThat(run.get(DAY_1).locationName()).isEqualTo("Seaham");
        assertThat(run.get(DAY_1).range()).isEqualTo("4.6 m");
    }

    @Test
    @DisplayName("the configured anchor wins over a bigger range elsewhere")
    void build_configuredAnchor_outranksBiggestRange() {
        // The complaint this exists for: biggest-range is not neutral across a long roster.
        // Range grows southward on this coast, so the maximum reliably lands at the southern end
        // and every run was drawn for the same distant place — named in the footer, but unplaceable
        // by the reader, which makes the numbers read as arbitrary rather than as measurements of
        // somewhere. Whitby swings 2.0 m against Seaham's 4.6 m and still wins when configured.
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_WHITBY, DAY_1,
                low("04:30", 1.0), high("10:40", 3.0), low("16:50", 1.0)));
        extremes.addAll(day(ID_SEAHAM, DAY_1,
                low("05:00", 0.4), high("11:10", 5.0), low("17:20", 0.4)));
        stubExtremes(extremes);

        Map<LocalDate, TideRunDay> run = anchoredTo("Whitby")
                .build(List.of(DAY_1), List.of(whitby(), seaham()), false);

        assertThat(run.get(DAY_1).locationName()).isEqualTo("Whitby");
        // The range still describes the location actually drawn, not the roster's maximum.
        assertThat(run.get(DAY_1).range()).isEqualTo("2.0 m");
    }

    @Test
    @DisplayName("an anchor absent from the run falls back to biggest range, silently")
    void build_anchorNotInRun_fallsBackToBiggestRange() {
        // A topic that never reaches the anchor — a Yorkshire-only run, say — must not be
        // mislabelled with a coastline it does not cover.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("05:00", 0.4), high("11:10", 5.0), low("17:20", 0.4)));

        Map<LocalDate, TideRunDay> run = anchoredTo("St Mary's Lighthouse")
                .build(List.of(DAY_1), List.of(seaham()), false);

        assertThat(run.get(DAY_1).locationName()).isEqualTo("Seaham");
    }

    @Test
    @DisplayName("an anchor present but undrawable falls back rather than yielding an empty run")
    void build_anchorWithNoDrawableDay_fallsBackToBiggestRange() {
        // Matching on name alone is not enough: an anchor with no derivable day would return a
        // representative whose every day is dropped, emptying the run and silently deleting a
        // topic that Seaham could have carried.
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_WHITBY, DAY_1,
                high("10:40", 3.0)));
        extremes.addAll(day(ID_SEAHAM, DAY_1,
                low("05:00", 0.4), high("11:10", 5.0), low("17:20", 0.4)));
        stubExtremes(extremes);

        Map<LocalDate, TideRunDay> run = anchoredTo("Whitby")
                .build(List.of(DAY_1), List.of(whitby(), seaham()), false);

        assertThat(run.get(DAY_1).locationName()).isEqualTo("Seaham");
    }

    @Test
    @DisplayName("a punctuation difference in the anchor name does not disable it")
    void build_anchorMatchIgnoresPunctuation() {
        // The production case, exactly: Location Management held "St. Mary's Lighthouse" and the
        // configuration said "St Mary's Lighthouse". One full stop, and equalsIgnoreCase missed —
        // so every spring run silently fell back to biggest range and drew itself at a cove far
        // from the reader, which is the precise outcome the anchor exists to prevent. Punctuation
        // and spacing are the part of a place name people disagree about; none of those
        // disagreements mean a different place.
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_WHITBY, DAY_1,
                low("04:30", 1.0), high("10:40", 3.0), low("16:50", 1.0)));
        extremes.addAll(day(ID_SEAHAM, DAY_1,
                low("05:00", 0.4), high("11:10", 5.0), low("17:20", 0.4)));
        stubExtremes(extremes);

        // Seaham has the biggest range (4.6 m vs 2.0 m), so a failed match is visible as Seaham.
        Map<LocalDate, TideRunDay> run = anchoredTo("Whit-by.")
                .build(List.of(DAY_1), List.of(whitby(), seaham()), false);

        assertThat(run.get(DAY_1).locationName()).isEqualTo("Whitby");
    }

    @Test
    @DisplayName("an exact anchor match always wins over a punctuation-equal neighbour")
    void build_exactAnchorMatchBeatsNormalisedOne() {
        // Tolerance must never override intent: if the configured name IS a roster entry, that
        // entry is the anchor, even when another entry differs from it only by punctuation.
        stubSolar();
        LocationEntity punctuated = LocationEntity.builder().id(99L).name("Whit-by")
                .lat(54.49).lon(-0.61).build();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_WHITBY, DAY_1,
                low("04:30", 1.0), high("10:40", 3.0), low("16:50", 1.0)));
        extremes.addAll(day(99L, DAY_1,
                low("04:30", 1.0), high("10:40", 9.0), low("16:50", 1.0)));
        stubExtremes(extremes);

        // "Whit-by" is listed first, so a normalised-only match would pick it.
        Map<LocalDate, TideRunDay> run = anchoredTo("Whitby")
                .build(List.of(DAY_1), List.of(punctuated, whitby()), false);

        assertThat(run.get(DAY_1).locationName()).isEqualTo("Whitby");
    }

    @Test
    @DisplayName("the anchor matches case-insensitively, so config casing cannot silently disable it")
    void build_anchorMatchIsCaseInsensitive() {
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_WHITBY, DAY_1,
                low("04:30", 1.0), high("10:40", 3.0), low("16:50", 1.0)));
        extremes.addAll(day(ID_SEAHAM, DAY_1,
                low("05:00", 0.4), high("11:10", 5.0), low("17:20", 0.4)));
        stubExtremes(extremes);

        Map<LocalDate, TideRunDay> run = anchoredTo("  wHiTbY  ")
                .build(List.of(DAY_1), List.of(whitby(), seaham()), false);

        assertThat(run.get(DAY_1).locationName()).isEqualTo("Whitby");
    }

    @Test
    @DisplayName("drops a day with no low water rather than charting half a tide")
    void build_dayWithoutLowWater_isOmitted() {
        // A day needs a high and a low to have a range or a curve. The pill for that day falls
        // back to its fact chips; it must not render a chart from one extremum.
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("05:00", 0.4), high("11:10", 5.0)));
        extremes.addAll(day(ID_SEAHAM, DAY_2, high("11:50", 5.0)));
        stubExtremes(extremes);

        Map<LocalDate, TideRunDay> run = builder.build(List.of(DAY_1, DAY_2),
                List.of(seaham()), false);

        assertThat(run).containsOnlyKeys(DAY_1);
        // The chip must count the days it can actually draw, not the days the topic covers.
        assertThat(run.get(DAY_1).dayCount()).isEqualTo(1);
    }

    // ── the run chip ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("numbers each day within the run and marks the biggest-range day as peak")
    void build_numbersDaysAndMarksPeak() {
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("05:00", 0.8), high("11:10", 4.0), low("17:20", 0.8)));
        extremes.addAll(day(ID_SEAHAM, DAY_2,
                low("05:40", 0.4), high("11:50", 5.0), low("18:00", 0.4)));
        stubExtremes(extremes);

        Map<LocalDate, TideRunDay> run = builder.build(List.of(DAY_1, DAY_2),
                List.of(seaham()), false);

        assertThat(run.get(DAY_1).dayNumber()).isEqualTo(1);
        assertThat(run.get(DAY_1).dayCount()).isEqualTo(2);
        assertThat(run.get(DAY_1).peak()).isFalse();
        assertThat(run.get(DAY_2).dayNumber()).isEqualTo(2);
        assertThat(run.get(DAY_2).peak()).isTrue();
        assertThat(run.get(DAY_1).runLabel()).isEqualTo("SPRING RUN");
        assertThat(run.get(DAY_1).dayLabel()).isEqualTo("TUE 27");
    }

    @Test
    @DisplayName("a king run whose high water is the one in the light takes the high-water line")
    void build_kingRun_usesHighWaterAndItsOwnPhrase() {
        // "low water bares the foreground" is the wrong sentence for a morning whose high water is
        // the one on the sun. Note what makes it the wrong sentence: the water, not the run label.
        // The low waters here are 6h10 and 2h10 out, so the high wins on distance alone.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("08:35", 5.0), low("14:20", 0.4)));

        when(tideService.getTideStats(ID_SEAHAM)).thenReturn(Optional.of(stats("3.5", "4.6")));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), true).get(DAY_1);

        assertThat(day.runLabel()).isEqualTo("KING RUN");
        assertThat(day.phrase()).isEqualTo(TideRunBuilder.HIGH_WATER_PHRASE);
        assertThat(day.verdict()).isEqualTo("HW 08:35 · 25m after sunrise");
        assertThat(day.aligned()).isTrue();
        // A king tide's defining number is how HIGH the water gets and how far that clears the
        // spring threshold — the range-against-mean framing belongs to a spring run.
        assertThat(day.highWater()).isEqualTo("5.0 m");
        assertThat(day.highWaterAnomaly()).isEqualTo("+0.4 m over spring");
        // Named rather than left to be parsed out of the verdict: the hot-topic headline states
        // this event, and that sentence and this chart must not be able to disagree.
        assertThat(day.alignedEvent()).isEqualTo("sunrise");
    }

    @Test
    @DisplayName("an unaligned day names no solar event at all")
    void build_unalignedDay_carriesNoAlignedEvent() {
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("12:30", 5.0), low("18:20", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), true).get(DAY_1);

        assertThat(day.aligned()).isFalse();
        assertThat(day.alignedEvent()).isNull();
    }

    @Test
    @DisplayName("each window's water is measured against ITS OWN event, not the nearer one")
    void build_windowWater_measuresAgainstItsOwnSolarEvent() {
        // Both extremes sit in the morning half (sunrise 08:10, sunset 16:30), so the low at 11:00
        // is nearest to SUNSET of the two waters while itself being nearer to sunrise. That is the
        // trap: `signedGap` deliberately picks whichever solar event is closer — right for the
        // alignment question, and here it would report the evening card "2h50 after sunrise",
        // wrong in both the event named and the number.
        //
        // The two phrases also differ in their state word, so a hardcoded "high water " fails too.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1, high("09:00", 5.0), low("11:00", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.sunriseWater()).isEqualTo("high water 50m after sunrise");
        assertThat(day.sunsetWater()).isEqualTo("low water 5h30 before sunset");
    }

    @Test
    @DisplayName("both windows get their water on an ALIGNED day — the phrases are not gated on it")
    void build_windowWater_isNotGatedOnAlignment() {
        // Everything else descending from the day's geometry is keyed to `inLight` and silent when
        // no water reaches the light. These two are not: "Spring tide" is a claim about the day, so
        // the day's other window is owed its own water even when the alignment belongs elsewhere.
        // Here the alignment is the morning's, and the evening still gets a sentence of its own.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("08:35", 5.0), low("14:20", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), true).get(DAY_1);

        assertThat(day.alignedEvent()).isEqualTo("sunrise");
        assertThat(day.sunriseWater()).isEqualTo("high water 25m after sunrise");
        assertThat(day.sunsetWater()).isEqualTo("low water 2h10 before sunset");
    }

    @Test
    @DisplayName("a SPRING run whose high water is the one in the light is aligned, and says so")
    void build_springRun_highWaterInTheLight_isAlignedThroughout() {
        // THE case this change exists for. A spring tide's gift is a big RANGE, which lifts high
        // water exactly as far as it drops low water, and most of this coastal roster is configured
        // to shoot the high one. While `useful = king ? HIGH : LOW` decided the framing, this
        // morning — high water 50 minutes after sunrise — was reported as a day whose water missed
        // the light: no emphasis, no editorial line, and a headline denying an alignment the chart
        // beneath it drew.
        //
        // Every field is asserted together on purpose. They read one point now, so the only way
        // this can regress is a field going back to a water of its own.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("09:00", 5.0), low("15:20", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.runLabel()).isEqualTo("SPRING RUN");
        assertThat(day.verdict()).isEqualTo("HW 09:00 · 50m after sunrise");
        assertThat(day.aligned()).isTrue();
        assertThat(day.alignedEvent()).isEqualTo("sunrise");
        assertThat(day.alignmentPhrase()).isEqualTo("HW 09:00 · 50m after sunrise");
        assertThat(day.phrase()).isEqualTo(TideRunBuilder.HIGH_WATER_PHRASE);
    }

    @Test
    @DisplayName("a KING run whose low water is the one in the light takes the low-water line")
    void build_kingRun_lowWaterInTheLight_takesTheLowWaterPhrase() {
        // The mirror of the test above, and the reason it is here: the fix is not "spring now means
        // high water". The label stopped choosing at all. A king run whose low water is the one on
        // the sun gets the bared-foreground sentence, because that is what the reader will find.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("08:30", 0.4), high("14:40", 5.0), low("20:50", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), true).get(DAY_1);

        assertThat(day.runLabel()).isEqualTo("KING RUN");
        assertThat(day.verdict()).isEqualTo("LW 08:30 · 20m after sunrise");
        assertThat(day.aligned()).isTrue();
        assertThat(day.phrase()).isEqualTo(TideRunBuilder.LOW_WATER_PHRASE);
    }

    @Test
    @DisplayName("when both waters reach the light, the nearer one is the day's water")
    void build_bothWatersInTheLight_namesTheNearer() {
        // A short winter day is not much longer than the ~6h12 between a high and a low, so both
        // can sit inside the window: the high is 40m after sunrise, the low 60m before sunset.
        // Nothing arbitrates that but distance — a type preference here would be the old hard-coded
        // framing surviving in the one place it still had something to decide.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1, high("08:50", 5.0), low("15:30", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.verdict()).isEqualTo("HW 08:50 · 40m after sunrise");
        assertThat(day.alignedEvent()).isEqualTo("sunrise");
        assertThat(day.phrase()).isEqualTo(TideRunBuilder.HIGH_WATER_PHRASE);
    }

    @Test
    @DisplayName("equal gaps break toward the earlier water, never toward a type")
    void build_equalGaps_breakTowardTheEarlierWater() {
        // Constructed spacing — the subject is the tiebreak, not the tide. Both waters sit exactly
        // 30 minutes from their nearer event, and the rule has to be decidable without asking which
        // kind of water it is, or `king ? HIGH : LOW` is back in the last place it could hide.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1, high("07:40", 5.0), low("17:00", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.verdict()).isEqualTo("HW 07:40 · 30m before sunrise");
    }

    // ── roster alignment — the count behind "at N of M coastal locations" ─────

    @Test
    @DisplayName("the roster tally counts every location at its own sunrise, not the chart's")
    void build_rosterAlignment_countsEachLocationAtItsOwnSolarTimes() {
        // The headline says "at N of M coastal locations" while the chart draws one coastline, so
        // the tally has to be measured across the roster or the sentence overstates its evidence.
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("08:35", 5.0), low("14:20", 0.4)));
        // Whitby has no extreme within the hour of its own sunrise (08:10) or sunset (16:30), so
        // it must not count. Its low water was originally 08:00, which the roster rightly counted
        // via the same other-extremum fallback the chart's verdict uses — the fixture was wrong,
        // not the rule, and it is worth keeping the corrected times as the record of that.
        extremes.addAll(day(ID_WHITBY, DAY_1,
                low("11:00", 1.0), high("14:40", 3.0), low("20:50", 1.0)));
        stubExtremes(extremes);

        TideRunDay day = builder.build(List.of(DAY_1), List.of(whitby(), seaham()), true).get(DAY_1);

        assertThat(day.roster().measured()).isEqualTo(2);
        assertThat(day.roster().sunriseAligned()).isEqualTo(1);
        assertThat(day.roster().sunsetAligned()).isZero();
    }

    @Test
    @DisplayName("the representative is a member of its own tally, so an aligned chart is never 0")
    void build_rosterAlignment_alwaysIncludesTheRepresentative() {
        // The guarantee that keeps the headline and the chart from contradicting each other: the
        // roster rule is the same rule the representative's own row uses, so whenever the chart is
        // aligned the count it sits above is at least one.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("08:35", 5.0), low("14:20", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), true).get(DAY_1);

        assertThat(day.aligned()).isTrue();
        assertThat(day.roster().alignedWith(day.alignedEvent())).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("the roster tally counts a high-water alignment on a spring run")
    void build_rosterAlignment_countsHighWaterOnASpringRun() {
        // The tally and the chart must run one rule, or the headline prints "at 0 of N" over a
        // chart drawing an alignment. While the rule was `spring means low water`, a roster that
        // shoots high water counted for nothing on exactly the mornings it cared about — and the
        // tally is what the pill's "at N of M coastal locations" clause is built from.
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("08:35", 5.0), low("14:20", 0.4)));
        extremes.addAll(day(ID_WHITBY, DAY_1,
                low("03:00", 1.0), high("08:45", 3.0), low("15:10", 1.0)));
        stubExtremes(extremes);

        TideRunDay day = builder.build(List.of(DAY_1), List.of(whitby(), seaham()), false).get(DAY_1);

        assertThat(day.roster().measured()).isEqualTo(2);
        assertThat(day.roster().sunriseAligned()).isEqualTo(2);
        assertThat(day.roster().sunsetAligned()).isZero();
    }

    @Test
    @DisplayName("a location with no derivable day is not counted either way")
    void build_rosterAlignment_undrawableLocationIsNotInTheDenominator() {
        // Calling it unaligned would state something never measured; the denominator is what the
        // tide could actually be derived for.
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("08:35", 5.0), low("14:20", 0.4)));
        extremes.addAll(day(ID_WHITBY, DAY_1, high("10:40", 3.0)));
        stubExtremes(extremes);

        TideRunDay day = builder.build(
                List.of(DAY_1), List.of(seaham(), whitby()), true).get(DAY_1);

        assertThat(day.roster().measured()).isEqualTo(1);
    }

    @Test
    @DisplayName("evening high water names sunset, not sunrise")
    void build_eveningHighWater_namesSunset() {
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("10:00", 0.4), high("16:00", 5.0), low("22:20", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), true).get(DAY_1);

        assertThat(day.alignedEvent()).isEqualTo("sunset");
    }

    // ── highWaterRank — how extraordinary, measured against the record ────────

    // ── the size axis, which is independent of the run's lunar label ─────────

    @Test
    @DisplayName("a SPRING run whose water is genuinely big carries the high-water chips")
    void build_springRun_bigWater_carriesTheSizeChips() {
        // These were king-only, which was the spring/king conflation one level down: "king" is a
        // statement about the moon being at perigee, and the moon does not decide whether this
        // port's water is remarkable. A spring tide clearing the port's own threshold is exactly
        // the case a reader wants the number for — and, since the height arm was removed from the
        // king label, it is now also the common case.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("08:35", 5.0), low("14:20", 0.4)));
        when(tideService.getTideStats(ID_SEAHAM))
                .thenReturn(Optional.of(ranked("5.4", "4.9", 1400L)));

        // false = a spring run, not a king one.
        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.highWater()).isEqualTo("5.0 m");
        assertThat(day.highWaterRank()).isEqualTo("0.4 m off the record");
    }

    @Test
    @DisplayName("a KING run at a port having an ordinary day carries no size chips")
    void build_kingRun_ordinaryWater_claimsNoSize() {
        // The mirror image, and the reason this is not simply "show it everywhere". A perigean
        // spring is a king tide wherever you are, but the water at one particular port can still be
        // unremarkable — and three chips insisting otherwise would be the old defect with the
        // arguments swapped.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("02:00", 1.2), high("08:35", 3.9), low("14:20", 1.2)));
        when(tideService.getTideStats(ID_SEAHAM))
                .thenReturn(Optional.of(ranked("5.4", "4.9", 1400L)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), true).get(DAY_1);

        // 3.9 m is below the 4.4 m spring threshold, so nothing about size is claimed.
        assertThat(day.highWater()).isNull();
        assertThat(day.highWaterAnomaly()).isNull();
        assertThat(day.highWaterRank()).isNull();
    }

    @Test
    @DisplayName("high water short of the record states the shortfall")
    void build_kingRun_belowRecord_statesDistanceToIt() {
        // The spring excess cannot answer "how extraordinary is this?": the spring threshold is
        // 125% of mean high water, so metres-over-the-mean is the spring figure plus a constant.
        // The distance to the ceiling is the one baseline that moves independently of it.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("08:35", 5.0), low("14:20", 0.4)));
        when(tideService.getTideStats(ID_SEAHAM))
                .thenReturn(Optional.of(ranked("5.4", "4.9", 1400L)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), true).get(DAY_1);

        assertThat(day.highWaterRank()).isEqualTo("0.4 m off the record");
    }

    @Test
    @DisplayName("high water above everything on record says so")
    void build_kingRun_aboveRecord_claimsIt() {
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("08:35", 5.6), low("14:20", 0.4)));
        when(tideService.getTideStats(ID_SEAHAM))
                .thenReturn(Optional.of(ranked("5.4", "4.9", 1400L)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), true).get(DAY_1);

        assertThat(day.highWaterRank()).isEqualTo("highest recorded here");
    }

    @Test
    @DisplayName("a shortfall below display noise reads as the record, not as 0.0 m off it")
    void build_kingRun_shortfallWithinNoise_readsAsRecord() {
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("08:35", 5.38), low("14:20", 0.4)));
        when(tideService.getTideStats(ID_SEAHAM))
                .thenReturn(Optional.of(ranked("5.4", "4.9", 1400L)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), true).get(DAY_1);

        assertThat(day.highWaterRank()).isEqualTo("highest recorded here");
    }

    @Test
    @DisplayName("a fortnight of history is not a record to measure against")
    void build_kingRun_thinHistory_claimsNoRank() {
        // A location added last week has a maximum, not a record. Without this gate its first
        // spring tide would be announced as the highest water ever seen there.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("08:35", 5.0), low("14:20", 0.4)));
        when(tideService.getTideStats(ID_SEAHAM))
                .thenReturn(Optional.of(ranked("5.4", "4.9", 120L)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), true).get(DAY_1);

        assertThat(day.highWaterRank()).isNull();
    }

    @Test
    @DisplayName("no observed spring-neap cycle withholds the rank, as it withholds the excess")
    void build_kingRun_noPercentiles_claimsNoRank() {
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("08:35", 5.0), low("14:20", 0.4)));
        when(tideService.getTideStats(ID_SEAHAM))
                .thenReturn(Optional.of(ranked("5.4", null, 1400L)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), true).get(DAY_1);

        assertThat(day.highWaterRank()).isNull();
    }

    @Test
    @DisplayName("a spring run states no rank — the height is not its story")
    void build_springRun_carriesNoRank() {
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("05:00", 0.4), high("11:10", 5.0), low("17:20", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.highWaterRank()).isNull();
    }

    @Test
    @DisplayName("a spring run states no high water — the swing is its story, not the height")
    void build_springRun_carriesNoHighWaterMetric() {
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("05:00", 0.4), high("11:10", 5.0), low("17:20", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.highWater()).isNull();
        assertThat(day.highWaterAnomaly()).isNull();
    }

    @Test
    @DisplayName("water landing on the event says so, rather than measuring zero minutes")
    void verdict_coincidentWater_readsAtSunrise() {
        // "LW 08:10 · 0m after sunrise" is a null statement dressed as a measurement, and it fires
        // on the single most emphatic day a run can have.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("08:10", 0.4), high("14:20", 5.0), low("20:30", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.verdict()).isEqualTo("LW 08:10 · at sunrise");
        assertThat(day.aligned()).isTrue();
    }

    // ── the verdict, one form per branch ──────────────────────────────────────

    @Test
    @DisplayName("aligned day states the exact gap to the nearer solar event")
    void verdict_aligned() {
        // 08:44 low water against an 08:10 sunrise — 34 minutes, inside the hour that counts.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("08:44", 0.4), high("14:56", 5.0), low("21:09", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.verdict()).isEqualTo("LW 08:44 · 34m after sunrise");
        assertThat(day.aligned()).isTrue();
    }

    @Test
    @DisplayName("the row states one water, not the near one and then the far one")
    void verdict_waterNearSunrise_isStatedAlone() {
        // This used to read "HW at sunrise · LW 2h05 before sunset" — a second clause about a low
        // water two hours out of the light, printed because the row was obliged to say something
        // about the "useful" water whatever the sun was doing. The high water eleven minutes before
        // sunrise is the whole answer; the low is on the chart with its own clock time.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                high("07:59", 5.0), low("14:25", 0.4), high("20:24", 5.0)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.verdict()).isEqualTo("HW 07:59 · 11m before sunrise");
        assertThat(day.aligned()).isTrue();
    }

    @Test
    @DisplayName("an aligned high water is named, not a low water five hours away")
    void verdict_otherExtremumAlignedButNotCoincident() {
        // The reported production case, to the minute. At St. Mary's the run read
        //   "peak range · LW 5h03 before sunrise"
        // — a low water at 00:12, in the dark — on a morning when high water landed 58 minutes
        // after sunrise. Two thresholds were answering one question: `aligned` allows 60 minutes,
        // the other-extremum test demanded 30, so the 58-minute high water fell between them and
        // was discarded in favour of a number nobody can use.
        stubSolar();
        // DAY_1 carries the bigger range, so it is the run's peak day, exactly as reported.
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("03:07", 0.4), high("09:08", 5.0)));
        extremes.addAll(day(ID_SEAHAM, DAY_2,
                low("03:50", 1.0), high("09:50", 4.0)));
        stubExtremes(extremes);

        TideRunDay day = builder.build(List.of(DAY_1, DAY_2), List.of(seaham()), false).get(DAY_1);

        assertThat(day.verdict()).isEqualTo("peak range · HW 58m after sunrise");
        // And the flag now agrees with the sentence. It used to read false here — it described the
        // "useful" water, a spring run's low, which really was five hours out — so the emphasis and
        // the editorial line were withheld from the run's best morning while the verdict beside
        // them named the high water on the sunrise.
        assertThat(day.aligned()).isTrue();
        assertThat(day.phrase()).isEqualTo(TideRunBuilder.HIGH_WATER_PHRASE);
    }

    @Test
    @DisplayName("an aligned high water on a non-peak day carries its clock time")
    void verdict_otherExtremumAlignedOnNonPeakDay() {
        // Same rule, without the peak prefix competing for the ~40 characters the column holds:
        // the clock time comes back, matching the shape the aligned-useful-water branch uses.
        stubSolar();
        // DAY_2 carries the bigger range, so DAY_1 keeps the aligned high water without the badge.
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("03:07", 1.0), high("09:08", 4.0)));
        extremes.addAll(day(ID_SEAHAM, DAY_2,
                low("03:50", 0.4), high("09:50", 5.5)));
        stubExtremes(extremes);

        TideRunDay day = builder.build(List.of(DAY_1, DAY_2), List.of(seaham()), false).get(DAY_1);

        assertThat(day.verdict()).isEqualTo("HW 09:08 · 58m after sunrise");
    }

    @Test
    @DisplayName("an unaligned peak day leads with its range")
    void verdict_peakButUnaligned() {
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("11:30", 0.8), high("17:40", 4.0), low("23:50", 0.8)));
        extremes.addAll(day(ID_SEAHAM, DAY_2,
                low("12:00", 0.4), high("18:10", 5.0), low("23:59", 0.4)));
        stubExtremes(extremes);

        TideRunDay day = builder.build(List.of(DAY_1, DAY_2), List.of(seaham()), false).get(DAY_2);

        assertThat(day.peak()).isTrue();
        // The high water 1h40 after sunset is this day's nearest miss; the low at 12:00 is 3h50 out.
        // Neither is in the light, so the peak day leads with the range it does have.
        assertThat(day.verdict()).isEqualTo("peak range · HW 1h40 after sunset");
        assertThat(day.aligned()).isFalse();
    }

    // ── the alignment clause, which the almanac feed states on its own ────────

    @Test
    @DisplayName("the alignment clause keeps the clock time and drops the peak-range prefix")
    void alignmentPhrase_onAPeakDay_carriesNeitherThePrefixNorLosesTheTime() {
        // Same fixture as verdict_otherExtremumAlignedButNotCoincident, whose verdict compresses to
        // "peak range · HW 58m after sunrise" to fit a 224px column. The "Coming up" row states the
        // range in its own chip and names the biggest day in its own line, so that prefix would be
        // a third copy — under a label that says "alignment".
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("03:07", 0.4), high("09:08", 5.0)));
        extremes.addAll(day(ID_SEAHAM, DAY_2,
                low("03:50", 1.0), high("09:50", 4.0)));
        stubExtremes(extremes);

        TideRunDay day = builder.build(List.of(DAY_1, DAY_2), List.of(seaham()), false).get(DAY_1);

        assertThat(day.verdict()).isEqualTo("peak range · HW 58m after sunrise");
        assertThat(day.alignmentPhrase()).isEqualTo("HW 09:08 · 58m after sunrise");
    }

    @Test
    @DisplayName("the clause names the same water the verdict does — it is built from that point, "
            + "not recovered from that sentence")
    void alignmentPhrase_namesTheVerdictsWater() {
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1, low("08:44", 0.4), high("14:50", 4.0)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.verdict()).isEqualTo("LW 08:44 · 34m after sunrise");
        assertThat(day.alignmentPhrase()).isEqualTo("LW 08:44 · 34m after sunrise");
    }

    @Test
    @DisplayName("no water in the light means no clause at all — the invariant the almanac reads")
    void alignmentPhrase_unalignedDay_isNull() {
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("11:30", 0.8), high("17:40", 4.0), low("23:50", 0.8)));
        extremes.addAll(day(ID_SEAHAM, DAY_2,
                low("12:00", 0.4), high("18:10", 5.0), low("23:59", 0.4)));
        stubExtremes(extremes);

        TideRunDay day = builder.build(List.of(DAY_1, DAY_2), List.of(seaham()), false).get(DAY_2);

        // The verdict still has a sentence — that is its job, and it is why reading it as an
        // alignment printed a water well out of the light as this run's finding.
        assertThat(day.verdict()).isEqualTo("peak range · HW 1h40 after sunset");
        assertThat(day.alignmentPhrase()).isNull();
        // Non-null together, always. TideAlmanacSource keys the whole distinction on this.
        assertThat(day.alignedEvent()).isNull();
    }

    // ── bestAligned — which single day of the run takes the accent ────────────

    @Test
    @DisplayName("only the closest-aligned day of the run is marked best, not every aligned day")
    void build_bestAligned_marksOneDayEvenWhenSeveralAlign() {
        // The reason this field exists. Once either water can align, a run can have every day
        // aligned — measured at ~28% of runs — and an accent on all of them marks nothing. DAY_1's
        // low is 34m after sunrise, DAY_2's is 5m. Both are aligned; only the nearer takes the
        // accent, and the other keeps everything else it had.
        //
        // DAY_2 also carries the bigger range, so DAY_1 is not the peak day — that keeps the
        // verdict assertion below on the plain aligned form rather than the peak one, which
        // compresses the clock time away. Peak and best-aligned are pulled apart in their own test.
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("08:44", 0.4), high("14:56", 5.0)));
        extremes.addAll(day(ID_SEAHAM, DAY_2,
                low("08:15", 0.5), high("14:20", 5.6)));
        stubExtremes(extremes);

        Map<LocalDate, TideRunDay> run = builder.build(
                List.of(DAY_1, DAY_2), List.of(seaham()), false);

        assertThat(run.get(DAY_1).aligned()).isTrue();
        assertThat(run.get(DAY_2).aligned()).isTrue();
        assertThat(run.get(DAY_1).bestAligned()).isFalse();
        assertThat(run.get(DAY_2).bestAligned()).isTrue();
        // The un-accented day is not demoted in any other way: it keeps its verdict and the
        // editorial line for the water that did reach the light. Only emphasis is reserved.
        assertThat(run.get(DAY_1).verdict()).isEqualTo("LW 08:44 · 34m after sunrise");
        assertThat(run.get(DAY_1).phrase()).isEqualTo(TideRunBuilder.LOW_WATER_PHRASE);
    }

    @Test
    @DisplayName("a run where nothing reaches the light accents no day at all")
    void build_bestAligned_absentWhenNoDayAligns() {
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("11:30", 0.8), high("17:40", 4.0), low("23:50", 0.8)));
        extremes.addAll(day(ID_SEAHAM, DAY_2,
                low("12:00", 0.4), high("18:10", 5.0), low("23:59", 0.4)));
        stubExtremes(extremes);

        Map<LocalDate, TideRunDay> run = builder.build(
                List.of(DAY_1, DAY_2), List.of(seaham()), false);

        assertThat(run.values()).allSatisfy(d -> {
            assertThat(d.aligned()).isFalse();
            assertThat(d.bestAligned()).isFalse();
        });
    }

    @Test
    @DisplayName("an aligned single-day run still takes the accent — unlike peak, this is no claim")
    void build_bestAligned_isClaimedOnASingleDayRun() {
        // `peak` is suppressed on a one-day run because "biggest of the run" asserts a comparison
        // never made. This is an emphasis marker, not a comparative badge: withholding it would
        // leave a genuinely aligned day with no accent anywhere, which is a regression.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("08:30", 0.4), high("14:40", 5.0), low("20:50", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.peak()).isFalse();
        assertThat(day.aligned()).isTrue();
        assertThat(day.bestAligned()).isTrue();
    }

    @Test
    @DisplayName("equally aligned days give the accent to the earlier date")
    void build_bestAligned_tieGoesToTheEarlierDay() {
        // Both lows sit 34m after their own sunrise. The sooner morning is the one a reader can
        // still act on, and the rule has to be decidable rather than left to map ordering.
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("08:44", 0.4), high("14:56", 5.0)));
        extremes.addAll(day(ID_SEAHAM, DAY_2,
                low("08:44", 0.5), high("14:56", 4.5)));
        stubExtremes(extremes);

        Map<LocalDate, TideRunDay> run = builder.build(
                List.of(DAY_1, DAY_2), List.of(seaham()), false);

        assertThat(run.get(DAY_1).bestAligned()).isTrue();
        assertThat(run.get(DAY_2).bestAligned()).isFalse();
    }

    @Test
    @DisplayName("the accent is independent of peak range — the best light need not be the biggest")
    void build_bestAligned_isNotThePeakDay() {
        // Two separate questions about a run, and conflating them would put the accent on the
        // biggest swing rather than the best light. DAY_1 has the bigger range; DAY_2 has the
        // closer water.
        stubSolar();
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("08:44", 0.4), high("14:56", 5.6)));
        extremes.addAll(day(ID_SEAHAM, DAY_2,
                low("08:12", 1.0), high("14:20", 4.0)));
        stubExtremes(extremes);

        Map<LocalDate, TideRunDay> run = builder.build(
                List.of(DAY_1, DAY_2), List.of(seaham()), false);

        assertThat(run.get(DAY_1).peak()).isTrue();
        assertThat(run.get(DAY_1).bestAligned()).isFalse();
        assertThat(run.get(DAY_2).peak()).isFalse();
        assertThat(run.get(DAY_2).bestAligned()).isTrue();
    }

    @Test
    @DisplayName("a single-day run claims no peak — there is nothing to be the peak of")
    void build_singleDayRun_isNeverPeak() {
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("11:30", 0.8), high("17:40", 4.0), low("23:50", 0.8)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.peak()).isFalse();
        assertThat(day.verdict()).doesNotContain("peak range");
    }

    @Test
    @DisplayName("everything else places the water in the day and measures it")
    void verdict_plainlyUnaligned() {
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("10:46", 0.4), high("13:00", 5.0), low("23:11", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        // 10:46 is 2h36 after sunrise and 5h44 before sunset — the nearer event wins. It is also
        // this day's nearest water to the light at all: the 13:00 high is 3h30 from sunset.
        assertThat(day.verdict()).isEqualTo("LW mid-morning · 2h36 after sunrise");
        assertThat(day.aligned()).isFalse();
    }

    @Test
    @DisplayName("an evening water is measured forward from sunset, never back across midnight")
    void verdict_eveningWater_doesNotWrapMidnight() {
        // 17:40 against a 16:30 sunset is 1h10 after it. Wrapping the day would instead measure the
        // gap to the *next* morning's sunrise — a different day, and not what the card is about.
        //
        // The 23:50 low this test used to assert on can no longer be the named water: with the row
        // naming whatever water is nearest the light, something is always nearer than a water seven
        // hours past sunset. That is the rule working, not a gap in it — the late low is still drawn
        // on the chart with its own clock time.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("11:30", 0.8), high("17:40", 4.0), low("23:50", 0.8)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.verdict()).isEqualTo("HW early evening · 1h10 after sunset");
    }

    @Test
    @DisplayName("an overnight water is measured back to sunrise and placed in words")
    void verdict_overnightWater_measuredBackToSunrise() {
        // The other end of the same rule, and the band a run at this latitude in winter can still
        // reach: the 04:30 low is 3h40 before sunrise, nearer the light than the midday high, which
        // sits at the furthest point a water can be from both events at once.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1, low("04:30", 0.4), high("12:20", 5.0)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.verdict()).isEqualTo("LW overnight · 3h40 before sunrise");
        assertThat(day.aligned()).isFalse();
    }

    // ── range anomaly, seas, extrema ──────────────────────────────────────────

    @Test
    @DisplayName("range anomaly is signed against the location's own mean range")
    void build_rangeAnomaly_isSignedAgainstMean() {
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("05:00", 0.4), high("11:10", 5.0), low("17:20", 0.4)));
        when(tideService.getTideStats(ID_SEAHAM)).thenReturn(Optional.of(stats("3.5")));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.range()).isEqualTo("4.6 m");
        assertThat(day.rangeAnomaly()).isEqualTo("+1.1");
    }

    @Test
    @DisplayName("an anomaly below display noise is left off rather than shown as +0.0")
    void build_tinyAnomaly_isOmitted() {
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("05:00", 0.4), high("11:10", 4.42), low("17:20", 0.4)));
        when(tideService.getTideStats(ID_SEAHAM)).thenReturn(Optional.of(stats("4.0")));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.rangeAnomaly()).isNull();
    }

    @Test
    @DisplayName("sea state is sampled for the event the day's named water is measured against")
    void build_seas_prefersTheMeasuredEvent() {
        // The low water sits nearest sunrise, so the sunrise sample is the one that describes it.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("08:44", 0.4), high("14:56", 5.0), low("21:09", 0.4)));
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                ID_SEAHAM, DAY_1, TargetType.SUNRISE)).thenReturn(Optional.of(wave(0.3)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.seas()).isEqualTo("0.3 m · smooth");
    }

    @Test
    @DisplayName("every extreme in the local day is carried, in order, typed H or L")
    void build_carriesEveryExtremeInOrder() {
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                high("04:57", 5.0), low("11:27", 0.4), high("17:22", 5.0), low("23:52", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.tides()).containsExactly(
                new TideRunDay.Extreme("H", "04:57"),
                new TideRunDay.Extreme("L", "11:27"),
                new TideRunDay.Extreme("H", "17:22"),
                new TideRunDay.Extreme("L", "23:52"));
        assertThat(day.sunrise()).isEqualTo("08:10");
        assertThat(day.sunset()).isEqualTo("16:30");
    }

    @Test
    @DisplayName("stored UTC extremes are read as British Summer Time in summer")
    void build_summerDate_convertsToBst() {
        // Tide extremes and solar times are stored UTC. Through BST every clock time on the card is
        // an hour later than its stored value — slicing the raw string would put the whole chart out.
        LocalDate july = LocalDate.of(2026, 7, 28);
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), eq(july)))
                .thenReturn(july.atTime(4, 10));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), eq(july)))
                .thenReturn(july.atTime(20, 22));
        stubExtremes(List.of(
                extreme(ID_SEAHAM, july.atTime(4, 44), 0.4, TideExtremeType.LOW),
                extreme(ID_SEAHAM, july.atTime(10, 56), 5.0, TideExtremeType.HIGH),
                extreme(ID_SEAHAM, july.atTime(17, 9), 0.4, TideExtremeType.LOW)));

        TideRunDay day = builder.build(List.of(july), List.of(seaham()), false).get(july);

        assertThat(day.sunrise()).isEqualTo("05:10");
        assertThat(day.sunset()).isEqualTo("21:22");
        assertThat(day.tides().get(0).time()).isEqualTo("05:44");
        assertThat(day.verdict()).isEqualTo("LW 05:44 · 34m after sunrise");
    }

    // ── degenerate inputs ─────────────────────────────────────────────────────

    @Test
    @DisplayName("no coastal locations means no run, not an empty chart")
    void build_noLocations_returnsEmpty() {
        assertThat(builder.build(List.of(DAY_1), List.of(), false)).isEmpty();
    }

    @Test
    @DisplayName("no stored extremes means no run")
    void build_noExtremes_returnsEmpty() {
        stubExtremes(List.of());
        assertThat(builder.build(List.of(DAY_1), List.of(seaham()), false)).isEmpty();
    }

    // ── peakRange / peakRangeAt (plan D4 — magnitude scoring's numeric read) ──

    @Test
    @DisplayName("peakRange selects the same representative and range as build(), as a raw double")
    void peakRange_matchesBuild() {
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_WHITBY, DAY_1,
                low("04:30", 1.0), high("10:40", 3.0), low("16:50", 1.0)));
        extremes.addAll(day(ID_SEAHAM, DAY_1,
                low("05:00", 0.4), high("11:10", 5.0), low("17:20", 0.4)));
        stubExtremes(extremes);

        Optional<TideRunBuilder.RunPeak> peak = builder.peakRange(List.of(DAY_1), List.of(whitby(), seaham()));

        assertThat(peak).isPresent();
        assertThat(peak.get().representative().getName()).isEqualTo("Seaham");
        assertThat(peak.get().rangeMetres()).isEqualTo(4.6, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("peakRange takes the biggest day across a multi-day run, not just the first")
    void peakRange_takesTheBiggestDayInTheRun() {
        List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_SEAHAM, DAY_1,
                low("05:00", 0.4), high("11:10", 3.0), low("17:20", 0.4)));
        extremes.addAll(day(ID_SEAHAM, DAY_2,
                low("05:40", 0.2), high("11:50", 5.0), low("18:00", 0.2)));
        stubExtremes(extremes);

        Optional<TideRunBuilder.RunPeak> peak =
                builder.peakRange(List.of(DAY_1, DAY_2), List.of(seaham()));

        assertThat(peak).isPresent();
        assertThat(peak.get().rangeMetres()).isEqualTo(4.8, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("peakRange is empty with no dates, no roster, or no derivable day")
    void peakRange_emptyInputsYieldEmpty() {
        assertThat(builder.peakRange(List.of(), List.of(seaham()))).isEmpty();
        assertThat(builder.peakRange(List.of(DAY_1), List.of())).isEmpty();

        stubExtremes(List.of());
        assertThat(builder.peakRange(List.of(DAY_1), List.of(seaham()))).isEmpty();
    }

    @Test
    @DisplayName("peakRangeAt measures the location it is given, not the biggest range available — "
            + "unlike peakRange, it never auto-selects, because the historical distribution must "
            + "stay pinned to the same port the forward run was drawn for")
    void peakRangeAt_usesTheGivenLocation_neverAutoSelects() {
        List<TideExtremeEntity> both = new ArrayList<>(day(ID_WHITBY, DAY_1,
                low("04:30", 1.0), high("10:40", 3.0), low("16:50", 1.0)));
        // Seaham's range (4.6 m) is bigger, but the call below asks for Whitby specifically.
        both.addAll(day(ID_SEAHAM, DAY_1, low("05:00", 0.4), high("11:10", 5.0), low("17:20", 0.4)));
        stubExtremes(both);

        java.util.OptionalDouble range = builder.peakRangeAt(whitby(), List.of(DAY_1));

        assertThat(range).hasValue(2.0);
    }

    @Test
    @DisplayName("peakRangeAt is empty for a null representative, no dates, or no derivable day")
    void peakRangeAt_emptyInputsYieldEmpty() {
        assertThat(builder.peakRangeAt(null, List.of(DAY_1))).isEmpty();
        assertThat(builder.peakRangeAt(whitby(), List.of())).isEmpty();

        stubExtremes(List.of());
        assertThat(builder.peakRangeAt(whitby(), List.of(DAY_1))).isEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private void stubSolar() {
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> ((LocalDate) inv.getArgument(2))
                        .atTime(SUNRISE.toLocalTime()));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> ((LocalDate) inv.getArgument(2))
                        .atTime(SUNSET.toLocalTime()));
    }

    private void stubExtremes(List<TideExtremeEntity> extremes) {
        when(tideExtremeRepository.findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
                any(), any(), any())).thenReturn(extremes);
    }

    private static LocationEntity seaham() {
        return LocationEntity.builder().id(ID_SEAHAM).name("Seaham")
                .lat(54.84).lon(-1.33).build();
    }

    private static LocationEntity whitby() {
        return LocationEntity.builder().id(ID_WHITBY).name("Whitby")
                .lat(54.49).lon(-0.61).build();
    }

    private static TideStats stats(String avgRange) {
        return stats(avgRange, null);
    }

    private static TideStats stats(String avgRange, String springThreshold) {
        return new TideStats(null, null, null, null, 100L, new BigDecimal(avgRange),
                null, null, null, 0L, null,
                springThreshold == null ? null : new BigDecimal(springThreshold), null, 0L);
    }

    /**
     * Statistics with enough observed history to rank a high water against.
     *
     * @param maxHigh    the highest water on record
     * @param p95        the 95th percentile, or null when no spring-neap cycle was observed
     * @param dataPoints stored extremes behind the sample
     * @return the statistics
     */
    private static TideStats ranked(String maxHigh, String p95, long dataPoints) {
        return new TideStats(null, new BigDecimal(maxHigh), null, null, dataPoints,
                new BigDecimal("3.5"), null, null,
                p95 == null ? null : new BigDecimal(p95),
                // A spring threshold below the high waters these tests use, so the size gate opens.
                // It moves with p95 rather than being independent of it because TideService derives
                // both under one `cycleObserved` check — a location has both or neither, and a
                // fixture supplying only one describes a state production cannot reach. This helper
                // used to omit it entirely, which was harmless only while the high-water chips were
                // gated on the run's lunar label instead of on the size of the water.
                0L, null, p95 == null ? null : new BigDecimal("4.4"), null, 0L);
    }

    private static MarineWaveEntity wave(double hs) {
        MarineWaveEntity entity = new MarineWaveEntity();
        entity.setSignificantWaveHeightMetres(hs);
        return entity;
    }

    /** A pending extreme, resolved to a date by {@link #day}. */
    private record Pending(String clock, double height, TideExtremeType type) {
    }

    private static Pending high(String clock, double height) {
        return new Pending(clock, height, TideExtremeType.HIGH);
    }

    private static Pending low(String clock, double height) {
        return new Pending(clock, height, TideExtremeType.LOW);
    }

    private static List<TideExtremeEntity> day(long locationId, LocalDate date, Pending... pending) {
        List<TideExtremeEntity> out = new ArrayList<>();
        for (Pending p : pending) {
            String[] parts = p.clock().split(":");
            out.add(extreme(locationId,
                    date.atTime(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])),
                    p.height(), p.type()));
        }
        return out;
    }

    private static TideExtremeEntity extreme(long locationId, LocalDateTime utc, double height,
            TideExtremeType type) {
        return TideExtremeEntity.builder()
                .locationId(locationId)
                .eventTime(utc)
                .heightMetres(BigDecimal.valueOf(height))
                .type(type)
                .build();
    }
    @Test
    @DisplayName("a day with NO water in the light claims no draw — the phrase stands down")
    void build_unalignedDay_hasNoPhrase() {
        // "low water bares the foreground" is true of the water and useless to the reader when the
        // low is at 01:00 in the dark. The verdict beside it already says the water misses the
        // light; printing the draw anyway had the two lines arguing, the confident one wrong.
        //
        // Both waters have to miss now, which is what makes this an honest silence rather than an
        // artefact of the run's label: the 12:00 high is 3h50 out and the 18:00 low 1h30 past
        // sunset. Under the old framing this same day was silent because the LOW missed, while a
        // high water 50 minutes after sunrise would have been ignored.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("01:00", 0.4), high("12:00", 5.0), low("18:00", 0.4)));

        TideRunDay result = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(result.aligned()).isFalse();
        assertThat(result.phrase()).isNull();
        // The verdict still carries the whole answer — silence is only about the editorial claim.
        assertThat(result.verdict()).isNotBlank();
    }

    @Test
    @DisplayName("an ALIGNED day still claims it — the gate must not silence the phrase entirely")
    void build_alignedDay_keepsPhrase() {
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("08:30", 0.4), high("14:40", 5.0), low("20:50", 0.4)));

        TideRunDay result = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(result.aligned()).isTrue();
        assertThat(result.phrase()).isEqualTo(TideRunBuilder.LOW_WATER_PHRASE);
    }
}
