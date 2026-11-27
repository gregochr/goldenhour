package com.gregochr.goldenhour.util;

import java.util.List;

/**
 * The fixed, reviewed list of summits whose snowpack stands for "white tops in view"
 * (snow-topics-truth-plan §3.3, owner Q1).
 *
 * <p>Each summit is fetched with {@code elevation=<height>} so the point model runs at the backdrop's height, not
 * the valley's. Heights are Ordnance Survey summit heights in metres. Coordinates are WGS84 to four decimal places
 * (about 10 m), converted from each summit's published six-figure OS grid reference (OSGB36 to WGS84 by a Helmert
 * transform, good to a few metres plus the 100 m the grid reference itself carries); the 12 km nearest-summit rule
 * is insensitive to that.
 */
public final class SnowSummits {

    /** Prefix of a summit's {@code snow_state.point_key}. */
    public static final String KEY_PREFIX = "summit:";

    /** Radius within which a place sees a summit as its backdrop, in kilometres (great-circle). */
    public static final double VIEW_RADIUS_KM = 12.0;

    /**
     * One summit.
     *
     * @param key     stable lower-case identifier, used in {@code snow_state.point_key} as {@code summit:<key>}
     * @param name    the name shown in facts ("Helvellyn white")
     * @param lat     latitude, WGS84 degrees
     * @param lon     longitude, WGS84 degrees
     * @param heightM OS summit height, metres
     */
    public record Summit(String key, String name, double lat, double lon, int heightM) {

        /**
         * The summit's {@code snow_state.point_key}.
         *
         * @return {@code summit:<key>}
         */
        public String pointKey() {
            return KEY_PREFIX + key;
        }
    }

    /** The summits, in the order they are fetched (and written). */
    public static final List<Summit> ALL = List.of(
            new Summit("helvellyn", "Helvellyn", 54.5269, -3.0182, 950),
            new Summit("skiddaw", "Skiddaw", 54.6506, -3.1484, 931),
            new Summit("blencathra", "Blencathra", 54.6399, -3.0505, 868),
            new Summit("scafell-pike", "Scafell Pike", 54.4541, -3.2123, 978),
            new Summit("coniston-old-man", "Coniston Old Man", 54.3705, -3.1221, 803),
            new Summit("high-street", "High Street", 54.4912, -2.8660, 828),
            new Summit("cross-fell", "Cross Fell", 54.7027, -2.4872, 893),
            new Summit("whernside", "Whernside", 54.2276, -2.4034, 736),
            new Summit("ingleborough", "Ingleborough", 54.1656, -2.3997, 723),
            new Summit("pen-y-ghent", "Pen-y-ghent", 54.1553, -2.2496, 694),
            new Summit("great-whernside", "Great Whernside", 54.1609, -1.9984, 704),
            new Summit("buckden-pike", "Buckden Pike", 54.2040, -2.0628, 702),
            new Summit("the-cheviot", "The Cheviot", 55.4782, -2.1455, 815),
            new Summit("hedgehope-hill", "Hedgehope Hill", 55.4711, -2.0917, 714),
            new Summit("urra-moor", "Urra Moor", 54.4055, -1.0803, 454));

    private SnowSummits() {
    }
}
