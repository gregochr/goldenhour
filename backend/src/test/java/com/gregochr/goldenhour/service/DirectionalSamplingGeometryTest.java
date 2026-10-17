package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.util.GeoUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests pinning the named point indices of {@link DirectionalSamplingGeometry} to the
 * geometry {@link DirectionalSamplingGeometry#computeDirectionalCloudPoints} produces.
 */
class DirectionalSamplingGeometryTest {

    private static final double KM_PER_MILE = 1.609344;
    private static final double LAT = 54.7753;
    private static final double LON = -1.5849;
    private static final int AZIMUTH = 245;

    @Test
    @DisplayName("FAR_SOLAR_POINT_INDEX is 226 km along the solar azimuth")
    void farSolarPoint_is226kmAlongSolarAzimuth() {
        List<double[]> points = DirectionalSamplingGeometry.computeDirectionalCloudPoints(LAT, LON, AZIMUTH);
        double[] expected = GeoUtils.offsetPoint(LAT, LON, AZIMUTH, 226_000.0);
        double[] actual = points.get(DirectionalSamplingGeometry.FAR_SOLAR_POINT_INDEX);

        assertThat(distanceKm(actual)).isCloseTo(226.0, within(1.0));
        assertThat(actual[0]).isCloseTo(expected[0], within(1e-9));
        assertThat(actual[1]).isCloseTo(expected[1], within(1e-9));
    }

    @Test
    @DisplayName("ANTISOLAR_POINT_INDEX is 113 km along azimuth + 180")
    void antisolarPoint_is113kmAlongOppositeAzimuth() {
        List<double[]> points = DirectionalSamplingGeometry.computeDirectionalCloudPoints(LAT, LON, AZIMUTH);
        double[] expected = GeoUtils.offsetPoint(LAT, LON, (AZIMUTH + 180) % 360, 113_000.0);
        double[] actual = points.get(DirectionalSamplingGeometry.ANTISOLAR_POINT_INDEX);

        assertThat(distanceKm(actual)).isCloseTo(113.0, within(1.0));
        assertThat(actual[0]).isCloseTo(expected[0], within(1e-6));
        assertThat(actual[1]).isCloseTo(expected[1], within(1e-6));
    }

    @Test
    @DisplayName("the two indices are distinct and follow the three cone points")
    void indices_followConePoints() {
        assertThat(DirectionalSamplingGeometry.ANTISOLAR_POINT_INDEX)
                .isEqualTo(DirectionalSamplingGeometry.SOLAR_CONE_POINT_COUNT);
        assertThat(DirectionalSamplingGeometry.FAR_SOLAR_POINT_INDEX)
                .isEqualTo(DirectionalSamplingGeometry.ANTISOLAR_POINT_INDEX + 1);
    }

    private static double distanceKm(double[] point) {
        return GeoUtils.distanceMiles(LAT, LON, point[0], point[1]) * KM_PER_MILE;
    }
}
