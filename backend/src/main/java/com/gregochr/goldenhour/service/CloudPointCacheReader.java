package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.UpwindCloudSample;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-extracts directional cloud and cloud-approach data from an already-fetched
 * {@link CloudPointCache} without making any API call.
 *
 * <p>Stateless — composes {@link DirectionalSamplingGeometry} (which points to sample) with
 * {@link OpenMeteoResponseParser} (how to read the hourly slots), reading everything from the
 * supplied cache. Extracted from {@code OpenMeteoService} so the cache re-assembly is isolated
 * from the live-fetch orchestration.
 */
public final class CloudPointCacheReader {

    private static final Logger LOG = LoggerFactory.getLogger(CloudPointCacheReader.class);

    /** Number of pressure levels recorded (850, 700, 600, 500, 400, 300, 250 and 200 hPa). */
    static final int PRESSURE_LEVEL_COUNT = 8;

    private CloudPointCacheReader() {
    }

    /**
     * Reads the eight pressure-level cloud values (850, 700, 600, 500, 400, 300, 250, 200 hPa, in order)
     * at the event's hour from a pressure-level response. Shared by the cache reader and the live
     * reader. Has its own failure isolation: a null response, null hourly block or time list, an
     * unparseable time or any other exception yields eight nulls, and never throws.
     *
     * @param response       the pressure-level response for the far point, or {@code null}
     * @param solarEventTime UTC time of the solar event
     * @param targetType     SUNRISE or SUNSET
     * @return array of eight values in level order, each possibly {@code null}
     */
    static Integer[] readPressureLevels(OpenMeteoForecastResponse response,
            LocalDateTime solarEventTime, TargetType targetType) {
        Integer[] levels = new Integer[PRESSURE_LEVEL_COUNT];
        try {
            if (response == null || response.getHourly() == null
                    || response.getHourly().getTime() == null) {
                return levels;
            }
            OpenMeteoForecastResponse.Hourly h = response.getHourly();
            int idx = TimeSlotUtils.findBestIndex(h.getTime(), solarEventTime, targetType);
            levels[0] = OpenMeteoResponseParser.integerAt(h.getCloudCover850hPa(), idx);
            levels[1] = OpenMeteoResponseParser.integerAt(h.getCloudCover700hPa(), idx);
            levels[2] = OpenMeteoResponseParser.integerAt(h.getCloudCover600hPa(), idx);
            levels[3] = OpenMeteoResponseParser.integerAt(h.getCloudCover500hPa(), idx);
            levels[4] = OpenMeteoResponseParser.integerAt(h.getCloudCover400hPa(), idx);
            levels[5] = OpenMeteoResponseParser.integerAt(h.getCloudCover300hPa(), idx);
            levels[6] = OpenMeteoResponseParser.integerAt(h.getCloudCover250hPa(), idx);
            levels[7] = OpenMeteoResponseParser.integerAt(h.getCloudCover200hPa(), idx);
            return levels;
        } catch (Exception e) {
            LOG.warn("Pressure-level cloud extraction failed, levels not recorded: {}",
                    e.getMessage());
            return new Integer[PRESSURE_LEVEL_COUNT];
        }
    }

    /**
     * Extracts directional cloud data from a pre-fetched {@link CloudPointCache}.
     * Falls back to {@code null} if any required point is missing from the cache.
     *
     * <p>A missing far cell leaves every far field {@code null} and returns the rest. A malformed
     * far point (a null hourly block, a null or short low-cloud series) still nulls the whole
     * result, deliberately unchanged: that outcome removes the prompt's directional block and
     * switches triage's cloud source, so it is a rating input. The far mid, high and dark-hour
     * values are read null-safely and can never throw. The eight pressure-level values come from
     * {@link CloudPointCache#getPressureLevels} through a helper with its own failure isolation, so
     * they can never affect any other field or the result's nullness.
     *
     * @param lat              observer latitude
     * @param lon              observer longitude
     * @param solarAzimuthDeg  compass bearing of the sun
     * @param solarEventTime   UTC time of the solar event
     * @param targetType       SUNRISE or SUNSET
     * @param cloudCache       pre-fetched cloud data cache
     * @return directional cloud data, or {@code null} if cache is incomplete
     */
    public static DirectionalCloudData fetchDirectionalCloudDataFromCache(double lat, double lon,
            int solarAzimuthDeg, LocalDateTime solarEventTime, TargetType targetType,
            CloudPointCache cloudCache) {
        List<double[]> points = DirectionalSamplingGeometry.computeDirectionalCloudPoints(
                lat, lon, solarAzimuthDeg);

        try {
            int solarLowSum = 0;
            int solarMidSum = 0;
            int solarHighSum = 0;
            for (int i = 0; i < DirectionalSamplingGeometry.SOLAR_CONE_POINT_COUNT; i++) {
                OpenMeteoForecastResponse f = cloudCache.get(points.get(i)[0], points.get(i)[1]);
                if (f == null) {
                    return null;
                }
                int idx = TimeSlotUtils.findBestIndex(f.getHourly().getTime(),
                        solarEventTime, targetType);
                OpenMeteoForecastResponse.Hourly h = f.getHourly();
                solarLowSum += h.getCloudCoverLow().get(idx);
                solarMidSum += h.getCloudCoverMid().get(idx);
                solarHighSum += h.getCloudCoverHigh().get(idx);
            }

            // Antisolar (index 3)
            OpenMeteoForecastResponse antisolarF = cloudCache.get(
                    points.get(DirectionalSamplingGeometry.ANTISOLAR_POINT_INDEX)[0],
                    points.get(DirectionalSamplingGeometry.ANTISOLAR_POINT_INDEX)[1]);
            if (antisolarF == null) {
                return null;
            }
            int antisolarIdx = TimeSlotUtils.findBestIndex(antisolarF.getHourly().getTime(),
                    solarEventTime, targetType);
            OpenMeteoForecastResponse.Hourly ah = antisolarF.getHourly();

            // Far solar (index 4)
            Integer farSolarLow = null;
            Integer farSolarMid = null;
            Integer farSolarHigh = null;
            Integer farLowDark = null;
            Integer farMidDark = null;
            Integer farHighDark = null;
            OpenMeteoForecastResponse farF = cloudCache.get(
                    points.get(DirectionalSamplingGeometry.FAR_SOLAR_POINT_INDEX)[0],
                    points.get(DirectionalSamplingGeometry.FAR_SOLAR_POINT_INDEX)[1]);
            if (farF != null) {
                OpenMeteoForecastResponse.Hourly fh = farF.getHourly();
                int farIdx = TimeSlotUtils.findBestIndex(fh.getTime(), solarEventTime, targetType);
                farSolarLow = fh.getCloudCoverLow().get(farIdx);
                // Recorded, never prompted: null-safe reads that cannot throw.
                int darkIdx = TimeSlotUtils.darkSideIndex(farIdx, targetType);
                farSolarMid = OpenMeteoResponseParser.integerAt(fh.getCloudCoverMid(), farIdx);
                farSolarHigh = OpenMeteoResponseParser.integerAt(fh.getCloudCoverHigh(), farIdx);
                farLowDark = OpenMeteoResponseParser.integerAt(fh.getCloudCoverLow(), darkIdx);
                farMidDark = OpenMeteoResponseParser.integerAt(fh.getCloudCoverMid(), darkIdx);
                farHighDark = OpenMeteoResponseParser.integerAt(fh.getCloudCoverHigh(), darkIdx);
            }

            // Pressure levels: own failure isolation, can never affect any other field.
            double[] farPoint = points.get(DirectionalSamplingGeometry.FAR_SOLAR_POINT_INDEX);
            Integer[] levels = readPressureLevels(
                    cloudCache.getPressureLevels(farPoint[0], farPoint[1]), solarEventTime, targetType);

            return new DirectionalCloudData(
                    solarLowSum / DirectionalSamplingGeometry.SOLAR_CONE_POINT_COUNT,
                    solarMidSum / DirectionalSamplingGeometry.SOLAR_CONE_POINT_COUNT,
                    solarHighSum / DirectionalSamplingGeometry.SOLAR_CONE_POINT_COUNT,
                    ah.getCloudCoverLow().get(antisolarIdx),
                    ah.getCloudCoverMid().get(antisolarIdx),
                    ah.getCloudCoverHigh().get(antisolarIdx),
                    farSolarLow, farSolarMid, farSolarHigh, farLowDark, farMidDark, farHighDark,
                    levels[0], levels[1], levels[2], levels[3], levels[4], levels[5],
                    levels[6], levels[7]);
        } catch (Exception e) {
            LOG.warn("Directional cloud extraction from cache failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts cloud approach data from a pre-fetched {@link CloudPointCache}.
     *
     * @param lat              observer latitude
     * @param lon              observer longitude
     * @param solarAzimuthDeg  compass bearing of the sun
     * @param solarEventTime   UTC time of the solar event
     * @param currentTime      current UTC time
     * @param targetType       SUNRISE or SUNSET
     * @param windFromDeg      wind-from bearing
     * @param windSpeedMs      wind speed in m/s
     * @param cloudCache       pre-fetched cloud data cache
     * @return cloud approach data, or {@code null} if cache is incomplete
     */
    public static CloudApproachData fetchCloudApproachDataFromCache(double lat, double lon,
            int solarAzimuthDeg, LocalDateTime solarEventTime, LocalDateTime currentTime,
            TargetType targetType, int windFromDeg, double windSpeedMs,
            CloudPointCache cloudCache) {
        try {
            // Average the trend across the solar cone, not the centre bearing alone. These three
            // points are already in the cache — computeDirectionalCloudPoints fetched them for the
            // same observer, distance and bearings — so the smoothing costs no extra request.
            List<double[]> conePoints = DirectionalSamplingGeometry.computeSolarConePoints(
                    lat, lon, solarAzimuthDeg);
            List<SolarCloudTrend> coneTrends = new ArrayList<>(conePoints.size());
            for (double[] point : conePoints) {
                OpenMeteoForecastResponse coneF = cloudCache.get(point[0], point[1]);
                if (coneF != null) {
                    coneTrends.add(OpenMeteoResponseParser.extractSolarTrend(
                            coneF, solarEventTime, targetType));
                }
            }

            SolarCloudTrend trend = SolarCloudTrend.average(coneTrends);
            if (trend == null) {
                return null;
            }

            UpwindCloudSample upwind = null;
            double[] upwindPoint = DirectionalSamplingGeometry.computeUpwindPoint(
                    lat, lon, windFromDeg, windSpeedMs, currentTime, solarEventTime);
            if (upwindPoint != null) {
                OpenMeteoForecastResponse upwindF = cloudCache.get(
                        upwindPoint[0], upwindPoint[1]);
                if (upwindF != null) {
                    long secondsToEvent = Duration.between(currentTime, solarEventTime)
                            .getSeconds();
                    double dist = Math.min(windSpeedMs * secondsToEvent,
                            DirectionalSamplingGeometry.MAX_UPWIND_DISTANCE_M);
                    upwind = OpenMeteoResponseParser.extractUpwindSample(upwindF, solarEventTime,
                            currentTime, targetType, (int) (dist / 1000), windFromDeg);
                }
            }

            return new CloudApproachData(trend, upwind);
        } catch (Exception e) {
            LOG.warn("Cloud approach extraction from cache failed: {}", e.getMessage());
            return null;
        }
    }
}
