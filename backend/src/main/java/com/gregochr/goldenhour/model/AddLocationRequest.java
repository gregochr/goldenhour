package com.gregochr.goldenhour.model;

/**
 * Request body for adding a new location via {@code POST /api/locations}.
 *
 * @param name human-readable location identifier (e.g. "Durham UK")
 * @param lat  latitude in decimal degrees
 * @param lon  longitude in decimal degrees
 */
public record AddLocationRequest(String name, double lat, double lon) {
}
