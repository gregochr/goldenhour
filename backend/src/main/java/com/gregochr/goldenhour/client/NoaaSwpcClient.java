package com.gregochr.goldenhour.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.AuroraViewlineResponse;
import com.gregochr.goldenhour.model.AuroraViewlineResponse.ViewlinePoint;
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
import java.util.TreeMap;

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

    /**
     * Aurora probability threshold (%) for viewline extraction.
     *
     * <p>The viewline marks the southernmost latitude where aurora
     * probability meets this threshold. Set to 5% based on real-world
     * calibration — on a Kp 6 event, aurora was visibly active from
     * Northumberland (~55°N) but the 10% threshold placed the viewline
     * north of Edinburgh (~56°N), producing a false negative.
     *
     * <p>A 5% threshold represents "aurora possible from a dark sky site
     * with a clear northern horizon" — appropriate for a photography app
     * where photographers will make their own go/no-go judgement. The
     * viewline is advisory, not a guarantee.
     *
     * <p>The active: false threshold uses the same value — if nothing
     * reaches 5% anywhere in the UK longitude range, the viewline
     * is inactive.
     */
    static final int VIEWLINE_THRESHOLD = 5;

    /** Western bound for UK viewline longitude range (°W expressed as negative). */
    static final double VIEWLINE_LON_WEST = -12.0;

    /** Eastern bound for UK viewline longitude range (°E). */
    static final double VIEWLINE_LON_EAST = 4.0;

    /** Half-window size for the moving-average smoother on the viewline. */
    static final int VIEWLINE_SMOOTH_HALF_WINDOW = 2;

    /**
     * Conservative maximum southward aurora visibility by Kp index.
     *
     * <p>Based on real-world UK aurora observer reports, not theoretical models.
     * OVATION tends to be optimistic about how far south aurora reaches because
     * it models probability of overhead aurora, not naked-eye visibility from
     * the ground (which requires clear northern horizon, dark skies, and
     * sufficient brightness above atmospheric extinction at low elevation angles).
     *
     * <p>These latitudes represent where a keen photographer at a dark sky site
     * with a clear northern horizon has a realistic chance of capturing aurora
     * on camera. Naked-eye visibility will typically be 1-2° further north.
     */
    static final Map<Integer, Double> KP_LATITUDE_CAP = Map.of(
            4, 58.0,   // Northern Scotland only
            5, 56.0,   // Central Scotland
            6, 54.0,   // Northern England (Durham, Northumberland)
            7, 52.0,   // Midlands
            8, 50.0,   // Southern England
            9, 48.0    // Entire UK and beyond
    );

    /** Default latitude cap for Kp &lt; 4 (far northern Scotland — aurora barely visible). */
    static final double DEFAULT_KP_CAP = 60.0;

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
     * Returns the cached Kp forecast without making an HTTP call.
     * Returns {@code null} if no cached data is available.
     *
     * @return cached Kp forecast, or null
     */
    public List<KpForecast> getCachedKpForecast() {
        CachedResult<List<KpForecast>> cache = cachedKpForecast;
        return cache != null ? cache.data() : null;
    }

    /**
     * Returns the cached solar wind readings without making an HTTP call.
     * Returns {@code null} if no cached data is available.
     *
     * @return cached solar wind readings (oldest first), or null
     */
    public List<SolarWindReading> getCachedSolarWind() {
        CachedResult<List<SolarWindReading>> cache = cachedSolarWind;
        return cache != null ? cache.data() : null;
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

    /**
     * Returns the aurora viewline (southernmost visible aurora boundary) for UK longitudes,
     * derived from the cached OVATION data, clamped by a physically grounded Kp-to-latitude cap.
     *
     * <p>Reuses the OVATION cache (5-minute TTL). Returns an inactive response if OVATION data
     * is unavailable or no aurora probability above threshold exists in the UK longitude range.
     *
     * @param currentKp the current or forecast Kp index, used to cap the viewline latitude
     * @return viewline response, never {@code null}
     */
    public AuroraViewlineResponse fetchViewline(double currentKp) {
        try {
            String json = restClient.get()
                    .uri(properties.getNoaa().getOvationUrl())
                    .retrieve()
                    .body(String.class);
            AuroraViewlineResponse raw = parseViewline(json, VIEWLINE_THRESHOLD);
            return applyKpCap(raw, currentKp);
        } catch (Exception e) {
            LOG.warn("NOAA viewline fetch failed: {}", e.getMessage());
            return new AuroraViewlineResponse(
                    List.of(), "Aurora viewline unavailable", 90.0,
                    ZonedDateTime.now(ZoneOffset.UTC), false, false);
        }
    }

    // -------------------------------------------------------------------------
    // Parsing — package-visible for unit testing
    // -------------------------------------------------------------------------

    /**
     * Parses the NOAA Kp index JSON (array-of-arrays, first row is headers).
     *
     * <p>Supports two formats:
     * <ul>
     *   <li>Object format (current): {@code [{"time_tag": "...", "Kp": 2.67, ...}, ...]}</li>
     *   <li>Array format (legacy): {@code [["time_tag", "Kp", ...], ...]} (first row is headers)</li>
     * </ul>
     *
     * @param json raw JSON from NOAA
     * @return list of Kp readings (oldest first)
     * @throws Exception if JSON parsing fails
     */
    List<KpReading> parseKpReadings(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        List<KpReading> result = new ArrayList<>();
        boolean isFirstRow = true;
        for (JsonNode row : root) {
            if (row.isObject()) {
                // New NOAA format: array of objects {"time_tag": "...", "Kp": ...}
                String timeTag = row.path("time_tag").asText("");
                double kp = parseDouble(row.get("Kp"));
                if (!Double.isNaN(kp)) {
                    parseUtcDateTime(timeTag).ifPresent(ts -> result.add(new KpReading(ts, kp)));
                }
            } else {
                // Legacy format: array of arrays, first row is headers
                if (isFirstRow) {
                    isFirstRow = false;
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
        }
        return result;
    }

    /**
     * Parses the NOAA Kp 3-day forecast JSON (array-of-arrays, first row is headers).
     *
     * <p>Supports two formats:
     * <ul>
     *   <li>Object format (current): {@code [{"time_tag": "...", "kp": 2.67, ...}, ...]}</li>
     *   <li>Array format (legacy): {@code [["time_tag", "Kp", ...], ...]} (first row is headers)</li>
     * </ul>
     * Each entry is a 3-hour window starting at {@code time_tag}.
     *
     * @param json raw JSON from NOAA
     * @return list of Kp forecast windows (oldest first)
     * @throws Exception if JSON parsing fails
     */
    List<KpForecast> parseKpForecasts(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        List<KpForecast> result = new ArrayList<>();
        boolean isFirstRow = true;
        for (JsonNode row : root) {
            if (row.isObject()) {
                // New NOAA format: array of objects {"time_tag": "...", "kp": ...}
                String timeTag = row.path("time_tag").asText("");
                double kp = parseDouble(row.get("kp"));
                if (!Double.isNaN(kp)) {
                    parseUtcDateTime(timeTag)
                            .ifPresent(from -> result.add(new KpForecast(from, from.plusHours(3), kp)));
                }
            } else {
                // Legacy format: array of arrays, first row is headers
                if (isFirstRow) {
                    isFirstRow = false;
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

        ZonedDateTime forecastTime = parseUtcDateTime(root.path("Forecast Time").asText(""))
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
            String type = alert.path("message_type").asText("");
            String id = alert.path("message_id").asText("");
            String issuedStr = alert.path("issue_datetime").asText("");
            String message = alert.path("message").asText("");
            ZonedDateTime issued = parseUtcDateTime(issuedStr)
                    .orElseGet(() -> ZonedDateTime.now(ZoneOffset.UTC));
            result.add(new SpaceWeatherAlert(type, id, issued, message));
        }
        return result;
    }

    /**
     * Parses the OVATION JSON and extracts the southernmost aurora viewline for UK longitudes.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Filter coordinates to the UK longitude range ({@value VIEWLINE_LON_WEST}°W to
     *       {@value VIEWLINE_LON_EAST}°E)</li>
     *   <li>Per longitude: scan south→north and find the southernmost latitude with aurora
     *       probability ≥ {@code threshold}</li>
     *   <li>Smooth with a moving average (half-window = {@value VIEWLINE_SMOOTH_HALF_WINDOW})</li>
     *   <li>Generate a UK-centric summary from the southernmost latitude</li>
     * </ol>
     *
     * @param json      raw OVATION JSON from NOAA
     * @param threshold minimum aurora probability (0–100) to consider as "visible"
     * @return viewline response
     * @throws Exception if JSON parsing fails
     */
    AuroraViewlineResponse parseViewline(String json, int threshold) throws Exception {
        JsonNode root = mapper.readTree(json);

        ZonedDateTime forecastTime = parseUtcDateTime(root.path("Forecast Time").asText(""))
                .orElseGet(() -> ZonedDateTime.now(ZoneOffset.UTC));

        JsonNode coords = root.path("coordinates");
        if (coords.isMissingNode() || coords.isEmpty()) {
            return new AuroraViewlineResponse(
                    List.of(), "No OVATION data available", 90.0, forecastTime, false, false);
        }

        // Collect: for each longitude in the UK range, find the southernmost lat above threshold.
        // Key = longitude (integer), Value = southernmost latitude with probability >= threshold.
        TreeMap<Integer, Double> southernmostByLon = new TreeMap<>();
        for (JsonNode coord : coords) {
            if (coord.size() < 3) {
                continue;
            }
            double lon = coord.get(0).asDouble();
            double lat = coord.get(1).asDouble();
            double prob = coord.get(2).asDouble();

            // Normalise longitude to -180..180 range (OVATION uses 0..360)
            if (lon > 180) {
                lon -= 360;
            }

            if (lon < VIEWLINE_LON_WEST || lon > VIEWLINE_LON_EAST) {
                continue;
            }

            int lonKey = (int) Math.round(lon);

            if (prob >= threshold) {
                southernmostByLon.merge(lonKey, lat, Math::min);
            }
        }

        if (southernmostByLon.isEmpty()) {
            return new AuroraViewlineResponse(
                    List.of(), "No significant aurora in UK range", 90.0, forecastTime, false, false);
        }

        // Convert to list and smooth with moving average
        List<Map.Entry<Integer, Double>> entries = new ArrayList<>(southernmostByLon.entrySet());
        List<ViewlinePoint> smoothed = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            int fromIdx = Math.max(0, i - VIEWLINE_SMOOTH_HALF_WINDOW);
            int toIdx = Math.min(entries.size() - 1, i + VIEWLINE_SMOOTH_HALF_WINDOW);
            double sum = 0;
            int count = 0;
            for (int j = fromIdx; j <= toIdx; j++) {
                sum += entries.get(j).getValue();
                count++;
            }
            smoothed.add(new ViewlinePoint(entries.get(i).getKey(), sum / count));
        }

        double southernmost = smoothed.stream()
                .mapToDouble(ViewlinePoint::latitude)
                .min()
                .orElse(90.0);

        String summary = viewlineSummary(southernmost);

        return new AuroraViewlineResponse(smoothed, summary, southernmost, forecastTime, true, false);
    }

    /**
     * Generates a UK-centric summary string from the southernmost viewline latitude.
     *
     * @param latitude degrees north
     * @return human-readable summary
     */
    String viewlineSummary(double latitude) {
        if (latitude <= 51) {
            return "Visible across the whole of the UK";
        } else if (latitude <= 53) {
            return "Visible as far south as the Midlands";
        } else if (latitude <= 55) {
            return "Visible as far south as northern England";
        } else if (latitude <= 57) {
            return "Visible as far south as central Scotland";
        } else if (latitude <= 59) {
            return "Visible as far south as northern Scotland";
        } else {
            return "Faint aurora possible in far north Scotland";
        }
    }

    /**
     * Returns the maximum realistic southward visibility latitude for a given Kp value.
     *
     * <p>For fractional Kp values (e.g. 5.3), truncates to the integer floor (Kp 5).
     * For Kp &lt; 4, returns {@value DEFAULT_KP_CAP}°N. For Kp &ge; 10, returns 0
     * (effectively no cap).
     *
     * @param kp the Kp index
     * @return latitude cap in degrees north
     */
    double getKpLatitudeCap(double kp) {
        int kpFloor = (int) kp;
        if (kpFloor >= 10) {
            return 0.0;
        }
        return KP_LATITUDE_CAP.getOrDefault(kpFloor, DEFAULT_KP_CAP);
    }

    /**
     * Applies the Kp-to-latitude hard cap to a raw OVATION viewline response.
     *
     * <p>OVATION can pull the line north of the cap (real-time data overrides the
     * theoretical cap in the conservative direction), but cannot push it south
     * (preventing unrealistic claims like "visible across the whole UK" at Kp 6).
     *
     * @param raw       the uncapped viewline from {@link #parseViewline}
     * @param currentKp the current or forecast Kp index
     * @return a new response with latitude-capped points, southernmost latitude, and summary
     */
    AuroraViewlineResponse applyKpCap(AuroraViewlineResponse raw, double currentKp) {
        if (!raw.active()) {
            return raw;
        }

        double kpCap = getKpLatitudeCap(currentKp);

        List<ViewlinePoint> clampedPoints = raw.points().stream()
                .map(p -> new ViewlinePoint(p.longitude(), Math.max(p.latitude(), kpCap)))
                .toList();

        double clampedSouthernmost = Math.max(raw.southernmostLatitude(), kpCap);
        String summary = viewlineSummary(clampedSouthernmost);

        return new AuroraViewlineResponse(
                clampedPoints, summary, clampedSouthernmost, raw.forecastTime(), true, false);
    }

    /**
     * Builds a forecast extent viewline from the Kp-to-latitude cap table.
     *
     * <p>Returns a straight horizontal line at the cap latitude across the UK longitude
     * range. Used when triggerType is FORECAST_LOOKAHEAD and no live OVATION data applies.
     *
     * @param forecastKp the forecast Kp index
     * @return forecast viewline with {@code isForecast: true}
     */
    public AuroraViewlineResponse buildForecastViewline(double forecastKp) {
        double capLatitude = getKpLatitudeCap(forecastKp);
        List<ViewlinePoint> points = List.of(
                new ViewlinePoint(VIEWLINE_LON_WEST, capLatitude),
                new ViewlinePoint(VIEWLINE_LON_EAST, capLatitude));
        String summary = viewlineSummary(capLatitude);
        return new AuroraViewlineResponse(
                points, summary, capLatitude, ZonedDateTime.now(ZoneOffset.UTC), true, true);
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
