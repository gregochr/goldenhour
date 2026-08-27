package com.gregochr.goldenhour.model;

import java.time.LocalDateTime;

/**
 * The region nested inside a {@link LocationDto}, mirroring every field
 * {@code RegionEntity} itself serialises as.
 *
 * @param id        region primary key
 * @param name      human-readable region name
 * @param enabled   whether the region is enabled and visible in location dropdowns
 * @param baseName  the region's admin-entered base town, or {@code null}
 * @param baseLat   the base town's latitude, or {@code null}
 * @param baseLon   the base town's longitude, or {@code null}
 * @param createdAt UTC timestamp when the region was created
 */
public record LocationRegionDto(
        Long id,
        String name,
        boolean enabled,
        String baseName,
        Double baseLat,
        Double baseLon,
        LocalDateTime createdAt) {
}
