package com.gregochr.goldenhour.model;

/**
 * One location's sub-region as {@code GET /api/locations} labels it: the location, and the sub-region's
 * area id and name.
 *
 * @param locationId the member location's id
 * @param areaId     the sub-region's area id
 * @param areaName   the sub-region's name
 */
public record LocationSubRegionRow(Long locationId, Long areaId, String areaName) {
}
