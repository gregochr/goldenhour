package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.BrathayValleyMistFixture;
import com.gregochr.goldenhour.model.AtmosphericData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Inversion baseline on the owner's real Brathay forecast (inversion-v2-plan section 4 Phase 0):
 * Phase 1 changes how the warm layer is measured, and this morning should stay at 6.0.
 *
 * <p>At the 07:00 UTC slot production reads on 17 Oct, every pressure level is colder than the
 * surface (surface 5.8 C; 975 hPa 4.0 C, 950 hPa 2.5 C, 925 hPa 1.8 C), so there is no reversal and
 * the lapse gate caps the score at {@code InversionScoreCalculator.VETOED_CEILING} (6.0). The raw
 * parts are 3 (gap 2.0 C) + 2 (wind 2.10 m/s) + 2 (30 percent low cloud, inside the 25-65 deck), 7
 * before the cap. The patched 100 m boundary layer is below the 335 m viewpoint, so clearance passes
 * and the cap comes from the lapse gate alone. Only the 925 hPa level is parsed in production today.
 */
class InversionBrathayBaselineTest {

    private static final int LOUGHRIGG_ELEVATION_M = 335;

    @Test
    @DisplayName("17 Oct 07:00 at a 335 m site: every level colder than the surface caps inversion at 6.0")
    void brathay0700_at335m_isCappedAtSix() {
        AtmosphericData data = BrathayValleyMistFixture.sunriseAt(
                BrathayValleyMistFixture.SUNRISE_READS_0700);

        // Guard the guard: the 925 hPa temperature and the surface are the file's, so the reversal
        // really is negative (1.8 - 5.8 = -4.0).
        assertThat(data.weather().temperature925hPaCelsius()).isEqualTo(1.8);
        assertThat(InversionScoreCalculator.reversalCelsius(data.weather(),
                data.comfort().temperatureCelsius())).isCloseTo(-4.0, within(1e-9));

        Double score = InversionScoreCalculator.calculate(data, LOUGHRIGG_ELEVATION_M);

        assertThat(score).isEqualTo(6.0);
    }
}
