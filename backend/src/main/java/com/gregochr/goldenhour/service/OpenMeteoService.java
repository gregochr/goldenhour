package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.CloudData;
import com.gregochr.goldenhour.model.ComfortData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.UpwindCloudSample;
import com.gregochr.goldenhour.model.WeatherData;
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
import java.util.List;

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
            return data;
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
                getDoubleValue(h.getDewPoint2m(), idx));

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
                mistTrend,
                null); // locationOrientation — populated later by ForecastDataAugmentor
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
        // Sample 3 solar points in a cone (azimuth ± CONE_HALF_ANGLE) to smooth grid-cell effects
        int[] solarBearings = {
            solarAzimuthDeg - SOLAR_CONE_HALF_ANGLE_DEG,
            solarAzimuthDeg,
            solarAzimuthDeg + SOLAR_CONE_HALF_ANGLE_DEG
        };
        double antisolarBearing = GeoUtils.antisolarBearing(solarAzimuthDeg);
        double[] antisolarPoint = GeoUtils.offsetPoint(lat, lon, antisolarBearing,
                DIRECTIONAL_OFFSET_METRES);

        LOG.info("Directional cloud fetch: solar cone {}±{}deg, antisolar=[{},{}] ({}deg)",
                solarAzimuthDeg, SOLAR_CONE_HALF_ANGLE_DEG,
                String.format("%.3f", antisolarPoint[0]), String.format("%.3f", antisolarPoint[1]),
                (int) antisolarBearing);

        long startMs = System.currentTimeMillis();
        try {
            // Fetch cloud data at 3 solar cone points and average
            int solarLowSum = 0;
            int solarMidSum = 0;
            int solarHighSum = 0;
            for (int bearing : solarBearings) {
                double[] point = GeoUtils.offsetPoint(lat, lon, bearing,
                        DIRECTIONAL_OFFSET_METRES);
                OpenMeteoForecastResponse forecast = openMeteoClient.fetchCloudOnly(
                        point[0], point[1]);
                int idx = findBestIndex(forecast.getHourly().getTime(),
                        solarEventTime, targetType);
                OpenMeteoForecastResponse.Hourly h = forecast.getHourly();
                solarLowSum += h.getCloudCoverLow().get(idx);
                solarMidSum += h.getCloudCoverMid().get(idx);
                solarHighSum += h.getCloudCoverHigh().get(idx);
            }
            int solarLow = solarLowSum / solarBearings.length;
            int solarMid = solarMidSum / solarBearings.length;
            int solarHigh = solarHighSum / solarBearings.length;

            // Fetch antisolar (single point — less sensitive to grid boundaries)
            OpenMeteoForecastResponse antisolarForecast = openMeteoClient.fetchCloudOnly(
                    antisolarPoint[0], antisolarPoint[1]);
            int antisolarIdx = findBestIndex(antisolarForecast.getHourly().getTime(),
                    solarEventTime, targetType);
            OpenMeteoForecastResponse.Hourly ah = antisolarForecast.getHourly();

            // Fetch far solar point (226 km) for horizon strip vs blanket detection
            Integer farSolarLow = null;
            try {
                double[] farSolarPoint = GeoUtils.offsetPoint(lat, lon, solarAzimuthDeg,
                        FAR_SOLAR_OFFSET_METRES);
                OpenMeteoForecastResponse farForecast = openMeteoClient.fetchCloudOnly(
                        farSolarPoint[0], farSolarPoint[1]);
                int farIdx = findBestIndex(farForecast.getHourly().getTime(),
                        solarEventTime, targetType);
                farSolarLow = farForecast.getHourly().getCloudCoverLow().get(farIdx);
            } catch (Exception e) {
                LOG.warn("Far solar cloud fetch failed, strip detection unavailable: {}", e.getMessage());
            }

            long durationMs = System.currentTimeMillis() - startMs;
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "directional-cloud-solar-cone(3)", null, durationMs, 200,
                        null, true, null);
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "directional-cloud-antisolar", null, durationMs, 200, null,
                        true, null);
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "directional-cloud-far-solar", null, durationMs, 200, null,
                        farSolarLow != null, farSolarLow == null ? "fetch failed" : null);
            }

            DirectionalCloudData result = new DirectionalCloudData(
                    solarLow, solarMid, solarHigh,
                    ah.getCloudCoverLow().get(antisolarIdx),
                    ah.getCloudCoverMid().get(antisolarIdx),
                    ah.getCloudCoverHigh().get(antisolarIdx),
                    farSolarLow);

            LOG.info("Directional cloud -> solar(avg3): L{}% M{}% H{}%, antisolar: L{}% M{}% H{}%, "
                    + "farSolar: L{}% ({}ms)",
                    result.solarLowCloudPercent(), result.solarMidCloudPercent(),
                    result.solarHighCloudPercent(),
                    result.antisolarLowCloudPercent(), result.antisolarMidCloudPercent(),
                    result.antisolarHighCloudPercent(),
                    result.farSolarLowCloudPercent(), durationMs);

            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.warn("Directional cloud fetch failed ({}ms), falling back to layer inference: {}",
                    durationMs, e.getMessage());
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "directional-cloud-solar-cone(3)", null, durationMs,
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
            // 1. Fetch cloud at primary solar horizon point for temporal trend
            double[] solarPoint = GeoUtils.offsetPoint(lat, lon, solarAzimuthDeg,
                    DIRECTIONAL_OFFSET_METRES);
            long startMs = System.currentTimeMillis();
            OpenMeteoForecastResponse solarForecast = openMeteoClient.fetchCloudOnly(
                    solarPoint[0], solarPoint[1]);
            long durationMs = System.currentTimeMillis() - startMs;
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "cloud-approach-solar-trend", null, durationMs, 200,
                        null, true, null);
            }

            SolarCloudTrend trend = extractSolarTrend(solarForecast, solarEventTime, targetType);

            // 2. Upwind sample — skip if wind too light or event has passed
            UpwindCloudSample upwind = null;
            long secondsToEvent = Duration.between(currentTime, solarEventTime).getSeconds();
            if (secondsToEvent > 0 && windSpeedMs > 0) {
                double upwindDistanceM = Math.min(windSpeedMs * secondsToEvent, MAX_UPWIND_DISTANCE_M);
                if (upwindDistanceM >= MIN_UPWIND_DISTANCE_M) {
                    double[] upwindPoint = GeoUtils.offsetPoint(lat, lon, windFromDeg,
                            upwindDistanceM);
                    startMs = System.currentTimeMillis();
                    OpenMeteoForecastResponse upwindForecast = openMeteoClient.fetchCloudOnly(
                            upwindPoint[0], upwindPoint[1]);
                    durationMs = System.currentTimeMillis() - startMs;
                    if (jobRun != null) {
                        jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                                "GET", "cloud-approach-upwind", null, durationMs, 200,
                                null, true, null);
                    }

                    upwind = extractUpwindSample(upwindForecast, solarEventTime, currentTime,
                            targetType, (int) (upwindDistanceM / 1000), windFromDeg);
                }
            }

            return new CloudApproachData(trend, upwind);
        } catch (Exception e) {
            LOG.warn("Cloud approach data fetch failed, continuing without: {}", e.getMessage());
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "cloud-approach-data", null, 0L,
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
