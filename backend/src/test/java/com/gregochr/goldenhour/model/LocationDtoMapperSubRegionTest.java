package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LocationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the sub-region fields on {@link LocationDtoMapper}: a member carries its sub-region's id and
 * name, an unassigned place carries both as null, and the list form labels each place from one batched map.
 */
class LocationDtoMapperSubRegionTest {

    private final LocationDtoMapper mapper = new LocationDtoMapper();

    @Test
    @DisplayName("a member location serves its sub-region's id and name")
    void memberCarriesItsSubRegion() {
        LocationEntity entity = LocationEntity.builder().id(5L).name("High Force").build();

        LocationDto dto = mapper.toDto(entity, new LocationSubRegionRow(5L, 71L, "Swaledale"));

        assertThat(dto.subRegionId()).isEqualTo(71L);
        assertThat(dto.subRegionName()).isEqualTo("Swaledale");
    }

    @Test
    @DisplayName("an unassigned location serves both sub-region fields as null")
    void unassignedServesNulls() {
        LocationDto dto = mapper.toDto(LocationEntity.builder().id(6L).name("Bamburgh").build());

        assertThat(dto.subRegionId()).isNull();
        assertThat(dto.subRegionName()).isNull();
    }

    @Test
    @DisplayName("the list form labels each place from the batched map and leaves the others unlabelled")
    void listFormLabelsFromTheMap() {
        LocationEntity placed = LocationEntity.builder().id(5L).name("High Force").build();
        LocationEntity loose = LocationEntity.builder().id(6L).name("Bamburgh").build();

        List<LocationDto> dtos = mapper.toDtoList(List.of(placed, loose),
                Map.of(5L, new LocationSubRegionRow(5L, 71L, "Swaledale")));

        assertThat(dtos).extracting(LocationDto::subRegionName).containsExactly("Swaledale", null);
        assertThat(dtos).extracting(LocationDto::subRegionId).containsExactly(71L, null);
    }
}
