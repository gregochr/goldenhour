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
 * @param southernmostLatitude degrees N. LIVE: the true minimum of the parsed OVATION contour (measured).
 *                             FORECAST: the rule evaluated at the UK reference longitude (-2), not the
 *                             curve's minimum (its west end is mid-Atlantic), so it agrees with the
 *                             Plan card's glow; the summary band is read from it
 * @param forecastTime         OVATION model run timestamp
 * @param active               {@code true} when aurora probability above threshold exists
 *                             in the UK longitude range; {@code false} otherwise
 * @param isForecast           {@code true} when derived from the Kp rule
 *                             ({@code AuroraViewlineRule}), {@code false} for live OVATION data
 * @param kp                   the Kp the forecast line was drawn for; {@code null} for the live line
 *                             and for an inactive response
 * @param asOf                 when the line is true of: the OVATION run time for the live line, the
 *                             moment the forecast line was built for the forecast one; {@code null}
 *                             when inactive
 */
public record AuroraViewlineResponse(
        List<ViewlinePoint> points,
        String summary,
        double southernmostLatitude,
        ZonedDateTime forecastTime,
        boolean active,
        boolean isForecast,
        Double kp,
        ZonedDateTime asOf) {

    /** Defensive copy. */
    public AuroraViewlineResponse {
        points = List.copyOf(points);
    }

    /**
     * A response with no Kp and no as-of instant — the shape of every inactive answer.
     *
     * @param points               the polyline
     * @param summary              the human-readable position
     * @param southernmostLatitude the lowest latitude reached
     * @param forecastTime         the OVATION model run timestamp
     * @param active               whether there is a line to draw
     * @param isForecast           whether the line is the Kp-rule forecast
     */
    public AuroraViewlineResponse(List<ViewlinePoint> points, String summary,
            double southernmostLatitude, ZonedDateTime forecastTime, boolean active,
            boolean isForecast) {
        this(points, summary, southernmostLatitude, forecastTime, active, isForecast, null, null);
    }

    /**
     * A single point on the viewline polyline.
     *
     * @param longitude degrees (negative = west)
     * @param latitude  degrees north
     */
    public record ViewlinePoint(double longitude, double latitude) {
    }
}
