package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.util.GeoUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Retrieves atmospheric forecast data from the Open-Meteo Forecast and Air Quality APIs.
 *
 * <p>Delegates HTTP calls to {@link OpenMeteoClient} (which provides declarative retry
 * via {@code @Retryable}) and extracts the values nearest to the solar event time.
 * Both APIs are free and require no API key.
 */
@Service
public class OpenMeteoService {

    private static final Logger LOG = LoggerFactory.getLogger(OpenMeteoService.class);

    /** Distance in metres to sample directional horizon cloud data (50 km). */
    static final double DIRECTIONAL_OFFSET_METRES = 50_000.0;

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

        return new AtmosphericData(
                locationName,
                solarEventTime,
                targetType,
                h.getCloudCoverLow().get(idx),
                h.getCloudCoverMid().get(idx),
                h.getCloudCoverHigh().get(idx),
                h.getVisibility().get(idx).intValue(),
                BigDecimal.valueOf(h.getWindSpeed10m().get(idx))
                        .setScale(WIND_SPEED_SCALE, RoundingMode.HALF_UP),
                h.getWindDirection10m().get(idx),
                BigDecimal.valueOf(h.getPrecipitation().get(idx))
                        .setScale(PRECIP_SCALE, RoundingMode.HALF_UP),
                h.getRelativeHumidity2m().get(idx),
                h.getWeatherCode().get(idx),
                h.getBoundaryLayerHeight().get(idx).intValue(),
                BigDecimal.valueOf(h.getShortwaveRadiation().get(idx))
                        .setScale(RADIATION_SCALE, RoundingMode.HALF_UP),
                toDecimal(pm25Raw, PRECIP_SCALE),
                toDecimal(dustRaw, PRECIP_SCALE),
                toDecimal(aodRaw, AOD_SCALE),
                getDoubleValue(h.getTemperature2m(), idx),
                getDoubleValue(h.getApparentTemperature(), idx),
                getIntegerValue(h.getPrecipitationProbability(), idx),
                null, // directionalCloud - populated by fetchDirectionalCloudData if colour location
                null, // tideState - populated by TideService if location is coastal
                null, // nextHighTideTime
                null, // nextHighTideHeightMetres
                null, // nextLowTideTime
                null, // nextLowTideHeightMetres
                null); // tideAligned
    }

    /**
     * Fetches cloud cover at the solar and antisolar horizon points (50 km offset from observer).
     *
     * <p>Makes two additional Open-Meteo calls (cloud layers only) — one toward the sun's
     * azimuth, one in the opposite direction. Returns {@code null} if either call fails,
     * so the evaluation can gracefully fall back to the single-point layer inference.
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
        double[] solarPoint = GeoUtils.offsetPoint(lat, lon, solarAzimuthDeg,
                DIRECTIONAL_OFFSET_METRES);
        double antisolarBearing = GeoUtils.antisolarBearing(solarAzimuthDeg);
        double[] antisolarPoint = GeoUtils.offsetPoint(lat, lon, antisolarBearing,
                DIRECTIONAL_OFFSET_METRES);

        LOG.info("Directional cloud fetch: solar=[{},{}] ({}deg), antisolar=[{},{}] ({}deg)",
                String.format("%.3f", solarPoint[0]), String.format("%.3f", solarPoint[1]),
                solarAzimuthDeg,
                String.format("%.3f", antisolarPoint[0]), String.format("%.3f", antisolarPoint[1]),
                (int) antisolarBearing);

        long startMs = System.currentTimeMillis();
        try {
            OpenMeteoForecastResponse solarForecast = openMeteoClient.fetchCloudOnly(
                    solarPoint[0], solarPoint[1]);
            OpenMeteoForecastResponse antisolarForecast = openMeteoClient.fetchCloudOnly(
                    antisolarPoint[0], antisolarPoint[1]);

            int solarIdx = findBestIndex(solarForecast.getHourly().getTime(),
                    solarEventTime, targetType);
            int antisolarIdx = findBestIndex(antisolarForecast.getHourly().getTime(),
                    solarEventTime, targetType);

            long durationMs = System.currentTimeMillis() - startMs;
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "directional-cloud-solar", null, durationMs, 200, null, true, null);
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "directional-cloud-antisolar", null, durationMs, 200, null,
                        true, null);
            }

            OpenMeteoForecastResponse.Hourly sh = solarForecast.getHourly();
            OpenMeteoForecastResponse.Hourly ah = antisolarForecast.getHourly();

            DirectionalCloudData result = new DirectionalCloudData(
                    sh.getCloudCoverLow().get(solarIdx),
                    sh.getCloudCoverMid().get(solarIdx),
                    sh.getCloudCoverHigh().get(solarIdx),
                    ah.getCloudCoverLow().get(antisolarIdx),
                    ah.getCloudCoverMid().get(antisolarIdx),
                    ah.getCloudCoverHigh().get(antisolarIdx));

            LOG.info("Directional cloud -> solar: L{}% M{}% H{}%, antisolar: L{}% M{}% H{}% ({}ms)",
                    result.solarLowCloudPercent(), result.solarMidCloudPercent(),
                    result.solarHighCloudPercent(),
                    result.antisolarLowCloudPercent(), result.antisolarMidCloudPercent(),
                    result.antisolarHighCloudPercent(), durationMs);

            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.warn("Directional cloud fetch failed ({}ms), falling back to layer inference: {}",
                    durationMs, e.getMessage());
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "directional-cloud-solar", null, durationMs,
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
     * <p>For sunset, selects the latest slot at or before the event time (the slot after
     * sunset has 0 radiation and is useless). For sunrise, selects the earliest slot at
     * or after the event time (the slot before sunrise is pre-dawn darkness).
     * Falls back to the absolute nearest slot if no valid slot exists on the preferred side.
     *
     * @param times      list of ISO-8601 time strings from the API response
     * @param targetTime the solar event time
     * @param targetType SUNRISE or SUNSET
     * @return the index of the best matching slot
     */
    int findBestIndex(List<String> times, LocalDateTime targetTime, TargetType targetType) {
        int bestIdx = -1;
        long bestDiff = Long.MAX_VALUE;

        for (int i = 0; i < times.size(); i++) {
            LocalDateTime slotTime = LocalDateTime.parse(times.get(i));
            long diffSeconds = ChronoUnit.SECONDS.between(slotTime, targetTime);
            // diffSeconds > 0 means slot is before targetTime; < 0 means slot is after

            boolean validSide = targetType == TargetType.SUNSET
                    ? diffSeconds >= 0   // slot at or before sunset
                    : diffSeconds <= 0;  // slot at or after sunrise

            long absDiff = Math.abs(diffSeconds);
            if (validSide && absDiff < bestDiff) {
                bestDiff = absDiff;
                bestIdx = i;
            }
        }

        // Fall back to absolute nearest if no valid slot on the preferred side
        if (bestIdx == -1) {
            bestIdx = 0;
            long minDiff = Long.MAX_VALUE;
            for (int i = 0; i < times.size(); i++) {
                long diff = Math.abs(ChronoUnit.SECONDS.between(
                        LocalDateTime.parse(times.get(i)), targetTime));
                if (diff < minDiff) {
                    minDiff = diff;
                    bestIdx = i;
                }
            }
        }

        return bestIdx;
    }
}
