package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.util.GeoUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry for Open-Meteo directional cloud sampling: computes the lat/lon points sampled
 * around an observer for the solar cone, antisolar, far-solar and upwind directions.
 *
 * <p>Stateless — all methods are pure functions of their arguments over {@link GeoUtils} math.
 * Extracted from {@code OpenMeteoService} so the sampling geometry can be reasoned about and
 * tested in isolation from HTTP orchestration and response parsing.
 */
public final class DirectionalSamplingGeometry {

    /**
     * Distance in metres to sample directional horizon cloud data.
     * Derived from sqrt(2Rh) for cloud at 1 km altitude: sqrt(2 × 6371 km × 1 km) ≈ 113 km.
     * This is the geometric horizon distance for low cloud.
     */
    public static final double DIRECTIONAL_OFFSET_METRES = 113_000.0;

    /**
     * Far-field distance for horizon cloud structure detection (2 × horizon distance = 226 km).
     * Comparing low cloud at this distance to {@link #DIRECTIONAL_OFFSET_METRES} reveals whether
     * high solar horizon low cloud is a thin strip (drops sharply) or an extensive blanket.
     */
    public static final double FAR_SOLAR_OFFSET_METRES = 226_000.0;

    /** Minimum upwind distance in metres below which the upwind sample is skipped. */
    public static final double MIN_UPWIND_DISTANCE_M = 5_000.0;

    /** Maximum upwind distance in metres (cap at 200 km). */
    public static final double MAX_UPWIND_DISTANCE_M = 200_000.0;

    /**
     * Half-angle of the sampling cone for the solar horizon direction (degrees).
     * Three points are sampled at azimuth-CONE, azimuth, azimuth+CONE and averaged,
     * smoothing out Open-Meteo grid-cell boundary effects (~11 km resolution).
     */
    public static final int SOLAR_CONE_HALF_ANGLE_DEG = 15;

    private DirectionalSamplingGeometry() {
    }

    /**
     * Computes the 5 directional cloud sampling points for a given observer and solar azimuth:
     * 3 solar cone points (azimuth ± 15°), 1 antisolar, 1 far-solar (226 km).
     *
     * @param lat             observer latitude
     * @param lon             observer longitude
     * @param solarAzimuthDeg compass bearing of the sun
     * @return list of 5 [lat, lon] pairs
     */
    public static List<double[]> computeDirectionalCloudPoints(double lat, double lon,
            int solarAzimuthDeg) {
        List<double[]> points = new ArrayList<>();
        int[] solarBearings = {
            solarAzimuthDeg - SOLAR_CONE_HALF_ANGLE_DEG,
            solarAzimuthDeg,
            solarAzimuthDeg + SOLAR_CONE_HALF_ANGLE_DEG
        };
        for (int bearing : solarBearings) {
            points.add(GeoUtils.offsetPoint(lat, lon, bearing, DIRECTIONAL_OFFSET_METRES));
        }
        points.add(GeoUtils.offsetPoint(lat, lon,
                GeoUtils.antisolarBearing(solarAzimuthDeg), DIRECTIONAL_OFFSET_METRES));
        points.add(GeoUtils.offsetPoint(lat, lon, solarAzimuthDeg, FAR_SOLAR_OFFSET_METRES));
        return points;
    }

    /**
     * Computes the solar horizon point used for cloud approach trend analysis.
     *
     * @param lat             observer latitude
     * @param lon             observer longitude
     * @param solarAzimuthDeg compass bearing of the sun
     * @return [lat, lon] pair at 113 km along the solar bearing
     */
    public static double[] computeSolarHorizonPoint(double lat, double lon, int solarAzimuthDeg) {
        return GeoUtils.offsetPoint(lat, lon, solarAzimuthDeg, DIRECTIONAL_OFFSET_METRES);
    }

    /**
     * Computes the upwind sampling point, or {@code null} if conditions don't warrant it.
     *
     * @param lat           observer latitude
     * @param lon           observer longitude
     * @param windFromDeg   wind-from bearing in degrees
     * @param windSpeedMs   wind speed in m/s
     * @param currentTime   current UTC time
     * @param eventTime     UTC time of the solar event
     * @return [lat, lon] pair, or {@code null} if wind is calm or event has passed
     */
    public static double[] computeUpwindPoint(double lat, double lon, int windFromDeg,
            double windSpeedMs, LocalDateTime currentTime, LocalDateTime eventTime) {
        long secondsToEvent = Duration.between(currentTime, eventTime).getSeconds();
        if (secondsToEvent <= 0 || windSpeedMs <= 0) {
            return null;
        }
        double dist = Math.min(windSpeedMs * secondsToEvent, MAX_UPWIND_DISTANCE_M);
        if (dist < MIN_UPWIND_DISTANCE_M) {
            return null;
        }
        return GeoUtils.offsetPoint(lat, lon, windFromDeg, dist);
    }
}
