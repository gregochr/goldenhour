package com.gregochr.goldenhour.client;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.AuroraViewlineResponse;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.OvationReading;
import com.gregochr.goldenhour.model.SolarWindReading;
import com.gregochr.goldenhour.model.SpaceWeatherAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The cache, fail-open and malformed-input behaviour of every {@link NoaaSwpcClient} feed: the Kp forecast,
 * OVATION, solar wind and alerts fetches, the status endpoint's cache-first reads, and the tolerant parsers.
 */
@ExtendWith(MockitoExtension.class)
class NoaaSwpcClientFeedBranchesTest {

    private static final String KP_FORECAST_JSON =
            "[{\"time_tag\":\"2025-08-01T00:00:00\",\"kp\":4.33}]";
    private static final String OVATION_JSON =
            "{\"Forecast Time\":\"2025-08-01T12:00:00Z\",\"coordinates\":[[0,55,10],[1,55,20]]}";
    private static final String MAG_JSON =
            "[{\"time_tag\":\"2025-08-01T00:00:00\",\"active\":true,\"bz_gsm\":-4.5}]";
    private static final String PLASMA_JSON =
            "[{\"time_tag\":\"2025-08-01T00:00:00\",\"active\":true,\"proton_speed\":500.0,"
                    + "\"proton_density\":6.0}]";
    private static final String ALERTS_JSON =
            "[{\"message_type\":\"WARN\",\"message_id\":\"id-1\",\"issue_datetime\":\"2025-08-01 12:00:00\","
                    + "\"message\":\"G1 watch\"}]";

    @Mock
    private RestClient restClient;

    private MutableClock clock;
    private NoaaSwpcClient client;
    private final AtomicInteger httpCalls = new AtomicInteger();
    private boolean httpInstalled;
    private final AtomicReference<IntFunction<String>> behaviour = new AtomicReference<>(n -> "[]");

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-07-17T12:00:00Z"));
        client = new NoaaSwpcClient(restClient, new AuroraProperties(), new ObjectMapper(), clock);
    }

    /**
     * Routes every HTTP body read through one swappable behaviour, installing the client mock's single
     * {@code get()} stub on first use so tests that never fetch carry no stub at all.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void serveHttp(IntFunction<String> body) {
        behaviour.set(body);
        if (httpInstalled) {
            return;
        }
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class, inv ->
                "body".equals(inv.getMethod().getName())
                        ? behaviour.get().apply(httpCalls.incrementAndGet()) : null);
        RestClient.RequestHeadersUriSpec spec = mock(RestClient.RequestHeadersUriSpec.class, inv -> {
            String name = inv.getMethod().getName();
            if ("uri".equals(name)) {
                return inv.getMock();
            }
            return "retrieve".equals(name) ? responseSpec : null;
        });
        when(restClient.get()).thenReturn(spec);
        httpInstalled = true;
    }

    private void answerWith(String body) {
        serveHttp(n -> body);
    }

    private void failWith(String message) {
        serveHttp(n -> {
            throw new RestClientException(message);
        });
    }

    // ── Kp forecast ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("fetchKpForecast fetches once, serves the cache within the TTL, and refetches after it")
    void fetchKpForecast_cachesWithinTtl() {
        answerWith(KP_FORECAST_JSON);

        List<KpForecast> first = client.fetchKpForecast();
        List<KpForecast> second = client.fetchKpForecast();

        assertThat(first).hasSize(1);
        assertThat(first.get(0).kp()).isEqualTo(4.33);
        assertThat(second).isSameAs(first);
        assertThat(httpCalls).hasValue(1);

        clock.advanceMinutes(16);
        client.fetchKpForecast();
        assertThat(httpCalls).hasValue(2);
    }

    @Test
    @DisplayName("fetchKpForecast keeps the stale forecast when a refresh fails, and is empty when cold")
    void fetchKpForecast_failOpen() {
        failWith("NOAA down");
        assertThat(client.fetchKpForecast()).isEmpty();

        answerWith(KP_FORECAST_JSON);
        List<KpForecast> good = client.fetchKpForecast();
        clock.advanceMinutes(16);
        failWith("NOAA down again");

        assertThat(client.fetchKpForecast()).isSameAs(good);
    }

    @Test
    @DisplayName("getCachedKpForecast and getCachedSolarWind are null until fetched, and never call NOAA")
    void cachedAccessors_nullUntilFetched() {
        assertThat(client.getCachedKpForecast()).isNull();
        assertThat(client.getCachedSolarWind()).isNull();
        assertThat(httpCalls).hasValue(0);

        answerWith(KP_FORECAST_JSON);
        List<KpForecast> forecast = client.fetchKpForecast();
        assertThat(client.getCachedKpForecast()).isSameAs(forecast);

        serveHttp(n -> n % 2 == 1 ? MAG_JSON : PLASMA_JSON);
        int before = httpCalls.get();
        List<SolarWindReading> wind = client.fetchSolarWind();
        assertThat(client.getCachedSolarWind()).isSameAs(wind);
        assertThat(httpCalls.get()).isEqualTo(before + 2);
    }

    // ── OVATION ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fetchOvation is null when the very first fetch fails")
    void fetchOvation_coldFailure_isNull() {
        failWith("NOAA down");

        assertThat(client.fetchOvation()).isNull();
    }

    @Test
    @DisplayName("getOrRefreshAsyncOvation waits for the first fetch, then serves the cache without refetching")
    void asyncOvation_coldThenFresh() {
        answerWith(OVATION_JSON);

        OvationReading cold = client.getOrRefreshAsyncOvation();
        OvationReading warm = client.getOrRefreshAsyncOvation();

        assertThat(cold.probabilityAtLatitude()).isEqualTo(15.0);
        assertThat(warm).isSameAs(cold);
        assertThat(httpCalls).hasValue(1);
    }

    @Test
    @DisplayName("getOrRefreshAsyncOvation is null when the first fetch fails")
    void asyncOvation_coldFailure_isNull() {
        failWith("NOAA down");

        assertThat(client.getOrRefreshAsyncOvation()).isNull();
    }

    @Test
    @DisplayName("getOrRefreshAsyncOvation serves the stale reading at once and refreshes it in the background")
    void asyncOvation_stale_servedThenRefreshed() {
        answerWith(OVATION_JSON);
        OvationReading stale = client.getOrRefreshAsyncOvation();
        clock.advanceMinutes(6);

        OvationReading served = client.getOrRefreshAsyncOvation();

        assertThat(served).isSameAs(stale);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(httpCalls).hasValue(2);
            assertThat(client.getOrRefreshAsyncOvation()).isNotSameAs(stale);
        });
    }

    // ── solar wind ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("fetchSolarWind merges the mag and plasma feeds, caches within the TTL, and fails open")
    void fetchSolarWind_cachesAndFailsOpen() {
        serveHttp(n -> n % 2 == 1 ? MAG_JSON : PLASMA_JSON);

        List<SolarWindReading> first = client.fetchSolarWind();
        List<SolarWindReading> second = client.fetchSolarWind();

        assertThat(first).hasSize(1);
        assertThat(first.get(0).bzNanoTesla()).isEqualTo(-4.5);
        assertThat(first.get(0).speedKmPerSec()).isEqualTo(500.0);
        assertThat(first.get(0).densityPerCm3()).isEqualTo(6.0);
        assertThat(second).isSameAs(first);
        assertThat(httpCalls).hasValue(2);

        clock.advanceMinutes(2);
        failWith("NOAA down");
        assertThat(client.fetchSolarWind()).isSameAs(first);
    }

    @Test
    @DisplayName("fetchSolarWind is empty when the first fetch fails")
    void fetchSolarWind_coldFailure_isEmpty() {
        failWith("NOAA down");

        assertThat(client.fetchSolarWind()).isEmpty();
    }

    @Test
    @DisplayName("getOrRefreshAsyncSolarWind waits when cold, serves the cache when fresh, and is empty on failure")
    void asyncSolarWind_coldFreshAndFailure() {
        failWith("NOAA down");
        assertThat(client.getOrRefreshAsyncSolarWind()).isEmpty();

        serveHttp(n -> n % 2 == 0 ? MAG_JSON : PLASMA_JSON);
        List<SolarWindReading> cold = client.getOrRefreshAsyncSolarWind();
        List<SolarWindReading> warm = client.getOrRefreshAsyncSolarWind();

        assertThat(cold).hasSize(1);
        assertThat(warm).isSameAs(cold);
    }

    @Test
    @DisplayName("getOrRefreshAsyncSolarWind serves the stale readings at once and refreshes in the background")
    void asyncSolarWind_stale_servedThenRefreshed() {
        serveHttp(n -> n % 2 == 1 ? MAG_JSON : PLASMA_JSON);
        List<SolarWindReading> stale = client.getOrRefreshAsyncSolarWind();
        int callsAfterPrime = httpCalls.get();
        clock.advanceMinutes(2);

        List<SolarWindReading> served = client.getOrRefreshAsyncSolarWind();

        assertThat(served).isSameAs(stale);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(httpCalls.get()).isEqualTo(callsAfterPrime + 2);
            assertThat(client.getOrRefreshAsyncSolarWind()).isNotSameAs(stale);
        });
    }

    // ── alerts ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fetchAlerts serves the cache within the TTL and is empty when the first fetch fails")
    void fetchAlerts_cacheAndColdFailure() {
        failWith("NOAA down");
        assertThat(client.fetchAlerts()).isEmpty();

        answerWith(ALERTS_JSON);
        List<SpaceWeatherAlert> first = client.fetchAlerts();
        List<SpaceWeatherAlert> second = client.fetchAlerts();

        assertThat(first).hasSize(1);
        assertThat(first.get(0).messageId()).isEqualTo("id-1");
        assertThat(second).isSameAs(first);
        assertThat(httpCalls).hasValue(2);
    }

    // ── background refresh failure ───────────────────────────────────────────

    /** A hand-advanced clock that fails on virtual threads once armed, so only a background refresh sees it. */
    private static final class VirtualThreadFailingClock extends Clock {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicInteger virtualReads = new AtomicInteger();
        private volatile Instant now = Instant.parse("2026-07-17T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (armed.get() && Thread.currentThread().isVirtual()) {
                virtualReads.incrementAndGet();
                throw new IllegalStateException("clock unavailable");
            }
            return now;
        }
    }

    @Test
    @DisplayName("an unexpected failure in the background refresh keeps the stale value and frees the slot to retry")
    void backgroundRefreshFailure_keepsStaleAndRetries() {
        VirtualThreadFailingClock failing = new VirtualThreadFailingClock();
        NoaaSwpcClient failingClient =
                new NoaaSwpcClient(restClient, new AuroraProperties(), new ObjectMapper(), failing);
        answerWith("[{\"time_tag\":\"2025-08-01T00:00:00\",\"Kp\":3.0}]");
        List<KpReading> cached = failingClient.getOrRefreshAsyncKp();
        failing.now = failing.now.plus(Duration.ofMinutes(16));
        failing.armed.set(true);

        assertThat(failingClient.getOrRefreshAsyncKp()).isSameAs(cached);
        await().atMost(5, TimeUnit.SECONDS).until(() -> failing.virtualReads.get() >= 1);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(failingClient.getOrRefreshAsyncKp()).isSameAs(cached);
            assertThat(failing.virtualReads.get()).isGreaterThanOrEqualTo(2);
        });
        assertThat(httpCalls).hasValue(1);
    }

    // ── tolerant parsing ─────────────────────────────────────────────────────

    @Test
    @DisplayName("parseKpReadings skips object rows with a NaN Kp or an unparseable time")
    void parseKpReadings_objectFormat_skipsBadRows() throws Exception {
        String json = "[{\"time_tag\":\"2025-08-01T00:00:00\",\"Kp\":\"NaN\"},"
                + "{\"time_tag\":\"not a time\",\"Kp\":2.0},"
                + "{\"time_tag\":\"2025-08-01T03:00:00\",\"Kp\":3.67}]";

        List<KpReading> readings = client.parseKpReadings(json);

        assertThat(readings).hasSize(1);
        assertThat(readings.get(0).kp()).isEqualTo(3.67);
    }

    @Test
    @DisplayName("parseKpReadings skips legacy rows that are short, NaN or untimed")
    void parseKpReadings_legacyFormat_skipsBadRows() throws Exception {
        String json = "[[\"time_tag\",\"Kp\"],[\"2025-08-01 00:00:00\"],"
                + "[\"2025-08-01 03:00:00\",\"nan\"],[\"garbage\",\"2.0\"],[\"2025-08-01 06:00:00\",\"5.0\"]]";

        List<KpReading> readings = client.parseKpReadings(json);

        assertThat(readings).hasSize(1);
        assertThat(readings.get(0).kp()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("parseKpForecasts skips bad object rows and bad legacy rows, keeping three-hour windows")
    void parseKpForecasts_skipsBadRows() throws Exception {
        String objects = "[{\"time_tag\":\"2025-08-01T00:00:00\",\"kp\":\"null\"},"
                + "{\"time_tag\":\"\",\"kp\":2.0},{\"time_tag\":\"2025-08-01T03:00:00\",\"kp\":4.0}]";
        String legacy = "[[\"time_tag\",\"Kp\"],[\"2025-08-01 00:00:00\"],[\"2025-08-01 03:00:00\",\"-9999.9\"],"
                + "[\"garbage\",\"2.0\"],[\"2025-08-01 06:00:00\",\"3.0\"]]";

        List<KpForecast> fromObjects = client.parseKpForecasts(objects);
        List<KpForecast> fromLegacy = client.parseKpForecasts(legacy);

        assertThat(fromObjects).hasSize(1);
        assertThat(fromObjects.get(0).kp()).isEqualTo(4.0);
        assertThat(fromObjects.get(0).to()).isEqualTo(fromObjects.get(0).from().plusHours(3));
        assertThat(fromLegacy).hasSize(1);
        assertThat(fromLegacy.get(0).kp()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("parseOvation ignores grid points with fewer than three values")
    void parseOvation_skipsShortCoordinates() throws Exception {
        String json = "{\"Forecast Time\":\"2025-08-01T12:00:00Z\","
                + "\"coordinates\":[[0,55],[0,55,30],[1,54,90]]}";

        OvationReading reading = client.parseOvation(json, 55.0);

        assertThat(reading.probabilityAtLatitude()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("parseSolarWind drops untimed rows and NaN readings, defaulting a missing plasma half to zero")
    void parseSolarWind_dropsBadRowsAndDefaultsPlasma() throws Exception {
        String mag = "[5,"
                + "{\"time_tag\":\"\",\"active\":true,\"bz_gsm\":-1.0},"
                + "{\"time_tag\":\"2025-08-01T00:00:00\",\"active\":true,\"bz_gsm\":-2.0},"
                + "{\"time_tag\":\"2025-08-01T00:01:00\",\"active\":true,\"bz_gsm\":-3.0},"
                + "{\"time_tag\":\"2025-08-01T00:02:00\",\"active\":true,\"bz_gsm\":-4.0},"
                + "{\"time_tag\":\"2025-08-01T00:03:00\",\"active\":true,\"bz_gsm\":-5.0}]";
        String plasma = "[{\"time_tag\":\"\",\"active\":true,\"proton_speed\":1.0,\"proton_density\":1.0},"
                + "{\"time_tag\":\"2025-08-01T00:00:00\",\"active\":true,\"proton_speed\":null,"
                + "\"proton_density\":7.0},"
                + "{\"time_tag\":\"2025-08-01T00:01:00\",\"active\":true,\"proton_speed\":450.0,"
                + "\"proton_density\":null},"
                + "{\"time_tag\":\"2025-08-01T00:02:00\",\"active\":true,\"proton_speed\":null,"
                + "\"proton_density\":null}]";

        List<SolarWindReading> readings = client.parseSolarWind(mag, plasma);

        assertThat(readings).hasSize(4);
        assertThat(readings.get(0).speedKmPerSec()).isEqualTo(0.0);
        assertThat(readings.get(0).densityPerCm3()).isEqualTo(7.0);
        assertThat(readings.get(1).speedKmPerSec()).isEqualTo(450.0);
        assertThat(readings.get(1).densityPerCm3()).isEqualTo(0.0);
        assertThat(readings.get(2).speedKmPerSec()).isEqualTo(0.0);
        assertThat(readings.get(2).densityPerCm3()).isEqualTo(0.0);
        assertThat(readings.get(3).bzNanoTesla()).isEqualTo(-5.0);
    }

    @Test
    @DisplayName("parseViewline reports no data when the coordinates are missing or empty")
    void parseViewline_missingCoordinates_noData() throws Exception {
        AuroraViewlineResponse missing = client.parseViewline("{\"Forecast Time\":\"2025-08-01T12:00:00Z\"}", 5);
        AuroraViewlineResponse empty =
                client.parseViewline("{\"Forecast Time\":\"2025-08-01T12:00:00Z\",\"coordinates\":[]}", 5);

        assertThat(missing.active()).isFalse();
        assertThat(missing.summary()).isEqualTo("No OVATION data available");
        assertThat(empty.active()).isFalse();
        assertThat(empty.summary()).isEqualTo("No OVATION data available");
    }

    @Test
    @DisplayName("parseViewline normalises 0..360 longitudes and ignores short and out-of-range points")
    void parseViewline_normalisesLongitudeAndIgnoresBadPoints() throws Exception {
        // 358 deg east is 2 deg west; 20 deg east and 200 deg (=-160) are outside the UK window.
        String json = "{\"Forecast Time\":\"2025-08-01T12:00:00Z\",\"coordinates\":["
                + "[358,56],[358,56,40],[20,50,90],[200,50,90],[0,57,60]]}";

        AuroraViewlineResponse response = client.parseViewline(json, 5);

        assertThat(response.active()).isTrue();
        assertThat(response.points()).extracting(AuroraViewlineResponse.ViewlinePoint::longitude)
                .containsExactly(-2.0, 0.0);
    }

    @Test
    @DisplayName("parseDouble treats null, JSON null, blank, sentinel, null/nan text and junk as NaN")
    void parseDouble_everyAbsentForm_isNaN() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        assertThat(client.parseDouble(null)).isNaN();
        assertThat(client.parseDouble(mapper.readTree("null"))).isNaN();
        assertThat(client.parseDouble(mapper.readTree("\"  \""))).isNaN();
        assertThat(client.parseDouble(mapper.readTree("\"-9999.9\""))).isNaN();
        assertThat(client.parseDouble(mapper.readTree("\"NULL\""))).isNaN();
        assertThat(client.parseDouble(mapper.readTree("\"NaN\""))).isNaN();
        assertThat(client.parseDouble(mapper.readTree("\"abc\""))).isNaN();
        assertThat(client.parseDouble(mapper.readTree("\"2.5\""))).isEqualTo(2.5);
    }

    @Test
    @DisplayName("parseUtcDateTime keeps an explicit offset, appends Z otherwise, and rejects junk")
    void parseUtcDateTime_offsetsAndJunk() {
        assertThat(client.parseUtcDateTime("2025-08-01T12:00:00+01:00"))
                .contains(ZonedDateTime.parse("2025-08-01T12:00:00+01:00"));
        assertThat(client.parseUtcDateTime("2025-08-01T12:00:00-05:00"))
                .contains(ZonedDateTime.parse("2025-08-01T12:00:00-05:00"));
        assertThat(client.parseUtcDateTime("2025-08-01 12:00:00.250"))
                .contains(ZonedDateTime.parse("2025-08-01T12:00:00Z"));
        assertThat(client.parseUtcDateTime("last Tuesday")).isEmpty();
    }

    /** A Clock whose instant the test advances by hand. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advanceMinutes(long minutes) {
            now = now.plus(Duration.ofMinutes(minutes));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
