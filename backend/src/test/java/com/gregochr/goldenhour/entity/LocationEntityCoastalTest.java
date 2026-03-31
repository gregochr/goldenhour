package com.gregochr.goldenhour.entity;

import com.gregochr.goldenhour.model.CoastalParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LocationEntity#toCoastalParameters()}.
 */
class LocationEntityCoastalTest {

    @Test
    @DisplayName("Non-coastal location returns NON_TIDAL")
    void nonCoastalReturnsNonTidal() {
        LocationEntity loc = LocationEntity.builder()
                .name("Lake District")
                .lat(54.5)
                .lon(-3.0)
                .build();

        assertThat(loc.toCoastalParameters()).isEqualTo(CoastalParameters.NON_TIDAL);
    }

    @Test
    @DisplayName("Coastal location returns populated CoastalParameters")
    void coastalReturnsPopulated() {
        LocationEntity loc = LocationEntity.builder()
                .name("Craster")
                .lat(55.47)
                .lon(-1.59)
                .coastalTidal(true)
                .shoreNormalBearingDegrees(80.0)
                .effectiveFetchMetres(250_000.0)
                .avgShelfDepthMetres(30.0)
                .build();

        CoastalParameters params = loc.toCoastalParameters();

        assertThat(params.isCoastalTidal()).isTrue();
        assertThat(params.shoreNormalBearingDegrees()).isEqualTo(80.0);
        assertThat(params.effectiveFetchMetres()).isEqualTo(250_000.0);
        assertThat(params.avgShelfDepthMetres()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("Coastal with null values uses safe defaults")
    void coastalWithNullValues() {
        LocationEntity loc = LocationEntity.builder()
                .name("Incomplete")
                .lat(55.0)
                .lon(-1.5)
                .coastalTidal(true)
                .build();

        CoastalParameters params = loc.toCoastalParameters();

        assertThat(params.isCoastalTidal()).isTrue();
        assertThat(params.shoreNormalBearingDegrees()).isEqualTo(0);
        assertThat(params.effectiveFetchMetres()).isEqualTo(0);
        assertThat(params.avgShelfDepthMetres()).isEqualTo(1); // avoids divide-by-zero
    }

    @Test
    @DisplayName("Default coastalTidal is false")
    void defaultIsNotCoastal() {
        LocationEntity loc = LocationEntity.builder()
                .name("Inland")
                .lat(54.0)
                .lon(-2.0)
                .build();

        assertThat(loc.isCoastalTidal()).isFalse();
    }
}
