package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.model.AuroraViewlineResponse.ViewlinePoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link AuroraViewlineRule}: the three known checks, the shape of the line and the
 * clamp. The dipole figures are approximate, so the checks carry a deliberate ±0.5° tolerance.
 */
class AuroraViewlineRuleTest {

    private static final double CHECK_TOLERANCE = 0.5;

    @Test
    @DisplayName("Kp 0 at -2 degrees is about 60N")
    void kpZero_centreLongitude_aboutSixtyNorth() {
        assertThat(AuroraViewlineRule.geographicLatitudeFor(0.0, -2.0))
                .isCloseTo(60.0, within(CHECK_TOLERANCE));
    }

    @Test
    @DisplayName("Kp 6.67 at -2 degrees is about 46.7N, between the Kp 6 and Kp 7 lines")
    void kpSixSixtySeven_centreLongitude_aboutFortySixPointSeven() {
        double fractional = AuroraViewlineRule.geographicLatitudeFor(6.67, -2.0);

        assertThat(fractional).isCloseTo(46.7, within(CHECK_TOLERANCE));
        assertThat(fractional).isLessThan(AuroraViewlineRule.geographicLatitudeFor(6.0, -2.0));
        assertThat(fractional).isGreaterThan(AuroraViewlineRule.geographicLatitudeFor(7.0, -2.0));
    }

    @Test
    @DisplayName("Kp 9 at -2 degrees is about 42N")
    void kpNine_centreLongitude_aboutFortyTwoNorth() {
        assertThat(AuroraViewlineRule.geographicLatitudeFor(9.0, -2.0))
                .isCloseTo(42.0, within(CHECK_TOLERANCE));
    }

    @Test
    @DisplayName("the rule matches an independent evaluation of the dipole to 0.05 degrees, across Kp and longitude")
    void rule_matchesIndependentDipoleValues() {
        // Computed outside the repo (a separate bisection in Python: pole 80.9N 72.6W, edge 66 - 2*Kp,
        // minus 4 degrees), so a wrong pole, slope, allowance or sign cannot agree with itself.
        double[][] expected = {
            // kp, lon, latitude
            {0.0, -12.0, 58.775}, {0.0, 4.0, 61.475}, {4.0, -2.0, 52.008},
            {6.67, -12.0, 44.893}, {6.67, -2.0, 46.473}, {9.0, -2.0, 41.680}, {9.0, 4.0, 42.647},
        };
        for (double[] row : expected) {
            assertThat(AuroraViewlineRule.geographicLatitudeFor(row[0], row[1]))
                    .as("Kp %.2f at %.0f", row[0], row[1])
                    .isCloseTo(row[2], within(0.05));
        }
    }

    @Test
    @DisplayName("latitude falls strictly as Kp rises, continuously")
    void latitude_strictlyDecreasesWithKp() {
        double previous = Double.MAX_VALUE;
        for (double kp = 0.0; kp <= 9.0; kp += 0.25) {
            double lat = AuroraViewlineRule.geographicLatitudeFor(kp, -2.0);
            assertThat(lat).as("Kp %.2f", kp).isLessThan(previous);
            previous = lat;
        }
    }

    @Test
    @DisplayName("the line dips further south to the west, at every Kp")
    void latitude_lowerToTheWestThanTheEast() {
        for (double kp : new double[] {0.0, 3.0, 6.67, 9.0}) {
            double west = AuroraViewlineRule.geographicLatitudeFor(kp, -12.0);
            double east = AuroraViewlineRule.geographicLatitudeFor(kp, 4.0);
            assertThat(west).as("Kp %.2f west vs east", kp).isLessThan(east);
        }
    }

    @Test
    @DisplayName("Kp outside 0..9 clamps to the scale's ends, and NaN reads as 0")
    void kpOutsideScale_clamps() {
        assertThat(AuroraViewlineRule.geographicLatitudeFor(-3.0, -2.0))
                .isEqualTo(AuroraViewlineRule.geographicLatitudeFor(0.0, -2.0));
        assertThat(AuroraViewlineRule.geographicLatitudeFor(12.0, -2.0))
                .isEqualTo(AuroraViewlineRule.geographicLatitudeFor(9.0, -2.0));
        assertThat(AuroraViewlineRule.geographicLatitudeFor(Double.NaN, -2.0))
                .isEqualTo(AuroraViewlineRule.geographicLatitudeFor(0.0, -2.0));
    }

    @Test
    @DisplayName("line() has one point per integer longitude from -12 to 4, west to east")
    void line_seventeenPointsWestToEast() {
        List<ViewlinePoint> line = AuroraViewlineRule.line(6.67);

        assertThat(line).hasSize(17);
        assertThat(line.get(0).longitude()).isEqualTo(-12.0);
        assertThat(line.get(16).longitude()).isEqualTo(4.0);
        for (int i = 1; i < line.size(); i++) {
            assertThat(line.get(i).longitude()).isEqualTo(line.get(i - 1).longitude() + 1.0);
        }
        assertThat(line.get(10).latitude())
                .isEqualTo(AuroraViewlineRule.geographicLatitudeFor(6.67, -2.0));
    }

    @Test
    @DisplayName("the horizon allowance is 4 degrees")
    void horizonAllowance_isFourDegrees() {
        assertThat(AuroraViewlineRule.HORIZON_ALLOWANCE_DEG).isEqualTo(4.0);
    }
}
