package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LocationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the tide point on {@link LocationEntity#getEffectiveTidePoint()} and on
 * {@link LocationDtoMapper}.
 */
class LocationDtoMapperTidePointTest {

    private final LocationDtoMapper mapper = new LocationDtoMapper();

    @Test
    @DisplayName("a location with a tide point serves its coordinates")
    void servesTidePoint() {
        LocationEntity entity = LocationEntity.builder().id(1L).name("Staithes")
                .lat(54.56).lon(-0.79).tideLat(54.51).tideLng(-0.74).build();

        LocationDto dto = mapper.toDto(entity);

        assertThat(dto.tideLat()).isEqualTo(54.51);
        assertThat(dto.tideLng()).isEqualTo(-0.74);
    }

    @Test
    @DisplayName("a location without one serves both as null")
    void servesNullsWhenUnset() {
        LocationDto dto = mapper.toDto(LocationEntity.builder().id(2L).name("Bamburgh").build());

        assertThat(dto.tideLat()).isNull();
        assertThat(dto.tideLng()).isNull();
    }

    @Test
    @DisplayName("the effective tide point is the tide point when set, otherwise the pin")
    void effectiveTidePoint() {
        LocationEntity withPoint = LocationEntity.builder().lat(54.56).lon(-0.79)
                .tideLat(54.51).tideLng(-0.74).build();
        LocationEntity pinOnly = LocationEntity.builder().lat(54.56).lon(-0.79).build();

        assertThat(withPoint.getEffectiveTidePoint()).isEqualTo(new LocationEntity.TidePoint(54.51, -0.74));
        assertThat(pinOnly.getEffectiveTidePoint()).isEqualTo(new LocationEntity.TidePoint(54.56, -0.79));
    }
}
