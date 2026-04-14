package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.CloudData;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.ComfortData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.UpwindCloudSample;
import com.gregochr.goldenhour.model.WeatherData;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.util.GeoUtils;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retrieves atmospheric forecast data from the Open-Meteo Forecast and Air Quality APIs.
 *
 * <p>Delegates HTTP calls to {@link OpenMeteoClient} (which provides declarative retry
 * via Resilience4j {@code @Retry}) and extracts the values nearest to the solar event time.
 * Both APIs are free and require no API key.
 */
@Service
public class OpenMeteoService {

    private static final Logger LOG = LoggerFactory.getLogger(OpenMeteoService.class);

    /**
     * Distance in metres to sample directional horizon cloud data.
     * Derived from sqrt(2Rh) for cloud at 1 km altitude: sqrt(2 × 6371 km × 1 km) ≈ 113 km.
     * This is the geometric horizon distance for low cloud.
     */
    static final double DIRECTIONAL_OFFSET_METRES = 113_000.0;

    /**
     * Far-field distance for horizon cloud structure detection (2 × horizon distance = 226 km).
     * Comparing low cloud at this distance to {@link #DIRECTIONAL_OFFSET_METRES} reveals whether
     * high solar horizon low cloud is a thin strip (drops sharply) or an extensive blanket.
     */
    static final double FAR_SOLAR_OFFSET_METRES = 226_000.0;

    /** Minimum upwind distance in metres below which the upwind sample is skipped. */
    private static final double MIN_UPWIND_DISTANCE_M = 5_000.0;

    /** Maximum upwind distance in metres (cap at 200 km). */
    private static final double MAX_UPWIND_DISTANCE_M = 200_000.0;

    /** Number of hours before the event to sample for the solar trend. */
    private static final int TREND_HOURS_BACK = 3;

    /** Number of hours before the event to include in the mist/visibility trend. */
    private static final int MIST_TREND_HOURS_BACK = 3;

    /** Number of hours after the event to include in the mist/visibility trend. */
    private static final int MIST_TREND_HOURS_FORWARD = 2;

    /**
     * Half-angle of the sampling cone for the solar horizon direction (degrees).
     * Three points are sampled at azimuth-CONE, azimuth, azimuth+CONE and averaged,
     * smoothing out Open-Meteo grid-cell boundary effects (~11 km resolution).
     */
    static final int SOLAR_CONE_HALF_ANGLE_DEG = 15;

    private static final int WIND_SPEED_SCALE = 2;
    private static final int PRECIP_SCALE = 2;
    private static final int RADIATION_SCALE = 2;
    private static final int AOD_SCALE = 3;

    private final OpenMeteoClient openMeteoClient;
    private final JobRunService jobRunService;

    /**
     * Constructs an {@code OpenMeteoService}.
     *
     * @param openMeteoClient resilient client for Open-Meteo API calls
     * @param jobRunService   service for recording API call metrics
     */
    public OpenMeteoService(OpenMeteoClient openMeteoClient, JobRunService jobRunService) {
        this.openMeteoClient = openMeteoClient;
        this.jobRunService = jobRunService;
    }

    /**
     * Fetches atmospheric data from the Open-Meteo APIs and extracts values
     * for the slot nearest to the solar event time.
     *
     * @param request        the forecast request (location, date, target type)
     * @param solarEventTime UTC time of the sunrise or sunset being evaluated
     * @return pre-processed atmospheric data for the ±30-minute window
     */
    public AtmosphericData getAtmosphericData(ForecastRequest request, LocalDateTime solarEventTime) {
        return getAtmosphericData(request, solarEventTime, null);
    }

    /**
     * Fetches atmospheric data from the Open-Meteo APIs and extracts values
     * for the slot nearest to the solar event time, with optional job run tracking.
     *
     * @param request        the forecast request (location, date, target type)
     * @param solarEventTime UTC time of the sunrise or sunset being evaluated
     * @param jobRun         the parent job run for metrics tracking, or {@code null}
     * @return pre-processed atmospheric data for the ±30-minute window
     */
    public AtmosphericData getAtmosphericData(ForecastRequest request, LocalDateTime solarEventTime,
            JobRunEntity jobRun) {
        return getAtmosphericDataWithResponse(request, solarEventTime, jobRun).atmosphericData();
    }

    /**
     * Fetches atmospheric data and returns both the extracted data and the raw forecast response.
     *
     * <p>The raw response is needed by callers that must extract values at times other than
     * the solar event (e.g. storm surge calculation at high-tide time).
     *
     * @param request        the forecast request (location, date, target type)
     * @param solarEventTime UTC time of the sunrise or sunset being evaluated
     * @param jobRun         the parent job run for metrics tracking, or {@code null}
     * @return extraction result containing atmospheric data and raw forecast response
     */
    public WeatherExtractionResult getAtmosphericDataWithResponse(ForecastRequest request,
            LocalDateTime solarEventTime, JobRunEntity jobRun) {
        LOG.info("Open-Meteo <- {} {} {}", request.locationName(), request.targetType(),
                solarEventTime.toLocalDate());
        long startMs = System.currentTimeMillis();

        String forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + request.latitude()
                + "&longitude=" + request.longitude()
                + "&hourly=" + OpenMeteoClient.FORECAST_PARAMS
                + "&wind_speed_unit=ms&timezone=UTC";
        String airQualityUrl = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude="
                + request.latitude() + "&longitude=" + request.longitude()
                + "&hourly=" + OpenMeteoClient.AIR_QUALITY_PARAMS + "&timezone=UTC";

        try {
            OpenMeteoForecastResponse forecast = openMeteoClient.fetchForecast(
                    request.latitude(), request.longitude());
            OpenMeteoAirQualityResponse airQuality = openMeteoClient.fetchAirQuality(
                    request.latitude(), request.longitude());

            AtmosphericData data = extractAtmosphericData(forecast, airQuality,
                    request.locationName(), solarEventTime, request.targetType());

            long durationMs = System.currentTimeMillis() - startMs;
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", forecastUrl, null, durationMs, 200, null, true, null);
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_AIR_QUALITY,
                        "GET", airQualityUrl, null, durationMs, 200, null, true, null);
            }

            LOG.info("Open-Meteo -> {} {}: {}ms", request.locationName(), request.targetType(),
                    durationMs);
            return new WeatherExtractionResult(data, forecast);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            if (jobRun != null) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", forecastUrl, null, durationMs,
                        getStatusCode(e), null, false, errorMsg);
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_AIR_QUALITY,
                        "GET", airQualityUrl, null, durationMs,
                        getStatusCode(e), null, false, errorMsg);
            }
            throw e;
        }
    }

    /**
     * Batch pre-fetches forecast and air quality data for a set of unique locations.
     *
     * <p>Open-Meteo returns 7 days of hourly data regardless of which date is being evaluated,
     * so a single response per location covers all dates and events. This method deduplicates
     * coordinates and makes two batch API calls (forecast + air quality) instead of 2N individual
     * calls.
     *
     * @param coords list of unique [lat, lon] pairs
     * @param jobRun the parent job run for metrics tracking, or {@code null}
     * @return map from coordinate key ("lat,lon") to the paired responses
     */
    public Map<String, WeatherExtractionResult> prefetchWeatherBatch(
            List<double[]> coords, JobRunEntity jobRun) {
        LOG.info("Open-Meteo batch prefetch: {} unique locations", coords.size());
        long startMs = System.currentTimeMillis();

        try {
            List<OpenMeteoForecastResponse> forecasts = openMeteoClient.fetchForecastBatch(coords);
            List<OpenMeteoAirQualityResponse> airQualities = openMeteoClient.fetchAirQualityBatch(coords);

            Map<String, WeatherExtractionResult> cache = new LinkedHashMap<>();
            int nullCount = 0;
            for (int i = 0; i < coords.size(); i++) {
                if (forecasts.get(i) == null) {
                    nullCount++;
                    continue;
                }
                String key = coordKey(coords.get(i)[0], coords.get(i)[1]);
                cache.put(key, new WeatherExtractionResult(null, forecasts.get(i),
                        airQualities.get(i)));
            }

            long durationMs = System.currentTimeMillis() - startMs;
            LOG.info("Open-Meteo batch prefetch complete: {}/{} locations in {}ms (2 API calls)",
                    cache.size(), coords.size(), durationMs);
            if (nullCount > 0) {
                LOG.warn("Weather batch: {} location(s) missing forecast due to chunk failure",
                        nullCount);
            }
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "batch-forecast(" + coords.size() + ")", null, durationMs,
                        200, null, true, null);
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_AIR_QUALITY,
                        "GET", "batch-air-quality(" + coords.size() + ")", null, durationMs,
                        200, null, true, null);
            }
            return cache;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.error("Open-Meteo batch prefetch failed ({}ms): {}", durationMs, e.getMessage());
            if (jobRun != null) {
                String errorMsg = e.getMessage() != null ? e.getMessage()
                        : e.getClass().getSimpleName();
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "batch-forecast(" + coords.size() + ")", null, durationMs,
                        getStatusCode(e), null, false, errorMsg);
            }
            throw e;
        }
    }

    /**
     * Extracts atmospheric data from pre-fetched responses (no API call).
     *
     * @param request        the forecast request (location, date, target type)
     * @param solarEventTime UTC time of the solar event
     * @param prefetched     pre-fetched weather data keyed by coordinate key
     * @return extraction result, or {@code null} if no data for this location
     */
    public WeatherExtractionResult getAtmosphericDataFromCache(ForecastRequest request,
            LocalDateTime solarEventTime,
            Map<String, WeatherExtractionResult> prefetched) {
        String key = coordKey(request.latitude(), request.longitude());
        WeatherExtractionResult cached = prefetched.get(key);
        if (cached == null) {
            return null;
        }
        AtmosphericData data = extractAtmosphericData(cached.forecastResponse(),
                cached.airQualityResponse(), request.locationName(), solarEventTime,
                request.targetType());
        return new WeatherExtractionResult(data, cached.forecastResponse(),
                cached.airQualityResponse());
    }

    /**
     * Returns a coordinate key for cache lookups.
     *
     * @param lat latitude
     * @param lon longitude
     * @return coordinate key string
     */
    public static String coordKey(double lat, double lon) {
        return lat + "," + lon;
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
    public List<double[]> computeDirectionalCloudPoints(double lat, double lon,
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
    public double[] computeSolarHorizonPoint(double lat, double lon, int solarAzimuthDeg) {
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
    public double[] computeUpwindPoint(double lat, double lon, int windFromDeg,
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

    /**
     * Batch pre-fetches cloud-only data for a set of sampling points.
     *
     * <p>Deduplicates by Open-Meteo's ~0.1° grid resolution and makes a single batch
     * API call. Returns a {@link CloudPointCache} for lookup during augmentation.
     *
     * @param allCoords raw [lat, lon] pairs (may contain duplicates)
     * @param jobRun    the parent job run for metrics tracking, or {@code null}
     * @return a cache of cloud-only responses keyed by grid cell
     */
    public CloudPointCache prefetchCloudBatch(List<double[]> allCoords, JobRunEntity jobRun) {
        // Deduplicate by grid key
        Map<String, double[]> uniqueByGrid = new LinkedHashMap<>();
        for (double[] coord : allCoords) {
            String key = CloudPointCache.gridKey(coord[0], coord[1]);
            uniqueByGrid.putIfAbsent(key, coord);
        }

        List<String> gridKeys = new ArrayList<>(uniqueByGrid.keySet());
        List<double[]> coords = new ArrayList<>(uniqueByGrid.values());

        LOG.info("Cloud batch prefetch: {} raw points -> {} unique grid cells",
                allCoords.size(), coords.size());

        if (coords.isEmpty()) {
            return new CloudPointCache(Map.of());
        }

        long startMs = System.currentTimeMillis();
        try {
            List<OpenMeteoForecastResponse> responses =
                    openMeteoClient.fetchCloudOnlyBatch(coords);

            Map<String, OpenMeteoForecastResponse> cacheMap = new LinkedHashMap<>();
            int nullCount = 0;
            for (int i = 0; i < gridKeys.size(); i++) {
                if (responses.get(i) != null) {
                    cacheMap.put(gridKeys.get(i), responses.get(i));
                } else {
                    nullCount++;
                }
            }

            long durationMs = System.currentTimeMillis() - startMs;
            LOG.info("Cloud batch prefetch complete: {}/{} grid cells populated in {}ms (1 API call)",
                    cacheMap.size(), coords.size(), durationMs);
            if (nullCount > 0) {
                LOG.warn("Cloud batch: {} grid cell(s) missing due to chunk failure — "
                        + "affected locations will fall back to cached data", nullCount);
            }
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "cloud-batch-prefetch(" + coords.size() + ")", null,
                        durationMs, 200, null, true, null);
            }
            return new CloudPointCache(cacheMap);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.warn("Cloud batch prefetch failed ({}ms), directional cloud will be unavailable: {}",
                    durationMs, e.getMessage());
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "cloud-batch-prefetch(" + coords.size() + ")", null,
                        durationMs, getStatusCode(e), null, false, e.getMessage());
            }
            return new CloudPointCache(Map.of());
        }
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
    public DirectionalCloudData fetchDirectionalCloudDataFromCache(double lat, double lon,
            int solarAzimuthDeg, LocalDateTime solarEventTime, TargetType targetType,
            CloudPointCache cloudCache) {
        List<double[]> points = computeDirectionalCloudPoints(lat, lon, solarAzimuthDeg);

        try {
            int[] solarBearings = {
                solarAzimuthDeg - SOLAR_CONE_HALF_ANGLE_DEG,
                solarAzimuthDeg,
                solarAzimuthDeg + SOLAR_CONE_HALF_ANGLE_DEG
            };

            int solarLowSum = 0;
            int solarMidSum = 0;
            int solarHighSum = 0;
            for (int i = 0; i < solarBearings.length; i++) {
                OpenMeteoForecastResponse f = cloudCache.get(points.get(i)[0], points.get(i)[1]);
                if (f == null) {
                    return null;
                }
                int idx = findBestIndex(f.getHourly().getTime(), solarEventTime, targetType);
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
            int antisolarIdx = findBestIndex(antisolarF.getHourly().getTime(),
                    solarEventTime, targetType);
            OpenMeteoForecastResponse.Hourly ah = antisolarF.getHourly();

            // Far solar (index 4)
            Integer farSolarLow = null;
            OpenMeteoForecastResponse farF = cloudCache.get(points.get(4)[0], points.get(4)[1]);
            if (farF != null) {
                int farIdx = findBestIndex(farF.getHourly().getTime(), solarEventTime, targetType);
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
    public CloudApproachData fetchCloudApproachDataFromCache(double lat, double lon,
            int solarAzimuthDeg, LocalDateTime solarEventTime, LocalDateTime currentTime,
            TargetType targetType, int windFromDeg, double windSpeedMs,
            CloudPointCache cloudCache) {
        try {
            double[] solarPoint = computeSolarHorizonPoint(lat, lon, solarAzimuthDeg);
            OpenMeteoForecastResponse solarF = cloudCache.get(solarPoint[0], solarPoint[1]);
            if (solarF == null) {
                return null;
            }

            SolarCloudTrend trend = extractSolarTrend(solarF, solarEventTime, targetType);

            UpwindCloudSample upwind = null;
            double[] upwindPoint = computeUpwindPoint(lat, lon, windFromDeg, windSpeedMs,
                    currentTime, solarEventTime);
            if (upwindPoint != null) {
                OpenMeteoForecastResponse upwindF = cloudCache.get(
                        upwindPoint[0], upwindPoint[1]);
                if (upwindF != null) {
                    long secondsToEvent = Duration.between(currentTime, solarEventTime)
                            .getSeconds();
                    double dist = Math.min(windSpeedMs * secondsToEvent, MAX_UPWIND_DISTANCE_M);
                    upwind = extractUpwindSample(upwindF, solarEventTime, currentTime,
                            targetType, (int) (dist / 1000), windFromDeg);
                }
            }

            return new CloudApproachData(trend, upwind);
        } catch (Exception e) {
            LOG.warn("Cloud approach extraction from cache failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fetches atmospheric data from the Open-Meteo APIs and extracts values for each full UTC
     * hour between {@code from} and {@code to} (both inclusive, truncated to the hour).
     *
     * <p>Makes a single pair of API calls for the full day and extracts data for each
     * hourly slot, so the cost is one request regardless of how many hours are covered.
     *
     * @param request  the forecast request (location, date, target type)
     * @param from     start of the window (truncated to hour)
     * @param to       end of the window (truncated to hour)
     * @return pre-processed atmospheric data for each full UTC hour in [from, to]
     */
    public List<AtmosphericData> getHourlyAtmosphericData(ForecastRequest request,
            LocalDateTime from, LocalDateTime to) {
        return getHourlyAtmosphericData(request, from, to, null);
    }

    /**
     * Fetches atmospheric data from the Open-Meteo APIs and extracts values for each full UTC
     * hour between {@code from} and {@code to} (both inclusive, truncated to the hour),
     * with optional job run tracking.
     *
     * <p>Makes a single pair of API calls for the full day and extracts data for each
     * hourly slot, so the cost is one request regardless of how many hours are covered.
     *
     * @param request  the forecast request (location, date, target type)
     * @param from     start of the window (truncated to hour)
     * @param to       end of the window (truncated to hour)
     * @param jobRun   the parent job run for metrics tracking, or {@code null}
     * @return pre-processed atmospheric data for each full UTC hour in [from, to]
     */
    public List<AtmosphericData> getHourlyAtmosphericData(ForecastRequest request,
            LocalDateTime from, LocalDateTime to, JobRunEntity jobRun) {
        LOG.info("Open-Meteo (hourly) <- {} {} to {}", request.locationName(), from, to);
        long startMs = System.currentTimeMillis();

        String forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + request.latitude()
                + "&longitude=" + request.longitude()
                + "&hourly=" + OpenMeteoClient.FORECAST_PARAMS
                + "&wind_speed_unit=ms&timezone=UTC";
        String airQualityUrl = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude="
                + request.latitude() + "&longitude=" + request.longitude()
                + "&hourly=" + OpenMeteoClient.AIR_QUALITY_PARAMS + "&timezone=UTC";

        try {
            OpenMeteoForecastResponse forecast = openMeteoClient.fetchForecast(
                    request.latitude(), request.longitude());
            OpenMeteoAirQualityResponse airQuality = openMeteoClient.fetchAirQuality(
                    request.latitude(), request.longitude());

            LocalDateTime startHour = from.truncatedTo(ChronoUnit.HOURS);
            LocalDateTime endHour = to.truncatedTo(ChronoUnit.HOURS);
            List<AtmosphericData> slots = new ArrayList<>();
            LocalDateTime current = startHour;
            while (!current.isAfter(endHour)) {
                slots.add(extractAtmosphericData(forecast, airQuality,
                        request.locationName(), current, request.targetType()));
                current = current.plusHours(1);
            }

            long durationMs = System.currentTimeMillis() - startMs;
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", forecastUrl, null, durationMs, 200, null, true, null);
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_AIR_QUALITY,
                        "GET", airQualityUrl, null, durationMs, 200, null, true, null);
            }

            LOG.info("Open-Meteo (hourly) -> {}: {} slots, {}ms",
                    request.locationName(), slots.size(), durationMs);
            return slots;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            if (jobRun != null) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", forecastUrl, null, durationMs,
                        getStatusCode(e), null, false, errorMsg);
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_AIR_QUALITY,
                        "GET", airQualityUrl, null, durationMs,
                        getStatusCode(e), null, false, errorMsg);
            }
            throw e;
        }
    }

    /**
     * Extracts the HTTP status code from an exception, if available.
     *
     * @param e the exception
     * @return the HTTP status code, or null if not available
     */
    private Integer getStatusCode(Exception e) {
        if (e instanceof RestClientResponseException rex) {
            return rex.getStatusCode().value();
        }
        return null;
    }

    /**
     * Extracts the forecast values nearest to the solar event time from the API responses.
     *
     * <p>Package-private for unit testing.
     *
     * @param forecast       the Open-Meteo forecast response
     * @param airQuality     the Open-Meteo air quality response
     * @param locationName   human-readable location name
     * @param solarEventTime UTC time of the solar event
     * @param targetType     SUNRISE or SUNSET
     * @return pre-processed atmospheric data for the closest forecast slot
     */
    AtmosphericData extractAtmosphericData(OpenMeteoForecastResponse forecast,
            OpenMeteoAirQualityResponse airQuality, String locationName,
            LocalDateTime solarEventTime, TargetType targetType) {
        List<String> times = forecast.getHourly().getTime();
        int idx = findBestIndex(times, solarEventTime, targetType);

        OpenMeteoForecastResponse.Hourly h = forecast.getHourly();
        OpenMeteoAirQualityResponse.Hourly aq = airQuality.getHourly();

        Double pm25Raw = getAirQualityValue(aq.getPm25(), idx);
        Double dustRaw = getAirQualityValue(aq.getDust(), idx);
        Double aodRaw = getAirQualityValue(aq.getAerosolOpticalDepth(), idx);

        CloudData cloud = new CloudData(
                h.getCloudCoverLow().get(idx),
                h.getCloudCoverMid().get(idx),
                h.getCloudCoverHigh().get(idx));

        WeatherData weather = new WeatherData(
                h.getVisibility().get(idx).intValue(),
                BigDecimal.valueOf(h.getWindSpeed10m().get(idx))
                        .setScale(WIND_SPEED_SCALE, RoundingMode.HALF_UP),
                h.getWindDirection10m().get(idx),
                BigDecimal.valueOf(h.getPrecipitation().get(idx))
                        .setScale(PRECIP_SCALE, RoundingMode.HALF_UP),
                h.getRelativeHumidity2m().get(idx),
                h.getWeatherCode().get(idx),
                BigDecimal.valueOf(h.getShortwaveRadiation().get(idx))
                        .setScale(RADIATION_SCALE, RoundingMode.HALF_UP),
                getDoubleValue(h.getDewPoint2m(), idx),
                getDoubleValue(h.getSurfacePressure(), idx));

        AerosolData aerosol = new AerosolData(
                toDecimal(pm25Raw, PRECIP_SCALE),
                toDecimal(dustRaw, PRECIP_SCALE),
                toDecimal(aodRaw, AOD_SCALE),
                h.getBoundaryLayerHeight().get(idx).intValue());

        ComfortData comfort = new ComfortData(
                getDoubleValue(h.getTemperature2m(), idx),
                getDoubleValue(h.getApparentTemperature(), idx),
                getIntegerValue(h.getPrecipitationProbability(), idx));

        MistTrend mistTrend = extractMistTrend(h, idx);

        return new AtmosphericData(
                locationName, solarEventTime, targetType,
                cloud, weather, aerosol, comfort,
                null,  // directionalCloud — populated later for colour locations
                null,  // tide — populated later for coastal locations
                null,  // cloudApproach — populated later if directional data available
                mistTrend);
    }

    /**
     * Fetches cloud cover at the solar and antisolar horizon points (113 km from observer),
     * plus a far-field solar sample at 226 km for horizon cloud structure detection.
     *
     * <p>Makes up to five additional Open-Meteo calls (cloud layers only): three for the
     * solar cone, one for the antisolar point, and one for the far solar point. Returns
     * {@code null} if the core solar/antisolar fetches fail; the far fetch failing sets
     * {@code farSolarLowCloudPercent} to {@code null} only, not the whole result.
     *
     * @param lat              observer latitude in decimal degrees
     * @param lon              observer longitude in decimal degrees
     * @param solarAzimuthDeg  compass bearing of the sun (sunrise or sunset azimuth)
     * @param solarEventTime   UTC time of the solar event (for finding the best hourly slot)
     * @param targetType       SUNRISE or SUNSET (determines slot selection direction)
     * @param jobRun           the parent job run for metrics tracking, or {@code null}
     * @return directional cloud data, or {@code null} if the fetch fails
     */
    public DirectionalCloudData fetchDirectionalCloudData(double lat, double lon,
            int solarAzimuthDeg, LocalDateTime solarEventTime, TargetType targetType,
            JobRunEntity jobRun) {
        // Compute all 5 sampling points upfront
        int[] solarBearings = {
            solarAzimuthDeg - SOLAR_CONE_HALF_ANGLE_DEG,
            solarAzimuthDeg,
            solarAzimuthDeg + SOLAR_CONE_HALF_ANGLE_DEG
        };
        double antisolarBearing = GeoUtils.antisolarBearing(solarAzimuthDeg);
        double[] antisolarPoint = GeoUtils.offsetPoint(lat, lon, antisolarBearing,
                DIRECTIONAL_OFFSET_METRES);
        double[] farSolarPoint = GeoUtils.offsetPoint(lat, lon, solarAzimuthDeg,
                FAR_SOLAR_OFFSET_METRES);

        // Build batch: [cone0, cone1, cone2, antisolar, far-solar]
        List<double[]> coords = new ArrayList<>();
        for (int bearing : solarBearings) {
            coords.add(GeoUtils.offsetPoint(lat, lon, bearing, DIRECTIONAL_OFFSET_METRES));
        }
        coords.add(antisolarPoint);
        coords.add(farSolarPoint);

        LOG.info("Directional cloud batch fetch: 5 points (solar cone {}±{}deg, antisolar, far-solar)",
                solarAzimuthDeg, SOLAR_CONE_HALF_ANGLE_DEG);

        long startMs = System.currentTimeMillis();
        try {
            List<OpenMeteoForecastResponse> responses = openMeteoClient.fetchCloudOnlyBatch(coords);

            // Extract cloud data from batch response
            int solarLowSum = 0;
            int solarMidSum = 0;
            int solarHighSum = 0;
            for (int i = 0; i < solarBearings.length; i++) {
                OpenMeteoForecastResponse f = responses.get(i);
                int idx = findBestIndex(f.getHourly().getTime(), solarEventTime, targetType);
                OpenMeteoForecastResponse.Hourly h = f.getHourly();
                solarLowSum += h.getCloudCoverLow().get(idx);
                solarMidSum += h.getCloudCoverMid().get(idx);
                solarHighSum += h.getCloudCoverHigh().get(idx);
            }
            int solarLow = solarLowSum / solarBearings.length;
            int solarMid = solarMidSum / solarBearings.length;
            int solarHigh = solarHighSum / solarBearings.length;

            // Antisolar (index 3)
            OpenMeteoForecastResponse antisolarForecast = responses.get(3);
            int antisolarIdx = findBestIndex(antisolarForecast.getHourly().getTime(),
                    solarEventTime, targetType);
            OpenMeteoForecastResponse.Hourly ah = antisolarForecast.getHourly();

            // Far solar (index 4) — treat parse failures gracefully
            Integer farSolarLow = null;
            try {
                OpenMeteoForecastResponse farForecast = responses.get(4);
                int farIdx = findBestIndex(farForecast.getHourly().getTime(),
                        solarEventTime, targetType);
                farSolarLow = farForecast.getHourly().getCloudCoverLow().get(farIdx);
            } catch (Exception e) {
                LOG.warn("Far solar extraction failed, strip detection unavailable: {}", e.getMessage());
            }

            long durationMs = System.currentTimeMillis() - startMs;
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "directional-cloud-batch(5)", null, durationMs, 200,
                        null, true, null);
            }

            DirectionalCloudData result = new DirectionalCloudData(
                    solarLow, solarMid, solarHigh,
                    ah.getCloudCoverLow().get(antisolarIdx),
                    ah.getCloudCoverMid().get(antisolarIdx),
                    ah.getCloudCoverHigh().get(antisolarIdx),
                    farSolarLow);

            LOG.info("Directional cloud -> solar(avg3): L{}% M{}% H{}%, antisolar: L{}% M{}% H{}%, "
                    + "farSolar: L{}% ({}ms, 1 batch call)",
                    result.solarLowCloudPercent(), result.solarMidCloudPercent(),
                    result.solarHighCloudPercent(),
                    result.antisolarLowCloudPercent(), result.antisolarMidCloudPercent(),
                    result.antisolarHighCloudPercent(),
                    result.farSolarLowCloudPercent(), durationMs);

            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.warn("Directional cloud batch fetch failed ({}ms), falling back to layer inference: {}",
                    durationMs, e.getMessage());
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "directional-cloud-batch(5)", null, durationMs,
                        getStatusCode(e), null, false, e.getMessage());
            }
            return null;
        }
    }

    private Double getAirQualityValue(List<Double> values, int idx) {
        if (values == null || idx >= values.size()) {
            return null;
        }
        return values.get(idx);
    }

    private Double getDoubleValue(List<Double> values, int idx) {
        if (values == null || idx >= values.size()) {
            return null;
        }
        return values.get(idx);
    }

    private Integer getIntegerValue(List<Integer> values, int idx) {
        if (values == null || idx >= values.size()) {
            return null;
        }
        return values.get(idx);
    }

    private BigDecimal toDecimal(Double value, int scale) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Finds the best hourly slot index for a solar event, respecting event direction.
     *
     * @param times      list of ISO-8601 time strings from the API response
     * @param targetTime the solar event time
     * @param targetType SUNRISE or SUNSET
     * @return the index of the best matching slot
     * @see TimeSlotUtils#findBestIndex(List, LocalDateTime, TargetType)
     */
    int findBestIndex(List<String> times, LocalDateTime targetTime, TargetType targetType) {
        return TimeSlotUtils.findBestIndex(times, targetTime, targetType);
    }

    /**
     * Fetches cloud approach risk data: a temporal trend at the solar horizon and an
     * upwind spatial sample.
     *
     * <p>Makes up to 2 additional Open-Meteo calls. Returns {@code null} gracefully on failure.
     *
     * @param lat              observer latitude
     * @param lon              observer longitude
     * @param solarAzimuthDeg  compass bearing of the sun
     * @param solarEventTime   UTC time of the solar event
     * @param currentTime      current UTC time (for upwind distance calculation)
     * @param targetType       SUNRISE or SUNSET
     * @param windFromDeg      wind-from bearing in degrees
     * @param windSpeedMs      wind speed in m/s
     * @param jobRun           the parent job run for metrics tracking, or {@code null}
     * @return cloud approach risk data, or {@code null} if the fetch fails
     */
    public CloudApproachData fetchCloudApproachData(double lat, double lon,
            int solarAzimuthDeg, LocalDateTime solarEventTime, LocalDateTime currentTime,
            TargetType targetType, int windFromDeg, double windSpeedMs, JobRunEntity jobRun) {
        try {
            double[] solarPoint = GeoUtils.offsetPoint(lat, lon, solarAzimuthDeg,
                    DIRECTIONAL_OFFSET_METRES);

            // Determine if we need an upwind sample
            double[] upwindPoint = null;
            double upwindDistanceM = 0;
            long secondsToEvent = Duration.between(currentTime, solarEventTime).getSeconds();
            if (secondsToEvent > 0 && windSpeedMs > 0) {
                upwindDistanceM = Math.min(windSpeedMs * secondsToEvent, MAX_UPWIND_DISTANCE_M);
                if (upwindDistanceM >= MIN_UPWIND_DISTANCE_M) {
                    upwindPoint = GeoUtils.offsetPoint(lat, lon, windFromDeg, upwindDistanceM);
                }
            }

            // Batch fetch: solar point + optional upwind point
            List<double[]> coords = new ArrayList<>();
            coords.add(solarPoint);
            if (upwindPoint != null) {
                coords.add(upwindPoint);
            }

            long startMs = System.currentTimeMillis();
            List<OpenMeteoForecastResponse> responses = openMeteoClient.fetchCloudOnlyBatch(coords);
            long durationMs = System.currentTimeMillis() - startMs;
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "cloud-approach-batch(" + coords.size() + ")", null,
                        durationMs, 200, null, true, null);
            }

            SolarCloudTrend trend = extractSolarTrend(responses.get(0), solarEventTime, targetType);

            UpwindCloudSample upwind = null;
            if (upwindPoint != null && responses.size() > 1) {
                upwind = extractUpwindSample(responses.get(1), solarEventTime, currentTime,
                        targetType, (int) (upwindDistanceM / 1000), windFromDeg);
            }

            return new CloudApproachData(trend, upwind);
        } catch (Exception e) {
            LOG.warn("Cloud approach data fetch failed, continuing without: {}", e.getMessage());
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "cloud-approach-batch", null, 0L,
                        getStatusCode(e), null, false, e.getMessage());
            }
            return null;
        }
    }

    /**
     * Extracts an hourly visibility and dew point trend from T-3h through T+2h.
     *
     * <p>Data is sourced from the already-fetched main forecast response — no additional
     * API call required. Returns {@code null} if dew point or visibility data is absent.
     *
     * <p>Package-private for unit testing.
     *
     * @param h        the hourly forecast arrays from Open-Meteo
     * @param eventIdx the slot index corresponding to the solar event time
     * @return the trend, or {@code null} if data is insufficient
     */
    MistTrend extractMistTrend(OpenMeteoForecastResponse.Hourly h, int eventIdx) {
        List<Double> vis = h.getVisibility();
        List<Double> dew = h.getDewPoint2m();
        List<Double> temp = h.getTemperature2m();

        if (vis == null || dew == null || temp == null) {
            return null;
        }

        List<MistTrend.MistSlot> slots = new ArrayList<>();
        for (int offset = -MIST_TREND_HOURS_BACK; offset <= MIST_TREND_HOURS_FORWARD; offset++) {
            int idx = eventIdx + offset;
            if (idx >= 0 && idx < vis.size() && idx < dew.size() && idx < temp.size()) {
                Double dewVal = dew.get(idx);
                Double tempVal = temp.get(idx);
                if (dewVal != null && tempVal != null) {
                    slots.add(new MistTrend.MistSlot(
                            offset,
                            vis.get(idx).intValue(),
                            dewVal,
                            tempVal));
                }
            }
        }

        return slots.isEmpty() ? null : new MistTrend(slots);
    }

    /**
     * Extracts the solar horizon low cloud trend from T-3h through T.
     *
     * <p>Package-private for unit testing.
     *
     * @param forecast       the Open-Meteo forecast for the solar horizon point
     * @param eventTime      UTC time of the solar event
     * @param targetType     SUNRISE or SUNSET
     * @return the trend, or {@code null} if no valid slots found
     */
    SolarCloudTrend extractSolarTrend(OpenMeteoForecastResponse forecast,
            LocalDateTime eventTime, TargetType targetType) {
        List<String> times = forecast.getHourly().getTime();
        int eventIdx = findBestIndex(times, eventTime, targetType);

        List<SolarCloudTrend.SolarCloudSlot> slots = new ArrayList<>();
        List<Integer> lowCloud = forecast.getHourly().getCloudCoverLow();

        for (int h = TREND_HOURS_BACK; h >= 0; h--) {
            int idx = eventIdx - h;
            if (idx >= 0 && idx < lowCloud.size()) {
                slots.add(new SolarCloudTrend.SolarCloudSlot(h, lowCloud.get(idx)));
            }
        }

        return slots.isEmpty() ? null : new SolarCloudTrend(slots);
    }

    /**
     * Extracts low cloud at the upwind point for both current time and event time.
     *
     * <p>Package-private for unit testing.
     *
     * @param forecast        the Open-Meteo forecast for the upwind point
     * @param eventTime       UTC time of the solar event
     * @param currentTime     current UTC time
     * @param targetType      SUNRISE or SUNSET
     * @param distanceKm      distance to the upwind point in km
     * @param windFromBearing wind-from bearing in degrees
     * @return the upwind sample
     */
    UpwindCloudSample extractUpwindSample(OpenMeteoForecastResponse forecast,
            LocalDateTime eventTime, LocalDateTime currentTime, TargetType targetType,
            int distanceKm, int windFromBearing) {
        List<String> times = forecast.getHourly().getTime();
        List<Integer> lowCloud = forecast.getHourly().getCloudCoverLow();

        int eventIdx = findBestIndex(times, eventTime, targetType);
        int currentIdx = findNearestIndex(times, currentTime);

        int eventLowCloud = lowCloud.get(eventIdx);
        int currentLowCloud = lowCloud.get(currentIdx);

        return new UpwindCloudSample(distanceKm, windFromBearing,
                currentLowCloud, eventLowCloud);
    }

    /**
     * Finds the index of the time slot nearest to the target time (absolute nearest, no
     * direction preference).
     *
     * @param times      list of ISO-8601 time strings
     * @param targetTime the target time
     * @return the index of the nearest slot
     */
    private int findNearestIndex(List<String> times, LocalDateTime targetTime) {
        int bestIdx = 0;
        long minDiff = Long.MAX_VALUE;
        for (int i = 0; i < times.size(); i++) {
            long diff = Math.abs(ChronoUnit.SECONDS.between(
                    LocalDateTime.parse(times.get(i)), targetTime));
            if (diff < minDiff) {
                minDiff = diff;
                bestIdx = i;
            }
        }
        return bestIdx;
    }
}
