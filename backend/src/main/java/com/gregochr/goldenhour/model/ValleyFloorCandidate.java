package com.gregochr.goldenhour.model;

/**
 * One suggested valley-floor point: the lowest sampled point in a compass sector around a location.
 *
 * @param metres     the DEM height in metres, rounded
 * @param lat        the point's latitude
 * @param lng        the point's longitude
 * @param distanceKm great-circle distance from the location, in kilometres
 * @param bearing    the eight-point compass direction of the point from the location, e.g. {@code "NE"}
 */
public record ValleyFloorCandidate(int metres, double lat, double lng, double distanceKm, String bearing) {
}
