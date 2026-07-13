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

    private CloudPointCacheReader() {
    }

    /**
     * Extracts directional cloud data from a pre-fetched {@link CloudPointCache}.
     * Falls back to {@code null} if any required point is missing from the cache.
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
            int[] solarBearings = {
                solarAzimuthDeg - DirectionalSamplingGeometry.SOLAR_CONE_HALF_ANGLE_DEG,
                solarAzimuthDeg,
                solarAzimuthDeg + DirectionalSamplingGeometry.SOLAR_CONE_HALF_ANGLE_DEG
            };

            int solarLowSum = 0;
            int solarMidSum = 0;
            int solarHighSum = 0;
            for (int i = 0; i < solarBearings.length; i++) {
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
            OpenMeteoForecastResponse antisolarF = cloudCache.get(points.get(3)[0], points.get(3)[1]);
            if (antisolarF == null) {
                return null;
            }
            int antisolarIdx = TimeSlotUtils.findBestIndex(antisolarF.getHourly().getTime(),
                    solarEventTime, targetType);
            OpenMeteoForecastResponse.Hourly ah = antisolarF.getHourly();

            // Far solar (index 4)
            Integer farSolarLow = null;
            OpenMeteoForecastResponse farF = cloudCache.get(points.get(4)[0], points.get(4)[1]);
            if (farF != null) {
                int farIdx = TimeSlotUtils.findBestIndex(farF.getHourly().getTime(),
                        solarEventTime, targetType);
                farSolarLow = farF.getHourly().getCloudCoverLow().get(farIdx);
            }

            return new DirectionalCloudData(
                    solarLowSum / solarBearings.length,
                    solarMidSum / solarBearings.length,
                    solarHighSum / solarBearings.length,
                    ah.getCloudCoverLow().get(antisolarIdx),
                    ah.getCloudCoverMid().get(antisolarIdx),
                    ah.getCloudCoverHigh().get(antisolarIdx),
                    farSolarLow);
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
            double[] solarPoint = DirectionalSamplingGeometry.computeSolarHorizonPoint(
                    lat, lon, solarAzimuthDeg);
            OpenMeteoForecastResponse solarF = cloudCache.get(solarPoint[0], solarPoint[1]);
            if (solarF == null) {
                return null;
            }

            SolarCloudTrend trend = OpenMeteoResponseParser.extractSolarTrend(
                    solarF, solarEventTime, targetType);

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
