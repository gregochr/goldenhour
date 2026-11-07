package com.gregochr.goldenhour.model;

/**
 * One place an admin can pick as a location's drive point, from {@code GET /api/admin/drive-times/places}.
 *
 * @param name       the name to store as the drive point and show readers, e.g. "Glenridding"
 * @param detail     what distinguishes it: the kind of place and where it is, e.g. "Village, Westmorland and Furness"
 * @param lat        WGS84 latitude
 * @param lon        WGS84 longitude
 * @param distanceKm straight-line kilometres from the location, to one decimal place
 */
public record DrivePointCandidate(String name, String detail, double lat, double lon, double distanceKm) {
}
