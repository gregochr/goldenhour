package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Maps {@link LocationEntity} to {@link LocationDto} for {@code GET /api/locations}.
 *
 * <p>A pure field-for-field projection — see {@link LocationDto} for why this exists.
 */
@Component
public class LocationDtoMapper {

    /**
     * Maps a list of location entities to DTOs.
     *
     * @param entities the location entities
     * @return the mapped DTOs in the same order
     */
    public List<LocationDto> toDtoList(List<LocationEntity> entities) {
        return toDtoList(entities, Map.of());
    }

    /**
     * Maps a list of location entities to DTOs, labelling each with its sub-region.
     *
     * @param entities   the location entities
     * @param subRegions each member location's sub-region, keyed by location id (one batched read)
     * @return the mapped DTOs in the same order; a location absent from the map carries no sub-region
     */
    public List<LocationDto> toDtoList(List<LocationEntity> entities,
            Map<Long, LocationSubRegionRow> subRegions) {
        return entities.stream().map(entity -> toDto(entity, subRegions.get(entity.getId()))).toList();
    }

    /**
     * Maps a single location entity to a DTO.
     *
     * @param entity the location entity
     * @return the mapped DTO
     */
    public LocationDto toDto(LocationEntity entity) {
        return toDto(entity, null);
    }

    /**
     * Maps a single location entity to a DTO carrying its sub-region.
     *
     * @param entity    the location entity
     * @param subRegion the location's sub-region, or null when it has none
     * @return the mapped DTO
     */
    public LocationDto toDto(LocationEntity entity, LocationSubRegionRow subRegion) {
        return new LocationDto(
                entity.getId(),
                entity.getName(),
                entity.getLat(),
                entity.getLon(),
                entity.getSolarEventType(),
                entity.getTideType(),
                entity.getLocationType(),
                toRegionDto(entity.getRegion()),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getConsecutiveFailures(),
                entity.getLastFailureAt(),
                entity.getDisabledReason(),
                entity.getBortleClass(),
                entity.getSkyBrightnessSqm(),
                entity.getShoreNormalBearingDegrees(),
                entity.getEffectiveFetchMetres(),
                entity.getAvgShelfDepthMetres(),
                entity.isCoastalTidal(),
                entity.getElevationMetres(),
                entity.isOverlooksWater(),
                entity.isMistProne(),
                entity.getGridLat(),
                entity.getGridLng(),
                entity.getBluebellExposure(),
                entity.getCoastalStructure(),
                entity.getStructureFacesDeg(),
                entity.getStructureLabel(),
                entity.isWoodlandOnly(),
                subRegion == null ? null : subRegion.areaId(),
                subRegion == null ? null : subRegion.areaName());
    }

    private LocationRegionDto toRegionDto(RegionEntity region) {
        if (region == null) {
            return null;
        }
        return new LocationRegionDto(
                region.getId(),
                region.getName(),
                region.isEnabled(),
                region.getBaseName(),
                region.getBaseLat(),
                region.getBaseLon(),
                region.getCreatedAt());
    }
}
