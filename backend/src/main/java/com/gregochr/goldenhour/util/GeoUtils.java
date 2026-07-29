package com.gregochr.goldenhour.util;

/**
 * Geodesic utility methods for coordinate calculations.
 */
public final class GeoUtils {

    /** Mean Earth radius in metres. */
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    /** Mean Earth radius in MILES. Never mix with {@link #EARTH_RADIUS_M}. */
    private static final double EARTH_RADIUS_MILES = 3958.8;

    private GeoUtils() {
    }

    /**
     * Computes a destination point given a start point, bearing, and distance.
     *
     * <p>Uses the Haversine forward formula. Accurate to within metres for
     * distances under 100 km.
     *
     * @param lat            start latitude in decimal degrees
     * @param lon            start longitude in decimal degrees
     * @param bearingDegrees bearing in degrees clockwise from north (0-360)
     * @param distanceMetres distance in metres
     * @return a two-element array {@code [latitude, longitude]} in decimal degrees
     */
    public static double[] offsetPoint(double lat, double lon, double bearingDegrees,
            double distanceMetres) {
        double latRad = Math.toRadians(lat);
        double lonRad = Math.toRadians(lon);
        double bearingRad = Math.toRadians(bearingDegrees);
        double angularDistance = distanceMetres / EARTH_RADIUS_M;

        double sinLat = Math.sin(latRad);
        double cosLat = Math.cos(latRad);
        double sinDist = Math.sin(angularDistance);
        double cosDist = Math.cos(angularDistance);

        double newLat = Math.asin(sinLat * cosDist + cosLat * sinDist * Math.cos(bearingRad));
        double newLon = lonRad + Math.atan2(
                Math.sin(bearingRad) * sinDist * cosLat,
                cosDist - sinLat * Math.sin(newLat));

        return new double[]{Math.toDegrees(newLat), Math.toDegrees(newLon)};
    }

    /**
     * Great-circle distance between two points, in MILES.
     *
     * <p>Miles rather than the metres used elsewhere in this class, deliberately: the only caller
     * is the Close to home proximity gate, whose radius is a user-facing "22 miles". Converting at
     * the boundary would put a unit change between the configured number and the comparison, which
     * is where a factor-of-1.6 bug hides. The constant below is the mean Earth radius in miles and
     * must never be mixed with {@code EARTH_RADIUS_M} above.
     *
     * @param lat1 first latitude in degrees
     * @param lon1 first longitude in degrees
     * @param lat2 second latitude in degrees
     * @param lon2 second longitude in degrees
     * @return the distance in miles
     */
    public static double distanceMiles(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_MILES * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * Returns the antisolar bearing (180 degrees opposite).
     *
     * @param azimuthDegrees solar azimuth in degrees (0-360)
     * @return the opposite bearing in degrees (0-360)
     */
    public static double antisolarBearing(double azimuthDegrees) {
        return (azimuthDegrees + 180.0) % 360.0;
    }
}
