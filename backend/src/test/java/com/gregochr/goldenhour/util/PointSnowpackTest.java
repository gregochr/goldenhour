package com.gregochr.goldenhour.util;

import com.gregochr.goldenhour.util.PointSnowpack.Hour;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Every boundary of {@link PointSnowpack} (snow-topics-truth-plan §3.1 and S2): the wet-bulb ramp, the settling
 * gate, the melt factors, no melt at 0 C, the floor, the 24 h edge, the 10 % missing-input share, an uncovered
 * lookback and an empty series.
 */
class PointSnowpackTest {

    private static final int SLOT = PointSnowpack.LOOKBACK_HOURS - 1;
    private static final double EXACT = 1e-9;

    private static final double COLD = -5.0;
    private static final double DRY = 50.0;
    private static final double SATURATED = 100.0;

    private static Hour hour(double precip, double temperature, double humidity, double wind, double shortwave) {
        return new Hour(precip, temperature, humidity, wind, shortwave);
    }

    /** A cold, dry, still, dark hour with no precipitation: it adds nothing and melts nothing. */
    private static Hour quiet() {
        return hour(0, COLD, DRY, 0, 0);
    }

    private static List<Hour> quietSeries() {
        return new ArrayList<>(Collections.nCopies(PointSnowpack.LOOKBACK_HOURS, quiet()));
    }

    private static PointSnowpack.Result compute(List<Hour> series, int slot) {
        Optional<PointSnowpack.Result> result = PointSnowpack.compute(series, slot);
        assertThat(result).isPresent();
        return result.get();
    }

    // ── The pure steps ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("wet-bulb: Stull's check point (20 C, 50 %) is 13.7 C; saturated air is near its dry bulb")
    void wetBulb() {
        assertThat(PointSnowpack.wetBulbC(20.0, 50.0)).isCloseTo(13.7, within(0.1));
        assertThat(PointSnowpack.wetBulbC(0.0, SATURATED)).isCloseTo(0.0, within(0.4));
        assertThat(PointSnowpack.wetBulbC(COLD, 0.0)).isFinite();
    }

    @Test
    @DisplayName("dew point: 20 C at 50 % is 9.3 C, saturated air has its dry bulb as dew point")
    void dewPoint() {
        assertThat(PointSnowpack.dewPointC(20.0, 50.0)).isCloseTo(9.3, within(0.1));
        assertThat(PointSnowpack.dewPointC(3.0, SATURATED)).isCloseTo(3.0, within(EXACT * 1e3));
        assertThat(PointSnowpack.dewPointC(COLD, 0.0)).isFinite();
    }

    @Test
    @DisplayName("snow fraction: 1 at Tw 0.5 and below, 0.5 at 1.0, 0 at 1.5 and above, linear between")
    void snowFractionRamp() {
        assertThat(PointSnowpack.snowFraction(-3.0)).isEqualTo(1.0);
        assertThat(PointSnowpack.snowFraction(0.5)).isEqualTo(1.0);
        assertThat(PointSnowpack.snowFraction(0.75)).isCloseTo(0.75, within(EXACT));
        assertThat(PointSnowpack.snowFraction(1.0)).isCloseTo(0.5, within(EXACT));
        assertThat(PointSnowpack.snowFraction(1.25)).isCloseTo(0.25, within(EXACT));
        assertThat(PointSnowpack.snowFraction(1.5)).isEqualTo(0.0);
        assertThat(PointSnowpack.snowFraction(4.0)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("settling gate: always at 1.0 C or below; to 2.0 C only with 2 mm in the hour; never above")
    void settlingGate() {
        assertThat(PointSnowpack.settles(-2.0, 0.1)).isTrue();
        assertThat(PointSnowpack.settles(1.0, 0.1)).isTrue();
        assertThat(PointSnowpack.settles(1.01, 1.99)).isFalse();
        assertThat(PointSnowpack.settles(1.01, 2.0)).isTrue();
        assertThat(PointSnowpack.settles(2.0, 1.99)).isFalse();
        assertThat(PointSnowpack.settles(2.0, 2.0)).isTrue();
        assertThat(PointSnowpack.settles(2.01, 50.0)).isFalse();
    }

    @Test
    @DisplayName("melt factor: 3 base; 6 only with Td above 0 AND wind at 5 m/s or more; at least 5 above 200 W/m2")
    void degreeDayFactor() {
        assertThat(PointSnowpack.degreeDayFactor(-1.0, 0.0, 0.0)).isEqualTo(3.0);
        assertThat(PointSnowpack.degreeDayFactor(0.0, 5.0, 0.0)).isEqualTo(3.0);
        assertThat(PointSnowpack.degreeDayFactor(0.01, 5.0, 0.0)).isEqualTo(6.0);
        assertThat(PointSnowpack.degreeDayFactor(0.01, 4.99, 0.0)).isEqualTo(3.0);
        assertThat(PointSnowpack.degreeDayFactor(0.01, 12.0, 0.0)).isEqualTo(6.0);
        assertThat(PointSnowpack.degreeDayFactor(-1.0, 0.0, 200.0)).isEqualTo(3.0);
        assertThat(PointSnowpack.degreeDayFactor(-1.0, 0.0, 200.01)).isEqualTo(5.0);
        assertThat(PointSnowpack.degreeDayFactor(0.01, 5.0, 900.0)).isEqualTo(6.0);
    }

    @Test
    @DisplayName("the named thresholds are 3 mm for lying and 5 mm for a white summit")
    void thresholds() {
        assertThat(PointSnowpack.LYING_MM).isEqualTo(3.0);
        assertThat(PointSnowpack.SUMMIT_WHITE_MM).isEqualTo(5.0);
        assertThat(PointSnowpack.MAX_MISSING_HOURS).isEqualTo(16);
    }

    // ── Accumulation, melt and the 24 h edge ──────────────────────────────────────────────

    @Test
    @DisplayName("a cold, dry, quiet week is an empty pack, not unknown")
    void quietWeekIsZero() {
        PointSnowpack.Result result = compute(quietSeries(), SLOT);

        assertThat(result.snowWaterMm()).isZero();
        assertThat(result.snowfall24hCm()).isZero();
    }

    @Test
    @DisplayName("2 mm of snow in the slot hour is 2 mm of water and 1.4 cm fresh (the ratio is 0.7)")
    void freshSnowInTheSlotHour() {
        List<Hour> series = quietSeries();
        series.set(SLOT, hour(2.0, COLD, 90.0, 0, 0));

        PointSnowpack.Result result = compute(series, SLOT);

        assertThat(result.snowWaterMm()).isCloseTo(2.0, within(EXACT));
        assertThat(result.snowfall24hCm()).isCloseTo(1.4, within(EXACT));
    }

    @Test
    @DisplayName("the 24 h window: 23 hours before the slot counts, 24 before does not, the pack keeps both")
    void twentyFourHourEdge() {
        List<Hour> inside = quietSeries();
        inside.set(SLOT - 23, hour(1.0, COLD, 90.0, 0, 0));
        List<Hour> outside = quietSeries();
        outside.set(SLOT - 24, hour(1.0, COLD, 90.0, 0, 0));

        assertThat(compute(inside, SLOT).snowfall24hCm()).isCloseTo(0.7, within(EXACT));
        assertThat(compute(inside, SLOT).snowWaterMm()).isCloseTo(1.0, within(EXACT));
        assertThat(compute(outside, SLOT).snowfall24hCm()).isZero();
        assertThat(compute(outside, SLOT).snowWaterMm()).isCloseTo(1.0, within(EXACT));
    }

    @Test
    @DisplayName("snow in the oldest hour of the lookback is counted; the hour before the lookback is not")
    void lookbackStartEdge() {
        List<Hour> series = new ArrayList<>(Collections.nCopies(PointSnowpack.LOOKBACK_HOURS + 1, quiet()));
        series.set(1, hour(4.0, COLD, 90.0, 0, 0));
        assertThat(compute(series, PointSnowpack.LOOKBACK_HOURS).snowWaterMm()).isCloseTo(4.0, within(EXACT));

        series.set(1, quiet());
        series.set(0, hour(4.0, COLD, 90.0, 0, 0));
        assertThat(compute(series, PointSnowpack.LOOKBACK_HOURS).snowWaterMm()).isZero();
    }

    @Test
    @DisplayName("wet-bulb ramp through the walk: a mild saturated hour at 0.9 C is part snow, part rain")
    void partSnowHour() {
        List<Hour> series = quietSeries();
        series.set(SLOT, hour(10.0, 0.9, SATURATED, 0, 0));
        double fraction = PointSnowpack.snowFraction(PointSnowpack.wetBulbC(0.9, SATURATED));
        double melt = 3.0 / 24.0 * 0.9;

        PointSnowpack.Result result = compute(series, SLOT);

        assertThat(fraction).isBetween(0.0, 1.0);
        assertThat(result.snowWaterMm()).isCloseTo(10.0 * fraction - melt, within(EXACT));
        assertThat(result.snowfall24hCm()).isCloseTo(10.0 * fraction * 0.7, within(EXACT));
    }

    @Test
    @DisplayName("falling is not settling: 1.99 mm at 2.0 C is not added, 2.0 mm is (less that hour's melt)")
    void settlingThroughTheWalk() {
        List<Hour> light = quietSeries();
        light.set(SLOT, hour(1.99, 2.0, DRY, 0, 0));
        List<Hour> heavy = quietSeries();
        heavy.set(SLOT, hour(2.0, 2.0, DRY, 0, 0));
        List<Hour> warm = quietSeries();
        warm.set(SLOT, hour(30.0, 2.01, DRY, 0, 0));

        assertThat(compute(light, SLOT).snowWaterMm()).isZero();
        assertThat(compute(light, SLOT).snowfall24hCm()).isZero();
        assertThat(compute(heavy, SLOT).snowWaterMm()).isCloseTo(2.0 - 3.0 / 24.0 * 2.0, within(EXACT));
        assertThat(compute(heavy, SLOT).snowfall24hCm()).isCloseTo(1.4, within(EXACT));
        assertThat(compute(warm, SLOT).snowWaterMm()).isZero();
    }

    @Test
    @DisplayName("at 1.0 C any snow settles: 1 mm is added, less that hour's melt")
    void anySnowSettlesAtOneDegree() {
        List<Hour> series = quietSeries();
        series.set(SLOT, hour(1.0, 1.0, DRY, 0, 0));

        assertThat(compute(series, SLOT).snowWaterMm()).isCloseTo(1.0 - 3.0 / 24.0, within(EXACT));
    }

    @Test
    @DisplayName("no melt at exactly 0 C, and a melt hour reduces the pack by DDF / 24 x T")
    void meltStartsAboveZero() {
        List<Hour> atZero = quietSeries();
        atZero.set(0, hour(10.0, COLD, 90.0, 0, 0));
        atZero.set(SLOT, hour(0, 0.0, DRY, 0, 0));
        List<Hour> aboveZero = quietSeries();
        aboveZero.set(0, hour(10.0, COLD, 90.0, 0, 0));
        aboveZero.set(SLOT, hour(0, 0.5, DRY, 0, 0));

        assertThat(compute(atZero, SLOT).snowWaterMm()).isCloseTo(10.0, within(EXACT));
        assertThat(compute(aboveZero, SLOT).snowWaterMm()).isCloseTo(10.0 - 3.0 / 24.0 * 0.5, within(EXACT));
    }

    @Test
    @DisplayName("condensation melt doubles to 6 with Td above 0 and 5 m/s; sun lifts the floor to 5")
    void meltFactorsThroughTheWalk() {
        List<Hour> breezy = quietSeries();
        breezy.set(0, hour(10.0, COLD, 90.0, 0, 0));
        breezy.set(SLOT, hour(0, 1.0, SATURATED, 5.0, 0));
        List<Hour> calm = quietSeries();
        calm.set(0, hour(10.0, COLD, 90.0, 0, 0));
        calm.set(SLOT, hour(0, 1.0, SATURATED, 4.99, 0));
        List<Hour> sunny = quietSeries();
        sunny.set(0, hour(10.0, COLD, 90.0, 0, 0));
        sunny.set(SLOT, hour(0, 1.0, DRY, 0, 200.01));
        List<Hour> bright = quietSeries();
        bright.set(0, hour(10.0, COLD, 90.0, 0, 0));
        bright.set(SLOT, hour(0, 1.0, DRY, 0, 200.0));

        assertThat(compute(breezy, SLOT).snowWaterMm()).isCloseTo(10.0 - 6.0 / 24.0, within(EXACT));
        assertThat(compute(calm, SLOT).snowWaterMm()).isCloseTo(10.0 - 3.0 / 24.0, within(EXACT));
        assertThat(compute(sunny, SLOT).snowWaterMm()).isCloseTo(10.0 - 5.0 / 24.0, within(EXACT));
        assertThat(compute(bright, SLOT).snowWaterMm()).isCloseTo(10.0 - 3.0 / 24.0, within(EXACT));
    }

    @Test
    @DisplayName("melt floors the pack at zero and it never goes negative or refreezes into extra snow")
    void floorAtZero() {
        List<Hour> series = quietSeries();
        series.set(0, hour(0.2, COLD, 90.0, 0, 0));
        for (int i = 1; i < PointSnowpack.LOOKBACK_HOURS; i++) {
            series.set(i, hour(0, 10.0, DRY, 0, 0));
        }

        assertThat(compute(series, SLOT).snowWaterMm()).isZero();
    }

    @Test
    @DisplayName("negative precipitation adds nothing")
    void negativePrecipitationIsIgnored() {
        List<Hour> series = quietSeries();
        series.set(SLOT, hour(-1.0, COLD, 90.0, 0, 0));

        assertThat(compute(series, SLOT).snowWaterMm()).isZero();
    }

    // ── Unknown, never zero ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an empty or absent series, or a slot beyond it, is unknown")
    void emptyAndOutOfRange() {
        assertThat(PointSnowpack.compute(List.of(), 0)).isEmpty();
        assertThat(PointSnowpack.compute(null, SLOT)).isEmpty();
        assertThat(PointSnowpack.compute(quietSeries(), PointSnowpack.LOOKBACK_HOURS)).isEmpty();
    }

    @Test
    @DisplayName("a lookback the series does not cover is unknown; exactly 168 hours is enough")
    void lookbackNotCovered() {
        List<Hour> short167 = new ArrayList<>(Collections.nCopies(PointSnowpack.LOOKBACK_HOURS - 1, quiet()));

        assertThat(PointSnowpack.compute(short167, SLOT - 1)).isEmpty();
        assertThat(PointSnowpack.compute(quietSeries(), SLOT)).isPresent();
    }

    @Test
    @DisplayName("16 hours missing is 9.5 % and still computes; 17 is over 10 % and is unknown")
    void missingShare() {
        List<Hour> sixteen = quietSeries();
        List<Hour> seventeen = quietSeries();
        for (int i = 0; i < PointSnowpack.MAX_MISSING_HOURS; i++) {
            sixteen.set(i, null);
            seventeen.set(i, null);
        }
        seventeen.set(PointSnowpack.MAX_MISSING_HOURS, null);

        assertThat(PointSnowpack.compute(sixteen, SLOT)).isPresent();
        assertThat(PointSnowpack.compute(seventeen, SLOT)).isEmpty();
    }

    @ParameterizedTest(name = "a hour missing input #{0} counts as missing")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void eachInputCountsAsMissing(int field) {
        Hour broken = switch (field) {
            case 0 -> new Hour(null, COLD, DRY, 0.0, 0.0);
            case 1 -> new Hour(0.0, null, DRY, 0.0, 0.0);
            case 2 -> new Hour(0.0, COLD, null, 0.0, 0.0);
            case 3 -> new Hour(0.0, COLD, DRY, null, 0.0);
            case 4 -> new Hour(0.0, COLD, DRY, 0.0, null);
            default -> new Hour(0.0, Double.NaN, DRY, 0.0, 0.0);
        };
        List<Hour> sixteen = quietSeries();
        List<Hour> seventeen = quietSeries();
        for (int i = 0; i < PointSnowpack.MAX_MISSING_HOURS; i++) {
            sixteen.set(i, broken);
            seventeen.set(i, broken);
        }
        seventeen.set(PointSnowpack.MAX_MISSING_HOURS, broken);

        assertThat(PointSnowpack.compute(sixteen, SLOT)).isPresent();
        assertThat(PointSnowpack.compute(seventeen, SLOT)).isEmpty();
    }

    @Test
    @DisplayName("a missing hour neither adds snow nor melts it")
    void missingHourIsSkipped() {
        List<Hour> series = quietSeries();
        series.set(0, hour(10.0, COLD, 90.0, 0, 0));
        series.set(1, new Hour(50.0, null, 90.0, 0.0, 0.0));
        series.set(2, null);
        series.set(3, new Hour(0.0, 20.0, null, 0.0, 0.0));

        assertThat(compute(series, SLOT).snowWaterMm()).isCloseTo(10.0, within(EXACT));
    }

    @Test
    @DisplayName("a hour outside the lookback never counts toward the missing share")
    void outsideTheLookbackIsNotCounted() {
        List<Hour> series = new ArrayList<>(Collections.nCopies(PointSnowpack.LOOKBACK_HOURS + 1, quiet()));
        series.set(0, null);

        assertThat(PointSnowpack.compute(series, PointSnowpack.LOOKBACK_HOURS)).isPresent();
    }
}
