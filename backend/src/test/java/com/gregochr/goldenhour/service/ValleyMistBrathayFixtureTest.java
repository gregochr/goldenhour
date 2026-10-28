package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.BrathayValleyMistFixture;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.MistPotential;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins valley mist on the owner's REAL River Brathay forecast for the morning of 17 Oct 2026 (the
 * mist photographed on the river that prompted the feature), read through the production
 * {@link OpenMeteoResponseParser}: inversion v2 Phase 0 (inversion-v2-plan section 4), so later
 * inversion work can never silently move mist.
 *
 * <p>The file is Open-Meteo UTC (the file says Europe/London with offset 0 although 17 Oct is BST;
 * production requests {@code timezone=UTC}). Sunrise at Brathay is 06:41.8 UTC, and
 * {@code TimeSlotUtils.findBestIndex} takes the earliest slot at or after it, so production reads the
 * 07:00 slot. Night-side slots are offsets -3..0, i.e. 04:00..07:00 UTC.
 *
 * <p>Hours that matter (UTC; temp / dew / total cloud / 10 m wind in m/s):
 * <pre>
 *   04:00  5.8 / 2.8 /  0 / 2.28
 *   05:00  5.1 / 2.2 /  0 / 1.79
 *   06:00  5.9 / 3.8 / 47 / 1.79
 *   07:00  5.8 / 3.8 / 30 / 2.10
 * </pre>
 */
class ValleyMistBrathayFixtureTest {

    private static final double TOLERANCE = 0.01;

    @Test
    @DisplayName("17 Oct sunrise 06:41 UTC reads the 07:00 slot and scores 7.03, LIKELY")
    void sunrise0641_readsTheSevenOClockSlot_andIsLikely() {
        AtmosphericData data = BrathayValleyMistFixture.sunriseAt(
                BrathayValleyMistFixture.SUNRISE_READS_0700);

        // The parser chose the slot itself: 07:00 is the one with 30 percent low cloud and a
        // 5.8 C / 3.8 C reading (06:00 has 47 percent and 5.9 C).
        assertThat(data.cloud().lowCloudPercent()).isEqualTo(30);
        assertThat(data.comfort().temperatureCelsius()).isEqualTo(5.8);
        assertThat(data.weather().dewPointCelsius()).isEqualTo(3.8);
        assertThat(data.mistTrend().slots()).hasSize(6);

        Double score = ValleyMistCalculator.calculate(data);

        // Sub-scores (night-side slots 04:00..07:00):
        //   clear        1.63  mean total cloud 19.25 percent: (0 + 0 + 47 + 30) / 4
        //   calm         1.50  minimum 10 m wind 1.79 m/s (4.0 mph), at or under the 2 m/s full mark
        //   moisture     2.40  minimum gap 2.0 C (07:00) -> gapScore 3 of 5 -> 3/5 of 4
        //   cooling      1.50  gap narrows 3.0 -> 2.0 while the air does not warm (5.8 -> 5.8)
        //   wet ground   0     precipitation patched to 0.0
        //   confirmation 0     visibility patched to 20 km
        //   total        7.03  LIKELY (>= 6.0)
        //
        // LIKELY RESTS ON A TIE. The cooling term is zeroed when the last night-side temperature
        // is ABOVE the first (ValleyMistCalculator.coolingScore, '>'); 04:00 and 07:00 are both
        // 5.8 C. Had 07:00 been 0.1 C warmer the term would be 0 and the score 5.53, POSSIBLE.
        assertThat(score).isCloseTo(7.03, within(TOLERANCE));
        assertThat(ValleyMistCalculator.classify(data, score)).isEqualTo(MistPotential.LIKELY);
    }

    @Test
    @DisplayName("a sunrise one hour earlier reads the 06:00 slot and drops to 5.03, POSSIBLE")
    void sunrise0541_readsTheSixOClockSlot_andIsOnlyPossible() {
        AtmosphericData data = BrathayValleyMistFixture.sunriseAt(
                BrathayValleyMistFixture.SUNRISE_READS_0600);

        // findBestIndex takes the earliest slot at or after 05:41, which is 06:00 (47 percent low
        // cloud, 5.9 C).
        assertThat(data.cloud().lowCloudPercent()).isEqualTo(47);
        assertThat(data.comfort().temperatureCelsius()).isEqualTo(5.9);

        Double score = ValleyMistCalculator.calculate(data);

        // Night-side slots 03:00..06:00:
        //   clear        1.93  mean total cloud 11.75 percent
        //   calm         1.50  minimum wind 1.79 m/s
        //   moisture     1.60  minimum gap 2.1 C (06:00) -> gapScore 2 of 5 -> 2/5 of 4
        //   cooling      0     06:00 (5.9 C) is warmer than 03:00 (5.8 C)
        //   total        5.03  POSSIBLE
        // One hour of sunrise time moves this site a whole band, which is why Phase 0 pins both.
        assertThat(score).isCloseTo(5.03, within(TOLERANCE));
        assertThat(ValleyMistCalculator.classify(data, score)).isEqualTo(MistPotential.POSSIBLE);
    }
}
