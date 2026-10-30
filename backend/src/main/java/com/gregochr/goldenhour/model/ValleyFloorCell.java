package com.gregochr.goldenhour.model;

/**
 * The model cell Open-Meteo resolves a valley-floor point to, shown beside the chosen floor and never stored.
 *
 * @param cellLat             the snapped grid latitude
 * @param cellLng             the snapped grid longitude
 * @param cellElevationMetres the surface height in metres the response refers to
 * @param cellDistanceKm      great-circle distance from the requested point to the cell centre, in kilometres
 */
public record ValleyFloorCell(double cellLat, double cellLng, double cellElevationMetres,
        double cellDistanceKm) {
}
