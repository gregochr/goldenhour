package com.gregochr.goldenhour.model;

/**
 * An enabled, regioned location that belongs to no sub-region, as the admin Areas section lists it.
 *
 * @param id         the location's primary key
 * @param name       the location's name
 * @param regionId   the location's region id
 * @param regionName the location's region name
 */
public record UncoveredLocationDto(Long id, String name, Long regionId, String regionName) {
}
