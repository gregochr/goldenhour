package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.MistTrend.MistSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins {@link ValleyMistCalculator} on the M0 fixtures (valley-mist-plan section 3, measured on the
 * production host 2026-10-21) and on each term's zero and full-marks case.
 *
 * <p>The three LIKELY fixtures and the two NONE ones are real morning shapes; the band thresholds
 * are priors until M3, so these tests are what stop a threshold tweak silently moving a measured
 * morning from one band to another.
 */
class ValleyMistCalculatorTest {

    private static final double EPS = 0.001;

    // ------------------------------------------------------------------ fixtures

    /** One slot with every field. */
    private static MistSlot slot(int offset, int vis, double temp, double gap, Integer cloud, Double wind) {
        return new MistSlot(offset, vis, temp - gap, temp, cloud, wind);
    }

    /**
     * River Brathay, 17 Oct 2026 SUNRISE: a SYNTHETIC variant of the real morning, with invented
     * temperatures. It is laid out with offsets -3..+2 as 03:00..08:00 UTC and event hour 06:00, but
     * production reads 07:00 for this sunrise (sunrise 06:41.8 UTC, and TimeSlotUtils.findBestIndex
     * takes the earliest slot at or after it). The real-data reading, built from the owner's Open-Meteo
     * file through the production parser, is pinned in ValleyMistBrathayFixtureTest (7.03 LIKELY at
     * 07:00, 5.03 POSSIBLE at 06:00).
     * Measured (plan section 3): gaps, wind, humidity, visibility, 47 percent low cloud at 06:00.
     * INVENTED to fit "temperature falling toward the dew point": the temperatures themselves, and
     * total cloud at 06:00 taken as the 47 percent low cloud. Without the cooling term this scores
     * 5.03 (POSSIBLE), so LIKELY here rests on that fall.
     */
    private static AtmosphericData brathay() {
        MistTrend trend = new MistTrend(List.of(
                slot(-3, 41000, 5.0, 2.9, 0, 2.6),
                slot(-2, 40000, 4.7, 2.9, 0, 1.8),
                slot(-1, 39000, 4.4, 2.8, 0, 2.2),
                slot(0, 37000, 4.1, 2.1, 47, 2.4),
                slot(1, 37000, 4.0, 2.0, 60, 2.3),
                slot(2, 36000, 5.0, 2.4, 70, 2.5)));
        return TestAtmosphericData.builder().targetType(TargetType.SUNRISE)
                .visibility(37000).mistTrend(trend).build();
    }

    /** Ingleton Waterfalls: visibility 8,840 m, gap 0.1 C, wind 1.3 m/s, low cloud 0. */
    private static AtmosphericData ingleton() {
        MistTrend trend = new MistTrend(List.of(
                slot(-3, 12000, 3.0, 0.1, 0, 1.3),
                slot(-2, 11000, 3.0, 0.1, 0, 1.3),
                slot(-1, 10000, 3.0, 0.1, 0, 1.3),
                slot(0, 8840, 3.0, 0.1, 0, 1.3),
                slot(1, 9000, 3.2, 0.2, 0, 1.4)));
        return TestAtmosphericData.builder().targetType(TargetType.SUNRISE)
                .visibility(8840).mistTrend(trend).build();
    }

    /** Buttermere: gap 0.5 C, wind 0.1 m/s, low cloud 37 percent at the event hour. */
    private static AtmosphericData buttermere() {
        MistTrend trend = new MistTrend(List.of(
                slot(-3, 18000, 5.2, 1.2, 0, 0.2),
                slot(-2, 17000, 5.0, 1.0, 0, 0.1),
                slot(-1, 16000, 4.8, 0.8, 10, 0.1),
                slot(0, 15000, 4.7, 0.5, 37, 0.1),
                slot(1, 15000, 4.8, 0.6, 40, 0.2)));
        return TestAtmosphericData.builder().targetType(TargetType.SUNRISE)
                .visibility(15000).mistTrend(trend).build();
    }

    /** A sunrise trend over offsets -3..+2 with constant cloud and wind, gap and temperature ramped. */
    private static MistTrend sunriseTrend(int cloud, double wind, double gapFirst, double gapLast,
            double tempFirst, double tempLast) {
        List<MistSlot> slots = new ArrayList<>();
        for (int i = 0; i <= 3; i++) {
            double f = i / 3.0;
            slots.add(slot(i - 3, 20000, tempFirst + (tempLast - tempFirst) * f,
                    gapFirst + (gapLast - gapFirst) * f, cloud, wind));
        }
        slots.add(slot(1, 20000, tempLast, gapLast, cloud, wind));
        slots.add(slot(2, 20000, tempLast, gapLast, cloud, wind));
        return new MistTrend(slots);
    }

    private static AtmosphericData sunrise(MistTrend trend, int eventVisibility, Double precedingMm) {
        return TestAtmosphericData.builder().targetType(TargetType.SUNRISE)
                .visibility(eventVisibility).precedingPrecipitationMm(precedingMm)
                .mistTrend(trend).build();
    }

    /** Clear, calm, saturated and FLAT: 2.0 + 1.5 + 4.0 + 0 cooling = 7.5 before any other term. */
    private static AtmosphericData ideal() {
        return sunrise(sunriseTrend(0, 1.0, 0.5, 0.5, 5.0, 5.0), 20000, null);
    }

    // ------------------------------------------------------------------ M0 fixtures

    static Stream<Arguments> m0Fixtures() {
        return Stream.of(
                Arguments.of("Brathay 17 Oct 2026 SUNRISE (the 2 km cell never saturates)",
                        brathay(), MistPotential.LIKELY),
                Arguments.of("Ingleton Waterfalls (confirmation on)", ingleton(), MistPotential.LIKELY),
                Arguments.of("Buttermere", buttermere(), MistPotential.LIKELY),
                Arguments.of("windy night, gap 0.5",
                        sunrise(sunriseTrend(0, 5.0, 0.5, 0.5, 5.0, 4.5), 20000, null),
                        MistPotential.NONE),
                Arguments.of("overcast night, gap 0.5",
                        sunrise(sunriseTrend(90, 1.0, 0.5, 0.5, 5.0, 4.5), 20000, null),
                        MistPotential.NONE),
                Arguments.of("200 m visibility, no improving trend",
                        sunrise(fogTrend(200, 150, 250), 200, null), MistPotential.FOGBOUND));
    }

    private static MistTrend fogTrend(int eventVis, int plus1, int plus2) {
        return new MistTrend(List.of(
                slot(-3, 800, 4.0, 0.2, 0, 0.5),
                slot(-2, 500, 4.0, 0.2, 0, 0.5),
                slot(-1, 300, 4.0, 0.1, 0, 0.5),
                slot(0, eventVis, 4.0, 0.1, 0, 0.5),
                slot(1, plus1, 4.2, 0.1, 0, 0.5),
                slot(2, plus2, 4.5, 0.2, 0, 0.5)));
    }

    @ParameterizedTest(name = "{0} reads {2}")
    @MethodSource("m0Fixtures")
    @DisplayName("the M0 fixtures land in the bands they were measured to be in")
    void m0Fixtures_landInTheirBands(String name, AtmosphericData data, MistPotential expected) {
        Double score = ValleyMistCalculator.calculate(data);

        assertThat(score).isNotNull();
        assertThat(ValleyMistCalculator.classify(data, score)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Brathay is carried to LIKELY by the set-up terms alone: no visibility, no wet ground")
    void brathay_likelyOnSetUpAlone() {
        Double score = ValleyMistCalculator.calculate(brathay());

        // clear 1.93 (mean cloud 11.75 percent) + calm 1.5 + moisture 1.6 (min gap 2.1 -> 2/5 of 4)
        // + cooling 1.5 (gap 2.9 -> 2.1 while the air cools) = 6.53; confirmation and wet ground 0.
        assertThat(score).isCloseTo(6.53, within(0.01));
        assertThat(score).isGreaterThanOrEqualTo(ValleyMistCalculator.LIKELY_MIN_SCORE);
    }

    @Test
    @DisplayName("Ingleton scores the model-saturated subclass higher than the same set-up with a clear view")
    void ingleton_confirmationTermIsOn() {
        AtmosphericData confirmed = ingleton();
        MistTrend sameTrend = confirmed.mistTrend();
        AtmosphericData unconfirmed = TestAtmosphericData.builder().targetType(TargetType.SUNRISE)
                .visibility(25000).mistTrend(sameTrend).build();

        double with = ValleyMistCalculator.calculate(confirmed);
        double without = ValleyMistCalculator.calculate(unconfirmed);

        assertThat(with - without).isCloseTo(ValleyMistCalculator.CONFIRM_MAX / 2, within(EPS));
    }

    @Test
    @DisplayName("a windy night is NONE only because of the veto: the same night calm is LIKELY")
    void windyNight_isVetoedNotMerelyLowScoring() {
        AtmosphericData windy = sunrise(sunriseTrend(0, 5.0, 0.5, 0.5, 5.0, 4.5), 20000, null);
        AtmosphericData calm = sunrise(sunriseTrend(0, 1.0, 0.5, 0.5, 5.0, 4.5), 20000, null);

        assertThat(ValleyMistCalculator.calculate(windy))
                .isLessThanOrEqualTo(ValleyMistCalculator.VETOED_CEILING);
        assertThat(ValleyMistCalculator.classify(calm, ValleyMistCalculator.calculate(calm)))
                .isEqualTo(MistPotential.LIKELY);
    }

    @Test
    @DisplayName("an overcast night is NONE only because of the veto")
    void overcastNight_isVetoedNotMerelyLowScoring() {
        AtmosphericData overcast = sunrise(sunriseTrend(90, 1.0, 0.5, 0.5, 5.0, 4.5), 20000, null);

        assertThat(ValleyMistCalculator.calculate(overcast))
                .isLessThanOrEqualTo(ValleyMistCalculator.VETOED_CEILING);
    }

    // ------------------------------------------------------------------ the terms, one at a time

    @Nested
    @DisplayName("each term's full and zero case")
    class Terms {

        @Test
        @DisplayName("ideal flat set-up is exactly clear 2.0 + calm 1.5 + moisture 4.0")
        void ideal_isTheSumOfThreeFullTerms() {
            assertThat(ValleyMistCalculator.calculate(ideal())).isCloseTo(7.5, within(EPS));
        }

        @Test
        @DisplayName("clear term: zero at 60 percent mean cloud, linear between 10 and 60, not yet vetoed")
        void clearTerm() {
            double full = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(10, 1.0, 0.5, 0.5, 5.0, 5.0), 20000, null));
            double half = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(35, 1.0, 0.5, 0.5, 5.0, 5.0), 20000, null));
            double zero = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(60, 1.0, 0.5, 0.5, 5.0, 5.0), 20000, null));

            assertThat(full).isCloseTo(7.5, within(EPS));
            assertThat(half).isCloseTo(6.5, within(EPS));
            assertThat(zero).isCloseTo(5.5, within(EPS));
        }

        @Test
        @DisplayName("overcast veto fires at exactly 70 percent mean cloud and not at 69")
        void overcastVetoBoundary() {
            double at69 = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(69, 1.0, 0.5, 0.5, 5.0, 5.0), 20000, null));
            double at70 = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(70, 1.0, 0.5, 0.5, 5.0, 5.0), 20000, null));

            assertThat(at69).isCloseTo(5.5, within(EPS));
            assertThat(at70).isCloseTo(ValleyMistCalculator.VETOED_CEILING, within(EPS));
        }

        @Test
        @DisplayName("calm term: full at 2 m/s, zero at 4 m/s, and 4.0 itself is not yet vetoed")
        void calmTerm() {
            double full = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(0, 2.0, 0.5, 0.5, 5.0, 5.0), 20000, null));
            double half = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(0, 3.0, 0.5, 0.5, 5.0, 5.0), 20000, null));
            double zero = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(0, 4.0, 0.5, 0.5, 5.0, 5.0), 20000, null));
            double vetoed = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(0, 4.1, 0.5, 0.5, 5.0, 5.0), 20000, null));

            assertThat(full).isCloseTo(7.5, within(EPS));
            assertThat(half).isCloseTo(6.75, within(EPS));
            assertThat(zero).isCloseTo(6.0, within(EPS));
            assertThat(vetoed).isCloseTo(ValleyMistCalculator.VETOED_CEILING, within(EPS));
        }

        @Test
        @DisplayName("calm term reads the calmest night-side hour, not the event hour or the mean")
        void calmTermUsesTheMinimum() {
            List<MistSlot> slots = new ArrayList<>(sunriseTrend(0, 6.0, 0.5, 0.5, 5.0, 5.0).slots());
            slots.set(1, slot(-2, 20000, 5.0, 0.5, 0, 1.0));

            double score = ValleyMistCalculator.calculate(sunrise(new MistTrend(slots), 20000, null));

            assertThat(score).isCloseTo(7.5, within(EPS));
        }

        @Test
        @DisplayName("moisture term follows the shared gap scale on the night-side MINIMUM gap")
        void moistureTerm() {
            // gap 0.5 -> 5/5, 1.0 -> 4/5, 2.0 -> 3/5, 3.0 -> 2/5, 5.0 -> 1/5, above 5 -> 0
            double[] gaps = {0.5, 1.0, 2.0, 3.0, 5.0, 5.1};
            double[] expectedMoisture = {4.0, 3.2, 2.4, 1.6, 0.8, 0.0};
            for (int i = 0; i < gaps.length; i++) {
                double score = ValleyMistCalculator.calculate(
                        sunrise(sunriseTrend(0, 1.0, gaps[i], gaps[i], 5.0, 5.0), 20000, null));
                assertThat(score).as("gap %s", gaps[i]).isCloseTo(3.5 + expectedMoisture[i], within(EPS));
            }
        }

        @Test
        @DisplayName("a 2 to 3 C gap with high humidity still scores: the gap is a term, never a gate")
        void gapIsNotAGate() {
            double score = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(0, 1.0, 2.9, 2.9, 5.0, 5.0), 20000, null));

            assertThat(score).isGreaterThan(3.5);
        }

        @Test
        @DisplayName("moisture takes the minimum gap anywhere on the night side, not the event-hour gap")
        void moistureUsesTheMinimumGap() {
            List<MistSlot> slots = new ArrayList<>(sunriseTrend(0, 1.0, 6.0, 6.0, 5.0, 5.0).slots());
            slots.set(1, slot(-2, 20000, 5.0, 0.4, 0, 1.0));

            double score = ValleyMistCalculator.calculate(sunrise(new MistTrend(slots), 20000, null));

            assertThat(score).isCloseTo(3.5 + 4.0, within(EPS));
        }

        @Test
        @DisplayName("cooling term: full at 0.5 C of narrowing, half at 0.25, zero when flat")
        void coolingTerm() {
            double full = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(0, 1.0, 1.0, 0.5, 5.0, 4.5), 20000, null));
            double half = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(0, 1.0, 0.75, 0.5, 5.0, 4.75), 20000, null));
            double flat = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(0, 1.0, 0.5, 0.5, 5.0, 4.5), 20000, null));

            assertThat(full).isCloseTo(9.0, within(EPS));
            assertThat(half).isCloseTo(8.25, within(EPS));
            assertThat(flat).isCloseTo(7.5, within(EPS));
        }

        @Test
        @DisplayName("cooling term is zero when the gap widens, even while the air cools")
        void coolingZeroWhenGapWidens() {
            double score = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(0, 1.0, 0.5, 1.0, 5.0, 4.5), 20000, null));

            // moisture still reads the 0.5 minimum; cooling contributes nothing
            assertThat(score).isCloseTo(7.5, within(EPS));
        }

        @Test
        @DisplayName("cooling term is zero when the air is warming, whatever the gap does")
        void coolingZeroWhenTemperatureRises() {
            double score = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(0, 1.0, 1.0, 0.5, 4.5, 5.0), 20000, null));

            assertThat(score).isCloseTo(7.5, within(EPS));
        }

        @Test
        @DisplayName("a single surviving night-side slot (a truncated array) is not scored")
        void singleNightSideSlotIsNotScored() {
            MistTrend onlyEvent = new MistTrend(List.of(slot(0, 20000, 5.0, 0.5, 0, 1.0)));

            assertThat(ValleyMistCalculator.calculate(sunrise(onlyEvent, 20000, null))).isNull();
        }

        @Test
        @DisplayName("cooling term still counts when the air temperature is exactly flat and the gap narrows")
        void coolingCountsOnFlatTemperature() {
            // dew point rising under a flat temperature: the guard is 'warming', not 'not falling'
            double score = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(0, 1.0, 1.0, 0.5, 5.0, 5.0), 20000, null));

            assertThat(score).isCloseTo(9.0, within(EPS));
        }

        @Test
        @DisplayName("cooling does not count when saturation is out of reach: a DRY night cooling stays NONE")
        void dryCoolingNightIsNone() {
            AtmosphericData dryCooling = sunrise(sunriseTrend(0, 1.0, 8.0, 7.5, 5.0, 4.5), 20000, null);

            Double score = ValleyMistCalculator.calculate(dryCooling);

            assertThat(score).isCloseTo(3.5, within(EPS));
            assertThat(ValleyMistCalculator.classify(dryCooling, score)).isEqualTo(MistPotential.NONE);
        }

        @Test
        @DisplayName("cooling counts when the night's minimum gap reaches exactly 5 C, the edge of the moisture scale")
        void coolingCountsAtTheEdgeOfTheMoistureScale() {
            double score = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(0, 1.0, 5.5, 5.0, 5.5, 5.0), 20000, null));

            // clear 2.0 + calm 1.5 + moisture 0.8 (minimum gap 5.0 -> 1/5) + cooling 1.5
            assertThat(score).isCloseTo(5.8, within(EPS));
        }

        @Test
        @DisplayName("the gap is rounded to 0.1 C before the shared scale: 4.4 - 3.9 is a saturated 0.5")
        void gapIsRoundedToTheSourcePrecision() {
            // 4.4 - 3.9 = 0.5000000000000004 in floating point, which would drop to the 4/5 step
            MistTrend trend = new MistTrend(List.of(
                    new MistSlot(-1, 20000, 3.9, 4.4, 0, 1.0),
                    new MistSlot(0, 20000, 3.9, 4.4, 0, 1.0)));

            double score = ValleyMistCalculator.calculate(sunrise(trend, 20000, null));

            assertThat(score).isCloseTo(2.0 + 1.5 + ValleyMistCalculator.MOISTURE_MAX, within(EPS));
        }

        @Test
        @DisplayName("slots handed over out of time order are still read first to last")
        void slotOrderDoesNotMatter() {
            List<MistSlot> shuffled = new ArrayList<>(
                    sunriseTrend(0, 1.0, 1.0, 0.5, 5.0, 4.5).slots());
            java.util.Collections.reverse(shuffled);

            double score = ValleyMistCalculator.calculate(sunrise(new MistTrend(shuffled), 20000, null));

            assertThat(score).isCloseTo(9.0, within(EPS));
        }

        @Test
        @DisplayName("wet-ground term: zero when dry or unknown, half marks at 1 mm, full at 2 mm")
        void wetGroundTerm() {
            AtmosphericData base = ideal();
            double dry = ValleyMistCalculator.calculate(
                    sunrise(base.mistTrend(), 20000, 0.0));
            double half = ValleyMistCalculator.calculate(
                    sunrise(base.mistTrend(), 20000, 1.0));
            double full = ValleyMistCalculator.calculate(
                    sunrise(base.mistTrend(), 20000, 2.0));
            double over = ValleyMistCalculator.calculate(
                    sunrise(base.mistTrend(), 20000, 9.0));

            assertThat(dry).isCloseTo(7.5, within(EPS));
            assertThat(half).isCloseTo(7.75, within(EPS));
            assertThat(full).isCloseTo(8.0, within(EPS));
            assertThat(over).isCloseTo(8.0, within(EPS));
        }

        @Test
        @DisplayName("confirmation term: zero at 10 km, half under 10 km, full under 5 km")
        void confirmationTerm() {
            MistTrend t = ideal().mistTrend();
            assertThat(ValleyMistCalculator.calculate(sunrise(t, 10_000, null))).isCloseTo(7.5, within(EPS));
            assertThat(ValleyMistCalculator.calculate(sunrise(t, 9_999, null))).isCloseTo(7.75, within(EPS));
            assertThat(ValleyMistCalculator.calculate(sunrise(t, 5_000, null))).isCloseTo(7.75, within(EPS));
            assertThat(ValleyMistCalculator.calculate(sunrise(t, 4_999, null))).isCloseTo(8.0, within(EPS));
        }

        @Test
        @DisplayName("a veto CLAMPS the score, it does not replace it: overcast and dry stays at 1.5")
        void vetoClampsRatherThanReplaces() {
            double score = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(90, 1.0, 8.0, 8.0, 5.0, 5.0), 20000, null));

            // clear 0 + calm 1.5 + moisture 0 + cooling 0
            assertThat(score).isCloseTo(1.5, within(EPS));
        }

        @Test
        @DisplayName("every term at full marks sums to exactly 10")
        void scoreIsTenAtFullMarks() {
            double score = ValleyMistCalculator.calculate(
                    sunrise(sunriseTrend(0, 0.0, 1.0, 0.0, 5.0, 4.0), 100, 9.0));

            assertThat(score).isCloseTo(10.0, within(EPS));
        }
    }

    // ------------------------------------------------------------------ night-side selection

    @Nested
    @DisplayName("night-side slots")
    class NightSide {

        @Test
        @DisplayName("a SUNRISE reads T-3h to the event and ignores later slots")
        void sunriseIgnoresAfterTheEvent() {
            List<MistSlot> slots = new ArrayList<>(ideal().mistTrend().slots());
            // after the event: overcast and windy, which would veto if they counted
            slots.set(4, slot(1, 20000, 5.0, 0.5, 100, 9.0));
            slots.set(5, slot(2, 20000, 5.0, 0.5, 100, 9.0));

            double score = ValleyMistCalculator.calculate(sunrise(new MistTrend(slots), 20000, null));

            assertThat(score).isCloseTo(7.5, within(EPS));
        }

        @Test
        @DisplayName("a SUNSET reads the event to T+2h and ignores earlier slots")
        void sunsetIgnoresBeforeTheEvent() {
            List<MistSlot> slots = new ArrayList<>();
            slots.add(slot(-3, 20000, 9.0, 4.0, 100, 9.0));
            slots.add(slot(-2, 20000, 9.0, 4.0, 100, 9.0));
            slots.add(slot(-1, 20000, 9.0, 4.0, 100, 9.0));
            slots.add(slot(0, 20000, 5.0, 1.0, 0, 1.0));
            slots.add(slot(1, 20000, 4.6, 0.7, 0, 1.0));
            slots.add(slot(2, 20000, 4.2, 0.5, 0, 1.0));
            AtmosphericData sunset = TestAtmosphericData.builder().targetType(TargetType.SUNSET)
                    .visibility(20000).mistTrend(new MistTrend(slots)).build();

            Double score = ValleyMistCalculator.calculate(sunset);

            // clear 2.0 + calm 1.5 + moisture 4.0 (min gap 0.5) + cooling 1.5 (gap 1.0 -> 0.5)
            assertThat(score).isCloseTo(9.0, within(EPS));
            assertThat(ValleyMistCalculator.classify(sunset, score)).isEqualTo(MistPotential.LIKELY);
        }

        @Test
        @DisplayName("a HOURLY target is not scored")
        void hourlyIsNotScored() {
            AtmosphericData hourly = TestAtmosphericData.builder().targetType(TargetType.HOURLY)
                    .mistTrend(ideal().mistTrend()).build();

            assertThat(ValleyMistCalculator.calculate(hourly)).isNull();
        }

        @Test
        @DisplayName("a SUNSET trend with no slot at or after the event is not scored")
        void sunsetWithNothingAfterTheEventIsNotScored() {
            MistTrend before = new MistTrend(List.of(slot(-1, 20000, 5.0, 0.5, 0, 1.0)));
            AtmosphericData sunset = TestAtmosphericData.builder().targetType(TargetType.SUNSET)
                    .mistTrend(before).build();

            assertThat(ValleyMistCalculator.calculate(sunset)).isNull();
        }
    }

    // ------------------------------------------------------------------ missing inputs

    @Nested
    @DisplayName("missing inputs return null, never a guess")
    class MissingInputs {

        @Test
        @DisplayName("null data, no trend")
        void nullDataAndNoTrend() {
            assertThat(ValleyMistCalculator.calculate(null)).isNull();
            assertThat(ValleyMistCalculator.calculate(
                    TestAtmosphericData.builder().targetType(TargetType.SUNRISE).build())).isNull();
        }

        @Test
        @DisplayName("data with no weather block")
        void noWeather() {
            AtmosphericData withWeather = ideal();
            AtmosphericData noWeather = new AtmosphericData(withWeather.locationName(),
                    withWeather.solarEventTime(), withWeather.targetType(), withWeather.cloud(), null,
                    withWeather.aerosol(), withWeather.comfort(), null, null, null,
                    withWeather.mistTrend());

            assertThat(ValleyMistCalculator.calculate(noWeather)).isNull();
        }

        @Test
        @DisplayName("a trend whose slots carry no total cloud series")
        void noCloudSeries() {
            MistTrend trend = new MistTrend(List.of(
                    new MistSlot(-1, 20000, 4.5, 5.0, null, 1.0),
                    new MistSlot(0, 20000, 4.5, 5.0, null, 1.0)));

            assertThat(ValleyMistCalculator.calculate(sunrise(trend, 20000, null))).isNull();
        }

        @Test
        @DisplayName("a trend whose slots carry no wind series (the four-argument slot)")
        void noWindSeries() {
            MistTrend trend = new MistTrend(List.of(
                    new MistSlot(-1, 20000, 4.5, 5.0),
                    new MistSlot(0, 20000, 4.5, 5.0)));

            assertThat(ValleyMistCalculator.calculate(sunrise(trend, 20000, null))).isNull();
        }

        @Test
        @DisplayName("cloud present but no wind series anywhere: not scored")
        void cloudWithoutWindIsNotScored() {
            MistTrend trend = new MistTrend(List.of(
                    new MistSlot(-1, 20000, 4.5, 5.0, 0, null),
                    new MistSlot(0, 20000, 4.5, 5.0, 0, null)));

            assertThat(ValleyMistCalculator.calculate(sunrise(trend, 20000, null))).isNull();
        }

        @Test
        @DisplayName("a partial series still scores from the slots that have it")
        void partialSeriesStillScores() {
            List<MistSlot> slots = new ArrayList<>(ideal().mistTrend().slots());
            slots.set(0, new MistSlot(-3, 20000, 4.5, 5.0, null, null));

            assertThat(ValleyMistCalculator.calculate(sunrise(new MistTrend(slots), 20000, null)))
                    .isCloseTo(7.5, within(EPS));
        }
    }

    // ------------------------------------------------------------------ bands

    @Nested
    @DisplayName("bands")
    class Bands {

        @Test
        @DisplayName("a null score has no band")
        void nullScoreHasNoBand() {
            assertThat(ValleyMistCalculator.classify(ideal(), null)).isNull();
        }

        @Test
        @DisplayName("NONE below 4, POSSIBLE from 4, LIKELY from 6")
        void thresholdBoundaries() {
            AtmosphericData data = ideal();

            assertThat(ValleyMistCalculator.classify(data, 3.99)).isEqualTo(MistPotential.NONE);
            assertThat(ValleyMistCalculator.classify(data, 4.0)).isEqualTo(MistPotential.POSSIBLE);
            assertThat(ValleyMistCalculator.classify(data, 5.99)).isEqualTo(MistPotential.POSSIBLE);
            assertThat(ValleyMistCalculator.classify(data, 6.0)).isEqualTo(MistPotential.LIKELY);
        }

        @Test
        @DisplayName("every band is reachable from a real set-up")
        void everyBandReachable() {
            // clear + calm + gap 4 C, flat: 2.0 + 1.5 + 0.8 = 4.3 -> POSSIBLE
            AtmosphericData possible = sunrise(sunriseTrend(0, 1.0, 4.0, 4.0, 5.0, 5.0), 20000, null);
            // clear + calm but DRY (gap 8 C), flat: 3.5 -> NONE
            AtmosphericData dry = sunrise(sunriseTrend(0, 1.0, 8.0, 8.0, 5.0, 5.0), 20000, null);

            assertThat(ValleyMistCalculator.classify(possible, ValleyMistCalculator.calculate(possible)))
                    .isEqualTo(MistPotential.POSSIBLE);
            assertThat(ValleyMistCalculator.classify(dry, ValleyMistCalculator.calculate(dry)))
                    .isEqualTo(MistPotential.NONE);
            assertThat(ValleyMistCalculator.classify(ideal(), ValleyMistCalculator.calculate(ideal())))
                    .isEqualTo(MistPotential.LIKELY);
        }

        @Test
        @DisplayName("FOGBOUND needs event visibility under 300 m; 300 itself is not fogbound")
        void fogboundBoundary() {
            AtmosphericData at299 = sunrise(fogTrend(299, 150, 250), 299, null);
            AtmosphericData at300 = sunrise(fogTrend(300, 150, 250), 300, null);

            assertThat(ValleyMistCalculator.classify(at299, 7.0)).isEqualTo(MistPotential.FOGBOUND);
            assertThat(ValleyMistCalculator.classify(at300, 7.0)).isEqualTo(MistPotential.LIKELY);
        }

        @Test
        @DisplayName("an improving trend (a later slot back above 300 m) is not FOGBOUND")
        void improvingTrendIsNotFogbound() {
            AtmosphericData clearing = sunrise(fogTrend(200, 280, 800), 200, null);

            assertThat(ValleyMistCalculator.classify(clearing, 7.0)).isEqualTo(MistPotential.LIKELY);
        }

        @Test
        @DisplayName("slots BEFORE the event clearing back do not count as an improving trend")
        void earlierSlotsDoNotCountAsImproving() {
            // fogTrend's earlier slots are 800 and 500 m: above 300, but before the event
            AtmosphericData fog = sunrise(fogTrend(200, 150, 250), 200, null);

            assertThat(ValleyMistCalculator.classify(fog, 7.0)).isEqualTo(MistPotential.FOGBOUND);
        }

        @Test
        @DisplayName("FOGBOUND is decided by the white frame, not by a low score")
        void fogboundIgnoresTheScore() {
            AtmosphericData fog = sunrise(fogTrend(200, 150, 250), 200, null);

            assertThat(ValleyMistCalculator.classify(fog, 0.0)).isEqualTo(MistPotential.FOGBOUND);
        }
    }
}
