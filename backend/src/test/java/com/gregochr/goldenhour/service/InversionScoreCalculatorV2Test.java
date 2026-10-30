package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AtmosphericData;
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

import static com.gregochr.goldenhour.service.InversionV2TestSupport.data;
import static com.gregochr.goldenhour.service.InversionV2TestSupport.profile;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InversionScoreCalculatorV2}: the warm-layer walk, each cap, the surface terms and the
 * whole-number rule. Every profile is hand-built around a valley floor of 100 m with levels at 450, 680, 900,
 * 1130 and 1600 m.
 */
class InversionScoreCalculatorV2Test {

    private static final int H0 = 100;

    /** A saturated, calm slot hour with the sea in place scores 5 + 3 + 2 = 10 before any cap. */
    private static H slot() {
        return H.at(7).t2(8.0).dew(7.8).wind(1.0).low(90).vis(10000.0);
    }

    private static InversionV2Result score(AtmosphericData data, int viewpoint) {
        return InversionScoreCalculatorV2.calculate(data, viewpoint);
    }

    @Nested
    @DisplayName("the walk")
    class Walk {

        @Test
        @DisplayName("elevated: base is the last point before the first rise, top the first local maximum")
        void elevatedLayer() {
            InversionProfile p = profile(H0, slot().temps(6.0, 5.0, 9.0, 11.0, 10.0));

            InversionV2Result r = score(data(p), 2000);

            assertThat(r.regime()).isEqualTo(InversionRegime.ELEVATED);
            assertThat(r.baseMetres()).isEqualTo(680.0);
            assertThat(r.topMetres()).isEqualTo(1130.0);
            assertThat(r.strengthCelsius()).isEqualTo(6.0);
            assertThat(r.valleyFloorMetres()).isEqualTo(H0);
            assertThat(r.viewpointMetres()).isEqualTo(2000);
        }

        @Test
        @DisplayName("the top is the FIRST local maximum, so a warmer layer aloft cannot extend it")
        void topIsFirstLocalMaximum() {
            InversionProfile p = profile(H0, slot().temps(6.0, 9.0, 7.0, 12.0, 11.0));

            InversionV2Result r = score(data(p), 2000);

            assertThat(r.baseMetres()).isEqualTo(450.0);
            assertThat(r.topMetres()).isEqualTo(680.0);
            assertThat(r.strengthCelsius()).isEqualTo(3.0);
        }

        @Test
        @DisplayName("a shallow rise whose top is the first valid level is surface-based with base = h0")
        void surfaceBased() {
            InversionProfile p = profile(H0, slot().t2(5.0).dew(4.8).temps(7.0, 6.0, 5.0, 4.0, 3.0));

            InversionV2Result r = score(data(p), 300);

            assertThat(r.regime()).isEqualTo(InversionRegime.SURFACE_BASED);
            assertThat(r.baseMetres()).isEqualTo((double) H0);
            assertThat(r.topMetres()).isEqualTo(450.0);
            assertThat(r.strengthCelsius()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("a rise from the surface that keeps going is elevated, base = the coldest LEVEL below the top")
        void riseFromSurfaceThatKeepsGoingIsElevated() {
            InversionProfile p = profile(H0, slot().t2(5.0).dew(4.8).temps(6.0, 7.0, 9.0, 8.0, 7.0));

            InversionV2Result r = score(data(p), 2000);

            assertThat(r.regime()).isEqualTo(InversionRegime.ELEVATED);
            assertThat(r.baseMetres()).isEqualTo(450.0);
            assertThat(r.topMetres()).isEqualTo(900.0);
            assertThat(r.strengthCelsius()).isEqualTo(3.0);
        }

        @Test
        @DisplayName("levels less than 50 m above the valley floor are skipped (the summit trap)")
        void levelsUnderFiftyMetresAboveFloorAreSkipped() {
            // h0 = 420: the 975 hPa level at 450 m is only 30 m up and is skipped, so its warm reading cannot
            // start a rise; the first valid level is 950 hPa, which is colder than the surface.
            InversionProfile p = profile(420, slot().t2(5.0).dew(4.8).temps(9.0, 4.0, 8.0, 6.0, 5.0));

            InversionV2Result r = score(data(p), 2000);

            assertThat(r.regime()).isEqualTo(InversionRegime.ELEVATED);
            assertThat(r.baseMetres()).isEqualTo(680.0);
            assertThat(r.topMetres()).isEqualTo(900.0);
        }

        @Test
        @DisplayName("a level exactly 50 m above the floor counts")
        void levelExactlyFiftyAboveFloorCounts() {
            InversionProfile p = profile(400, slot().t2(5.0).dew(4.8).temps(9.0, 4.0, 3.0, 2.0, 1.0));

            InversionV2Result r = score(data(p), 2000);

            assertThat(r.regime()).isEqualTo(InversionRegime.SURFACE_BASED);
            assertThat(r.topMetres()).isEqualTo(450.0);
        }

        @Test
        @DisplayName("a profile that only falls has no warm layer: regime NONE, capped at 6")
        void noRiseMeansNoLayer() {
            InversionProfile p = profile(H0, slot().t2(9.0).temps(8.0, 7.0, 6.0, 5.0, 4.0));

            InversionV2Result r = score(data(p), 2000);

            assertThat(r.regime()).isEqualTo(InversionRegime.NONE);
            assertThat(r.clearance()).isEqualTo(InversionClearance.NO_LAYER);
            assertThat(r.baseMetres()).isNull();
            assertThat(r.v2Raw()).isEqualTo(6.0);
            assertThat(r.caps()).isEqualTo("none(6)");
        }

        @Test
        @DisplayName("equal adjacent temperatures are not a rise")
        void equalTemperaturesAreNotARise() {
            InversionProfile p = profile(H0, slot().t2(6.0).temps(6.0, 6.0, 6.0, 6.0, 6.0));

            InversionV2Result r = score(data(p), 2000);

            assertThat(r.regime()).isEqualTo(InversionRegime.NONE);
        }

        @Test
        @DisplayName("a plateau ends the rise: the top is the last strictly warmer point")
        void plateauEndsTheRise() {
            InversionProfile p = profile(H0, slot().temps(6.0, 5.0, 9.0, 9.0, 12.0));

            InversionV2Result r = score(data(p), 2000);

            assertThat(r.baseMetres()).isEqualTo(680.0);
            assertThat(r.topMetres()).isEqualTo(900.0);
        }

        @Test
        @DisplayName("null levels are skipped and a single valid level still scores")
        void nullLevelsAreSkipped() {
            InversionProfile p = profile(H0, slot().t2(5.0).dew(4.8).temps(null, null, 8.0, null, null));

            InversionV2Result r = score(data(p), 2000);

            assertThat(r).isNotNull();
            assertThat(r.regime()).isEqualTo(InversionRegime.SURFACE_BASED);
            assertThat(r.topMetres()).isEqualTo(900.0);
        }

        @Test
        @DisplayName("a missing 900 hPa level leaves the rest of the walk intact (the 2c fixtures lack it)")
        void missingNineHundredLeavesTheWalkIntact() {
            InversionProfile p = profile(H0, slot().temps(6.0, 5.0, 9.0, null, 10.0));

            InversionV2Result r = score(data(p), 2000);

            assertThat(r.baseMetres()).isEqualTo(680.0);
            assertThat(r.topMetres()).isEqualTo(1600.0);
        }

        @Test
        @DisplayName("no valid level in any hour: v2 cannot score")
        void noValidLevelMeansNull() {
            InversionProfile p = profile(H0, slot().temps(null, null, null, null, null));

            assertThat(score(data(p), 2000)).isNull();
        }

        @Test
        @DisplayName("strength is rounded to 0.1 degC")
        void strengthIsRoundedToATenth() {
            InversionProfile p = profile(H0, slot().temps(6.0, 5.0, 8.04, 7.0, 6.0));

            InversionV2Result r = score(data(p), 2000);

            assertThat(r.strengthCelsius()).isEqualTo(3.0);
        }
    }

    @Nested
    @DisplayName("hours examined")
    class Hours {

        @Test
        @DisplayName("the strongest layer wins even when it is not at the event slot")
        void strongestLayerWins() {
            InversionProfile p = profile(H0,
                    H.at(4).temps(6.0, 5.0, 10.0, 12.0, 11.0).t2(8.0),
                    slot().temps(6.0, 5.0, 6.5, 7.0, 6.0));

            InversionV2Result r = score(data(p), 2000);

            assertThat(r.strengthCelsius()).isEqualTo(7.0);
        }

        @Test
        @DisplayName("a tie goes to the hour nearest the event slot")
        void tieGoesToNearestHour() {
            // 03:00 and 06:00 both give a 6.0 degC layer; they differ only in the 950 hPa height (base).
            InversionProfile p = profile(H0,
                    H.at(3).temps(6.0, 5.0, 9.0, 11.0, 10.0).heights(450.0, 700.0, 900.0, 1130.0, 1600.0),
                    H.at(6).temps(6.0, 5.0, 9.0, 11.0, 10.0).heights(450.0, 650.0, 900.0, 1130.0, 1600.0),
                    slot().temps(8.5, 9.0, 8.0, 7.0, 6.0));

            InversionV2Result r = score(data(p), 2000);

            assertThat(r.strengthCelsius()).isEqualTo(6.0);
            assertThat(r.baseMetres()).isEqualTo(650.0);
        }

        @Test
        @DisplayName("an event slot with no dew point, wind or temperature cannot be scored")
        void slotWithoutSurfaceReadingsIsNull() {
            assertThat(score(data(profile(H0, slot().dew(null).temps(6.0, 5.0, 9.0, 11.0, 10.0))), 2000)).isNull();
            assertThat(score(data(profile(H0, slot().wind(null).temps(6.0, 5.0, 9.0, 11.0, 10.0))), 2000)).isNull();
            assertThat(score(data(profile(H0, slot().t2(null).temps(6.0, 5.0, 9.0, 11.0, 10.0))), 2000)).isNull();
        }

        @Test
        @DisplayName("no profile, or no elevation, is null")
        void noProfileOrElevationIsNull() {
            assertThat(score(data(null), 2000)).isNull();
            assertThat(InversionScoreCalculatorV2.calculate(
                    data(profile(H0, slot().temps(6.0, 5.0, 9.0, 11.0, 10.0))), null)).isNull();
            assertThat(InversionScoreCalculatorV2.calculate(null, 2000)).isNull();
        }
    }

    @Nested
    @DisplayName("regimes, clearance and caps")
    class Caps {

        private final InversionProfile elevated = profile(H0, slot().temps(6.0, 5.0, 9.0, 11.0, 10.0));

        @Test
        @DisplayName("elevated, viewpoint below the base: inside, capped at 6")
        void elevatedInside() {
            InversionV2Result r = score(data(elevated), 500);

            assertThat(r.clearance()).isEqualTo(InversionClearance.INSIDE);
            assertThat(r.v2Raw()).isEqualTo(6.0);
            assertThat(r.caps()).isEqualTo("inside(6)");
        }

        @Test
        @DisplayName("elevated, viewpoint from the base up to the next level: the edge, capped at 8")
        void elevatedEdge() {
            InversionV2Result r = score(data(elevated), 700);

            assertThat(r.clearance()).isEqualTo(InversionClearance.EDGE);
            assertThat(r.v2Raw()).isEqualTo(8.0);
        }

        @Test
        @DisplayName("elevated, viewpoint exactly at the base: the edge")
        void elevatedAtBaseIsEdge() {
            assertThat(score(data(elevated), 680).clearance()).isEqualTo(InversionClearance.EDGE);
        }

        @Test
        @DisplayName("elevated, viewpoint at the next level or above: clear, allowed to reach 10")
        void elevatedClear() {
            InversionV2Result r = score(data(elevated), 900);

            assertThat(r.clearance()).isEqualTo(InversionClearance.CLEAR);
            assertThat(r.v2Raw()).isEqualTo(10.0);
            assertThat(r.caps()).isEqualTo("-");
        }

        @Test
        @DisplayName("a layer weaker than 2.0 degC caps at 8 even for a clear viewpoint")
        void weakLayerCapsAtEight() {
            InversionProfile weak = profile(H0, slot().t2(9.0).dew(8.8).temps(8.0, 7.0, 8.9, 8.0, 7.0));

            InversionV2Result r = score(data(weak), 2000);

            assertThat(r.strengthCelsius()).isEqualTo(1.9);
            assertThat(r.v2Raw()).isEqualTo(8.0);
            assertThat(r.caps()).isEqualTo("weak(8)");
        }

        @Test
        @DisplayName("a layer of exactly 2.0 degC is strong enough")
        void layerOfExactlyTwoIsStrong() {
            InversionProfile strong = profile(H0, slot().t2(9.0).dew(8.8).temps(8.0, 7.0, 9.0, 8.0, 7.0));

            InversionV2Result r = score(data(strong), 2000);

            assertThat(r.strengthCelsius()).isEqualTo(2.0);
            assertThat(r.v2Raw()).isEqualTo(10.0);
        }

        @Test
        @DisplayName("surface-based with night-side visibility under 5 km and E >= h0 + 100: the edge, capped at 8")
        void surfaceBasedWithFogVisibilityIsEdge() {
            InversionProfile p = profile(H0, slot().t2(5.0).dew(4.8).vis(4999.0).temps(7.0, 6.0, 5.0, 4.0, 3.0));

            InversionV2Result r = score(data(p), 200);

            assertThat(r.clearance()).isEqualTo(InversionClearance.EDGE);
            assertThat(r.v2Raw()).isEqualTo(8.0);
        }

        @Test
        @DisplayName("surface-based with fog evidence but E under h0 + 100: inside, capped at 6")
        void surfaceBasedTooCloseToTheFloor() {
            InversionProfile p = profile(H0, slot().t2(5.0).dew(4.8).vis(3000.0).temps(7.0, 6.0, 5.0, 4.0, 3.0));

            InversionV2Result r = score(data(p), 199);

            assertThat(r.clearance()).isEqualTo(InversionClearance.INSIDE);
            assertThat(r.v2Raw()).isEqualTo(6.0);
        }

        @Test
        @DisplayName("surface-based with no fog evidence: unconfirmed, capped at 6")
        void surfaceBasedWithoutFogEvidence() {
            InversionProfile p = profile(H0, slot().t2(5.0).dew(4.8).wind(4.0).vis(20000.0)
                    .temps(7.0, 6.0, 5.0, 4.0, 3.0));

            InversionV2Result r = score(data(p), 400);

            assertThat(r.clearance()).isEqualTo(InversionClearance.UNCONFIRMED);
            assertThat(r.v2Raw()).isEqualTo(6.0);
            assertThat(r.caps()).isEqualTo("unconfirmed(6)");
        }

        @Test
        @DisplayName("fog evidence by gap and wind must hold in the SAME hour")
        void gapAndWindMustCoincide() {
            // Hour 5: gap 0.5 but wind 4. Slot: gap 3 but wind 1. Neither hour has both; visibility is good.
            InversionProfile apart = profile(H0,
                    H.at(5).t2(5.0).dew(4.5).wind(4.0).vis(20000.0).temps(7.0, 6.0, 5.0, 4.0, 3.0),
                    slot().t2(5.0).dew(2.0).wind(1.0).vis(20000.0).low(10).temps(7.0, 6.0, 5.0, 4.0, 3.0));
            InversionProfile together = profile(H0,
                    H.at(5).t2(5.0).dew(4.5).wind(1.5).vis(20000.0).temps(7.0, 6.0, 5.0, 4.0, 3.0),
                    slot().t2(5.0).dew(2.0).wind(1.0).vis(20000.0).low(10).temps(7.0, 6.0, 5.0, 4.0, 3.0));

            assertThat(score(data(apart), 400).clearance()).isEqualTo(InversionClearance.UNCONFIRMED);
            assertThat(score(data(together), 400).clearance()).isEqualTo(InversionClearance.EDGE);
        }

        @Test
        @DisplayName("viewpoint in cloud: low cloud >= 80% AND visibility < 5 km caps at 6")
        void viewpointInCloud() {
            InversionV2Result r = score(data(elevated, 80, 4999), 2000);

            assertThat(r.v2Raw()).isEqualTo(6.0);
            assertThat(r.caps()).isEqualTo("in-cloud(6)");
        }

        @ParameterizedTest(name = "viewpoint low cloud {0}%, visibility {1} m is not in cloud")
        @CsvSource({"79, 4999", "80, 5000", "89, 12700", "100, 20000"})
        @DisplayName("high cloud with good visibility, or poor visibility with thin cloud, is not in cloud")
        void notInCloud(int lowCloud, int visibility) {
            InversionV2Result r = score(data(elevated, lowCloud, visibility), 2000);

            assertThat(r.v2Raw()).isEqualTo(10.0);
        }

        @Test
        @DisplayName("every cap that applies is listed, and the lowest wins")
        void capsAccumulate() {
            InversionV2Result r = score(data(elevated, 90, 1000), 700);

            assertThat(r.v2Raw()).isEqualTo(6.0);
            assertThat(r.caps()).isEqualTo("edge(8),in-cloud(6)");
        }
    }

    @Nested
    @DisplayName("surface terms")
    class SurfaceTerms {

        private final Double[] clearLayer = {6.0, 5.0, 9.0, 11.0, 10.0};

        @ParameterizedTest(name = "gap {0} degC scores moisture {1}")
        @CsvSource({"0.5, 5", "1.0, 4", "2.0, 3", "3.0, 2", "5.0, 1", "5.1, 0"})
        @DisplayName("moisture is MoistureScoring.gapScore, called unchanged")
        void moistureIsGapScore(double gap, double moisture) {
            // wind 1.0 -> 3 and low cloud 90 -> sea 2, so the score is moisture + 5.
            InversionProfile p = profile(H0, slot().t2(8.0).dew(8.0 - gap).temps(clearLayer));

            assertThat(score(data(p), 2000).v2Raw()).isEqualTo(moisture + 5.0);
        }

        @ParameterizedTest(name = "wind {0} m/s scores {1}")
        @CsvSource({"1.5, 3", "1.6, 2", "3.0, 2", "3.1, 1", "5.0, 1", "5.1, 0"})
        @DisplayName("wind uses the old 0-3 steps")
        void windSteps(double wind, double points) {
            // gap 0.2 -> moisture 5, sea 2.
            InversionProfile p = profile(H0, slot().wind(wind).temps(clearLayer));

            assertThat(score(data(p), 2000).v2Raw()).isEqualTo(7.0 + points);
        }

        @Test
        @DisplayName("sea evidence: 2 for valley low cloud >= 50% at the event")
        void seaFromLowCloud() {
            InversionProfile p = profile(H0, slot().low(50).vis(20000.0).temps(clearLayer));

            assertThat(score(data(p), 2000).v2Raw()).isEqualTo(10.0);
        }

        @Test
        @DisplayName("sea evidence: 2 for night-side visibility under 1 km even with thin cloud")
        void seaFromDenseFog() {
            InversionProfile p = profile(H0,
                    H.at(4).t2(8.0).dew(7.8).vis(900.0).temps(clearLayer),
                    slot().low(10).vis(20000.0).temps(clearLayer));

            assertThat(score(data(p), 2000).v2Raw()).isEqualTo(10.0);
        }

        @Test
        @DisplayName("sea evidence: 1 for night-side visibility under 5 km, 0 otherwise")
        void seaFromModerateFogAndNone() {
            InversionProfile moderate = profile(H0, slot().low(10).vis(4000.0).temps(clearLayer));
            InversionProfile none = profile(H0, slot().low(10).vis(20000.0).temps(clearLayer));

            assertThat(score(data(moderate), 2000).v2Raw()).isEqualTo(9.0);
            assertThat(score(data(none), 2000).v2Raw()).isEqualTo(8.0);
        }

        @Test
        @DisplayName("surface terms come from the event slot, not from the hour that supplied the layer")
        void surfaceTermsComeFromTheSlot() {
            // The strong layer is at 04:00 (wind 5 m/s there); the slot is calm. Wind scores from the slot.
            InversionProfile p = profile(H0,
                    H.at(4).t2(8.0).dew(7.8).wind(5.0).temps(6.0, 5.0, 10.0, 12.0, 11.0),
                    slot().wind(1.0).temps(6.0, 5.0, 6.5, 7.0, 6.0));

            InversionV2Result r = score(data(p), 2000);

            assertThat(r.strengthCelsius()).isEqualTo(7.0);
            assertThat(r.v2Raw()).isEqualTo(10.0);
        }
    }

    @Nested
    @DisplayName("sunset window")
    class SunsetWindow {

        @Test
        @DisplayName("a sunset examines the slot and the two hours after it, not the hours before")
        void sunsetWindowIsSlotToPlusTwo() {
            java.time.LocalDateTime event = java.time.LocalDateTime.of(2025, 10, 15, 16, 20);
            InversionProfile whole = new InversionProfile(54.5, -3.1, 100.0, H0, null,
                    java.util.stream.IntStream.range(10, 22)
                            .mapToObj(hour -> H.at(hour).temps(6.0, 5.0, 9.0, 11.0, 10.0).build())
                            .toList());

            InversionProfile sliced = whole.sliceForEvent(event, com.gregochr.goldenhour.entity.TargetType.SUNSET);

            assertThat(sliced.slotTime()).isEqualTo(java.time.LocalDateTime.of(2025, 10, 15, 16, 0));
            assertThat(sliced.hours()).extracting(InversionProfile.Hour::time).containsExactly(
                    java.time.LocalDateTime.of(2025, 10, 15, 16, 0),
                    java.time.LocalDateTime.of(2025, 10, 15, 17, 0),
                    java.time.LocalDateTime.of(2025, 10, 15, 18, 0));
        }
    }

    @Test
    @DisplayName("every v2 term and cap is whole, so the score is whole (the prompt and the readers cannot disagree)")
    void scoresAreAlwaysWholeNumbers() {
        Double[][] layers = {
            {6.0, 5.0, 9.0, 11.0, 10.0}, {7.0, 6.0, 5.0, 4.0, 3.0}, {8.0, 7.0, 6.0, 5.0, 4.0},
            {6.0, 6.5, 7.0, 7.5, 8.0}};
        double[] gaps = {0.1, 0.7, 1.4, 2.6, 4.0, 7.0};
        double[] winds = {0.5, 2.0, 4.0, 8.0};
        int[] viewpoints = {150, 300, 450, 700, 950};
        for (Double[] layer : layers) {
            for (double gap : gaps) {
                for (double wind : winds) {
                    for (int viewpoint : viewpoints) {
                        InversionProfile p = profile(H0, slot().t2(6.0).dew(6.0 - gap).wind(wind).temps(layer));
                        InversionV2Result r = score(data(p), viewpoint);
                        assertThat(r.v2Raw()).as("%s gap %s wind %s E %s", layer, gap, wind, viewpoint)
                                .isEqualTo(Math.rint(r.v2Raw()));
                    }
                }
            }
        }
    }
}
