package com.gregochr.goldenhour.model;

/**
 * Cloud sample at a point upstream along the wind vector.
 *
 * <p>The sample point is located at {@code windSpeed × timeToEvent} distance from the
 * observer, along the wind-from bearing. Captures both current-time and event-time low
 * cloud to detect approaching cloud banks that the model predicts will clear.
 *
 * @param distanceKm           distance to the upwind sample point in km
 * @param windFromBearing      compass bearing the wind is coming from (degrees)
 * @param currentLowCloudPercent low cloud at the upwind point RIGHT NOW
 * @param eventLowCloudPercent   low cloud at the upwind point at event time (model prediction)
 */
public record UpwindCloudSample(
        int distanceKm,
        int windFromBearing,
        int currentLowCloudPercent,
        int eventLowCloudPercent) {
}
