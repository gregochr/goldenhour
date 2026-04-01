package com.gregochr.goldenhour.model;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * REST response for {@code GET /api/aurora/viewline}.
 *
 * <p>Represents the southernmost visible aurora boundary ("viewline") extracted from
 * NOAA SWPC OVATION data, filtered to the UK longitude range (−12°W to 4°E).
 *
 * @param points               smoothed viewline as a west-to-east polyline
 * @param summary              human-readable description of the viewline position
 *                             (e.g. "Visible as far south as northern England")
 * @param southernmostLatitude the lowest latitude reached by the viewline (degrees N)
 * @param forecastTime         OVATION model run timestamp
 * @param active               {@code true} when aurora probability above threshold exists
 *                             in the UK longitude range; {@code false} otherwise
 */
public record AuroraViewlineResponse(
        List<ViewlinePoint> points,
        String summary,
        double southernmostLatitude,
        ZonedDateTime forecastTime,
        boolean active) {

    /**
     * A single point on the viewline polyline.
     *
     * @param longitude degrees (negative = west)
     * @param latitude  degrees north
     */
    public record ViewlinePoint(double longitude, double latitude) {
    }
}
