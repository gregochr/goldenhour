package com.gregochr.goldenhour.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.OvationReading;
import com.gregochr.goldenhour.model.SpaceWeatherAlert;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.SolarWindReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches and caches real-time space weather data from NOAA SWPC public endpoints.
 *
 * <p>All NOAA SWPC endpoints are US public domain — no API key required.
 * Each endpoint is cached independently at the appropriate TTL:
 * <ul>
 *   <li>Kp index and forecast — 15 minutes (3-hourly measurement cadence)</li>
 *   <li>OVATION aurora probability — 5 minutes (~7-minute nowcast cycle)</li>
 *   <li>Solar wind (mag + plasma) — 1 minute (1-minute satellite cadence)</li>
 *   <li>Space weather alerts — 5 minutes</li>
 * </ul>
 *
 * <p>All fetch methods fail-open: on network error or parse failure the previous
 * cached value is returned, or an empty collection if no cache exists yet.
 */
@Component
public class NoaaSwpcClient {

    private static final Logger LOG = LoggerFactory.getLogger(NoaaSwpcClient.class);

    /**
     * Target latitude for OVATION aurora probability lookups.
     * 55°N ≈ northern England / Scottish border — representative UK aurora-visible latitude.
     */
    static final double OVATION_TARGET_LAT = 55.0;

    private static final int KP_TTL_MINUTES = 15;
    private static final int OVATION_TTL_MINUTES = 5;
    private static final int SOLAR_WIND_TTL_MINUTES = 1;
    private static final int ALERTS_TTL_MINUTES = 5;

    /** NOAA sentinel value meaning "no data" in numeric fields. */
    private static final String NOAA_NULL_SENTINEL = "-9999.9";

    private final RestClient restClient;
    private final AuroraProperties properties;
    private final ObjectMapper mapper;

    private volatile CachedResult<List<KpReading>> cachedKp;
    private volatile CachedResult<List<KpForecast>> cachedKpForecast;
    private volatile CachedResult<OvationReading> cachedOvation;
    private volatile CachedResult<List<SolarWindReading>> cachedSolarWind;
    private volatile CachedResult<List<SpaceWeatherAlert>> cachedAlerts;

    /**
     * Constructs the client with the shared HTTP client, aurora configuration, and JSON mapper.
     *
     * @param restClient shared HTTP client
     * @param properties aurora configuration (NOAA endpoint URLs)
     * @param mapper     Jackson object mapper
     */
    public NoaaSwpcClient(RestClient restClient, AuroraProperties properties, ObjectMapper mapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.mapper = mapper;
    }

    /**
     * Returns an aggregate of all NOAA SWPC data, using per-endpoint caches.
     *
     * @return aggregated space weather data
     */
    public SpaceWeatherData fetchAll() {
        return new SpaceWeatherData(
                fetchKp(),
                fetchKpForecast(),
                fetchOvation(),
                fetchSolarWind(),
                fetchAlerts());
    }

    /**
     * Returns the recent Kp readings (3-hourly cadence), cached for 15 minutes.
     *
     * @return list of Kp readings (oldest first), or empty on first-call failure
     */
    public List<KpReading> fetchKp() {
        CachedResult<List<KpReading>> cache = cachedKp;
        if (isFresh(cache, KP_TTL_MINUTES)) {
            return cache.data();
        }
        try {
            String json = restClient.get()
                    .uri(properties.getNoaa().getKpUrl())
                    .retrieve()
                    .body(String.class);
            List<KpReading> readings = parseKpReadings(json);
            cachedKp = new CachedResult<>(readings);
            return readings;
        } catch (Exception e) {
            LOG.warn("NOAA Kp fetch failed (retaining cached): {}", e.getMessage());
            return cache != null ? cache.data() : List.of();
        }
    }

    /**
     * Returns the 3-day Kp forecast (3-hourly windows), cached for 15 minutes.
     *
     * @return list of Kp forecast windows (oldest first), or empty on first-call failure
     */
    public List<KpForecast> fetchKpForecast() {
        CachedResult<List<KpForecast>> cache = cachedKpForecast;
        if (isFresh(cache, KP_TTL_MINUTES)) {
            return cache.data();
        }
        try {
            String json = restClient.get()
                    .uri(properties.getNoaa().getKpForecastUrl())
                    .retrieve()
                    .body(String.class);
            List<KpForecast> forecasts = parseKpForecasts(json);
            cachedKpForecast = new CachedResult<>(forecasts);
            return forecasts;
        } catch (Exception e) {
            LOG.warn("NOAA Kp forecast fetch failed (retaining cached): {}", e.getMessage());
            return cache != null ? cache.data() : List.of();
        }
    }

    /**
     * Returns the OVATION aurora probability nowcast at UK latitude (~55°N), cached for 5 minutes.
     *
     * <p>The OVATION grid (~900 KB) covers the full globe at 1° resolution. This method
     * averages the aurora probability across all longitudes at the target latitude band.
     *
     * @return aurora probability reading, or {@code null} on first-call failure
     */
    public OvationReading fetchOvation() {
        CachedResult<OvationReading> cache = cachedOvation;
        if (isFresh(cache, OVATION_TTL_MINUTES)) {
            return cache.data();
        }
        try {
            String json = restClient.get()
                    .uri(properties.getNoaa().getOvationUrl())
                    .retrieve()
                    .body(String.class);
            OvationReading reading = parseOvation(json, OVATION_TARGET_LAT);
            cachedOvation = new CachedResult<>(reading);
            return reading;
        } catch (Exception e) {
            LOG.warn("NOAA OVATION fetch failed (retaining cached): {}", e.getMessage());
            return cache != null ? cache.data() : null;
        }
    }

    /**
     * Returns recent solar wind readings (1-minute cadence), cached for 1 minute.
     *
     * <p>Combines magnetic field (Bz) from {@code mag-1-day.json} and plasma data
     * (speed, density) from {@code plasma-1-day.json}, merging rows by timestamp.
     * Returns up to 60 readings (the most recent hour of data).
     *
     * @return list of solar wind readings (oldest first), or empty on first-call failure
     */
    public List<SolarWindReading> fetchSolarWind() {
        CachedResult<List<SolarWindReading>> cache = cachedSolarWind;
        if (isFresh(cache, SOLAR_WIND_TTL_MINUTES)) {
            return cache.data();
        }
        try {
            String magJson = restClient.get()
                    .uri(properties.getNoaa().getSolarWindUrl())
                    .retrieve()
                    .body(String.class);
            String plasmaJson = restClient.get()
                    .uri(properties.getNoaa().getSolarWindPlasmaUrl())
                    .retrieve()
                    .body(String.class);
            List<SolarWindReading> readings = parseSolarWind(magJson, plasmaJson);
            cachedSolarWind = new CachedResult<>(readings);
            return readings;
        } catch (Exception e) {
            LOG.warn("NOAA solar wind fetch failed (retaining cached): {}", e.getMessage());
            return cache != null ? cache.data() : List.of();
        }
    }

    /**
     * Returns active NOAA space weather alerts, cached for 5 minutes.
     *
     * @return list of active alerts, or empty on first-call failure
     */
    public List<SpaceWeatherAlert> fetchAlerts() {
        CachedResult<List<SpaceWeatherAlert>> cache = cachedAlerts;
        if (isFresh(cache, ALERTS_TTL_MINUTES)) {
            return cache.data();
        }
        try {
            String json = restClient.get()
                    .uri(properties.getNoaa().getAlertsUrl())
                    .retrieve()
                    .body(String.class);
            List<SpaceWeatherAlert> alerts = parseAlerts(json);
            cachedAlerts = new CachedResult<>(alerts);
            return alerts;
        } catch (Exception e) {
            LOG.warn("NOAA alerts fetch failed (retaining cached): {}", e.getMessage());
            return cache != null ? cache.data() : List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Parsing — package-visible for unit testing
    // -------------------------------------------------------------------------

    /**
     * Parses the NOAA Kp index JSON (array-of-arrays, first row is headers).
     *
     * <p>Row format: {@code ["time_tag", "Kp", "Kp_fraction", "Kp_int"]}
     *
     * @param json raw JSON from NOAA
     * @return list of Kp readings (oldest first)
     * @throws Exception if JSON parsing fails
     */
    List<KpReading> parseKpReadings(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        List<KpReading> result = new ArrayList<>();
        boolean isHeader = true;
        for (JsonNode row : root) {
            if (isHeader) {
                isHeader = false;
                continue;
            }
            if (row.size() < 2) {
                continue;
            }
            String timeTag = row.get(0).asText();
            double kp = parseDouble(row.get(1));
            if (!Double.isNaN(kp)) {
                parseUtcDateTime(timeTag).ifPresent(ts -> result.add(new KpReading(ts, kp)));
            }
        }
        return result;
    }

    /**
     * Parses the NOAA Kp 3-day forecast JSON (array-of-arrays, first row is headers).
     *
     * <p>Row format: {@code ["time_tag", "Kp", "observed"|"predicted", "noaa_scale"]}
     * Each row is a 3-hour window starting at {@code time_tag}.
     *
     * @param json raw JSON from NOAA
     * @return list of Kp forecast windows (oldest first)
     * @throws Exception if JSON parsing fails
     */
    List<KpForecast> parseKpForecasts(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        List<KpForecast> result = new ArrayList<>();
        boolean isHeader = true;
        for (JsonNode row : root) {
            if (isHeader) {
                isHeader = false;
                continue;
            }
            if (row.size() < 2) {
                continue;
            }
            String timeTag = row.get(0).asText();
            double kp = parseDouble(row.get(1));
            if (!Double.isNaN(kp)) {
                parseUtcDateTime(timeTag)
                        .ifPresent(from -> result.add(new KpForecast(from, from.plusHours(3), kp)));
            }
        }
        return result;
    }

    /**
     * Parses the OVATION aurora probability JSON and returns the average probability at
     * {@code targetLat} across all longitudes.
     *
     * <p>Coordinate format: {@code [longitude, latitude, aurora_probability_0_to_100]}
     *
     * @param json      raw JSON from NOAA
     * @param targetLat target latitude (degrees N) to look up
     * @return aurora probability reading
     * @throws Exception if JSON parsing fails
     */
    OvationReading parseOvation(String json, double targetLat) throws Exception {
        JsonNode root = mapper.readTree(json);

        ZonedDateTime forecastTime = parseUtcDateTime(root.path("Forecast Time").asText())
                .orElseGet(() -> ZonedDateTime.now(ZoneOffset.UTC));

        int targetLatInt = (int) Math.round(targetLat);
        double total = 0;
        int count = 0;
        JsonNode coords = root.path("coordinates");
        for (JsonNode coord : coords) {
            if (coord.size() < 3) {
                continue;
            }
            if (coord.get(1).asInt() == targetLatInt) {
                total += coord.get(2).asDouble();
                count++;
            }
        }
        double probability = count > 0 ? total / count : 0.0;
        return new OvationReading(forecastTime, probability, targetLat);
    }

    /**
     * Parses and merges magnetic field and plasma solar wind data.
     *
     * <p>Mag format: {@code ["time_tag", "bx_gsm", "by_gsm", "bz_gsm", "lon_gsm", "lat_gsm", "bt"]}
     * Plasma format: {@code ["time_tag", "density", "speed", "temperature"]}
     *
     * <p>Rows are merged by exact timestamp match. Returns the most recent 60 readings.
     *
     * @param magJson    raw JSON from the NOAA mag-1-day endpoint
     * @param plasmaJson raw JSON from the NOAA plasma-1-day endpoint
     * @return list of solar wind readings (oldest first, at most 60 entries)
     * @throws Exception if JSON parsing fails
     */
    List<SolarWindReading> parseSolarWind(String magJson, String plasmaJson) throws Exception {
        // Parse mag: time -> bz
        Map<String, Double> bzByTime = new HashMap<>();
        JsonNode magRoot = mapper.readTree(magJson);
        boolean isHeader = true;
        for (JsonNode row : magRoot) {
            if (isHeader) {
                isHeader = false;
                continue;
            }
            if (row.size() < 4) {
                continue;
            }
            String timeTag = row.get(0).asText();
            double bz = parseDouble(row.get(3));
            if (!Double.isNaN(bz)) {
                bzByTime.put(timeTag, bz);
            }
        }

        // Parse plasma: time -> [speed, density]
        Map<String, double[]> plasmaByTime = new HashMap<>();
        JsonNode plasmaRoot = mapper.readTree(plasmaJson);
        isHeader = true;
        for (JsonNode row : plasmaRoot) {
            if (isHeader) {
                isHeader = false;
                continue;
            }
            if (row.size() < 3) {
                continue;
            }
            String timeTag = row.get(0).asText();
            double density = parseDouble(row.get(1));
            double speed = parseDouble(row.get(2));
            if (!Double.isNaN(density) || !Double.isNaN(speed)) {
                plasmaByTime.put(timeTag, new double[]{
                    Double.isNaN(speed) ? 0.0 : speed,
                    Double.isNaN(density) ? 0.0 : density
                });
            }
        }

        // Merge by timestamp — emit a reading only when we have at least bz
        List<SolarWindReading> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : bzByTime.entrySet()) {
            String timeTag = entry.getKey();
            double bz = entry.getValue();
            double[] plasma = plasmaByTime.getOrDefault(timeTag, new double[]{0.0, 0.0});
            parseUtcDateTime(timeTag).ifPresent(ts ->
                    result.add(new SolarWindReading(ts, bz, plasma[0], plasma[1])));
        }

        // Sort oldest first, keep most recent 60 (1 hour at 1-minute cadence)
        result.sort((a, b) -> a.timestamp().compareTo(b.timestamp()));
        int from = Math.max(0, result.size() - 60);
        return result.subList(from, result.size());
    }

    /**
     * Parses the NOAA active alerts JSON (array of alert objects).
     *
     * @param json raw JSON from NOAA
     * @return list of active space weather alerts
     * @throws Exception if JSON parsing fails
     */
    List<SpaceWeatherAlert> parseAlerts(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        List<SpaceWeatherAlert> result = new ArrayList<>();
        for (JsonNode alert : root) {
            String type = alert.path("message_type").asText();
            String id = alert.path("message_id").asText();
            String issuedStr = alert.path("issue_datetime").asText();
            String message = alert.path("message").asText();
            ZonedDateTime issued = parseUtcDateTime(issuedStr)
                    .orElseGet(() -> ZonedDateTime.now(ZoneOffset.UTC));
            result.add(new SpaceWeatherAlert(type, id, issued, message));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isFresh(CachedResult<?> cache, int ttlMinutes) {
        return cache != null
                && ZonedDateTime.now(ZoneOffset.UTC)
                        .isBefore(cache.fetchedAt().plusMinutes(ttlMinutes));
    }

    /**
     * Parses a double from a JSON node, returning {@link Double#NaN} for null/missing/sentinel values.
     *
     * @param node the JSON node
     * @return parsed double or NaN
     */
    double parseDouble(JsonNode node) {
        if (node == null || node.isNull()) {
            return Double.NaN;
        }
        String text = node.asText();
        if (text.isBlank() || NOAA_NULL_SENTINEL.equals(text) || "null".equalsIgnoreCase(text)
                || "nan".equalsIgnoreCase(text)) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Parses a NOAA datetime string to a UTC {@link ZonedDateTime}.
     *
     * <p>Handles the formats NOAA uses:
     * <ul>
     *   <li>{@code "2025-08-01 12:00:00.000"} — space-separated with milliseconds</li>
     *   <li>{@code "2025-08-01 12:00:00"} — space-separated</li>
     *   <li>{@code "2025-08-01T12:00:00Z"} — ISO-8601</li>
     *   <li>{@code "2025-08-01T12:00:00.000Z"} — ISO-8601 with milliseconds</li>
     * </ul>
     *
     * @param text raw datetime string
     * @return parsed {@link ZonedDateTime}, or empty if unparseable
     */
    Optional<ZonedDateTime> parseUtcDateTime(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        // Normalise: space → T, strip fractional seconds, append Z if no zone offset
        String normalised = text.trim()
                .replace(" ", "T")
                .replaceAll("\\.\\d+$", "");
        // NOAA times are always UTC; append Z unless an explicit offset is already present.
        // Date separators are at positions 4 and 7; a timezone "-HH:MM" offset only appears
        // at position 19+ (after "yyyy-MM-ddTHH:mm:ss"), so lastIndexOf('-') < 18 means no offset.
        if (!normalised.endsWith("Z") && !normalised.contains("+")
                && normalised.lastIndexOf('-') < 18) {
            normalised += "Z";
        }
        try {
            return Optional.of(ZonedDateTime.parse(normalised));
        } catch (DateTimeParseException e) {
            LOG.debug("Could not parse NOAA datetime '{}': {}", text, e.getMessage());
            return Optional.empty();
        }
    }

    /** Immutable cache entry tracking when data was fetched. */
    private record CachedResult<T>(T data, ZonedDateTime fetchedAt) {
        CachedResult(T data) {
            this(data, ZonedDateTime.now(ZoneOffset.UTC));
        }
    }
}
