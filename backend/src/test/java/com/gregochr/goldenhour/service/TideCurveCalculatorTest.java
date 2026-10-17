package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.model.BriefingWindowTide;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The one cosine curve interpolation the app has — lifted out of {@code WindowTideRollupBuilder},
 * which is the class whose own tests already pin its serve-time behaviour end to end
 * ({@code WindowTideRollupBuilderTest}). This suite pins the maths directly, so a defect in the
 * shared curve shows up here rather than only through one of its two callers.
 *
 * <p>Dates sit in <b>January</b>, where Europe/London is UTC, so a stored UTC extreme and its
 * local clock time coincide.
 */
class TideCurveCalculatorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 27);

    private static TideExtremeEntity extreme(TideExtremeType type, LocalDateTime utc, double m) {
        TideExtremeEntity e = new TideExtremeEntity();
        e.setType(type);
        e.setEventTime(utc);
        e.setHeightMetres(BigDecimal.valueOf(m));
        return e;
    }

    // ── heightAt — the cosine interpolation itself ──────────────────────────────────────────

    @Test
    @DisplayName("the cosine's midpoint in time is exactly the mean of the two heights")
    void cosineMidpointIsExactlyTheMeanHeight() {
        // (1 - cos(pi * 0.5)) / 2 == 0.5 exactly, so the eased fraction at the halfway point
        // between two extremes is precisely 0.5 — the one point on a cosine ease where the
        // interpolation coincides with a linear one.
        List<TideCurveCalculator.Point> series = List.of(
                new TideCurveCalculator.Point(true, 0, 4.0),
                new TideCurveCalculator.Point(false, 400, 1.0));

        double height = TideCurveCalculator.heightAt(series, 200);

        assertThat(height).isCloseTo(2.5, within(1e-9));
    }

    @Test
    @DisplayName("at either extreme the height is exactly that extreme's own stored height")
    void atAnExtremeTheHeightIsExact() {
        List<TideCurveCalculator.Point> series = List.of(
                new TideCurveCalculator.Point(true, 0, 4.2),
                new TideCurveCalculator.Point(false, 400, 0.8));

        assertThat(TideCurveCalculator.heightAt(series, 0)).isCloseTo(4.2, within(1e-9));
        assertThat(TideCurveCalculator.heightAt(series, 400)).isCloseTo(0.8, within(1e-9));
    }

    @Test
    @DisplayName("a minute before the first point or after the last clamps to the series' ends")
    void outsideTheSeriesClampsToTheEnds() {
        List<TideCurveCalculator.Point> series = List.of(
                new TideCurveCalculator.Point(true, 0, 4.2),
                new TideCurveCalculator.Point(false, 400, 0.8));

        assertThat(TideCurveCalculator.heightAt(series, -50)).isCloseTo(4.2, within(1e-9));
        assertThat(TideCurveCalculator.heightAt(series, 900)).isCloseTo(0.8, within(1e-9));
    }

    // ── Shape.levelOf — normalisation, including the flat-series guard ─────────────────────

    @Test
    @DisplayName("a flat series (no swing) levels at 0.5, mid-height, rather than dividing by "
            + "near-zero")
    void flatSeriesLevelsAtOneHalf() {
        TideCurveCalculator.Shape flat = new TideCurveCalculator.Shape(List.of(), 2.5, 0.0);

        assertThat(flat.levelOf(2.5)).isEqualTo(0.5);
        assertThat(flat.levelOf(99.0)).as("even a wildly off height reads flat, not extrapolated")
                .isEqualTo(0.5);
    }

    @Test
    @DisplayName("levelOf clamps to 0..1 for a height outside the sampled span")
    void levelOfClampsOutsideTheSpan() {
        TideCurveCalculator.Shape shape = new TideCurveCalculator.Shape(List.of(), 1.0, 3.0);

        assertThat(shape.levelOf(1.0)).isEqualTo(0.0);
        assertThat(shape.levelOf(4.0)).isEqualTo(1.0);
        assertThat(shape.levelOf(0.0)).as("below the span clamps to 0, not negative")
                .isEqualTo(0.0);
        assertThat(shape.levelOf(9.0)).as("above the span clamps to 1, not beyond")
                .isEqualTo(1.0);
        assertThat(shape.levelOf(2.5)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    @DisplayName("the flat guard's own boundary: a span strictly below the threshold reads flat "
            + "regardless of height; a span AT the threshold does not, and normalises instead")
    void flatGuardBoundary() {
        // FLAT_SPAN_METRES is 1e-9, private to the class under test — reproduced here as a
        // literal rather than exposed, since the boundary itself is what this test pins. Both
        // shapes are probed at height = min + span (the top of their own tiny span), so a level
        // of 0.5 can only come from the flat guard firing, never from a coincidentally-flat
        // division.
        TideCurveCalculator.Shape belowThreshold = new TideCurveCalculator.Shape(List.of(), 1.0, 0.5e-9);
        TideCurveCalculator.Shape atThreshold = new TideCurveCalculator.Shape(List.of(), 1.0, 1e-9);

        assertThat(belowThreshold.levelOf(1.0 + 0.5e-9))
                .as("span < threshold: the guard fires, flat at 0.5 even at the span's own top")
                .isEqualTo(0.5);
        assertThat(atThreshold.levelOf(1.0 + 1e-9))
                .as("span == threshold: `<` is false, the guard does not fire, and the top of "
                        + "the span normalises to exactly 1.0")
                .isEqualTo(1.0);
    }

    // ── directionAt — read from the KIND of the next extreme, not a height comparison ──────

    @Test
    @DisplayName("direction flips exactly at an extreme: falling on the near side, rising on "
            + "the far side of a low")
    void directionFlipsAtAnExtreme() {
        List<TideCurveCalculator.Point> series = List.of(
                new TideCurveCalculator.Point(true, 0, 4.2),
                new TideCurveCalculator.Point(false, 400, 0.8),
                new TideCurveCalculator.Point(true, 800, 4.0));

        assertThat(TideCurveCalculator.directionAt(series, 399))
                .as("just before the low: still heading down")
                .isEqualTo(BriefingWindowTide.Direction.FALLING);
        assertThat(TideCurveCalculator.directionAt(series, 400))
                .as("AT the low itself: the next point strictly after 400 is the high, so RISING")
                .isEqualTo(BriefingWindowTide.Direction.RISING);
        assertThat(TideCurveCalculator.directionAt(series, 401))
                .as("just after the low: heading up")
                .isEqualTo(BriefingWindowTide.Direction.RISING);
    }

    @Test
    @DisplayName("past the last stored extreme, the tide has just turned the other way")
    void pastTheLastExtremeTurnsTheOtherWay() {
        List<TideCurveCalculator.Point> series = List.of(
                new TideCurveCalculator.Point(true, 0, 4.2),
                new TideCurveCalculator.Point(false, 400, 0.8));

        assertThat(TideCurveCalculator.directionAt(series, 1000))
                .as("last extreme was a LOW, so beyond it the water is RISING")
                .isEqualTo(BriefingWindowTide.Direction.RISING);
    }

    // ── bracket — extends the series half a cycle past each end ────────────────────────────

    @Test
    @DisplayName("bracketing a series that already spans the whole day adds no interior sample")
    void bracketAddsNoInteriorSampleWhenAlreadySpanning() {
        List<TideCurveCalculator.Point> spanning = List.of(
                new TideCurveCalculator.Point(false, -50, 0.9),
                new TideCurveCalculator.Point(true, 300, 4.1),
                new TideCurveCalculator.Point(false, 700, 0.9),
                new TideCurveCalculator.Point(true, 1100, 4.1),
                new TideCurveCalculator.Point(false, 1500, 0.9));

        List<TideCurveCalculator.Point> bracketed = TideCurveCalculator.bracket(spanning);

        assertThat(bracketed).as("first point already before 0 and last already past 1440")
                .hasSameSizeAs(spanning)
                .containsExactlyElementsOf(spanning);
    }

    @Test
    @DisplayName("bracketing a series that falls short of the day adds exactly one point at each "
            + "short end, half a cycle out, of the opposite kind and counterpartHeight's height")
    void bracketAddsOneBookendAtEachShortEnd() {
        List<TideCurveCalculator.Point> shortOfTheDay = List.of(
                new TideCurveCalculator.Point(true, 300, 4.1),
                new TideCurveCalculator.Point(false, 700, 0.9));

        List<TideCurveCalculator.Point> bracketed = TideCurveCalculator.bracket(shortOfTheDay);

        assertThat(bracketed).hasSize(4);
        assertThat(bracketed.get(0).minutes()).isEqualTo(300 - 745 / 2);
        assertThat(bracketed.get(0).high()).as("opposite kind to the first real point").isFalse();
        assertThat(bracketed.get(0).height())
                .as("counterpartHeight: no other LOW to copy, so it mirrors its own neighbour")
                .isEqualTo(0.9);
        assertThat(bracketed.get(3).minutes()).isEqualTo(700 + 745 / 2);
        assertThat(bracketed.get(3).high()).as("opposite kind to the last real point").isTrue();
        assertThat(bracketed.get(3).height())
                .as("counterpartHeight: no other HIGH to copy, so it mirrors its own neighbour")
                .isEqualTo(4.1);
    }

    @Test
    @DisplayName("counterpartHeight copies the nearest opposite-kind point when more than one "
            + "exists, not just the first or the farthest")
    void counterpartHeightCopiesTheNearestOppositeKindPoint() {
        List<TideCurveCalculator.Point> points = List.of(
                new TideCurveCalculator.Point(false, 100, 1.5),
                new TideCurveCalculator.Point(true, 300, 4.1),
                new TideCurveCalculator.Point(false, 500, 0.9),
                new TideCurveCalculator.Point(true, 900, 4.6));

        List<TideCurveCalculator.Point> bracketed = TideCurveCalculator.bracket(points);

        // The leading bookend anchors on the first point (a LOW at minute 100); the opposite kind
        // is HIGH, and two exist — 300 (distance 200) and 900 (distance 800). It must copy the
        // nearer one's height, not the farther one's, and not simply the first HIGH found by
        // list order (which here happen to coincide, so the distance is what this pins).
        assertThat(bracketed.get(0).height())
                .as("the nearer HIGH (minute 300, height 4.1) wins over the farther one (900, 4.6)")
                .isEqualTo(4.1);
    }

    @Test
    @DisplayName("bracketing a series exactly spanning the day (first point at minute 0, last at "
            + "1440) adds no bookend at either end — the boundary is inclusive")
    void bracketAtExactBoundariesAddsNoBookend() {
        List<TideCurveCalculator.Point> exact = List.of(
                new TideCurveCalculator.Point(true, 0, 4.1),
                new TideCurveCalculator.Point(false, 1440, 0.9));

        List<TideCurveCalculator.Point> bracketed = TideCurveCalculator.bracket(exact);

        assertThat(bracketed).as("first.minutes() > 0 and last.minutes() < 1440 are both false")
                .containsExactlyElementsOf(exact);
    }

    @Test
    @DisplayName("bracketing an empty series returns it unchanged rather than throwing")
    void bracketOfEmptySeriesIsEmpty() {
        assertThat(TideCurveCalculator.bracket(List.of())).isEmpty();
    }

    // ── seriesAround / localDate / clockMinutesFrom — the entity-facing assembly ───────────

    @Test
    @DisplayName("seriesAround places every real extreme at its own local-day minute, "
            + "chronologically")
    void seriesAroundPlacesRealExtremesAtTheirOwnMinutes() {
        List<TideExtremeEntity> extremes = List.of(
                // Fed out of order on purpose: the assertion below is what proves the sort.
                extreme(TideExtremeType.HIGH, DAY.atTime(21, 0), 4.1),
                extreme(TideExtremeType.LOW, DAY.atTime(3, 0), 0.9),
                extreme(TideExtremeType.HIGH, DAY.atTime(9, 0), 4.1),
                extreme(TideExtremeType.LOW, DAY.atTime(15, 0), 0.9));

        List<TideCurveCalculator.Point> series = TideCurveCalculator.seriesAround(extremes, DAY);

        // Every point in the full bracketed series is ascending by minute — deleting the sort in
        // seriesAround leaves this test failing regardless of which points also carry a bracket
        // bookend, so it needs no separate in-day filter to isolate the four real points.
        assertThat(series).extracting(TideCurveCalculator.Point::minutes)
                .isSorted();
        // Restricted to the day itself (0..1440): bracket() adds its own out-of-day bookend at
        // each short end, and the first real point here (3:00) is one of them — its bookend
        // would otherwise also match a height-4.1 filter, since it mirrors the nearest HIGH.
        assertThat(series).filteredOn(p -> p.height() == 4.1 && p.minutes() >= 0 && p.minutes() < 1440)
                .extracting(TideCurveCalculator.Point::minutes)
                .containsExactly(9 * 60, 21 * 60);
    }

    @Test
    @DisplayName("seriesAround drops an extreme with no stored height")
    void seriesAroundDropsAnExtremeWithNoHeight() {
        TideExtremeEntity noHeight = new TideExtremeEntity();
        noHeight.setType(TideExtremeType.HIGH);
        noHeight.setEventTime(DAY.atTime(9, 0));
        noHeight.setHeightMetres(null);

        List<TideExtremeEntity> extremes = List.of(
                extreme(TideExtremeType.LOW, DAY.atTime(3, 0), 0.9),
                noHeight,
                extreme(TideExtremeType.LOW, DAY.atTime(15, 0), 0.9));

        List<TideCurveCalculator.Point> series = TideCurveCalculator.seriesAround(extremes, DAY);

        // The null-height HIGH is dropped, leaving the two real LOWs (03:00, 15:00) adjacent —
        // the same shape a lost stored extreme leaves, so fillInteriorGaps correctly bridges it
        // with a synthetic HIGH exactly midway (09:00 = minute 540) rather than the trace running
        // LOW to LOW. bracket() then bookends both ends of that 3-point series (180, 540, 900)
        // half a cycle out (745/2 = 372): -192 leading, 1272 trailing. With no real HIGH anywhere
        // in the window, every synthetic point's height traces back to a real 0.9 m LOW.
        assertThat(series).extracting(TideCurveCalculator.Point::minutes)
                .containsExactly(-192, 180, 540, 900, 1272);
        assertThat(series).as("every point's height traces back to the two real 0.9m LOWs")
                .allMatch(p -> p.height() == 0.9);
    }

    @Test
    @DisplayName("localDate reads the Europe/London calendar day of a UTC instant")
    void localDateReadsTheLondonDay() {
        // 27 Jan is outside BST, so this alone would pass even if the zone conversion were
        // deleted entirely (UTC and London agree here by coincidence) — bstDateCanDifferFromUtc
        // below is the test that cannot pass on a broken conversion.
        assertThat(TideCurveCalculator.localDate(DAY.atTime(23, 30))).isEqualTo(DAY);
    }

    @Test
    @DisplayName("in BST, the London calendar day can differ from the UTC one — the case a bare "
            + "toLocalDate() would get wrong")
    void bstDateCanDifferFromUtc() {
        // 23:30 UTC on a July night is 00:30 the NEXT day in Europe/London (BST, UTC+1). A stored
        // extreme at that instant belongs to the later local day.
        LocalDate julyDay = LocalDate.of(2026, 7, 14);
        assertThat(TideCurveCalculator.localDate(julyDay.atTime(23, 30)))
                .isEqualTo(julyDay.plusDays(1));
    }

    @Test
    @DisplayName("clockMinutesFrom places an instant on the given day's own 00:00-24:00 axis, "
            + "negative for the previous day and past 1440 for the next")
    void clockMinutesFromPlacesOnTheGivenDaysAxis() {
        assertThat(TideCurveCalculator.clockMinutesFrom(DAY.atTime(8, 10), DAY)).isEqualTo(490);
        assertThat(TideCurveCalculator.clockMinutesFrom(DAY.minusDays(1).atTime(23, 45), DAY))
                .isEqualTo(-15);
        assertThat(TideCurveCalculator.clockMinutesFrom(DAY.plusDays(1).atTime(0, 5), DAY))
                .isEqualTo(1445);
    }

    @Test
    @DisplayName("clockMinutesFrom in BST: the London clock is an hour ahead of the UTC instant")
    void clockMinutesFromInBst() {
        // 23:30 UTC on 14 July is 00:30 BST on 15 July — minute 30 on the 15th's own axis, not
        // minute 1410 (23:30) on the 14th's, which a bare UTC read would give.
        LocalDate julyDay = LocalDate.of(2026, 7, 15);
        assertThat(TideCurveCalculator.clockMinutesFrom(
                LocalDate.of(2026, 7, 14).atTime(23, 30), julyDay))
                .isEqualTo(30);
    }
}
