package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LocationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the valley-floor fields on {@link LocationDtoMapper}.
 */
class LocationDtoMapperValleyFloorTest {

    private final LocationDtoMapper mapper = new LocationDtoMapper();

    @Test
    @DisplayName("a location with a valley floor serves its height and coordinates")
    void servesValleyFloor() {
        LocationEntity entity = LocationEntity.builder().id(1L).name("Latrigg")
                .valleyFloorMetres(80).valleyFloorLat(54.58).valleyFloorLng(-3.12).build();

        LocationDto dto = mapper.toDto(entity);

        assertThat(dto.valleyFloorMetres()).isEqualTo(80);
        assertThat(dto.valleyFloorLat()).isEqualTo(54.58);
        assertThat(dto.valleyFloorLng()).isEqualTo(-3.12);
    }

    @Test
    @DisplayName("a location without one serves all three as null")
    void servesNullsWhenUnset() {
        LocationDto dto = mapper.toDto(LocationEntity.builder().id(2L).name("Bamburgh").build());

        assertThat(dto.valleyFloorMetres()).isNull();
        assertThat(dto.valleyFloorLat()).isNull();
        assertThat(dto.valleyFloorLng()).isNull();
    }
}
