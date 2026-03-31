package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CoastalParameters}.
 */
class CoastalParametersTest {

    @Test
    @DisplayName("NON_TIDAL has isCoastalTidal = false")
    void nonTidalFlag() {
        assertThat(CoastalParameters.NON_TIDAL.isCoastalTidal()).isFalse();
    }

    @Test
    @DisplayName("NON_TIDAL has safe default depth (1m, not 0)")
    void nonTidalSafeDepth() {
        assertThat(CoastalParameters.NON_TIDAL.avgShelfDepthMetres()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Custom coastal parameters preserve all values")
    void customParametersPreserved() {
        var params = new CoastalParameters(80, 250_000, 30, true);

        assertThat(params.shoreNormalBearingDegrees()).isEqualTo(80);
        assertThat(params.effectiveFetchMetres()).isEqualTo(250_000);
        assertThat(params.avgShelfDepthMetres()).isEqualTo(30);
        assertThat(params.isCoastalTidal()).isTrue();
    }

    @Test
    @DisplayName("Record equality works correctly")
    void recordEquality() {
        var a = new CoastalParameters(80, 250_000, 30, true);
        var b = new CoastalParameters(80, 250_000, 30, true);
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("Different parameters are not equal")
    void differentParametersNotEqual() {
        var a = new CoastalParameters(80, 250_000, 30, true);
        var b = new CoastalParameters(90, 250_000, 30, true);
        assertThat(a).isNotEqualTo(b);
    }
}
