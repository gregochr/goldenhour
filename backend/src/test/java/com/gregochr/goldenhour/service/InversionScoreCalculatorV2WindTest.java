package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.InversionClearance;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.model.InversionRegime;
import com.gregochr.goldenhour.model.InversionV2Result;
import com.gregochr.goldenhour.service.InversionV2TestSupport.H;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.gregochr.goldenhour.service.InversionV2TestSupport.data;
import static com.gregochr.goldenhour.service.InversionV2TestSupport.profile;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The summit-wind cap in {@link InversionScoreCalculatorV2} (inversion-v2-plan Phase 3): which level is read,
 * the caps at their boundaries, the regimes that get none, and binding-only recording. All profiles are
 * SYNTHETIC; the real fixture rows are pinned in {@code InversionV2FixtureTest}.
 *
 * <p>The default layer here is 680 to 1130 m: the 950 hPa level (680 m) is the base and 925 hPa (900 m) is the
 * next level above it.
 */
class InversionScoreCalculatorV2WindTest {

    private static final int H0 = 100;

    /** 21:00 BST on the evening before the sunrise. */
    private static final LocalDateTime NIGHT_START = LocalDateTime.of(2025, 10, 14, 20, 0);

    /** An elevated layer 680 to 1130 m under a saturated calm slot: 5 + 3 + 2 = 10 before any cap. */
    private static H elevatedSlot() {
        return H.at(7).t2(8.0).dew(7.8).wind(1.0).low(90).vis(10000.0).temps(6.0, 5.0, 9.0, 11.0, 10.0);
    }

    private static InversionV2Result score(InversionProfile p, int viewpoint) {
        return InversionScoreCalculatorV2.calculate(data(p), viewpoint);
    }

    /** The default elevated profile with the given level winds (975, 950, 925, 900, 850 hPa), clear 2000 m summit. */
    private static InversionV2Result withWinds(Double... winds) {
        return score(profile(H0, elevatedSlot().winds(winds)), 2000);
    }

    @Nested
    @DisplayName("the level read: the larger of the base level and the next valid level above it")
    class Level {

        @Test
        @DisplayName("the base level (950 hPa) wins when it is the larger")
        void baseLevelWins() {
            InversionV2Result r = withWinds(null, 14.0, 5.0, null, null);

            assertThat(r.windMs()).isEqualTo(14.0);
            assertThat(r.windLevelHpa()).isEqualTo(950);
        }

        @Test
        @DisplayName("the level above the base (925 hPa) wins when it is the larger")
        void nextLevelWins() {
            InversionV2Result r = withWinds(null, 10.0, 13.0, null, null);

            assertThat(r.windMs()).isEqualTo(13.0);
            assertThat(r.windLevelHpa()).isEqualTo(925);
        }

        @Test
        @DisplayName("a tie keeps the lower (base) level")
        void tieKeepsTheBaseLevel() {
            InversionV2Result r = withWinds(null, 12.0, 12.0, null, null);

            assertThat(r.windMs()).isEqualTo(12.0);
            assertThat(r.windLevelHpa()).isEqualTo(950);
        }

        @Test
        @DisplayName("a null wind at the base level is skipped, and the level above is read")
        void nullBaseWindIsSkipped() {
            InversionV2Result r = withWinds(null, null, 13.0, null, null);

            assertThat(r.windMs()).isEqualTo(13.0);
            assertThat(r.windLevelHpa()).isEqualTo(925);
        }

        @Test
        @DisplayName("a null wind at the level above is skipped, and the base level is read")
        void nullNextWindIsSkipped() {
            InversionV2Result r = withWinds(null, 13.0, null, null, null);

            assertThat(r.windMs()).isEqualTo(13.0);
            assertThat(r.windLevelHpa()).isEqualTo(950);
            assertThat(r.windCapBound()).isTrue();
        }

        @Test
        @DisplayName("no wind at either level: no wind figure and no cap, whatever the other levels say")
        void neitherLevelHasWind() {
            InversionV2Result r = withWinds(30.0, null, null, 30.0, 30.0);

            assertThat(r.windMs()).isNull();
            assertThat(r.windLevelHpa()).isNull();
            assertThat(r.windCapBound()).isFalse();
            assertThat(r.v2Raw()).isEqualTo(10.0);
            assertThat(r.caps()).isEqualTo("-");
        }

        @Test
        @DisplayName("levels other than the base and the one above it are not read")
        void otherLevelsAreIgnored() {
            InversionV2Result r = withWinds(30.0, 5.0, 6.0, 30.0, 30.0);

            assertThat(r.windMs()).isEqualTo(6.0);
            assertThat(r.windLevelHpa()).isEqualTo(925);
            assertThat(r.windCapBound()).isFalse();
        }

        @Test
        @DisplayName("a layer from another hour reads the SLOT hour's levels, nearest its base height, "
                + "and a level under h0 + 50 m is not a candidate")
        void layerFromAnotherHourUsesTheSlotHoursFilteredLevels() {
            int floor = 600;
            // 06:00 supplies the only layer: base 680 m (950 hPa), +6 degC. The 07:00 slot hour has no usable
            // temperatures, so it supplies none, but it still has levels with winds.
            H earlier = H.at(6).t2(8.0).dew(7.8).wind(1.0).low(90).vis(10000.0).temps(6.0, 5.0, 9.0, 11.0, 10.0);
            // At 07:00 the 950 hPa level sits at 640 m, under floor + 50 = 650 m, so it is filtered out even
            // though it is the nearest to 680 m and carries 30 m/s.
            H slot = H.at(7).t2(8.0).dew(7.8).wind(1.0).low(90).vis(10000.0)
                    .temps(null, null, null, null, null)
                    .heights(450.0, 640.0, 900.0, 1130.0, 1600.0)
                    .winds(30.0, 30.0, 13.0, 14.0, 20.0);

            InversionV2Result r = score(profile(floor, earlier, slot), 2000);

            assertThat(r.regime()).isEqualTo(InversionRegime.ELEVATED);
            assertThat(r.baseMetres()).isEqualTo(680.0);
            // Nearest valid level to 680 m is 925 hPa (900 m); the level above it is 900 hPa (1130 m).
            assertThat(r.windMs()).isEqualTo(14.0);
            assertThat(r.windLevelHpa()).isEqualTo(900);
            assertThat(r.caps()).isEqualTo("summit-wind(8)");
        }

        @Test
        @DisplayName("a base height equidistant from two slot-hour levels goes to the higher one")
        void equidistantBaseHeightGoesToTheHigherLevel() {
            // The 06:00 layer's base is 790 m; the 07:00 slot hour has levels at 680 m and 900 m, 110 m each way.
            H earlier = H.at(6).t2(8.0).dew(7.8).wind(1.0).low(90).vis(10000.0).temps(6.0, 5.0, 9.0, 11.0, 10.0)
                    .heights(450.0, 790.0, 900.0, 1130.0, 1600.0);
            H slot = H.at(7).t2(8.0).dew(7.8).wind(1.0).low(90).vis(10000.0)
                    .temps(null, null, null, null, null)
                    .winds(null, 13.0, 5.0, null, null);

            InversionV2Result r = score(profile(H0, earlier, slot), 2000);

            assertThat(r.baseMetres()).isEqualTo(790.0);
            // Higher level (925 hPa, 900 m) is the base level, so the 13 m/s at 950 hPa is below it and unread.
            assertThat(r.windMs()).isEqualTo(5.0);
            assertThat(r.windLevelHpa()).isEqualTo(925);
            assertThat(r.windCapBound()).isFalse();
        }
    }

    @Nested
    @DisplayName("the caps: 12 m/s caps at 8, 18 m/s caps at 6")
    class Caps {

        @ParameterizedTest(name = "{0} m/s -> score {1}, caps {2}, bound {3}")
        @CsvSource(delimiter = '|', value = {
            "11.9|10.0|-|false",
            "12.0|8.0|summit-wind(8)|true",
            "17.9|8.0|summit-wind(8)|true",
            "18.0|6.0|summit-wind(6)|true"})
        void boundaries(double wind, double expectedScore, String expectedCaps, boolean bound) {
            InversionV2Result r = withWinds(null, wind, null, null, null);

            assertThat(r.windMs()).isEqualTo(wind);
            assertThat(r.v2Raw()).isEqualTo(expectedScore);
            assertThat(r.caps()).isEqualTo(expectedCaps);
            assertThat(r.windCapBound()).isEqualTo(bound);
        }

        @Test
        @DisplayName("recorded only when it binds: an edge viewpoint is already at 8, so 13 m/s adds nothing")
        void marginalWindUnderAnEdgeCapDoesNotBind() {
            // 800 m sits between the base (680) and the next level (900): the edge, already capped at 8.
            InversionV2Result r = score(profile(H0, elevatedSlot().winds(null, 13.0, null, null, null)), 800);

            assertThat(r.clearance()).isEqualTo(InversionClearance.EDGE);
            assertThat(r.caps()).isEqualTo("edge(8)");
            assertThat(r.v2Raw()).isEqualTo(8.0);
            assertThat(r.windMs()).isEqualTo(13.0);
            assertThat(r.windCapBound()).isFalse();
        }

        @Test
        @DisplayName("the veto lowers an edge viewpoint from 8 to 6 and is recorded after the edge cap")
        void vetoLowersAnEdge() {
            InversionV2Result r = score(profile(H0, elevatedSlot().winds(null, 19.0, null, null, null)), 800);

            assertThat(r.caps()).isEqualTo("edge(8),summit-wind(6)");
            assertThat(r.v2Raw()).isEqualTo(6.0);
            assertThat(r.windCapBound()).isTrue();
        }

        @Test
        @DisplayName("caps are listed clearance, in-cloud, then wind; a wind that cannot lower a 6 is not listed")
        void inCloudBeforeWind() {
            InversionV2Result r = InversionScoreCalculatorV2.calculate(
                    data(profile(H0, elevatedSlot().winds(null, 20.0, null, null, null)), 90, 1000), 2000);

            assertThat(r.caps()).isEqualTo("in-cloud(6)");
            assertThat(r.windCapBound()).isFalse();
            assertThat(r.windMs()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("caps are listed night before wind")
        void nightBeforeWind() {
            List<InversionProfile.Hour> night = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                night.add(H.on(NIGHT_START.plusHours(i)).t2(8.0).dew(7.8).wind(1.0).low(0).vis(10000.0)
                        .mid(75).high(0).build());
            }

            InversionV2Result r = score(profile(H0, night, elevatedSlot().winds(null, 20.0, null, null, null)),
                    2000);

            assertThat(r.caps()).isEqualTo("night-mid(8),summit-wind(6)");
            assertThat(r.nightCapBound()).isTrue();
            assertThat(r.windCapBound()).isTrue();
            assertThat(r.v2Raw()).isEqualTo(6.0);
        }
    }

    @Nested
    @DisplayName("regimes with no wind cap")
    class Regimes {

        @Test
        @DisplayName("a surface-based layer is not wind-capped, however strong the wind at its levels")
        void surfaceBasedHasNoWindCap() {
            H slot = H.at(7).t2(8.0).dew(7.8).wind(1.0).low(90).vis(10000.0).temps(10.5, 9.0, 8.0, 7.0, 6.0);
            InversionV2Result without = score(profile(H0, slot), 450);
            InversionV2Result with = score(profile(H0, slot.winds(25.0, 25.0, 25.0, 25.0, 25.0)), 450);

            assertThat(with.regime()).isEqualTo(InversionRegime.SURFACE_BASED);
            assertThat(with.windMs()).isNull();
            assertThat(with.windLevelHpa()).isNull();
            assertThat(with.windCapBound()).isFalse();
            assertThat(with.caps()).isEqualTo(without.caps()).doesNotContain("summit-wind");
            assertThat(with.v2Raw()).isEqualTo(without.v2Raw());
        }

        @Test
        @DisplayName("no layer is not wind-capped: it is already capped at 6")
        void noLayerHasNoWindCap() {
            H slot = H.at(7).t2(8.0).dew(7.8).wind(1.0).low(90).vis(10000.0).temps(7.0, 6.0, 5.0, 4.0, 3.0)
                    .winds(25.0, 25.0, 25.0, 25.0, 25.0);

            InversionV2Result r = score(profile(H0, slot), 2000);

            assertThat(r.regime()).isEqualTo(InversionRegime.NONE);
            assertThat(r.windMs()).isNull();
            assertThat(r.windCapBound()).isFalse();
            assertThat(r.caps()).isEqualTo("none(6)");
        }
    }
}
