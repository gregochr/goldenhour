package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingWindowTide;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The serve-time tide rollup behind the window-first Plan tab's tide row.
 *
 * <p>The row makes four claims a coastal photographer acts on — where the tide is and which way it
 * is going, which extreme is nearest and how far from the light, how big the day is against normal,
 * and what the sea is doing — all measured at <em>one named location</em>. What is pinned here is
 * that each of those is derived rather than assumed, that each degrades on its own, and that the
 * whole rollup disappears rather than being approximated when the data is not there.
 *
 * <p>Dates sit in <b>January</b>, where Europe/London is UTC, so a stored UTC extreme and its local
 * clock time coincide and each assertion reads as the string a reader sees. The BST offset is
 * exercised separately.
 */
@ExtendWith(MockitoExtension.class)
class WindowTideRollupBuilderTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 27);
    private static final LocalDate NEXT_DAY = DAY.plusDays(1);

    private static final long ID_WHITBY = 1L;
    private static final long ID_SEAHAM = 2L;

    /** Samples across the local day at 30-minute intervals, inclusive of both midnights. */
    private static final int CURVE_POINTS = 49;

    /**
     * A stand-in for the elevation-derived alignment window. Deliberately NOT 60: the defect this
     * pins is a row classified at a fixed hour while the drill-down beneath it uses half the
     * blue+golden span, so a fixture at 60 could not tell the two rules apart.
     */
    private static final long SHARED_WINDOW_MINUTES = 41;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private TideExtremeRepository tideExtremeRepository;

    @Mock
    private MarineWaveRepository marineWaveRepository;

    @Mock
    private TideService tideService;

    @Mock
    private SolarService solarService;

    @Mock
    private TideFactDeriver tideFactDeriver;

    private WindowTideRollupBuilder builder;

    @BeforeEach
    void setUp() {
        // No anchor configured: the default is pure biggest-range selection, and the anchor's own
        // behaviour is the shared selector's, exercised below and in TideRunBuilderTest.
        builder = anchoredTo("");
    }

    private WindowTideRollupBuilder anchoredTo(String anchor) {
        return new WindowTideRollupBuilder(locationRepository, tideExtremeRepository,
                marineWaveRepository, tideService, solarService, tideFactDeriver, anchor);
    }

    // ── the location the row names ────────────────────────────────────────────

    @Nested
    @DisplayName("the location every figure is measured at")
    class Representative {

        @Test
        @DisplayName("names the location the numbers come from, not the roster")
        void namesTheRepresentative() {
            // The row states an offset to the minute over a roster its own action line calls "61
            // coastal locations". Alignment differs ~20-30 min across a coastline, so an
            // unattributed high-water time is a claim this project cannot make.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));

            assertThat(rollup(DAY, TargetType.SUNSET).locationName()).isEqualTo("Whitby");
        }

        @Test
        @DisplayName("one location for the whole forecast, not one per window")
        void oneLocationAcrossEveryWindow() {
            // Choosing per window would let the curve jump coastlines between Tuesday and
            // Thursday, so a reader comparing two windows would be comparing two places.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby(), seaham());
            List<TideExtremeEntity> extremes = new ArrayList<>(whitbyDay(DAY));
            extremes.addAll(whitbyDay(NEXT_DAY));
            // Seaham swings wider on the SECOND day only, so a per-day choice would switch coasts.
            extremes.addAll(day(ID_SEAHAM, NEXT_DAY,
                    low("03:00", 0.2), high("09:15", 6.4), low("15:20", 0.2)));
            stubExtremes(extremes);

            Map<PlanWindowProjector.WindowKey, BriefingWindowTide> rollups =
                    builder.build(List.of(dayOf(DAY), dayOf(NEXT_DAY)));

            assertThat(rollups.values())
                    .extracting(BriefingWindowTide::locationName)
                    .containsOnly("Seaham");
        }

        @Test
        @DisplayName("the configured anchor wins over a bigger range elsewhere")
        void anchorOutranksBiggestRange() {
            // Same rule as the tide run's, from the same shared selector: biggest-range is not
            // neutral across a roster spanning a coastline.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby(), seaham());
            List<TideExtremeEntity> extremes = new ArrayList<>(whitbyDay(DAY));
            extremes.addAll(day(ID_SEAHAM, DAY,
                    low("03:00", 0.2), high("09:15", 6.4), low("15:20", 0.2)));
            stubExtremes(extremes);

            BriefingWindowTide tide = anchoredTo("Whitby")
                    .build(List.of(dayOf(DAY)))
                    .get(key(DAY, TargetType.SUNSET));

            assertThat(tide.locationName()).isEqualTo("Whitby");
            assertThat(tide.range()).isEqualTo("4.8 m");
        }
    }

    // ── the nearest water, and how far from the light ─────────────────────────

    @Nested
    @DisplayName("the nearest extreme")
    class Nearest {

        @Test
        @DisplayName("names the nearest extreme of EITHER kind, and its offset from the window")
        void nearestIsEitherKind() {
            // High water 25 minutes after sunrise is the water a reader can act on. Restricting
            // the search to one kind is what made the tide run's verdict state a low water five
            // hours away on exactly such a morning (#402).
            stubSunrise();
            stubState(TideState.HIGH);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));

            BriefingWindowTide tide = rollup(DAY, TargetType.SUNRISE);

            assertThat(tide.nearestType()).isEqualTo("HW");
            assertThat(tide.nearestTime()).isEqualTo("08:35");
            assertThat(tide.nearestOffset()).isEqualTo("25m after sunrise");
        }

        @Test
        @DisplayName("a low water nearer than the high wins, and reads as before the event")
        void lowWaterBeforeTheEvent() {
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));

            BriefingWindowTide tide = rollup(DAY, TargetType.SUNSET);

            // LW 14:45 is 1h45 before sunset; HW 20:55 is 4h25 after it.
            assertThat(tide.nearestType()).isEqualTo("LW");
            assertThat(tide.nearestTime()).isEqualTo("14:45");
            assertThat(tide.nearestOffset()).isEqualTo("1h45 before sunset");
        }

        @Test
        @DisplayName("water on the event states no duration")
        void waterOnTheEventStatesNoDuration() {
            // "0m after sunrise" is a null statement dressed as a measurement, and it fires on the
            // single most emphatic morning a coast can have.
            stubSunrise();
            stubState(TideState.HIGH);
            stubCoastal(whitby());
            stubExtremes(day(ID_WHITBY, DAY,
                    low("02:20", 0.6), high("08:12", 5.4), low("14:45", 0.9)));

            assertThat(rollup(DAY, TargetType.SUNRISE).nearestOffset()).isEqualTo("at sunrise");
        }

        @Test
        @DisplayName("an extreme on the neighbouring day still reads as its own clock time")
        void neighbouringDayExtremeKeepsItsClockTime() {
            // The fetch reaches a day either side so the curve can be interpolated at both ends;
            // a late-evening window can legitimately find its nearest water after midnight.
            stubState(TideState.MID);
            stubCoastal(whitby());
            List<TideExtremeEntity> extremes = new ArrayList<>(day(ID_WHITBY, DAY,
                    low("04:00", 0.6), high("10:15", 5.4), low("16:25", 0.9)));
            extremes.addAll(day(ID_WHITBY, NEXT_DAY, high("00:05", 5.2)));
            stubExtremes(extremes);
            when(solarService.sunsetUtc(anyDouble(), anyDouble(), eq(DAY)))
                    .thenReturn(DAY.atTime(23, 45));

            BriefingWindowTide tide = rollup(DAY, TargetType.SUNSET);

            assertThat(tide.nearestTime()).isEqualTo("00:05");
            assertThat(tide.nearestOffset()).isEqualTo("20m after sunset");
        }
    }

    // ── how big the day is ────────────────────────────────────────────────────

    @Nested
    @DisplayName("range against the location's own mean")
    class Range {

        @Test
        void rangeIsTheDaySwingAtTheRepresentative() {
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));

            // 5.4 m high against 0.6 m low.
            assertThat(rollup(DAY, TargetType.SUNSET).range()).isEqualTo("4.8 m");
        }

        @Test
        void aBiggerThanAverageDaySaysSoInMetres() {
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));
            // Mean range 4.55 against a 3.35 m baseline.
            when(tideService.getTideStats(ID_WHITBY)).thenReturn(Optional.of(stats("3.35")));

            assertThat(rollup(DAY, TargetType.SUNSET).rangeAnomaly())
                    .isEqualTo("1.2 m above an average tide");
        }

        @Test
        void aSmallerThanAverageDaySaysBelow() {
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));
            // Mean range 4.55 against a 5.75 m baseline.
            when(tideService.getTideStats(ID_WHITBY)).thenReturn(Optional.of(stats("5.75")));

            assertThat(rollup(DAY, TargetType.SUNSET).rangeAnomaly())
                    .isEqualTo("1.2 m below an average tide");
        }

        @Test
        @DisplayName("a difference below display noise reads as average rather than as a figure")
        void anUnremarkableDayReadsAsAverage() {
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));
            // The fixture's MEAN range: highs 5.4/5.2 -> 5.30, lows 0.6/0.9 -> 0.75, so 4.55.
            when(tideService.getTideStats(ID_WHITBY)).thenReturn(Optional.of(stats("4.54")));

            assertThat(rollup(DAY, TargetType.SUNSET).rangeAnomaly()).isEqualTo("about average");
        }

        @Test
        @DisplayName("an exactly average day reads average, though its biggest swing exceeds the mean")
        void anAverageDayIsNotReportedAsAboveAverage() {
            // The defect this pins: `range` is max(highs) - min(lows), an EXTREME, while the
            // baseline is AVG(highs) - AVG(lows), a MEAN. Subtracting one from the other is biased
            // upward by the day's own diurnal inequality — for this fixture by 0.25 m, five times
            // the 0.05 m display threshold. Because this row renders on EVERY window of every day,
            // that bias is the normal case: "about average" becomes unreachable and every window
            // claims an above-average tide. The comparison must therefore be mean against mean.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));
            when(tideService.getTideStats(ID_WHITBY)).thenReturn(Optional.of(stats("4.55")));

            BriefingWindowTide tide = rollup(DAY, TargetType.SUNSET);

            assertThat(tide.rangeAnomaly()).isEqualTo("about average");
            // The headline stays the biggest swing — the two figures measure different things.
            assertThat(tide.range()).isEqualTo("4.8 m");
        }

        @Test
        @DisplayName("a day with no low water states no anomaly rather than half a comparison")
        void aDayWithoutBothKindsStatesNoAnomaly() {
            // Reachable only through the neighbouring-day margin: the day itself must have both a
            // high and a low to be drawn at all, so this pins the guard rather than a live path.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));
            when(tideService.getTideStats(ID_WHITBY)).thenReturn(Optional.empty());

            assertThat(rollup(DAY, TargetType.SUNSET).rangeAnomaly()).isNull();
        }

        @Test
        @DisplayName("no historical baseline is silence, never 'about average'")
        void noBaselineIsNull() {
            // The two are different statements. "About average" claims a comparison; null says
            // none was possible, and the row must not print the first when it means the second.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));

            assertThat(rollup(DAY, TargetType.SUNSET).rangeAnomaly()).isNull();
        }
    }

    // ── the sea, which stops four days before the tides do ────────────────────

    @Nested
    @DisplayName("sea state")
    class Seas {

        @Test
        void statesTheWaveHeightWithItsBand() {
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));
            when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                    ID_WHITBY, DAY, TargetType.SUNSET)).thenReturn(Optional.of(wave(0.3)));

            assertThat(rollup(DAY, TargetType.SUNSET).seas()).isEqualTo("0.3 m · smooth");
        }

        @Test
        @DisplayName("a window past the wave horizon keeps its whole rollup, minus the sea")
        void missingSeaStateDoesNotSuppressTheRollup() {
            // marine_wave reaches T+4; tide_extreme reaches months ahead. A window beyond the wave horizon
            // is the NORMAL case at the far end of the rail, so the field has to degrade on its own
            // — letting it suppress the row would blank the tide for the last third of the forecast.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));

            BriefingWindowTide tide = rollup(DAY, TargetType.SUNSET);

            assertThat(tide.seas()).isNull();
            assertThat(tide.range()).isEqualTo("4.8 m");
            assertThat(tide.nearestTime()).isEqualTo("14:45");
            assertThat(tide.curve()).hasSize(CURVE_POINTS);
        }

        @Test
        @DisplayName("a sunrise row never borrows the sunset's sample")
        void noFallbackToTheOtherEvent() {
            // The tide run falls back between events because it describes a whole day. This row
            // describes one window, and borrowing the evening's sea for a dawn row would state a
            // sea the reader will not be standing in.
            stubSunrise();
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));
            when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                    ID_WHITBY, DAY, TargetType.SUNSET)).thenReturn(Optional.of(wave(2.7)));
            when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                    ID_WHITBY, DAY, TargetType.SUNRISE)).thenReturn(Optional.empty());

            Map<PlanWindowProjector.WindowKey, BriefingWindowTide> rollups =
                    builder.build(List.of(bothWindows(DAY)));

            assertThat(rollups.get(key(DAY, TargetType.SUNSET)).seas()).isEqualTo("2.7 m · rough");
            assertThat(rollups.get(key(DAY, TargetType.SUNRISE)).seas()).isNull();
        }
    }

    // ── state and direction ───────────────────────────────────────────────────

    @Nested
    @DisplayName("state and direction")
    class StateAndDirection {

        @Test
        @DisplayName("the state comes from the shared classifier, at this location's own event")
        void stateDelegatesToTheSharedRule() {
            // The row and the drill-down beneath it must not call the same water HIGH and MID, so
            // the rule is TideService's rather than a second +/-60-minute test written here. What
            // this pins is the seam: the classifier is asked, and asked at the representative's own
            // sunset rather than at the window's earliest-slot time.
            stubSunset();
            stubState(TideState.LOW);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));

            BriefingWindowTide tide = rollup(DAY, TargetType.SUNSET);

            ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TideExtremeEntity>> given =
                    ArgumentCaptor.forClass(List.class);
            verify(tideService).classifyTideState(
                    given.capture(), at.capture(), eq(SHARED_WINDOW_MINUTES));

            assertThat(tide.state()).isEqualTo(TideState.LOW);
            assertThat(at.getValue()).isEqualTo(DAY.atTime(16, 30));
            // Classified over the REPRESENTATIVE's own water, not the roster's — a coastline-wide
            // list here would have the row state one place's tide under another place's name.
            assertThat(given.getValue()).isNotEmpty().allSatisfy(
                    e -> assertThat(e.getLocationId()).isEqualTo(ID_WHITBY));
        }

        @Test
        @DisplayName("the window width is the per-slot facts' own, never a fixed hour")
        void stateIsClassifiedAtTheSameWindowAsThePerSlotFacts() {
            // A fixed +/-60 would be a second rule. At 54.5N around the equinox the real half-width
            // is ~41 minutes, so a high water 50 minutes off sunset would read HIGH in this row and
            // MID in the drill-down slot directly beneath it — one location, one evening, two
            // answers. The width must come from the same place, sized for the same location, date
            // and event.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));

            rollup(DAY, TargetType.SUNSET);

            verify(tideFactDeriver).tightAlignmentWindowMinutes(
                    eq(54.49), eq(-0.61), eq(DAY.atTime(16, 30)), eq(TargetType.SUNSET));
            verify(tideService).classifyTideState(anyList(), any(), eq(SHARED_WINDOW_MINUTES));
        }

        @Test
        void waterHeadingForAHighReadsAsRising() {
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));

            // Sunset 16:30 sits between LW 14:45 and HW 20:55.
            assertThat(rollup(DAY, TargetType.SUNSET).direction())
                    .isEqualTo(BriefingWindowTide.Direction.RISING);
        }

        @Test
        void waterHeadingForALowReadsAsFalling() {
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(day(ID_WHITBY, DAY,
                    low("02:20", 0.6), high("14:50", 5.4), low("21:05", 0.9)));

            // Sunset 16:30 now sits between HW 14:50 and LW 21:05.
            assertThat(rollup(DAY, TargetType.SUNSET).direction())
                    .isEqualTo(BriefingWindowTide.Direction.FALLING);
        }
    }

    // ── the sparkline ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the day's tide shape")
    class Curve {

        @Test
        @DisplayName("samples the whole local day and normalises to its own span")
        void curveSpansTheDayAndIsNormalised() {
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));

            List<Double> curve = rollup(DAY, TargetType.SUNSET).curve();

            assertThat(curve).hasSize(CURVE_POINTS);
            assertThat(curve).allSatisfy(v -> assertThat(v).isBetween(0.0, 1.0));
            assertThat(curve).contains(0.0).contains(1.0);
        }

        @Test
        @DisplayName("the window's mark sits on the line it is drawn against")
        void markerSitsOnTheCurve() {
            // The mark and the trace are the same claim seen twice; normalising them against
            // separately recomputed spans is how a mark comes to float off its own curve.
            stubSunrise();
            stubState(TideState.HIGH);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));

            BriefingWindowTide tide = rollup(DAY, TargetType.SUNRISE);

            int segment = (int) Math.floor(tide.windowPosition() * (CURVE_POINTS - 1));
            double before = tide.curve().get(segment);
            double after = tide.curve().get(segment + 1);
            assertThat(tide.windowLevel())
                    .isBetween(Math.min(before, after) - 0.02, Math.max(before, after) + 0.02);
        }

        @Test
        @DisplayName("sunrise sits at its own fraction of the day, not at an arbitrary point")
        void windowPositionIsTheClockFraction() {
            stubSunrise();
            stubState(TideState.HIGH);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));

            // 08:10 is 490 minutes into a 1440-minute day.
            assertThat(rollup(DAY, TargetType.SUNRISE).windowPosition())
                    .isEqualTo(Math.round(490 / 1440.0 * 1000) / 1000.0);
        }

        @Test
        @DisplayName("the hours before the day's first extreme draw the falling limb, not slack water")
        void theLeadingHoursAreNotDrawnFlat() {
            // The bookend extension is ~35 lines that can be deleted with every other test still
            // green: hasSize is fixed by the sample loop, and contains(0.0)/contains(1.0) hold for
            // ANY non-flat input because the normalisation maps the sampled min and max to exactly
            // those. Without it, heightAt clamps every sample before the day's first extreme to the
            // series minimum — 2h20 of dead-flat trace pinned to the floor of the sparkline, where
            // the water is in fact still falling toward 02:20.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(whitbyDay(DAY));

            List<Double> curve = rollup(DAY, TargetType.SUNSET).curve();

            // 00:00 through 02:00, ahead of the 02:20 low water.
            assertThat(curve.subList(0, 5)).isSortedAccordingTo(Comparator.reverseOrder());
            assertThat(curve.get(0)).isGreaterThan(curve.get(4));
        }

        @Test
        @DisplayName("a day with no swing draws flat rather than NaN")
        void aFlatDayDrawsFlat() {
            // Dividing by a zero span would emit NaN, which is not valid JSON — the whole briefing
            // would fail to serialise over one bad coastal row.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(day(ID_WHITBY, DAY,
                    low("02:20", 3.0), high("08:35", 3.0), low("14:45", 3.0)));

            BriefingWindowTide tide = rollup(DAY, TargetType.SUNSET);

            assertThat(tide.curve()).containsOnly(0.5);
            assertThat(tide.windowLevel()).isEqualTo(0.5);
            assertThat(tide.range()).isEqualTo("0.0 m");
        }

        @Test
        @DisplayName("two extremes stamped at the same minute do not produce a NaN trace")
        void duplicateTimestampsDoNotProduceNaN() {
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(day(ID_WHITBY, DAY,
                    low("02:20", 0.6), high("08:35", 5.4), low("08:35", 0.9), high("20:55", 5.2)));

            List<Double> curve = rollup(DAY, TargetType.SUNSET).curve();

            assertThat(curve).allSatisfy(v -> assertThat(Double.isFinite(v)).isTrue());
        }
    }

    // ── a lost extreme ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a day with an extreme missing from the store")
    class InteriorGap {

        @Test
        @DisplayName("the hours between two consecutive highs draw the missing trough, not slack water")
        void aGapBetweenTwoHighsDescends() {
            // The production defect, and the reason this fill exists: the weekly refresh destroyed a
            // frontier extreme, and the sparkline cosine-interpolated HIGH -> HIGH straight across
            // the hole — seven hours of dead-flat trace pinned at high-water level, which a reader
            // cannot tell from a real stand of tide. Sampled at the gap's midpoint the trace must
            // sit at the bottom of the day's span, not within a couple of percent of both highs.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(gappedByAMissingLow());

            List<Double> curve = rollup(DAY, TargetType.SUNSET).curve();

            assertThat(sampleAt(curve, "14:30")).isLessThan(0.2);
            assertThat(sampleAt(curve, "08:30")).isGreaterThan(0.9);
            assertThat(sampleAt(curve, "20:30")).isGreaterThan(0.9);
        }

        @Test
        @DisplayName("a gap straddling midnight opens the day at the bottom of its own span")
        void aGapAcrossMidnightIsFilledToo() {
            // The exact production geometry: the last extreme of one day and the first of the next
            // are both HIGH, with the low between them destroyed by the weekly merge. It is the
            // only shape that puts the implied extreme at a NEGATIVE minute offset — this series
            // spans the day either side — so it is the only fixture that exercises the midpoint
            // arithmetic below zero. The symptom was the first seven hours of the day drawn flat
            // at high-water level; the trace must instead open near the floor and climb.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            List<TideExtremeEntity> extremes =
                    new ArrayList<>(day(ID_WHITBY, DAY.minusDays(1), high("17:48", 5.3)));
            extremes.addAll(day(ID_WHITBY, DAY,
                    high("05:56", 5.4), low("12:10", 0.7), high("18:20", 5.2)));
            stubExtremes(extremes);

            List<Double> curve = rollup(DAY, TargetType.SUNSET).curve();

            assertThat(sampleAt(curve, "00:00")).isLessThan(0.1);
            assertThat(sampleAt(curve, "06:00")).isGreaterThan(0.9);
        }

        @Test
        @DisplayName("two consecutive lows draw the missing crest the same way")
        void aGapBetweenTwoLowsRises() {
            // The mirror case is not symmetric by construction — the fill has to pick the opposite
            // kind of the pair it found, and a rule hard-coded to insert a low would pass the test
            // above and fail here.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(day(ID_WHITBY, DAY,
                    high("02:20", 5.4), low("08:30", 0.6), low("20:30", 0.9)));

            List<Double> curve = rollup(DAY, TargetType.SUNSET).curve();

            assertThat(sampleAt(curve, "14:30")).isGreaterThan(0.8);
            assertThat(sampleAt(curve, "08:30")).isLessThan(0.1);
            assertThat(sampleAt(curve, "20:30")).isLessThan(0.2);
        }

        @Test
        @DisplayName("water inside the gap reads as falling, not as rising toward the next stored high")
        void directionInsideTheGapFollowsTheImpliedWater() {
            // The one claim outside the trace that reads the series, and the fill makes it MORE
            // correct rather than less: production's hole reported RISING mid-ebb, because the next
            // extreme the series could see was the high water on the far side of the missing low.
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(gappedByAMissingLow());
            when(solarService.sunsetUtc(anyDouble(), anyDouble(), eq(DAY)))
                    .thenReturn(DAY.atTime(11, 0));

            assertThat(rollup(DAY, TargetType.SUNSET).direction())
                    .isEqualTo(BriefingWindowTide.Direction.FALLING);
        }

        @Test
        @DisplayName("no worded figure names water that was never stored")
        void theWordedFiguresReadOnlyRealExtremes() {
            // Shape only is the whole licence for this fill. The synthetic low sits at 14:30, half
            // an hour before this fixture's sunset — nearer than any real extreme — so if the
            // nearest-extreme search or the range could see it, this row would state a low water
            // that nobody measured, to the minute.
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(gappedByAMissingLow());
            when(solarService.sunsetUtc(anyDouble(), anyDouble(), eq(DAY)))
                    .thenReturn(DAY.atTime(15, 0));

            BriefingWindowTide tide = rollup(DAY, TargetType.SUNSET);

            assertThat(tide.nearestType()).isEqualTo("HW");
            assertThat(tide.nearestTime()).isEqualTo("20:30");
            assertThat(tide.nearestOffset()).isEqualTo("5h30 after sunset");
            // Still max(highs) - min(lows) over the day's real rows: 5.4 m against 0.6 m.
            assertThat(tide.range()).isEqualTo("4.8 m");
        }

        @Test
        @DisplayName("the window's mark follows the corrected trace rather than floating above it")
        void theMarkSitsOnTheFilledCurve() {
            // The mark is normalised against the same span the curve is, so a fill that reached the
            // curve and not the mark would leave the mark pinned near high water over a trace that
            // now descends past it.
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(gappedByAMissingLow());
            when(solarService.sunsetUtc(anyDouble(), anyDouble(), eq(DAY)))
                    .thenReturn(DAY.atTime(11, 0));

            BriefingWindowTide tide = rollup(DAY, TargetType.SUNSET);

            int segment = (int) Math.floor(tide.windowPosition() * (CURVE_POINTS - 1));
            double before = tide.curve().get(segment);
            double after = tide.curve().get(segment + 1);
            assertThat(tide.windowLevel())
                    .isBetween(Math.min(before, after) - 0.02, Math.max(before, after) + 0.02);
            // Mid-ebb, two and a half hours past the 08:30 high water — not still up at the top.
            assertThat(tide.windowLevel()).isLessThan(0.8);
        }

        @Test
        @DisplayName("a long gap between alternating extremes is a neap tide, and stays untouched")
        void aLongGapBetweenAlternatingKindsIsNotFilled() {
            // The trigger is same-kind adjacency, never elapsed time. Eighteen hours from low water
            // to high water is legal on a shallow coast, and inserting anything into it would draw
            // a tide that does not exist — so the limb has to climb the whole way without a dip.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(day(ID_WHITBY, DAY, low("02:20", 0.6), high("20:30", 5.4)));

            List<Double> curve = rollup(DAY, TargetType.SUNSET).curve();

            // 02:30 through 20:30, the whole rising limb.
            assertThat(curve.subList(5, 42)).isSorted();
        }

        @Test
        @DisplayName("two same-kind extremes stamped at the same minute still draw as high water")
        void sameMinuteSameKindPairDrawsCleanly() {
            // The fill creates zero-length spans that the stored data never had, so this input has
            // two ways to go wrong and both are checked. NaN is not valid JSON, so one degenerate
            // coastal row would fail the whole briefing to serialise; and a zero-width trough
            // rendered rather than skipped would punch a spike to the floor of the sparkline at
            // the exact minute the water is highest.
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            stubExtremes(day(ID_WHITBY, DAY,
                    low("02:20", 0.6), high("08:35", 5.4), high("08:35", 5.2), low("14:45", 0.9)));

            List<Double> curve = rollup(DAY, TargetType.SUNSET).curve();

            assertThat(curve).allSatisfy(v -> assertThat(Double.isFinite(v)).isTrue());
            assertThat(sampleAt(curve, "08:30")).isGreaterThan(0.9);
        }
    }

    // ── never synthesise ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("absent rather than approximate")
    class Degrades {

        @Test
        void noDaysMeansNoRollupAndNoQueries() {
            assertThat(builder.build(List.of())).isEmpty();
            assertThat(builder.build(null)).isEmpty();
            verifyNoInteractions(locationRepository, tideExtremeRepository, marineWaveRepository);
        }

        @Test
        void anInlandRosterMeansNoRollup() {
            when(locationRepository.findCoastalLocations()).thenReturn(List.of());

            assertThat(builder.build(List.of(dayOf(DAY)))).isEmpty();
            verifyNoInteractions(tideExtremeRepository);
        }

        @Test
        void noStoredExtremesMeansNoRollup() {
            stubCoastal(whitby());
            stubExtremes(List.of());

            assertThat(builder.build(List.of(dayOf(DAY)))).isEmpty();
        }

        @Test
        @DisplayName("half a tide is not a rollup — the row falls back to its per-slot facts")
        void aDayWithNoLowWaterIsDropped() {
            // A day needs both a high and a low to have a range or a shape. Charting from one
            // extremum would state a range that was never measured.
            stubCoastal(whitby());
            stubExtremes(day(ID_WHITBY, DAY, high("08:35", 5.4)));

            assertThat(builder.build(List.of(dayOf(DAY)))).isEmpty();
        }

        @Test
        @DisplayName("one undrawable date loses its own windows and no others")
        void anUndrawableDateLosesOnlyItsOwnWindows() {
            stubSunrise();
            stubSunset();
            stubState(TideState.MID);
            stubCoastal(whitby());
            List<TideExtremeEntity> extremes = new ArrayList<>(whitbyDay(DAY));
            extremes.addAll(day(ID_WHITBY, NEXT_DAY, high("09:20", 5.3)));
            stubExtremes(extremes);

            Map<PlanWindowProjector.WindowKey, BriefingWindowTide> rollups =
                    builder.build(List.of(bothWindows(DAY), bothWindows(NEXT_DAY)));

            assertThat(rollups).containsOnlyKeys(
                    key(DAY, TargetType.SUNRISE), key(DAY, TargetType.SUNSET));
        }

        @Test
        @DisplayName("fetches a day either side, as a London day converted to UTC")
        void fetchWindowReachesNeighbouringDays() {
            // The margin is what lets the trace be interpolated rather than extrapolated at both
            // ends, and it is what lets a 23:45 sunset find the 00:05 high water rather than naming
            // a low water seven hours away. Stubbed with any() it is invisible: shrinking
            // plusDays(2) to plusDays(1) leaves the whole suite green, because the stub returns the
            // same fixture whatever bounds it is handed. July, so the London -> UTC conversion is
            // pinned too rather than coinciding with it.
            LocalDate july = LocalDate.of(2026, 7, 15);
            stubCoastal(whitby());
            stubExtremes(List.of());

            builder.build(List.of(dayOf(july), dayOf(july.plusDays(1))));

            ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(tideExtremeRepository).findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
                    eq(List.of(ID_WHITBY)), from.capture(), to.capture());
            // 14 Jul 00:00 BST and 18 Jul 00:00 BST, both as UTC.
            assertThat(from.getValue()).isEqualTo(LocalDateTime.of(2026, 7, 13, 23, 0));
            assertThat(to.getValue()).isEqualTo(LocalDateTime.of(2026, 7, 17, 23, 0));
        }

        @Test
        @DisplayName("a repository failure degrades the row, never the briefing")
        void aRepositoryFailureIsFailSoft() {
            // This runs on the serve path of the busiest endpoint and produces one decorative row.
            // Failing the whole briefing over it would be the wrong trade, and is the same one
            // getCachedBriefing already makes for the live aurora overlay.
            when(locationRepository.findCoastalLocations())
                    .thenThrow(new IllegalStateException("connection reset"));

            assertThat(builder.build(List.of(dayOf(DAY)))).isEmpty();
        }
    }

    // ── the BST offset ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a summer extreme reads as its British Summer Time clock, not its stored UTC")
    void summerExtremesReadAsLocalTime() {
        // Stored times are UTC; every clock string the reader sees is Europe/London. In July that
        // is an hour apart, and getting it wrong would put every summer high water an hour early.
        LocalDate july = LocalDate.of(2026, 7, 15);
        stubState(TideState.MID);
        stubCoastal(whitby());
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), eq(july)))
                .thenReturn(july.atTime(20, 0));
        stubExtremes(day(ID_WHITBY, july,
                low("02:20", 0.6), high("08:35", 5.4), low("14:45", 0.9), high("20:55", 5.2)));

        BriefingWindowTide tide = builder.build(List.of(dayOf(july))).get(key(july, TargetType.SUNSET));

        // Stored 20:55 UTC is 21:55 BST, 1h55 after a 20:00 UTC (21:00 BST) sunset.
        assertThat(tide.nearestTime()).isEqualTo("21:55");
        assertThat(tide.nearestOffset()).isEqualTo("55m after sunset");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private BriefingWindowTide rollup(LocalDate date, TargetType targetType) {
        return builder.build(List.of(new BriefingDay(date,
                        List.of(new BriefingEventSummary(targetType, List.of(), List.of())))))
                .get(key(date, targetType));
    }

    private static PlanWindowProjector.WindowKey key(LocalDate date, TargetType targetType) {
        return new PlanWindowProjector.WindowKey(date, targetType);
    }

    /** A day carrying only its sunset window — the default shape for a single-claim assertion. */
    private static BriefingDay dayOf(LocalDate date) {
        return new BriefingDay(date,
                List.of(new BriefingEventSummary(TargetType.SUNSET, List.of(), List.of())));
    }

    private static BriefingDay bothWindows(LocalDate date) {
        return new BriefingDay(date, List.of(
                new BriefingEventSummary(TargetType.SUNRISE, List.of(), List.of()),
                new BriefingEventSummary(TargetType.SUNSET, List.of(), List.of())));
    }

    /**
     * Stubbed one event at a time rather than both together: a test that asserts about a sunset
     * must not silently pass because a sunrise happened to be stubbed for it.
     */
    private void stubSunrise() {
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> ((LocalDate) inv.getArgument(2)).atTime(8, 10));
    }

    private void stubSunset() {
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> ((LocalDate) inv.getArgument(2)).atTime(16, 30));
    }

    /**
     * The tide-state seam. Both the window width and the classifier are stubbed, because the row's
     * contract is that it asks the SAME question the per-slot facts ask — the rule itself is
     * TideService's and is pinned by TideServiceTest.
     */
    private void stubState(TideState state) {
        when(tideFactDeriver.tightAlignmentWindowMinutes(anyDouble(), anyDouble(), any(), any()))
                .thenReturn(SHARED_WINDOW_MINUTES);
        when(tideService.classifyTideState(anyList(), any(), eq(SHARED_WINDOW_MINUTES)))
                .thenReturn(state);
    }

    private void stubCoastal(LocationEntity... locations) {
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(locations));
    }

    private void stubExtremes(List<TideExtremeEntity> extremes) {
        when(tideExtremeRepository.findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
                any(), any(), any())).thenReturn(extremes);
    }

    /**
     * The shape a lost extreme leaves: high water, high water again, and no low between them.
     *
     * <p>Times chosen so the implied trough falls at 14:30, exactly on a 30-minute sample, and so
     * the surviving highs sit on samples too — an assertion that has to hunt for the nearest sample
     * cannot say where the trace went. The missing low is the one this coast would have had around
     * 14:30; the day still holds a high and a low, so it is drawable and the row renders.
     */
    private static List<TideExtremeEntity> gappedByAMissingLow() {
        return day(ID_WHITBY, DAY, low("02:20", 0.6), high("08:30", 5.4), high("20:30", 5.2));
    }

    /**
     * The normalised sample at a local clock time, which must land on a 30-minute boundary.
     *
     * @param curve the day's 49 samples
     * @param clock the local time to read, HH:mm
     * @return the sampled value at that minute
     */
    private static double sampleAt(List<Double> curve, String clock) {
        String[] parts = clock.split(":");
        int minutes = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        assertThat(minutes % 30).as("%s must sit on a sample", clock).isZero();
        return curve.get(minutes / 30);
    }

    /** The fixture coast's day: low 0.6, high 5.4, low 0.9, high 5.2 — a 4.8 m range. */
    private static List<TideExtremeEntity> whitbyDay(LocalDate date) {
        return day(ID_WHITBY, date,
                low("02:20", 0.6), high("08:35", 5.4), low("14:45", 0.9), high("20:55", 5.2));
    }

    private static LocationEntity whitby() {
        return LocationEntity.builder().id(ID_WHITBY).name("Whitby")
                .lat(54.49).lon(-0.61).build();
    }

    private static LocationEntity seaham() {
        return LocationEntity.builder().id(ID_SEAHAM).name("Seaham")
                .lat(54.84).lon(-1.33).build();
    }

    private static TideStats stats(String avgRange) {
        return new TideStats(null, null, null, null, 100L, new BigDecimal(avgRange),
                null, null, null, 0L, null, null, null, 0L);
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
            out.add(TideExtremeEntity.builder()
                    .locationId(locationId)
                    .eventTime(date.atTime(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])))
                    .heightMetres(BigDecimal.valueOf(p.height()))
                    .type(p.type())
                    .build());
        }
        return out;
    }
}
