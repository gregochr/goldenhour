package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.PrefetchCall;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.UpwindCloudSample;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.util.GeoUtils;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

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

    private final OpenMeteoClient openMeteoClient;
    private final JobRunService jobRunService;
    private final InversionProfileFetcher inversionProfileFetcher;

    /**
     * Constructs an {@code OpenMeteoService}.
     *
     * @param openMeteoClient resilient client for Open-Meteo API calls
     * @param jobRunService   service for recording API call metrics
     */
    public OpenMeteoService(OpenMeteoClient openMeteoClient, JobRunService jobRunService) {
        this.openMeteoClient = openMeteoClient;
        this.jobRunService = jobRunService;
        this.inversionProfileFetcher = new InversionProfileFetcher(openMeteoClient);
    }

    /**
     * Pre-fetches the v2 inversion valley profile for a run's locations (inversion-v2-plan Phase 1b), one
     * batched request per 20 distinct valley-floor points. Best effort: never throws, and a location with
     * no entry in the result has no profile.
     *
     * @param locations the run's locations; ineligible ones are ignored
     * @return location id to profile, holding only the locations that got one
     */
    public Map<Long, InversionProfile> prefetchInversionProfiles(Collection<LocationEntity> locations) {
        return inversionProfileFetcher.prefetch(locations);
    }

    /**
     * Fetches the v2 inversion valley profile for one location, for callers with no prefetch. Memoised per
     * valley-floor request for a few minutes, so repeated slots of one site cost one request. Never throws.
     *
     * @param location the location
     * @return the profile, or null when the location is ineligible or the fetch failed
     */
    public InversionProfile fetchInversionProfileOnDemand(LocationEntity location) {
        return inversionProfileFetcher.fetchOnDemand(location);
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

            AtmosphericData data = OpenMeteoResponseParser.extractAtmosphericData(forecast, airQuality,
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
     * Resilient batch prefetch for use by the overnight batch.
     *
     * <p>Uses briefing-grade chunk retry and isolation so that a single timed-out chunk
     * does not abort the entire pre-fetch. Returns partial results — callers must handle
     * locations missing from the returned map (typically by skipping them during triage).
     *
     * <p>Every Open-Meteo call is reported to {@code sink} as a {@link PrefetchCall}. A call that
     * fails before the client method body runs (an open circuit breaker, a rate-limiter
     * rejection) leaves no chunk row, so one call-level failure row is recorded for it.
     *
     * @param coords list of unique [lat, lon] pairs
     * @param sink   receives the Open-Meteo calls made
     * @return map from coordinate key ("lat,lon") to the paired responses; may be smaller
     *         than the input if some chunks failed
     */
    public Map<String, WeatherExtractionResult> prefetchWeatherBatchResilient(
            List<double[]> coords, Consumer<PrefetchCall> sink) {
        if (coords.isEmpty()) {
            LOG.info("Open-Meteo resilient batch prefetch: 0 locations — skipping");
            return Map.of();
        }
        LOG.info("Open-Meteo resilient batch prefetch: {} unique locations", coords.size());
        long startMs = System.currentTimeMillis();

        List<OpenMeteoForecastResponse> forecasts;
        List<OpenMeteoAirQualityResponse> airQualities;

        Set<ServiceName> recorded = EnumSet.noneOf(ServiceName.class);
        Consumer<PrefetchCall> tracking = call -> {
            recorded.add(call.service());
            sink.accept(call);
        };

        LocalDateTime forecastCalledAt = LocalDateTime.now(ZoneOffset.UTC);
        try {
            forecasts = openMeteoClient.fetchForecastBriefingBatch(coords, tracking);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.error("Open-Meteo resilient batch: forecast call failed entirely ({}ms): {}",
                    durationMs, e.getMessage());
            recordCallLevelFailure(sink, recorded, ServiceName.OPEN_METEO_FORECAST,
                    "batch-prefetch forecast", coords.size(), forecastCalledAt, durationMs, e);
            return Map.of();
        }

        long airStartMs = System.currentTimeMillis();
        LocalDateTime airCalledAt = LocalDateTime.now(ZoneOffset.UTC);
        try {
            airQualities = openMeteoClient.fetchAirQualityBatchResilient(coords, tracking);
        } catch (Exception e) {
            recordCallLevelFailure(sink, recorded, ServiceName.OPEN_METEO_AIR_QUALITY,
                    "batch-prefetch air-quality", coords.size(), airCalledAt,
                    System.currentTimeMillis() - airStartMs, e);
            LOG.warn("Open-Meteo resilient batch: air quality call failed — continuing "
                    + "without AQ data: {}", e.getMessage());
            airQualities = new ArrayList<>(java.util.Collections.nCopies(coords.size(), null));
        }

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
        LOG.info("Open-Meteo resilient batch prefetch: {}/{} locations succeeded in {}ms "
                        + "({} missing due to chunk failure)",
                cache.size(), coords.size(), durationMs, nullCount);

        return cache;
    }

    /**
     * Records one call-level failure row for a service whose client call threw without the client
     * recording any chunk (a circuit-breaker or rate-limiter rejection, which fail before the method
     * body runs). Does nothing when the client already recorded a row for that service. Never throws.
     */
    private void recordCallLevelFailure(Consumer<PrefetchCall> sink, Set<ServiceName> recorded,
            ServiceName service, String label, int requested, LocalDateTime calledAt,
            long durationMs, Exception e) {
        if (recorded.contains(service)) {
            return;
        }
        try {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            sink.accept(new PrefetchCall(service, label, requested, 0, calledAt, durationMs,
                    callStatusCode(e, message), false, message, 1));
        } catch (RuntimeException sinkFailure) {
            LOG.warn("Open-Meteo pre-fetch call could not be recorded: {}", sinkFailure.getMessage());
        }
    }

    private Integer callStatusCode(Exception e, String message) {
        Integer status = getStatusCode(e);
        if (status == null && message.contains("429")) {
            return 429;
        }
        return status;
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
        AtmosphericData data = OpenMeteoResponseParser.extractAtmosphericData(cached.forecastResponse(),
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
        return DirectionalSamplingGeometry.computeDirectionalCloudPoints(lat, lon, solarAzimuthDeg);
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
        return DirectionalSamplingGeometry.computeUpwindPoint(lat, lon, windFromDeg, windSpeedMs,
                currentTime, eventTime);
    }

    /**
     * Batch pre-fetches cloud-only data for a set of sampling points.
     *
     * <p>Deduplicates by Open-Meteo's ~0.1° grid resolution and makes a single batch
     * API call. Returns a {@link CloudPointCache} for lookup during augmentation.
     *
     * <p>Makes no pressure-level call; see
     * {@link #prefetchCloudBatch(List, List, Consumer)}.
     *
     * @param allCoords raw [lat, lon] pairs (may contain duplicates)
     * @param sink      receives the call made, or {@code null} to record nothing
     * @return a cache of cloud-only responses keyed by grid cell
     */
    public CloudPointCache prefetchCloudBatch(List<double[]> allCoords, Consumer<PrefetchCall> sink) {
        return prefetchCloudBatch(allCoords, List.of(), sink);
    }

    /**
     * Batch pre-fetches cloud-only data, then pressure-level cloud at the far points.
     *
     * <p>The cloud fetch is exactly that of the two-argument method, including its failure
     * behaviour. When it succeeds, the far points' pressure levels (850 to 200 hPa) are then
     * requested in a separate best-effort call, deduplicated by grid cell: any exception or null
     * response means no levels for that cell, and can never affect the cloud map. When the cloud
     * fetch fails, no pressure-level request is made and the empty cache is returned.
     *
     * @param allCoords raw [lat, lon] pairs (may contain duplicates)
     * @param farCoords the far-solar [lat, lon] points (may contain duplicates, may be empty)
     * @param sink      receives one call-level row for the cloud fetch and one for the pressure-level
     *                  fetch, or {@code null} to record nothing
     * @return a cache of cloud-only responses keyed by grid cell, with pressure levels attached
     */
    public CloudPointCache prefetchCloudBatch(List<double[]> allCoords, List<double[]> farCoords,
            Consumer<PrefetchCall> sink) {
        Consumer<PrefetchCall> out = sink != null ? sink : call -> { };
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
        LocalDateTime calledAt = LocalDateTime.now(ZoneOffset.UTC);
        String label = "batch-prefetch cloud points (" + coords.size() + ")";
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
            // fetchForecastBatchInternal swallows chunk failures, so a "successful" return can be
            // partial: the row says so rather than claiming a clean 200.
            boolean complete = cacheMap.size() == coords.size();
            recordSafely(out, new PrefetchCall(ServiceName.OPEN_METEO_FORECAST, label, coords.size(),
                    cacheMap.size(), calledAt, durationMs, complete ? 200 : null, complete,
                    complete ? null : "cloud: " + cacheMap.size() + "/" + coords.size()
                            + " points returned", 1));
            return new CloudPointCache(cacheMap, prefetchPressureLevels(farCoords, out));
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.warn("Cloud batch prefetch failed ({}ms), directional cloud will be unavailable: {}",
                    durationMs, e.getMessage());
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            recordSafely(out, new PrefetchCall(ServiceName.OPEN_METEO_FORECAST, label, coords.size(),
                    0, calledAt, durationMs, callStatusCode(e, message), false, message, 1));
            return new CloudPointCache(Map.of());
        }
    }

    /**
     * Best-effort pressure-level cloud fetch for the far points. Never throws.
     *
     * @param farCoords far-solar [lat, lon] points (may be null or empty)
     * @param sink      receives one call-level row
     * @return grid key to pressure-level response; empty on any failure
     */
    private Map<String, OpenMeteoForecastResponse> prefetchPressureLevels(List<double[]> farCoords,
            Consumer<PrefetchCall> sink) {
        if (farCoords == null || farCoords.isEmpty()) {
            return Map.of();
        }
        String endpoint = "batch-prefetch pressure levels (" + farCoords.size() + ")";
        long startMs = System.currentTimeMillis();
        LocalDateTime calledAt = LocalDateTime.now(ZoneOffset.UTC);
        int requested = farCoords.size();
        try {
            Map<String, double[]> uniqueByGrid = new LinkedHashMap<>();
            for (double[] coord : farCoords) {
                uniqueByGrid.putIfAbsent(CloudPointCache.gridKey(coord[0], coord[1]), coord);
            }
            List<String> gridKeys = new ArrayList<>(uniqueByGrid.keySet());
            List<double[]> coords = new ArrayList<>(uniqueByGrid.values());
            endpoint = "batch-prefetch pressure levels (" + coords.size() + ")";
            requested = coords.size();
            List<OpenMeteoForecastResponse> responses =
                    openMeteoClient.fetchPressureLevelCloudBatch(coords);
            Map<String, OpenMeteoForecastResponse> levels = new LinkedHashMap<>();
            for (int i = 0; i < gridKeys.size(); i++) {
                if (responses.get(i) != null) {
                    levels.put(gridKeys.get(i), responses.get(i));
                }
            }
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.info("Pressure-level cloud prefetch: {}/{} far cells populated in {}ms",
                    levels.size(), coords.size(), durationMs);
            boolean complete = levels.size() == coords.size();
            recordSafely(sink, new PrefetchCall(ServiceName.OPEN_METEO_FORECAST, endpoint,
                    coords.size(), levels.size(), calledAt, durationMs, complete ? 200 : null,
                    complete, complete ? null : "pressure levels: " + levels.size() + "/"
                            + coords.size() + " cells returned", 1));
            return levels;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.warn("Pressure-level cloud prefetch failed ({}ms), no levels recorded: {}",
                    durationMs, e.getMessage());
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            recordSafely(sink, new PrefetchCall(ServiceName.OPEN_METEO_FORECAST, endpoint, requested,
                    0, calledAt, durationMs, callStatusCode(e, message), false, message, 1));
            return Map.of();
        }
    }

    private void logPressureLevelCall(JobRunEntity jobRun, String endpoint, long durationMs,
            Integer status, boolean succeeded, String error) {
        if (jobRun != null) {
            jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                    "GET", endpoint, null, durationMs, status, null, succeeded, error);
        }
    }

    /** Hands a call to the sink; a faulty sink must never change what the pre-fetch returns. */
    private void recordSafely(Consumer<PrefetchCall> sink, PrefetchCall call) {
        try {
            sink.accept(call);
        } catch (RuntimeException sinkFailure) {
            LOG.warn("Open-Meteo pre-fetch call could not be recorded: {}", sinkFailure.getMessage());
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
        return CloudPointCacheReader.fetchDirectionalCloudDataFromCache(lat, lon, solarAzimuthDeg,
                solarEventTime, targetType, cloudCache);
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
        return CloudPointCacheReader.fetchCloudApproachDataFromCache(lat, lon, solarAzimuthDeg,
                solarEventTime, currentTime, targetType, windFromDeg, windSpeedMs, cloudCache);
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
                slots.add(OpenMeteoResponseParser.extractAtmosphericData(forecast, airQuality,
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
     * Fetches cloud cover at the solar and antisolar horizon points (113 km from observer),
     * plus a far-field solar sample at 226 km for horizon cloud structure detection.
     *
     * <p>Makes up to five additional Open-Meteo calls (cloud layers only): three for the
     * solar cone, one for the antisolar point, and one for the far solar point, plus one
     * separate best-effort call for the far point's eight pressure-level cloud values (850 to
     * 200 hPa, recorded, never prompted). That call's failure leaves only those eight
     * {@code null}. Returns
     * {@code null} if the core solar/antisolar fetches fail; the far fetch failing leaves
     * every far field (low, mid, high and the dark-hour trio) {@code null} and nothing else, not the
     * whole result.
     *
     * <p>From the same far-point response it also records, never prompted, the far mid and high
     * at the event's slot and the far low, mid and high at the dark-side hour (see
     * {@link TimeSlotUtils#darkSideIndex}), each read null-safely so a malformed series nulls
     * only that value.
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
        // Build batch: [cone0, cone1, cone2, antisolar, far-solar] via the shared geometry.
        List<double[]> coords =
                DirectionalSamplingGeometry.computeDirectionalCloudPoints(lat, lon, solarAzimuthDeg);

        LOG.info("Directional cloud batch fetch: 5 points (solar cone {}±{}deg, antisolar, far-solar)",
                solarAzimuthDeg, DirectionalSamplingGeometry.SOLAR_CONE_HALF_ANGLE_DEG);

        long startMs = System.currentTimeMillis();
        try {
            List<OpenMeteoForecastResponse> responses = openMeteoClient.fetchCloudOnlyBatch(coords);

            // Extract cloud data from batch response
            int solarLowSum = 0;
            int solarMidSum = 0;
            int solarHighSum = 0;
            for (int i = 0; i < DirectionalSamplingGeometry.SOLAR_CONE_POINT_COUNT; i++) {
                OpenMeteoForecastResponse f = responses.get(i);
                int idx = TimeSlotUtils.findBestIndex(f.getHourly().getTime(), solarEventTime, targetType);
                OpenMeteoForecastResponse.Hourly h = f.getHourly();
                solarLowSum += h.getCloudCoverLow().get(idx);
                solarMidSum += h.getCloudCoverMid().get(idx);
                solarHighSum += h.getCloudCoverHigh().get(idx);
            }
            int solarLow = solarLowSum / DirectionalSamplingGeometry.SOLAR_CONE_POINT_COUNT;
            int solarMid = solarMidSum / DirectionalSamplingGeometry.SOLAR_CONE_POINT_COUNT;
            int solarHigh = solarHighSum / DirectionalSamplingGeometry.SOLAR_CONE_POINT_COUNT;

            // Antisolar (index 3)
            OpenMeteoForecastResponse antisolarForecast =
                    responses.get(DirectionalSamplingGeometry.ANTISOLAR_POINT_INDEX);
            int antisolarIdx = TimeSlotUtils.findBestIndex(antisolarForecast.getHourly().getTime(),
                    solarEventTime, targetType);
            OpenMeteoForecastResponse.Hourly ah = antisolarForecast.getHourly();

            // Far solar (index 4) — treat parse failures gracefully
            Integer farSolarLow = null;
            Integer farSolarMid = null;
            Integer farSolarHigh = null;
            Integer farLowDark = null;
            Integer farMidDark = null;
            Integer farHighDark = null;
            try {
                OpenMeteoForecastResponse farForecast =
                        responses.get(DirectionalSamplingGeometry.FAR_SOLAR_POINT_INDEX);
                OpenMeteoForecastResponse.Hourly fh = farForecast.getHourly();
                int farIdx = TimeSlotUtils.findBestIndex(fh.getTime(), solarEventTime, targetType);
                farSolarLow = fh.getCloudCoverLow().get(farIdx);
                // Recorded, never prompted: null-safe reads that cannot throw.
                int darkIdx = TimeSlotUtils.darkSideIndex(farIdx, targetType);
                farSolarMid = OpenMeteoResponseParser.integerAt(fh.getCloudCoverMid(), farIdx);
                farSolarHigh = OpenMeteoResponseParser.integerAt(fh.getCloudCoverHigh(), farIdx);
                farLowDark = OpenMeteoResponseParser.integerAt(fh.getCloudCoverLow(), darkIdx);
                farMidDark = OpenMeteoResponseParser.integerAt(fh.getCloudCoverMid(), darkIdx);
                farHighDark = OpenMeteoResponseParser.integerAt(fh.getCloudCoverHigh(), darkIdx);
            } catch (Exception e) {
                LOG.warn("Far solar extraction failed, strip detection unavailable: {}", e.getMessage());
            }

            long durationMs = System.currentTimeMillis() - startMs;
            if (jobRun != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.OPEN_METEO_FORECAST,
                        "GET", "directional-cloud-batch(5)", null, durationMs, 200,
                        null, true, null);
            }

            // Pressure-level cloud at the far point: one separate best-effort call, never fatal.
            Integer[] levels = fetchFarPressureLevels(
                    DirectionalSamplingGeometry.computeDirectionalCloudPoints(lat, lon, solarAzimuthDeg)
                            .get(DirectionalSamplingGeometry.FAR_SOLAR_POINT_INDEX),
                    solarEventTime, targetType, jobRun);

            DirectionalCloudData result = new DirectionalCloudData(
                    solarLow, solarMid, solarHigh,
                    ah.getCloudCoverLow().get(antisolarIdx),
                    ah.getCloudCoverMid().get(antisolarIdx),
                    ah.getCloudCoverHigh().get(antisolarIdx),
                    farSolarLow, farSolarMid, farSolarHigh, farLowDark, farMidDark, farHighDark,
                    levels[0], levels[1], levels[2], levels[3], levels[4], levels[5],
                    levels[6], levels[7]);

            LOG.info("Directional cloud -> solar(avg3): L{}% M{}% H{}%, antisolar: L{}% M{}% H{}%, "
                    + "farSolar: L{}% M{}% H{}%, farSolar dark hour: L{}% M{}% H{}%, "
                    + "farSolar levels 850/700/600/500/400/300/250/200 hPa: {}/{}/{}/{}/{}/{}/{}/{}% "
                    + "({}ms for the cloud calls, excluding the separate pressure-level call)",
                    result.solarLowCloudPercent(), result.solarMidCloudPercent(),
                    result.solarHighCloudPercent(),
                    result.antisolarLowCloudPercent(), result.antisolarMidCloudPercent(),
                    result.antisolarHighCloudPercent(),
                    result.farSolarLowCloudPercent(), result.farSolarMidCloudPercent(),
                    result.farSolarHighCloudPercent(),
                    result.farSolarLowCloudPercentDarkHour(),
                    result.farSolarMidCloudPercentDarkHour(),
                    result.farSolarHighCloudPercentDarkHour(),
                    result.farSolarCloud850hPa(), result.farSolarCloud700hPa(),
                    result.farSolarCloud600hPa(), result.farSolarCloud500hPa(),
                    result.farSolarCloud400hPa(), result.farSolarCloud300hPa(),
                    result.farSolarCloud250hPa(), result.farSolarCloud200hPa(), durationMs);

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

    /**
     * Best-effort pressure-level cloud fetch for the single far point. Never throws: any exception
     * or null response yields eight nulls.
     */
    private Integer[] fetchFarPressureLevels(double[] farPoint, LocalDateTime solarEventTime,
            TargetType targetType, JobRunEntity jobRun) {
        String endpoint = "pressure-level-cloud(1)";
        long startMs = System.currentTimeMillis();
        try {
            List<OpenMeteoForecastResponse> responses =
                    openMeteoClient.fetchPressureLevelCloudBatch(List.of(farPoint));
            long durationMs = System.currentTimeMillis() - startMs;
            Integer[] levels = responses == null || responses.isEmpty()
                    ? new Integer[CloudPointCacheReader.PRESSURE_LEVEL_COUNT]
                    : CloudPointCacheReader.readPressureLevels(responses.get(0), solarEventTime,
                            targetType);
            boolean gotCell = responses != null && !responses.isEmpty() && responses.get(0) != null;
            try {
                if (responses == null || responses.isEmpty()) {
                    // An empty answer carries no levels: record it as a failed call.
                    logPressureLevelCall(jobRun, endpoint, durationMs, 200, false,
                            "empty pressure-level response");
                } else if (gotCell) {
                    logPressureLevelCall(jobRun, endpoint, durationMs, 200, true, null);
                } else {
                    logPressureLevelCall(jobRun, endpoint, durationMs, 200, false,
                            "no pressure-level data");
                }
            } catch (Exception logFailure) {
                LOG.warn("Pressure-level cloud call could not be logged: {}", logFailure.getMessage());
            }
            return levels;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.warn("Pressure-level cloud fetch failed ({}ms), no levels recorded: {}",
                    durationMs, e.getMessage());
            try {
                logPressureLevelCall(jobRun, endpoint, durationMs, getStatusCode(e), false,
                        e.getMessage());
            } catch (Exception logFailure) {
                LOG.warn("Pressure-level cloud call could not be logged: {}", logFailure.getMessage());
            }
            return new Integer[CloudPointCacheReader.PRESSURE_LEVEL_COUNT];
        }
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
            // Sample the trend across the whole solar cone rather than the centre bearing alone —
            // isBuilding() feeds an absolute rating ceiling, so it must not turn on one grid cell.
            List<double[]> conePoints =
                    DirectionalSamplingGeometry.computeSolarConePoints(lat, lon, solarAzimuthDeg);

            // Determine if we need an upwind sample
            double[] upwindPoint = null;
            double upwindDistanceM = 0;
            long secondsToEvent = Duration.between(currentTime, solarEventTime).getSeconds();
            if (secondsToEvent > 0 && windSpeedMs > 0) {
                upwindDistanceM = Math.min(windSpeedMs * secondsToEvent,
                        DirectionalSamplingGeometry.MAX_UPWIND_DISTANCE_M);
                if (upwindDistanceM >= DirectionalSamplingGeometry.MIN_UPWIND_DISTANCE_M) {
                    upwindPoint = GeoUtils.offsetPoint(lat, lon, windFromDeg, upwindDistanceM);
                }
            }

            // Batch fetch: solar cone points + optional upwind point
            List<double[]> coords = new ArrayList<>(conePoints);
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

            List<SolarCloudTrend> coneTrends = new ArrayList<>(conePoints.size());
            for (int i = 0; i < conePoints.size() && i < responses.size(); i++) {
                coneTrends.add(OpenMeteoResponseParser.extractSolarTrend(
                        responses.get(i), solarEventTime, targetType));
            }
            SolarCloudTrend trend = SolarCloudTrend.average(coneTrends);
            if (trend == null) {
                return null;
            }

            UpwindCloudSample upwind = null;
            int upwindIndex = conePoints.size();
            if (upwindPoint != null && responses.size() > upwindIndex) {
                upwind = OpenMeteoResponseParser.extractUpwindSample(responses.get(upwindIndex),
                        solarEventTime, currentTime,
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

}
