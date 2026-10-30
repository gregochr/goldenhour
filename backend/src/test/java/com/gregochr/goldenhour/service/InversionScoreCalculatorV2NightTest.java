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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static com.gregochr.goldenhour.service.InversionV2TestSupport.data;
import static com.gregochr.goldenhour.service.InversionV2TestSupport.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The clear night in {@link InversionScoreCalculatorV2} (inversion-v2-plan Phase 2): the night measure, the
 * cap by regime, the no-night-data rules and Option B. Night hours are hand-built from 20:00 UTC on 14 Oct
 * 2025, which is 21:00 BST, so local midnight falls at 23:00 UTC and the sunrise slot is 07:00 UTC on the 15th.
 * All profiles are SYNTHETIC; the real nights are pinned in {@code InversionV2FixtureTest}.
 */
class InversionScoreCalculatorV2NightTest {

    private static final int H0 = 100;

    /** 21:00 BST on the evening before the sunrise. */
    private static final LocalDateTime NIGHT_START = LocalDateTime.of(2025, 10, 14, 20, 0);

    /** An elevated warm layer 680 to 1130 m with a clear 2000 m summit: 5 + 3 + 2 = 10 before any cap. */
    private static H elevatedSlot() {
        return H.at(7).t2(8.0).dew(7.8).wind(1.0).low(90).vis(10000.0).temps(6.0, 5.0, 9.0, 11.0, 10.0);
    }

    /** A 2.5 degC surface-based layer under a saturated calm slot (fog evidence present): edge at 450 m, 8. */
    private static H surfaceSlotWithFog() {
        return H.at(7).t2(8.0).dew(7.8).wind(1.0).low(90).vis(10000.0).temps(10.5, 9.0, 8.0, 7.0, 6.0);
    }

    /** The same surface-based layer under a slot with NO fog evidence: unconfirmed unless Option B holds. */
    private static H surfaceSlotNoFog() {
        return H.at(7).t2(8.0).dew(5.0).wind(4.0).low(10).vis(10000.0).temps(10.5, 9.0, 8.0, 7.0, 6.0);
    }

    /**
     * A night of {@code count} hours from {@link #NIGHT_START}. Each hour starts clear, calm and moist (mid and
     * high 0, no low cloud, gap 0.2 degC, wind 1 m/s, 10 km visibility) and the shape varies what a test
     * needs; the index is the hour's position in the night.
     */
    private static List<InversionProfile.Hour> night(int count, BiFunction<Integer, H, H> shape) {
        List<InversionProfile.Hour> hours = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            H clear = H.on(NIGHT_START.plusHours(i)).t2(8.0).dew(7.8).wind(1.0).low(0).vis(10000.0)
                    .mid(0).high(0);
            hours.add(shape.apply(i, clear).build());
        }
        return hours;
    }

    private static List<InversionProfile.Hour> night(int count, java.util.function.UnaryOperator<H> shape) {
        return night(count, (i, h) -> shape.apply(h));
    }

    private static InversionV2Result score(InversionProfile p, int viewpoint) {
        return InversionScoreCalculatorV2.calculate(data(p), viewpoint);
    }

    @Nested
    @DisplayName("the night measure")
    class Measure {

        @Test
        @DisplayName("is the mean of max(mid, half of high, lid): mid 40 over high 100 reads 50, not 100")
        void highCloudCountsHalf() {
            InversionProfile p = profile(H0, night(8, h -> h.mid(0).high(100)), elevatedSlot());

            InversionV2Result r = score(p, 2000);

            assertThat(r.nightPercent()).isCloseTo(50.0, within(1e-9));
            assertThat(r.nightMidPercent()).isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("mid dominates when it is the larger of the three")
        void midDominates() {
            InversionProfile p = profile(H0, night(8, h -> h.mid(60).high(40)), elevatedSlot());

            InversionV2Result r = score(p, 2000);

            assertThat(r.nightPercent()).isCloseTo(60.0, within(1e-9));
            assertThat(r.nightMidPercent()).isCloseTo(60.0, within(1e-9));
        }

        @Test
        @DisplayName("a low deck over visibility of 5 km or more is a lid and counts at its full value")
        void lowCloudOverGoodVisibilityIsALid() {
            InversionProfile p = profile(H0, night(8, h -> h.low(80).vis(5000.0)), elevatedSlot());

            assertThat(score(p, 2000).nightPercent()).isCloseTo(80.0, within(1e-9));
        }

        @Test
        @DisplayName("low cloud with visibility under 5 km is the fog or sea itself and does not count")
        void lowCloudInFogIsNotALid() {
            InversionProfile p = profile(H0, night(8, h -> h.low(80).vis(4999.0)), elevatedSlot());

            assertThat(score(p, 2000).nightPercent()).isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("null low cloud or null visibility makes the lid 0, and the hour still counts")
        void nullLidInputsMakeNoLid() {
            InversionProfile noLow = profile(H0, night(8, h -> h.low(null)), elevatedSlot());
            InversionProfile noVis = profile(H0, night(8, h -> h.low(90).vis(null)), elevatedSlot());

            assertThat(score(noLow, 2000).nightPercent()).isCloseTo(0.0, within(1e-9));
            assertThat(score(noVis, 2000).nightPercent()).isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("the mean is of the unrounded values: six hours at 70 and one at 69 is 69.857, not 70")
        void meanIsUnrounded() {
            InversionProfile p = profile(H0, night(7, (i, h) -> h.mid(i == 6 ? 69 : 70)), surfaceSlotWithFog());

            InversionV2Result r = score(p, 450);

            assertThat(r.nightPercent()).isCloseTo(489.0 / 7.0, within(1e-9));
            assertThat(r.caps()).isEqualTo("edge(8)");
            assertThat(r.nightCapBound()).isFalse();
        }
    }

    @Nested
    @DisplayName("no night data")
    class NoNightData {

        @Test
        @DisplayName("a profile with no night side scores exactly as before, with null night figures")
        void noNightSide() {
            InversionProfile p = profile(H0, elevatedSlot());

            InversionV2Result r = score(p, 2000);

            assertThat(r.nightPercent()).isNull();
            assertThat(r.nightMidPercent()).isNull();
            assertThat(r.optionB()).isFalse();
            assertThat(r.v2Raw()).isEqualTo(10.0);
            assertThat(r.caps()).isEqualTo("-");
        }

        @Test
        @DisplayName("five counted hours are not enough, six are")
        void sixCountedHoursAreTheMinimum() {
            assertThat(score(profile(H0, night(5, h -> h.mid(100)), elevatedSlot()), 2000).nightPercent())
                    .isNull();
            assertThat(score(profile(H0, night(6, h -> h.mid(100)), elevatedSlot()), 2000).nightPercent())
                    .isCloseTo(100.0, within(1e-9));
        }

        @Test
        @DisplayName("hours without mid or without high cloud are not counted")
        void hoursWithoutMidOrHighAreNotCounted() {
            // Eight hours, two of them missing a series: six remain, which is enough, and they alone are averaged.
            InversionProfile enough = profile(H0, night(8, (i, h) -> i == 0 ? h.mid(null).high(100)
                    : i == 1 ? h.mid(100).high(null) : h.mid(40)), elevatedSlot());
            // One more missing leaves five.
            InversionProfile tooFew = profile(H0, night(8, (i, h) -> i < 3 ? h.mid(null) : h.mid(40)),
                    elevatedSlot());

            assertThat(score(enough, 2000).nightPercent()).isCloseTo(40.0, within(1e-9));
            assertThat(score(tooFew, 2000).nightPercent()).isNull();
        }

        @Test
        @DisplayName("hours that all fall after local midnight are a half night: no data")
        void halfNightIsNoData() {
            // 23:00 UTC is 00:00 BST: seven hours from there are all on the event date.
            List<InversionProfile.Hour> afterMidnight = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                afterMidnight.add(H.on(LocalDateTime.of(2025, 10, 14, 23, 0).plusHours(i)).mid(100).high(0)
                        .low(0).build());
            }
            InversionProfile half = profile(H0, afterMidnight, elevatedSlot());
            // The same seven hours starting an hour earlier include 22:00 UTC (23:00 BST) and so count.
            List<InversionProfile.Hour> startsBeforeMidnight = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                startsBeforeMidnight.add(H.on(LocalDateTime.of(2025, 10, 14, 22, 0).plusHours(i)).mid(100)
                        .high(0).low(0).build());
            }

            assertThat(score(half, 2000).nightPercent()).isNull();
            assertThat(score(profile(H0, startsBeforeMidnight, elevatedSlot()), 2000).nightPercent())
                    .isCloseTo(100.0, within(1e-9));
        }

        @Test
        @DisplayName("no night data means no night cap and no Option B, even over a cloudy night's readings")
        void noDataNoCapNoOptionB() {
            InversionProfile p = profile(H0, night(5, h -> h.mid(100)), surfaceSlotNoFog());

            InversionV2Result r = score(p, 450);

            assertThat(r.optionB()).isFalse();
            assertThat(r.clearance()).isEqualTo(InversionClearance.UNCONFIRMED);
            assertThat(r.nightCapBound()).isFalse();
        }
    }

    @Nested
    @DisplayName("the night cap by regime")
    class Caps {

        @ParameterizedTest(name = "elevated, mean mid {0}: score {1}, caps {2}")
        @CsvSource({"69,10,-", "70,8,night-mid(8)", "100,8,night-mid(8)"})
        @DisplayName("elevated: only the mean MID cloud counts, 70 caps at 8 (the 70 boundary)")
        void elevatedMidBoundary(int mid, double expected, String caps) {
            InversionProfile p = profile(H0, night(8, h -> h.mid(mid)), elevatedSlot());

            InversionV2Result r = score(p, 2000);

            assertThat(r.regime()).isEqualTo(InversionRegime.ELEVATED);
            assertThat(r.v2Raw()).isEqualTo(expected);
            assertThat(r.caps()).isEqualTo(caps);
            assertThat(r.nightCapBound()).isEqualTo(expected < 10.0);
        }

        @Test
        @DisplayName("elevated: a fully overcast sky of cirrus alone (15 Jan Glenridding) does not cap")
        void elevatedSurvivesCirrus() {
            InversionProfile p = profile(H0, night(8, h -> h.mid(0).high(100)), elevatedSlot());

            InversionV2Result r = score(p, 2000);

            assertThat(r.nightPercent()).isCloseTo(50.0, within(1e-9));
            assertThat(r.v2Raw()).isEqualTo(10.0);
            assertThat(r.nightCapBound()).isFalse();
        }

        @Test
        @DisplayName("elevated: a lid of low cloud over good visibility does not cap, only mid cloud does")
        void elevatedIgnoresTheLid() {
            InversionProfile p = profile(H0, night(8, h -> h.low(100).vis(20000.0)), elevatedSlot());

            InversionV2Result r = score(p, 2000);

            assertThat(r.nightPercent()).isCloseTo(100.0, within(1e-9));
            assertThat(r.v2Raw()).isEqualTo(10.0);
        }

        @ParameterizedTest(name = "surface-based, night {0}: score {1}, caps {2}")
        @CsvSource({"39,8,edge(8)", "40,8,edge(8)", "69,8,edge(8)", "70,6,'edge(8),overcast-night(6)'"})
        @DisplayName("surface-based: the full measure, 70 caps at 6; the 40 step never binds because the edge is 8")
        void surfaceBasedBoundaries(int mid, double expected, String caps) {
            InversionProfile p = profile(H0, night(8, h -> h.mid(mid)), surfaceSlotWithFog());

            InversionV2Result r = score(p, 450);

            assertThat(r.regime()).isEqualTo(InversionRegime.SURFACE_BASED);
            assertThat(r.v2Raw()).isEqualTo(expected);
            assertThat(r.caps()).isEqualTo(caps);
            assertThat(r.nightCapBound()).isEqualTo(mid >= 70);
        }

        @Test
        @DisplayName("surface-based: low cloud over good visibility is a lid and caps at 6; in fog it does not")
        void surfaceBasedLid() {
            InversionProfile lid = profile(H0, night(8, h -> h.low(70).vis(5000.0)), surfaceSlotWithFog());
            InversionProfile fog = profile(H0, night(8, h -> h.low(70).vis(4999.0)), surfaceSlotWithFog());

            assertThat(score(lid, 450).caps()).isEqualTo("edge(8),overcast-night(6)");
            assertThat(score(fog, 450).caps()).isEqualTo("edge(8)");
        }

        @Test
        @DisplayName("no layer: the night changes nothing, the cap of 6 is already there")
        void noLayerNoNightCap() {
            H flat = H.at(7).t2(9.0).temps(8.0, 7.0, 6.0, 5.0, 4.0);
            InversionProfile p = profile(H0, night(8, h -> h.mid(100)), flat);

            InversionV2Result r = score(p, 450);

            assertThat(r.clearance()).isEqualTo(InversionClearance.NO_LAYER);
            assertThat(r.caps()).isEqualTo("none(6)");
            assertThat(r.nightCapBound()).isFalse();
            assertThat(r.nightPercent()).isCloseTo(100.0, within(1e-9));
        }

        @Test
        @DisplayName("a night cap enters caps only when it binds: an already-lower score records nothing")
        void nonBindingCapIsNotRecorded() {
            // Inside the layer (E 150 m under the 680 m base) the score is already 6.
            InversionProfile p = profile(H0, night(8, h -> h.mid(100)), elevatedSlot());

            InversionV2Result r = score(p, 150);

            assertThat(r.v2Raw()).isEqualTo(6.0);
            assertThat(r.caps()).isEqualTo("inside(6)");
            assertThat(r.nightCapBound()).isFalse();
        }

        @Test
        @DisplayName("order: clearance, weak, in-cloud, night")
        void capOrder() {
            // Weak surface-based layer (1 degC from 8 to 9), fog evidence, viewpoint cell in cloud, overcast night.
            H weak = H.at(7).t2(8.0).dew(7.8).wind(1.0).low(90).vis(10000.0).temps(9.0, 8.0, 7.0, 6.0, 5.0);
            AtmosphericData inCloud = data(profile(H0, night(8, h -> h.mid(100)), weak), 90, 2000);

            InversionV2Result r = InversionScoreCalculatorV2.calculate(inCloud, 450);

            assertThat(r.caps()).isEqualTo("edge(8),weak(8),in-cloud(6)");
            assertThat(r.nightCapBound()).isFalse();
        }
    }

    @Nested
    @DisplayName("Option B: a clear, calm, moist night")
    class OptionB {

        @Test
        @DisplayName("all three conditions held: an unconfirmed surface-based layer at 450 m is an edge, night-fog(8)")
        void optionBTurnsUnconfirmedIntoEdge() {
            InversionProfile p = profile(H0, night(8, h -> h), surfaceSlotNoFog());

            InversionV2Result r = score(p, 450);

            assertThat(r.optionB()).isTrue();
            assertThat(r.optionBBound()).isTrue();
            assertThat(r.clearance()).isEqualTo(InversionClearance.EDGE);
            assertThat(r.caps()).isEqualTo("night-fog(8)");
        }

        @Test
        @DisplayName("without it the same slot is unconfirmed, capped at 6")
        void withoutOptionBItIsUnconfirmed() {
            InversionProfile p = profile(H0, surfaceSlotNoFog());

            InversionV2Result r = score(p, 450);

            assertThat(r.optionB()).isFalse();
            assertThat(r.clearance()).isEqualTo(InversionClearance.UNCONFIRMED);
            assertThat(r.caps()).isEqualTo("unconfirmed(6)");
        }

        @Test
        @DisplayName("under h0 + 100 m Option B holds but cannot make an edge: unconfirmed stays")
        void optionBNeedsTheViewpointAboveTheFloor() {
            InversionProfile p = profile(H0, night(8, h -> h), surfaceSlotNoFog());

            InversionV2Result r = score(p, 199);

            assertThat(r.optionB()).isTrue();
            assertThat(r.optionBBound()).isFalse();
            assertThat(r.clearance()).isEqualTo(InversionClearance.UNCONFIRMED);
            assertThat(r.caps()).isEqualTo("unconfirmed(6)");
        }

        @Test
        @DisplayName("h0 + 100 m exactly is enough")
        void optionBAtTheEdgeOfTheBoundary() {
            InversionProfile p = profile(H0, night(8, h -> h), surfaceSlotNoFog());

            assertThat(score(p, 200).clearance()).isEqualTo(InversionClearance.EDGE);
        }

        @Test
        @DisplayName("condition 1 failing alone: a night measure of 40 is not clear (39 is)")
        void cloudConditionAlone() {
            InversionProfile clear = profile(H0, night(8, h -> h.mid(39)), surfaceSlotNoFog());
            InversionProfile cloudy = profile(H0, night(8, h -> h.mid(40)), surfaceSlotNoFog());

            assertThat(score(clear, 450).optionB()).isTrue();
            InversionV2Result r = score(cloudy, 450);
            assertThat(r.optionB()).isFalse();
            assertThat(r.clearance()).isEqualTo(InversionClearance.UNCONFIRMED);
        }

        @Test
        @DisplayName("condition 2 failing alone: calm in under half of the hours with a wind reading")
        void windConditionAlone() {
            // 4 of 8 calm is half, which passes; 3 of 8 does not. A wind of exactly 3 m/s is calm.
            InversionProfile half = profile(H0, night(8, (i, h) -> h.wind(i < 4 ? 3.0 : 3.1)), surfaceSlotNoFog());
            InversionProfile fewer = profile(H0, night(8, (i, h) -> h.wind(i < 3 ? 3.0 : 3.1)), surfaceSlotNoFog());

            assertThat(score(half, 450).optionB()).isTrue();
            InversionV2Result r = score(fewer, 450);
            assertThat(r.optionB()).isFalse();
            assertThat(r.clearance()).isEqualTo(InversionClearance.UNCONFIRMED);
        }

        @Test
        @DisplayName("condition 2: hours with no wind reading are left out of the count, and no reading at all fails")
        void windReadingsMissing() {
            // Two calm readings and six nulls: all of those that have a reading are calm.
            InversionProfile some = profile(H0, night(8, (i, h) -> h.wind(i < 2 ? 1.0 : null)), surfaceSlotNoFog());
            InversionProfile none = profile(H0, night(8, h -> h.wind(null)), surfaceSlotNoFog());

            assertThat(score(some, 450).optionB()).isTrue();
            assertThat(score(none, 450).optionB()).isFalse();
        }

        @Test
        @DisplayName("condition 3 failing alone: no hour with a valley gap of 1 degC or less (1.0 passes)")
        void moistureConditionAlone() {
            // t2 8.0: dew 7.0 is a gap of exactly 1.0 in one hour (passes); 6.9 everywhere is a gap of 1.1 (fails).
            InversionProfile one = profile(H0, night(8, (i, h) -> h.dew(i == 3 ? 7.0 : 5.0)), surfaceSlotNoFog());
            InversionProfile none = profile(H0, night(8, h -> h.dew(6.9)), surfaceSlotNoFog());
            InversionProfile unreadable = profile(H0, night(8, h -> h.dew(null)), surfaceSlotNoFog());

            assertThat(score(one, 450).optionB()).isTrue();
            assertThat(score(none, 450).optionB()).isFalse();
            assertThat(score(unreadable, 450).optionB()).isFalse();
        }

        @Test
        @DisplayName("an elevated clear summit with Option B true and no sea evidence stays at 8: it lifts nothing")
        void elevatedClearSummitWithoutSeaStaysAtEight() {
            // Gap 0.2 (5) + calm (3) + sea 0 (low 10, good visibility) = 8; Option B must not add a sea point.
            H dry = H.at(7).t2(8.0).dew(7.8).wind(1.0).low(10).vis(10000.0).temps(6.0, 5.0, 9.0, 11.0, 10.0);
            InversionProfile p = profile(H0, night(8, h -> h), dry);

            InversionV2Result r = score(p, 2000);

            assertThat(r.optionB()).isTrue();
            assertThat(r.optionBBound()).isFalse();
            assertThat(r.clearance()).isEqualTo(InversionClearance.CLEAR);
            assertThat(r.v2Raw()).isEqualTo(8.0);
            assertThat(r.caps()).isEqualTo("-");
        }

        @Test
        @DisplayName("it changes the score a user sees: 8 with Option B, 6 with one condition broken")
        void optionBChangesTheScore() {
            // Gap 0.2 (5) + wind 2.5 (2) + sea 2 (low 90) = 9 before clearance; wind above 2 m/s means the slot
            // has no fog evidence of its own, so only the night can confirm the layer.
            H breezy = H.at(7).t2(8.0).dew(7.8).wind(2.5).low(90).vis(10000.0).temps(10.5, 9.0, 8.0, 7.0, 6.0);
            InversionProfile on = profile(H0, night(8, h -> h), breezy);
            InversionProfile off = profile(H0, night(8, h -> h.dew(5.0)), breezy);

            InversionV2Result with = score(on, 450);
            InversionV2Result without = score(off, 450);

            assertThat(with.optionB()).isTrue();
            assertThat(with.v2Raw()).isEqualTo(8.0);
            assertThat(with.caps()).isEqualTo("night-fog(8)");
            assertThat(without.optionB()).isFalse();
            assertThat(without.v2Raw()).isEqualTo(6.0);
            assertThat(without.caps()).isEqualTo("unconfirmed(6)");
        }

        @Test
        @DisplayName("it never lifts the in-cloud cap")
        void optionBKeepsInCloud() {
            AtmosphericData inCloud = data(profile(H0, night(8, h -> h), surfaceSlotNoFog()), 90, 2000);

            InversionV2Result r = InversionScoreCalculatorV2.calculate(inCloud, 450);

            assertThat(r.caps()).isEqualTo("night-fog(8),in-cloud(6)");
            assertThat(r.v2Raw()).isLessThanOrEqualTo(6.0);
        }

        @Test
        @DisplayName("with fog evidence already present the edge is the ordinary one, not night-fog")
        void existingFogEvidenceIsNotNightFog() {
            InversionProfile p = profile(H0, night(8, h -> h), surfaceSlotWithFog());

            InversionV2Result r = score(p, 450);

            assertThat(r.optionB()).isTrue();
            assertThat(r.optionBBound()).isFalse();
            assertThat(r.caps()).isEqualTo("edge(8)");
        }

        @Test
        @DisplayName("it does not change the sea term: the same slot scores the same with and without it")
        void seaTermUnchanged() {
            InversionProfile with = profile(H0, night(8, h -> h), surfaceSlotNoFog());
            InversionProfile without = profile(H0, night(8, h -> h.mid(100)), surfaceSlotNoFog());

            // Both cap at 6 or 8 by clearance; the pre-cap terms (moisture + wind + sea) are identical.
            assertThat(score(with, 450).v2Raw()).isEqualTo(score(without, 450).v2Raw());
        }
    }
}
