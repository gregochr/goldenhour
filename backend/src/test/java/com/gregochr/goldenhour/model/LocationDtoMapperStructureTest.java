package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.CoastalStructure;
import com.gregochr.goldenhour.entity.LocationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the big-seas structure fields on {@link LocationDtoMapper}: they reach the wire
 * unchanged, and a non-structure location serves them as null.
 */
class LocationDtoMapperStructureTest {

    private final LocationDtoMapper mapper = new LocationDtoMapper();

    @Test
    @DisplayName("a structure location serves its kind, faces and chip label")
    void structureFieldsMapped() {
        LocationEntity entity = LocationEntity.builder().id(243L).name("Roker Pier & Lighthouse")
                .coastalStructure(CoastalStructure.PIER).structureFacesDeg(45).structureLabel("Roker Pier")
                .build();

        LocationDto dto = mapper.toDto(entity);

        assertThat(dto.coastalStructure()).isEqualTo(CoastalStructure.PIER);
        assertThat(dto.structureFacesDeg()).isEqualTo(45);
        assertThat(dto.structureLabel()).isEqualTo("Roker Pier");
    }

    @Test
    @DisplayName("a location that is not a structure serves all three as null")
    void nonStructureServesNulls() {
        LocationDto dto = mapper.toDto(LocationEntity.builder().id(1L).name("Bamburgh").build());

        assertThat(dto.coastalStructure()).isNull();
        assertThat(dto.structureFacesDeg()).isNull();
        assertThat(dto.structureLabel()).isNull();
    }
}
