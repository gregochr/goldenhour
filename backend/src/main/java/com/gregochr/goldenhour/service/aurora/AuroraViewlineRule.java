package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.model.AuroraViewlineResponse.ViewlinePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * The forecast viewline: how far south, over the UK, aurora at a given Kp clears the northern
 * horizon <em>on camera</em>. Pure — no clock, no I/O, no state.
 *
 * <p><b>Camera visibility, not naked-eye and not the oval itself.</b> The line answers "from this
 * latitude, with a clear northern horizon and a dark site, can a camera pick the aurora up?". It is
 * deliberately south of the oval's equatorward edge, and a phone or DSLR sees further south than
 * an eye does.
 *
 * <p><b>Geomagnetic in, geographic out.</b> NOAA SWPC's <em>Tips on viewing the aurora</em> gives the
 * equatorward edge of the auroral oval as {@value #OVAL_EDGE_AT_KP_ZERO_DEG}° at Kp 0, moving
 * {@value #DEGREES_PER_KP}° equatorward per Kp step to 48° at Kp 9 — and states plainly that this
 * "holds true in geomagnetic latitude, not geographic". The app's old table used those figures as
 * geographic latitude, which put every row some 1.5° to 2.5° too far north over the UK. This class takes the
 * edge in geomagnetic latitude and converts it to geographic latitude <em>per longitude</em> with a
 * centred-dipole model, so the line is not flat: for one geomagnetic latitude the geographic one is
 * lower to the west (towards the pole's meridian) than to the east.
 *
 * <p><b>The horizon allowance.</b> The edge is where the aurora is overhead; NOAA says it "can often be
 * observed hundreds of kilometers equatorward" of it, low on the northern horizon. The line sits
 * {@value #HORIZON_ALLOWANCE_DEG}° of latitude (~450 km) south of the edge. That figure is a judgement
 * within "hundreds of kilometres", chosen by the owner (2026-10-11) for a camera, not a measurement.
 *
 * <p><b>Continuous in Kp.</b> No truncation: Kp 6.67 is not Kp 6. Kp is clamped to 0..9, the scale's
 * ends.
 *
 * <p><b>The dipole is approximate.</b> A centred dipole with the pole at 80.9°N, 72.6°W (IGRF-14, epoch
 * 2025, rounded) only approximates the full IGRF/AACGM conversion, and the owner chose not to take a
 * dependency for the difference: the line carries a 4° judgement of its own, and a degree or so either
 * way is inside it. The non-dipole terms are not modelled.
 *
 * <p><b>Three known checks</b> (at longitude −2°, the UK centre; ±0.5° is the precision claimed): Kp 0
 * gives about 60.5°N, Kp 6.67 about 46.5°N (52.7° geomagnetic, about 50.5°N geographic, less the
 * allowance) and Kp 9 about 41.7°N. The line is further south still at the west end: Kp 6.67 reaches
 * about 44.9°N at −12°.
 */
public final class AuroraViewlineRule {

    /** Geomagnetic latitude of the oval's equatorward edge at Kp 0 (NOAA SWPC), degrees. */
    static final double OVAL_EDGE_AT_KP_ZERO_DEG = 66.0;

    /** Equatorward movement of the edge per Kp step (NOAA SWPC), degrees of geomagnetic latitude. */
    static final double DEGREES_PER_KP = 2.0;

    /** Horizon allowance: how far south of the oval's edge a camera still sees it, degrees of latitude. */
    public static final double HORIZON_ALLOWANCE_DEG = 4.0;

    /** Geomagnetic north pole latitude, IGRF-14 epoch 2025 (rounded), degrees north. */
    static final double POLE_LATITUDE_DEG = 80.9;

    /** Geomagnetic north pole longitude, IGRF-14 epoch 2025 (rounded), degrees east (negative = west). */
    static final double POLE_LONGITUDE_DEG = -72.6;

    /** Lowest Kp the rule accepts. */
    static final double KP_MIN = 0.0;

    /** Highest Kp on the scale. */
    static final double KP_MAX = 9.0;

    /** Western end of the UK viewline, degrees east (negative = west). */
    public static final int LON_WEST_DEG = -12;

    /** Eastern end of the UK viewline, degrees east. */
    public static final int LON_EAST_DEG = 4;

    /**
     * The UK reference longitude: where the single "visible as far south as" figure is read, for the
     * Plan card's glow and the forecast response's {@code southernmostLatitude} alike.
     */
    public static final double UK_REFERENCE_LONGITUDE_DEG = -2.0;

    /** Bisection bracket, low end: well south of any latitude the rule can return, degrees. */
    private static final double SEARCH_LOW_DEG = 20.0;

    /** Bisection bracket, high end, degrees. */
    private static final double SEARCH_HIGH_DEG = 85.0;

    /** Bisection stops when the bracket is narrower than this, degrees (about a millimetre of arc). */
    private static final double SEARCH_TOLERANCE_DEG = 1.0e-8;

    /** Hard cap on bisection steps; 65 degrees halved this often is far below the tolerance. */
    private static final int MAX_BISECTION_STEPS = 80;

    private AuroraViewlineRule() {
    }

    /**
     * The viewline for a Kp: one point at every integer longitude from {@value #LON_WEST_DEG} to
     * {@value #LON_EAST_DEG}, west to east.
     *
     * @param kp the Kp index; clamped to 0..9
     * @return {@code LON_EAST_DEG - LON_WEST_DEG + 1} points (17)
     */
    public static List<ViewlinePoint> line(double kp) {
        List<ViewlinePoint> points = new ArrayList<>();
        for (int lon = LON_WEST_DEG; lon <= LON_EAST_DEG; lon++) {
            points.add(new ViewlinePoint(lon, geographicLatitudeFor(kp, lon)));
        }
        return points;
    }

    /**
     * The geographic latitude of the camera-visibility line at one longitude: the oval's edge for the
     * Kp, converted from geomagnetic to geographic latitude there, less the horizon allowance.
     *
     * @param kp     the Kp index; clamped to 0..9 (NaN reads as 0)
     * @param lonDeg geographic longitude in degrees east (negative = west)
     * @return geographic latitude in degrees north
     */
    public static double geographicLatitudeFor(double kp, double lonDeg) {
        double clamped = Double.isNaN(kp) ? KP_MIN : Math.min(KP_MAX, Math.max(KP_MIN, kp));
        double geomagnetic = OVAL_EDGE_AT_KP_ZERO_DEG - DEGREES_PER_KP * clamped;
        return geographicFromGeomagnetic(geomagnetic, lonDeg) - HORIZON_ALLOWANCE_DEG;
    }

    /**
     * Inverts the centred-dipole relation {@code sin(m) = sin(l) sin(p) + cos(l) cos(p) cos(lon - pLon)}
     * for the geographic latitude {@code l}, by bisection. {@code m} rises monotonically with {@code l}
     * across the bracket at every longitude the rule is used at: the derivative is
     * {@code cos(l) sin(p) - sin(l) cos(p) cos(lon - pLon)}, which only reaches zero at a latitude of
     * about 81° or more, well north of anything the rule returns. So no root-finding library is needed.
     */
    private static double geographicFromGeomagnetic(double geomagneticDeg, double lonDeg) {
        double low = SEARCH_LOW_DEG;
        double high = SEARCH_HIGH_DEG;
        for (int i = 0; i < MAX_BISECTION_STEPS && high - low > SEARCH_TOLERANCE_DEG; i++) {
            double mid = (low + high) / 2.0;
            if (geomagneticFromGeographic(mid, lonDeg) < geomagneticDeg) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2.0;
    }

    private static double geomagneticFromGeographic(double latDeg, double lonDeg) {
        double lat = Math.toRadians(latDeg);
        double poleLat = Math.toRadians(POLE_LATITUDE_DEG);
        double dLon = Math.toRadians(lonDeg - POLE_LONGITUDE_DEG);
        double sinMagnetic = Math.sin(lat) * Math.sin(poleLat)
                + Math.cos(lat) * Math.cos(poleLat) * Math.cos(dLon);
        return Math.toDegrees(Math.asin(sinMagnetic));
    }
}
