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
import com.gregochr.goldenhour.service.aurora.AuroraViewlineRule;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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

    /** Length of {@code yyyy-MM-ddTHH:mm}, the key solar wind rows are merged on. */
    private static final int MINUTE_KEY_LENGTH = 16;
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
    static final double VIEWLINE_LON_WEST = AuroraViewlineRule.LON_WEST_DEG;

    /** Eastern bound for UK viewline longitude range (°E). */
    static final double VIEWLINE_LON_EAST = AuroraViewlineRule.LON_EAST_DEG;

    /** Half-window size for the moving-average smoother on the viewline. */
    static final int VIEWLINE_SMOOTH_HALF_WINDOW = 2;

    private final RestClient restClient;
    private final AuroraProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** In-flight background refresh per feed (single-flight guard for the status endpoint path). */
    private final AtomicReference<CompletableFuture<Void>> kpFlight = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> ovationFlight = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> solarWindFlight = new AtomicReference<>();

    private volatile CachedResult<List<KpReading>> cachedKp;
    private volatile CachedResult<List<KpForecast>> cachedKpForecast;
    private volatile CachedResult<OvationReading> cachedOvation;
    private volatile CachedResult<List<SolarWindReading>> cachedSolarWind;
    private volatile CachedResult<List<SpaceWeatherAlert>> cachedAlerts;
    /** The parsed OVATION 5% contour, cached for the OVATION cadence. */
    private volatile CachedResult<AuroraViewlineResponse> cachedViewline;

    /**
     * Constructs the client with the shared HTTP client, aurora configuration, and JSON mapper.
     *
     * @param restClient shared HTTP client
     * @param properties aurora configuration (NOAA endpoint URLs)
     * @param mapper     Jackson object mapper
     * @param clock      clock backing the cache TTL checks
     */
    public NoaaSwpcClient(RestClient restClient, AuroraProperties properties,
            ObjectMapper mapper, Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
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
            cachedKp = new CachedResult<>(readings, ZonedDateTime.now(clock));
            return readings;
        } catch (Exception e) {
            LOG.warn("NOAA Kp fetch failed (retaining cached): {}", LogSanitizer.sanitize(e.getMessage()));
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
            cachedKpForecast = new CachedResult<>(forecasts, ZonedDateTime.now(clock));
            return forecasts;
        } catch (Exception e) {
            LOG.warn("NOAA Kp forecast fetch failed (retaining cached): {}",
                    LogSanitizer.sanitize(e.getMessage()));
            return cache != null ? cache.data() : List.of();
        }
    }

    /**
     * Kp for the status endpoint: the cached value at once, refreshed off the request thread.
     *
     * <p>Used only by {@code AuroraController}. The synchronous {@link #fetchKp()} stays what the
     * aurora state machine's poller decides on; this path may serve a value up to one refresh stale.
     * A stale (older than the 15-minute TTL) cache starts at most one background refresh; an empty
     * cache waits for the one in-flight fetch.
     *
     * @return the Kp readings (oldest first), or empty when none was ever fetched
     */
    public List<KpReading> getOrRefreshAsyncKp() {
        CachedResult<List<KpReading>> cache = cachedKp;
        if (!isFresh(cache, KP_TTL_MINUTES)) {
            CompletableFuture<Void> flight = refreshInBackground(kpFlight, this::fetchKp);
            if (cache == null) {
                flight.join();
                cache = cachedKp;
            }
        }
        return cache != null ? cache.data() : List.of();
    }

    /**
     * OVATION for the status endpoint: the cached value at once, refreshed off the request thread
     * (5-minute TTL); see {@link #getOrRefreshAsyncKp()}.
     *
     * @return the OVATION reading, or {@code null} when none was ever fetched
     */
    public OvationReading getOrRefreshAsyncOvation() {
        CachedResult<OvationReading> cache = cachedOvation;
        if (!isFresh(cache, OVATION_TTL_MINUTES)) {
            CompletableFuture<Void> flight = refreshInBackground(ovationFlight, this::fetchOvation);
            if (cache == null) {
                flight.join();
                cache = cachedOvation;
            }
        }
        return cache != null ? cache.data() : null;
    }

    /**
     * Solar wind for the status endpoint: the cached value at once, refreshed off the request thread
     * (1-minute TTL); see {@link #getOrRefreshAsyncKp()}.
     *
     * @return the solar wind readings (oldest first), or empty when none was ever fetched
     */
    public List<SolarWindReading> getOrRefreshAsyncSolarWind() {
        CachedResult<List<SolarWindReading>> cache = cachedSolarWind;
        if (!isFresh(cache, SOLAR_WIND_TTL_MINUTES)) {
            CompletableFuture<Void> flight =
                    refreshInBackground(solarWindFlight, this::fetchSolarWind);
            if (cache == null) {
                flight.join();
                cache = cachedSolarWind;
            }
        }
        return cache != null ? cache.data() : List.of();
    }

    /**
     * Single-flight refresh: starts {@code fetch} on a virtual thread unless one is already running
     * for this feed, and returns the flight in progress. A failed fetch leaves the stale cache
     * untouched (the synchronous fetch methods already fail open) and the slot is freed, so the next
     * stale call retries.
     */
    private CompletableFuture<Void> refreshInBackground(
            AtomicReference<CompletableFuture<Void>> slot, Supplier<?> fetch) {
        CompletableFuture<Void> mine = new CompletableFuture<>();
        while (true) {
            CompletableFuture<Void> current = slot.get();
            if (current != null) {
                return current;
            }
            if (slot.compareAndSet(null, mine)) {
                break;
            }
        }
        Thread.startVirtualThread(() -> {
            try {
                fetch.get();
            } catch (RuntimeException e) {
                LOG.warn("NOAA background refresh failed (retaining cached): {}",
                        LogSanitizer.sanitize(e.getMessage()));
            } finally {
                slot.set(null);
                mine.complete(null);
            }
        });
        return mine;
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
            cachedOvation = new CachedResult<>(reading, ZonedDateTime.now(clock));
            return reading;
        } catch (Exception e) {
            LOG.warn("NOAA OVATION fetch failed (retaining cached): {}",
                    LogSanitizer.sanitize(e.getMessage()));
            return cache != null ? cache.data() : null;
        }
    }

    /**
     * Returns recent solar wind readings (1-minute cadence), cached for 1 minute.
     *
     * <p>Combines magnetic field (Bz) from {@code rtsw_mag_1m.json} and plasma data
     * (speed, density) from {@code rtsw_wind_1m.json}, merging rows by minute.
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
            cachedSolarWind = new CachedResult<>(readings, ZonedDateTime.now(clock));
            return readings;
        } catch (Exception e) {
            LOG.warn("NOAA solar wind fetch failed (retaining cached): {}",
                    LogSanitizer.sanitize(e.getMessage()));
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
            cachedAlerts = new CachedResult<>(alerts, ZonedDateTime.now(clock));
            return alerts;
        } catch (Exception e) {
            LOG.warn("NOAA alerts fetch failed (retaining cached): {}",
                    LogSanitizer.sanitize(e.getMessage()));
            return cache != null ? cache.data() : List.of();
        }
    }

    /**
     * Returns the live aurora viewline (southernmost visible aurora boundary) for UK longitudes,
     * the OVATION nowcast's 5% probability contour, unchanged.
     *
     * <p>Nothing clamps it. A Kp-to-latitude cap used to override the contour on every path (it could
     * only move the line north, and it fed on a published Kp that lags the running block); OVATION is
     * the better product for "now", so the contour is served as parsed.
     *
     * <p>Cached for 5 minutes, matching the OVATION nowcast's own cadence. This endpoint is
     * user-facing — it previously re-downloaded the ~900 KB OVATION grid on every authenticated page
     * load, unbounded in user traffic.
     *
     * <p>Fails open like the sibling fetches: a NOAA outage keeps serving the last good
     * viewline rather than blanking the map overlay. Returns an inactive response if OVATION
     * data was never retrieved, or if no aurora probability above threshold exists in the UK
     * longitude range.
     *
     * @return viewline response, never {@code null}
     */
    public AuroraViewlineResponse fetchViewline() {
        CachedResult<AuroraViewlineResponse> cache = cachedViewline;
        if (isFresh(cache, OVATION_TTL_MINUTES)) {
            return cache.data();
        }
        try {
            String json = restClient.get()
                    .uri(properties.getNoaa().getOvationUrl())
                    .retrieve()
                    .body(String.class);
            AuroraViewlineResponse parsed = parseViewline(json, VIEWLINE_THRESHOLD);
            cachedViewline = new CachedResult<>(parsed, ZonedDateTime.now(clock));
            return parsed;
        } catch (Exception e) {
            LOG.warn("NOAA viewline fetch failed (retaining cached): {}",
                    LogSanitizer.sanitize(e.getMessage()));
            if (cache != null) {
                return cache.data();
            }
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
                String timeTag = row.path("time_tag").asString("");
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
                String timeTag = row.get(0).asString();
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
                String timeTag = row.path("time_tag").asString("");
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
                String timeTag = row.get(0).asString();
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

        ZonedDateTime forecastTime = parseUtcDateTime(root.path("Forecast Time").asString(""))
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
     * Parses and merges magnetic field and plasma solar wind data from NOAA's real-time solar wind
     * ({@code rtsw}) feeds.
     *
     * <p>Both feeds are JSON arrays of objects, newest first, with one row per minute per
     * spacecraft. Mag rows carry {@code bz_gsm}; wind rows carry {@code proton_speed} and
     * {@code proton_density}. Each row has an {@code active} flag marking the spacecraft NOAA is
     * currently using; inactive rows (a second spacecraft reporting the same minute) are ignored
     * unless a feed has no active row at all.
     *
     * <p>Rows are merged by minute (the seconds differ between spacecraft). Returns the most recent
     * 60 readings.
     *
     * @param magJson    raw JSON from the NOAA {@code rtsw_mag_1m} endpoint
     * @param plasmaJson raw JSON from the NOAA {@code rtsw_wind_1m} endpoint
     * @return list of solar wind readings (oldest first, at most 60 entries)
     * @throws Exception if JSON parsing fails
     */
    List<SolarWindReading> parseSolarWind(String magJson, String plasmaJson) throws Exception {
        // Parse mag: minute -> bz
        Map<String, Double> bzByMinute = new HashMap<>();
        for (JsonNode row : preferActive(mapper.readTree(magJson))) {
            double bz = parseDouble(row.get("bz_gsm"));
            String minute = minuteKey(row);
            if (minute != null && !Double.isNaN(bz)) {
                bzByMinute.putIfAbsent(minute, bz);
            }
        }

        // Parse wind: minute -> [speed, density]
        Map<String, double[]> plasmaByMinute = new HashMap<>();
        for (JsonNode row : preferActive(mapper.readTree(plasmaJson))) {
            double speed = parseDouble(row.get("proton_speed"));
            double density = parseDouble(row.get("proton_density"));
            String minute = minuteKey(row);
            if (minute != null && (!Double.isNaN(density) || !Double.isNaN(speed))) {
                plasmaByMinute.putIfAbsent(minute, new double[]{
                        Double.isNaN(speed) ? 0.0 : speed,
                        Double.isNaN(density) ? 0.0 : density
                });
            }
        }

        // Merge by minute — emit a reading only when we have at least bz
        List<SolarWindReading> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : bzByMinute.entrySet()) {
            double bz = entry.getValue();
            double[] plasma = plasmaByMinute.getOrDefault(entry.getKey(), new double[]{0.0, 0.0});
            parseUtcDateTime(entry.getKey() + ":00").ifPresent(ts ->
                    result.add(new SolarWindReading(ts, bz, plasma[0], plasma[1])));
        }

        // Sort oldest first, keep most recent 60 (1 hour at 1-minute cadence)
        result.sort((a, b) -> a.timestamp().compareTo(b.timestamp()));
        int from = Math.max(0, result.size() - 60);
        return result.subList(from, result.size());
    }

    /**
     * Selects the rows of an {@code rtsw} feed to read: those flagged {@code active}, or every row
     * when none is (so a feed that drops or renames the flag still yields readings).
     */
    private List<JsonNode> preferActive(JsonNode root) {
        List<JsonNode> all = new ArrayList<>();
        List<JsonNode> active = new ArrayList<>();
        for (JsonNode row : root) {
            if (!row.isObject()) {
                continue;
            }
            all.add(row);
            if (row.path("active").asBoolean(false)) {
                active.add(row);
            }
        }
        return active.isEmpty() ? all : active;
    }

    /** Truncates a row's {@code time_tag} to the minute ({@code yyyy-MM-ddTHH:mm}), or null. */
    private String minuteKey(JsonNode row) {
        String timeTag = row.path("time_tag").asString("");
        String normalised = timeTag.trim().replace(' ', 'T');
        return normalised.length() >= MINUTE_KEY_LENGTH ? normalised.substring(0, MINUTE_KEY_LENGTH) : null;
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
            String type = alert.path("message_type").asString("");
            String id = alert.path("message_id").asString("");
            String issuedStr = alert.path("issue_datetime").asString("");
            String message = alert.path("message").asString("");
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

        ZonedDateTime forecastTime = parseUtcDateTime(root.path("Forecast Time").asString(""))
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

        return new AuroraViewlineResponse(
                smoothed, summary, southernmost, forecastTime, true, false, null, forecastTime);
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
     * Returns the southernmost latitude (°N) to which aurora glow realistically reaches for the
     * given Kp: {@link AuroraViewlineRule} evaluated at the UK centre longitude
     * ({@value AuroraViewlineRule#UK_REFERENCE_LONGITUDE_DEG}°). A public accessor so a service-layer caller
     * (the aurora hot topic) can label the glow extent without crossing the client package boundary.
     *
     * @param kp the Kp index
     * @return glow latitude in degrees north
     */
    public double getGlowLatitudeCap(double kp) {
        return AuroraViewlineRule.geographicLatitudeFor(kp, AuroraViewlineRule.UK_REFERENCE_LONGITUDE_DEG);
    }

    /**
     * Builds the forecast viewline for a Kp from {@link AuroraViewlineRule}: the camera-visibility
     * line, one point per integer longitude across the UK range (17 points), dipping further south to
     * the west. Its {@code southernmostLatitude} (and so the summary band) is the rule at
     * {@value AuroraViewlineRule#UK_REFERENCE_LONGITUDE_DEG}°, not the curve's minimum. Used when no
     * live OVATION line is drawable.
     *
     * @param forecastKp the forecast Kp index (clamped to 0..9 by the rule)
     * @return forecast viewline with {@code isForecast: true}, {@code kp} and {@code asOf} set
     */
    public AuroraViewlineResponse buildForecastViewline(double forecastKp) {
        List<ViewlinePoint> points = AuroraViewlineRule.line(forecastKp);
        // The reference longitude, not the curve's west end (-12, mid-Atlantic): the same figure the
        // Plan card's glow reads, so the Map's summary band and the card agree for one Kp.
        double southernmost = getGlowLatitudeCap(forecastKp);
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ZoneOffset.UTC);
        return new AuroraViewlineResponse(
                points, viewlineSummary(southernmost), southernmost, now, true, true,
                forecastKp, now);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isFresh(CachedResult<?> cache, int ttlMinutes) {
        return cache != null
                && ZonedDateTime.now(clock)
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
        String text = node.asString();
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
    }
}
