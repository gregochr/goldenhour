package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import org.springframework.stereotype.Component;

import java.util.List;

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
        return entities.stream().map(this::toDto).toList();
    }

    /**
     * Maps a single location entity to a DTO.
     *
     * @param entity the location entity
     * @return the mapped DTO
     */
    public LocationDto toDto(LocationEntity entity) {
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
                entity.getGridLat(),
                entity.getGridLng(),
                entity.getBluebellExposure(),
                entity.isWoodlandOnly());
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
