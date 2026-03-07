package com.gregochr.goldenhour.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link GeoUtils}.
 */
class GeoUtilsTest {

    private static final double DURHAM_LAT = 54.7753;
    private static final double DURHAM_LON = -1.5849;

    @Test
    @DisplayName("offsetPoint() 50 km due north increases latitude by ~0.45 degrees")
    void offsetPoint_dueNorth_increasesLatitude() {
        double[] result = GeoUtils.offsetPoint(DURHAM_LAT, DURHAM_LON, 0, 50_000);

        assertThat(result[0]).isCloseTo(DURHAM_LAT + 0.449, within(0.01));
        assertThat(result[1]).isCloseTo(DURHAM_LON, within(0.01));
    }

    @Test
    @DisplayName("offsetPoint() 50 km due east shifts longitude")
    void offsetPoint_dueEast_shiftsLongitude() {
        double[] result = GeoUtils.offsetPoint(DURHAM_LAT, DURHAM_LON, 90, 50_000);

        assertThat(result[0]).isCloseTo(DURHAM_LAT, within(0.01));
        assertThat(result[1]).isGreaterThan(DURHAM_LON);
    }

    @Test
    @DisplayName("offsetPoint() 50 km due south decreases latitude")
    void offsetPoint_dueSouth_decreasesLatitude() {
        double[] result = GeoUtils.offsetPoint(DURHAM_LAT, DURHAM_LON, 180, 50_000);

        assertThat(result[0]).isLessThan(DURHAM_LAT);
        assertThat(result[1]).isCloseTo(DURHAM_LON, within(0.01));
    }

    @Test
    @DisplayName("offsetPoint() zero distance returns same point")
    void offsetPoint_zeroDistance_returnsSamePoint() {
        double[] result = GeoUtils.offsetPoint(DURHAM_LAT, DURHAM_LON, 270, 0);

        assertThat(result[0]).isCloseTo(DURHAM_LAT, within(0.0001));
        assertThat(result[1]).isCloseTo(DURHAM_LON, within(0.0001));
    }

    @Test
    @DisplayName("antisolarBearing() returns 180 degrees opposite")
    void antisolarBearing_returnsOpposite() {
        assertThat(GeoUtils.antisolarBearing(0)).isEqualTo(180.0);
        assertThat(GeoUtils.antisolarBearing(90)).isEqualTo(270.0);
        assertThat(GeoUtils.antisolarBearing(180)).isEqualTo(0.0);
        assertThat(GeoUtils.antisolarBearing(270)).isEqualTo(90.0);
        assertThat(GeoUtils.antisolarBearing(245)).isEqualTo(65.0);
    }
}
