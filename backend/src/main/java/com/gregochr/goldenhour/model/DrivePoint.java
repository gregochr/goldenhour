package com.gregochr.goldenhour.model;

/**
 * A location's drive point (reach-scoped-card-plan R1c): a place a driver can reach, with a name a reader
 * recognises, that drive times are routed to instead of the location's own pin.
 *
 * <p>The shape of a region base: a name and a coordinate pair that are set together or not at all.
 * {@link #parse} is the one place that rule is enforced, shared by the routing guard (before the transaction)
 * and the service (inside it), so the two can never disagree about what a half-supplied point means.
 *
 * @param name the place name, trimmed and non-blank
 * @param lat  WGS84 latitude
 * @param lon  WGS84 longitude
 */
public record DrivePoint(String name, double lat, double lon) {

    /** Longest accepted name; the column is {@code VARCHAR(120)}. */
    public static final int MAX_NAME_LENGTH = 120;

    /**
     * Furthest a drive point may be from the location's pin, in kilometres. A drive point is the nearest reachable
     * place, so it is close by definition; 25 km catches a village in another county from a summit on the boundary.
     * The admin lookup filters at the same figure, and the server enforces it too (the client's filter is not a check).
     */
    public static final double MAX_DISTANCE_KM = 25.0;

    private static final double MIN_LAT = -90.0;
    private static final double MAX_LAT = 90.0;
    private static final double MIN_LON = -180.0;
    private static final double MAX_LON = 180.0;

    /**
     * Reads a drive point from three optional request fields.
     *
     * @param name the name, or null
     * @param lat  the latitude, or null
     * @param lon  the longitude, or null
     * @return the drive point, or null when all three are absent (a blank name counts as absent)
     * @throws IllegalArgumentException if only some of the three are given, the name is too long, or a
     *                                  coordinate is not a finite number inside its range (HTTP 400)
     */
    public static DrivePoint parse(String name, Double lat, Double lon) {
        String trimmed = name == null ? "" : name.trim();
        boolean hasName = !trimmed.isEmpty();
        if (!hasName && lat == null && lon == null) {
            return null;
        }
        if (!hasName || lat == null || lon == null) {
            throw new IllegalArgumentException("A drive point needs a name, a latitude and a longitude together");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("A drive point name must be at most " + MAX_NAME_LENGTH
                    + " characters");
        }
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON) {
            throw new IllegalArgumentException("Drive point coordinates are out of range");
        }
        return new DrivePoint(trimmed, lat, lon);
    }

    /**
     * Refuses a drive point too far from the pin.
     *
     * @param pinLat the location's latitude
     * @param pinLon the location's longitude
     * @throws IllegalArgumentException if the drive point is more than {@link #MAX_DISTANCE_KM} from the pin (HTTP 400)
     */
    public void requireWithinRangeOf(double pinLat, double pinLon) {
        if (com.gregochr.goldenhour.util.GeoUtils.distanceKm(pinLat, pinLon, lat, lon) > MAX_DISTANCE_KM) {
            throw new IllegalArgumentException("The drive point must be within 25 km of the location");
        }
    }
}
