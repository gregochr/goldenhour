package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.PenrithValleyMistFixture;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.MistPotential;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins valley mist on the owner's REAL Open-Meteo forecast for the morning of 29 Oct 2026, read
 * through the production {@link OpenMeteoResponseParser}: Stainton (mist seen) and Keswick (clear).
 *
 * <p><b>These are characterisation tests of CURRENT behaviour on an observed morning, not tuning
 * targets.</b> They record what the calculator does today so a later change cannot move it
 * silently; a failure means "look at this deliberately", not "the number is wrong".
 *
 * <p>Observations: shallow valley mist from a car on the A66 west of Penrith (by Ullswater
 * Community College, Redhills, just west of Stainton) at 09:02-09:08 GMT, with frost or heavy dew,
 * puddles, broken cloud overhead, mist at tree height and sky visible through it. At 09:22 GMT at
 * Keswick, about 20 km west, there was no mist. Sunrise was 07:05 UTC at both points
 * ({@code TimeSlotUtils.findBestIndex} takes the earliest slot at or after it), so production reads
 * the 08:00 slot. Night-side slots are 05:00..08:00 UTC.
 */
class ValleyMistPenrithKeswickFixtureTest {

    private static final double TOLERANCE = 0.01;

    @Test
    @DisplayName("Stainton reads the 08:00 slot and scores 5.75, POSSIBLE (mist WAS seen: a near-miss)")
    void stainton_readsTheEightOClockSlot_andIsPossible() {
        AtmosphericData data = PenrithValleyMistFixture.sunrise(PenrithValleyMistFixture.STAINTON);

        // The parser chose the slot itself: 20 W/m2 shortwave exists only at 08:00 (07:00 is 0.0
        // and 09:00 is 68.0), and 8,680 m visibility is the 08:00 reading.
        assertThat(data.weather().shortwaveRadiationWm2().doubleValue()).isEqualTo(20.0);
        assertThat(data.weather().visibilityMetres()).isEqualTo(8680);
        assertThat(data.mistTrend().slots()).hasSize(6);

        Double score = ValleyMistCalculator.calculate(data);
        ValleyMistCalculator.NightFacts facts = ValleyMistCalculator.nightFacts(data);

        // Sub-scores (night-side slots 05:00..08:00 UTC):
        //   clear        0     mean total cloud 62.75 percent: (100 + 66 + 74 + 11) / 4, past the
        //                      60 percent zero mark (under the 70 percent overcast veto)
        //   calm         1.50  minimum 10 m wind 1.8 m/s (06:00), under the 2 m/s full mark
        //   moisture     4.0   minimum temperature minus dew point 0.1 C at 05:00 (saturated)
        //   cooling      0     the air WARMS 7.3 -> 8.6 C across the window
        //   wet ground   0     0.0 mm in the 6 h before 08:00 (the rain fell 01:00-12:00 the
        //                      previous day)
        //   confirmation 0.25  visibility 8,680 m at 08:00, between the 5 km and 10 km marks
        //   total        5.75  POSSIBLE (4.0 to under 6.0)
        //
        // MIST WAS OBSERVED HERE at 09:02-09:08, so POSSIBLE is a KNOWN NEAR-MISS, one band short
        // of the truth: the hot topic fires on LIKELY only. This test pins today's reading; it does
        // not say POSSIBLE is right. A later mist change that lifts this to LIKELY must still keep
        // the Brathay fixture LIKELY (ValleyMistBrathayFixtureTest).
        assertThat(facts.meanCloudPercent()).isCloseTo(62.75, within(TOLERANCE));
        assertThat(facts.minWindMs()).isCloseTo(1.8, within(TOLERANCE));
        assertThat(facts.minGapCelsius()).isCloseTo(0.1, within(TOLERANCE));
        assertThat(score).isCloseTo(5.75, within(TOLERANCE));
        assertThat(ValleyMistCalculator.classify(data, score)).isEqualTo(MistPotential.POSSIBLE);
    }

    @Test
    @DisplayName("Keswick is the overcast veto: capped at 3.9, NONE (no mist was seen)")
    void keswick_isTheOvercastVeto_andIsNone() {
        AtmosphericData data = PenrithValleyMistFixture.sunrise(PenrithValleyMistFixture.KESWICK);

        // 17,440 m visibility is the Keswick 08:00 reading (07:00 is 16,700, 09:00 is 12,080).
        assertThat(data.weather().visibilityMetres()).isEqualTo(17440);

        Double score = ValleyMistCalculator.calculate(data);
        ValleyMistCalculator.NightFacts facts = ValleyMistCalculator.nightFacts(data);

        // Sub-scores (night-side slots 05:00..08:00 UTC):
        //   clear        0     mean total cloud 92 percent: (73 + 98 + 97 + 100) / 4
        //   calm         1.50  minimum 10 m wind 0.6 m/s
        //   moisture     2.40  minimum temperature minus dew point 1.1 C
        //   cooling      0     the air does not cool
        //   wet ground   ~0.03 0.1 mm at 02:00, inside the 6 h before 08:00
        //   confirmation 0     visibility 17,440 m
        //   total        5.43  before the cap; the overcast veto caps it at 3.9, NONE
        // No mist was observed at Keswick at 09:22.
        assertThat(facts.meanCloudPercent()).isCloseTo(92.0, within(TOLERANCE));
        assertThat(facts.meanCloudPercent()).isGreaterThanOrEqualTo(
                (double) ValleyMistCalculator.OVERCAST_VETO_PERCENT);
        assertThat(facts.minWindMs()).isCloseTo(0.6, within(TOLERANCE));
        assertThat(facts.minGapCelsius()).isCloseTo(1.1, within(TOLERANCE));
        assertThat(score).isCloseTo(3.90, within(TOLERANCE));
        assertThat(ValleyMistCalculator.classify(data, score)).isEqualTo(MistPotential.NONE);
    }

    @Test
    @DisplayName("the calculator discriminates: Stainton scores strictly above Keswick, in a higher band")
    void stainton_outranksKeswick() {
        AtmosphericData stainton = PenrithValleyMistFixture.sunrise(PenrithValleyMistFixture.STAINTON);
        AtmosphericData keswick = PenrithValleyMistFixture.sunrise(PenrithValleyMistFixture.KESWICK);
        Double stScore = ValleyMistCalculator.calculate(stainton);
        Double keScore = ValleyMistCalculator.calculate(keswick);

        assertThat(stScore).isGreaterThan(keScore);
        assertThat(rank(ValleyMistCalculator.classify(stainton, stScore)))
                .isGreaterThan(rank(ValleyMistCalculator.classify(keswick, keScore)));
    }

    /** Band rank by likelihood, independent of the enum's declaration order. */
    private static int rank(MistPotential band) {
        return switch (band) {
            case NONE -> 0;
            case POSSIBLE -> 1;
            case LIKELY -> 2;
            default -> throw new IllegalArgumentException("unexpected band " + band);
        };
    }
}
