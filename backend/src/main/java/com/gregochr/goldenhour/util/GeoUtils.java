package com.gregochr.goldenhour.util;

/**
 * Geodesic utility methods for coordinate calculations.
 */
public final class GeoUtils {

    /** Mean Earth radius in metres. */
    private static final double EARTH_RADIUS_M = 6_371_000.0;

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
     * Returns the antisolar bearing (180 degrees opposite).
     *
     * @param azimuthDegrees solar azimuth in degrees (0-360)
     * @return the opposite bearing in degrees (0-360)
     */
    public static double antisolarBearing(double azimuthDegrees) {
        return (azimuthDegrees + 180.0) % 360.0;
    }
}
