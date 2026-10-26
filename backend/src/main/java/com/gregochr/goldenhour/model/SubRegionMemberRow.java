package com.gregochr.goldenhour.model;

/**
 * One {@code location_sub_region} row as the membership service reads it.
 *
 * @param areaId     the sub-region's area id
 * @param locationId the member location's id
 */
public record SubRegionMemberRow(Long areaId, Long locationId) {
}
