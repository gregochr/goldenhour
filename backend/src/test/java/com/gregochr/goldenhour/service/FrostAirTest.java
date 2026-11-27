package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopicFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FrostAir}, the rounding shared by the SNOW_MIST frost variant and FROST_MIST.
 */
class FrostAirTest {

    @Test
    @DisplayName("-0.5 rounds to 0 but is shown as -1, never '0 °C' beside a frost claim")
    void shownCelsius_minusHalf_clampedToMinusOne() {
        assertThat(FrostAir.shownCelsius(-0.5)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("-0.4 is shown as -1")
    void shownCelsius_minusPointFour_clampedToMinusOne() {
        assertThat(FrostAir.shownCelsius(-0.4)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("-1.6 rounds to -2 and is not clamped")
    void shownCelsius_minusOnePointSix_roundsToMinusTwo() {
        assertThat(FrostAir.shownCelsius(-1.6)).isEqualTo(-2L);
    }

    @Test
    @DisplayName("the fact is keyed air, reads 'N °C · frost likely' with an ASCII hyphen, and is not emphasised")
    void fact_formatsTheFrostLine() {
        HotTopicFact fact = FrostAir.fact(-3.2);

        assertThat(fact).isEqualTo(new HotTopicFact("air", "-3 °C · frost likely", null, false, false));
    }
}
