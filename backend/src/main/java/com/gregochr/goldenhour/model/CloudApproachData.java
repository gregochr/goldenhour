package com.gregochr.goldenhour.model;

/**
 * Cloud approach risk signals for a solar event.
 *
 * <p>Wraps two independent signals: a temporal trend at the solar horizon and a
 * spatial sample at the upwind point. Either may be {@code null} if the data was
 * unavailable or not applicable (e.g. low wind speed for upwind).
 *
 * @param solarTrend   low cloud trend at the 113 km solar horizon across T-3h to T, or null
 * @param upwindSample cloud at the upwind point along the wind vector, or null
 */
public record CloudApproachData(
        SolarCloudTrend solarTrend,
        UpwindCloudSample upwindSample) {
}
