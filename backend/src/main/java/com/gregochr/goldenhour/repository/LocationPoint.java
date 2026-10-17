package com.gregochr.goldenhour.repository;

/**
 * The four columns of an enabled location the neighbour guard needs, and nothing else: its id, its
 * coordinates and its elevation. Returned by {@link LocationRepository#findEnabledPoints()}, so a
 * serve-time read does not hydrate a full {@code LocationEntity} (and its metadata collections) for
 * each of ~250 places.
 *
 * @param id              the location id
 * @param lat             latitude in degrees
 * @param lon             longitude in degrees
 * @param elevationMetres the location's elevation in metres, or null when none is recorded
 */
public record LocationPoint(Long id, double lat, double lon, Integer elevationMetres) { }
