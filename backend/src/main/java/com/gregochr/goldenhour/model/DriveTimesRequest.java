package com.gregochr.goldenhour.model;

/**
 * Request body for {@code POST /api/locations/drive-times}.
 *
 * @param lat source latitude in decimal degrees (e.g. from browser geolocation)
 * @param lon source longitude in decimal degrees
 */
public record DriveTimesRequest(double lat, double lon) {}
