package com.gregochr.goldenhour.model;

/**
 * One enabled location of a sub-region, as the picks service reads it (counts, example names, centroids).
 *
 * @param areaId     the sub-region's area id
 * @param locationId the member location's id
 * @param name       the location's name
 * @param lat        its latitude
 * @param lon        its longitude
 */
public record SubRegionLocationRow(Long areaId, Long locationId, String name, double lat, double lon) {
}
