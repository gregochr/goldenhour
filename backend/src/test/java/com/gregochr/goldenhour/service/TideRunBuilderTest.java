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
 * <p>The run rows exist to answer one question — <em>does the useful water land near a solar
 * event?</em> — so what is pinned here is the arithmetic behind that answer: which location the run
 * is drawn for, how a day's range and anomaly are derived, when a day counts as aligned, and which
 * of the four verdict forms a day earns. Times are fixed rather than relative so the assertions read
 * as the strings a photographer sees.
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
    @DisplayName("a king run is labelled and framed by high water, not exposed foreground")
    void build_kingRun_usesHighWaterAndItsOwnPhrase() {
        // A king tide's draw is the highest water — "low water bares the foreground" is the wrong
        // sentence for it, and the verdict must measure HW rather than LW.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                low("02:00", 0.4), high("08:35", 5.0), low("14:20", 0.4)));

        when(tideService.getTideStats(ID_SEAHAM)).thenReturn(Optional.of(stats("3.5", "4.6")));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), true).get(DAY_1);

        assertThat(day.runLabel()).isEqualTo("KING RUN");
        assertThat(day.phrase()).isEqualTo(TideRunBuilder.KING_PHRASE);
        assertThat(day.verdict()).isEqualTo("HW 08:35 · 25m after sunrise");
        assertThat(day.aligned()).isTrue();
        // A king tide's defining number is how HIGH the water gets and how far that clears the
        // spring threshold — the range-against-mean framing belongs to a spring run.
        assertThat(day.highWater()).isEqualTo("5.0 m");
        assertThat(day.highWaterAnomaly()).isEqualTo("+0.4 m over spring");
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
    @DisplayName("high water sitting on sunrise is said plainly, then low water is placed")
    void verdict_otherExtremumCoincides() {
        // The low water is hours away, but "high water at sunrise" is itself a reason to go, so it
        // leads — and the low water is still measured against its own nearer event.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                high("07:59", 5.0), low("14:25", 0.4), high("20:24", 5.0)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.verdict()).isEqualTo("HW at sunrise · LW 2h05 before sunset");
        assertThat(day.aligned()).isFalse();
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
        // The 12:00 low is 3h50 after sunrise and 4h30 before sunset — the nearer event wins.
        assertThat(day.verdict()).isEqualTo("peak range · LW 3h50 after sunrise");
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
                low("10:46", 0.4), high("19:00", 5.0), low("23:11", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        // 10:46 is 2h36 after sunrise and 5h44 before sunset — the nearer event wins.
        assertThat(day.verdict()).isEqualTo("LW mid-morning · 2h36 after sunrise");
        assertThat(day.aligned()).isFalse();
    }

    @Test
    @DisplayName("a late low water is measured forward from sunset, never back across midnight")
    void verdict_lateWater_doesNotWrapMidnight() {
        // 23:50 against a 16:30 sunset is 7h20 after it. Wrapping the day would instead measure it
        // 8h20 before the *next* morning's sunrise — a different day, and not what the card is about.
        stubSolar();
        stubExtremes(day(ID_SEAHAM, DAY_1,
                high("05:20", 5.0), high("17:45", 5.0), low("23:50", 0.4)));

        TideRunDay day = builder.build(List.of(DAY_1), List.of(seaham()), false).get(DAY_1);

        assertThat(day.verdict()).isEqualTo("LW overnight · 7h20 after sunset");
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
    @DisplayName("sea state is sampled for the event the useful water is measured against")
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
}
