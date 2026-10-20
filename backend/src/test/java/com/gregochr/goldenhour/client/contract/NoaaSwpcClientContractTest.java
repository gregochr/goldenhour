package com.gregochr.goldenhour.client.contract;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.OvationReading;
import com.gregochr.goldenhour.model.SolarWindReading;
import com.gregochr.goldenhour.model.SpaceWeatherAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Outbound contract test for {@link NoaaSwpcClient} — six distinct NOAA SWPC endpoints reached
 * through the shared {@link RestClient} bean.
 *
 * <p><strong>Why this exists.</strong> {@code NoaaSwpcClientTest} is overwhelmingly parser-focused;
 * its four HTTP-touching tests go through {@code RestClientMocks}, whose own Javadoc states:
 * "Because uri(...) is matched with any(...), these helpers do NOT exercise URL-building."
 * {@code stubGetSequence} in particular feeds response bodies <em>positionally</em>, with no
 * reference to which URL asked for them — so today every one of the six endpoints could be
 * permuted and the suite would stay green.
 *
 * <p><strong>The highest-value assertion here is the mag/plasma pair.</strong>
 * {@code fetchSolarWind} issues two sequential GETs and merges them by timestamp:
 * {@code rtsw_mag_1m.json} carries Bz, {@code rtsw_wind_1m.json} carries speed and density. Swapping
 * the two URLs yields two well-formed NOAA array-of-arrays payloads that parse without error and
 * produce a nonsense Bz — and the Bz sign is a dual-signal input to
 * {@code AuroraOrchestrator.deriveAlertLevel()}. Expectation order is therefore deliberately left
 * <em>ordered</em> for that test: the order <em>is</em> the assertion.
 *
 * <p><strong>Fail-open caveat.</strong> Every fetch swallows its exception and returns an empty
 * result, so an unmatched URL surfaces as an empty list rather than a thrown assertion. Each test
 * therefore ends with {@code server.verify()} <em>and</em> asserts the parsed content.
 *
 * <p><strong>Honest limit.</strong> The URLs are asserted against the production defaults declared
 * in {@code AuroraProperties.NoaaConfig}; a test cannot catch a deliberate config override in
 * {@code application.yml}.
 */
class NoaaSwpcClientContractTest {

    private static final String KP_URL =
            "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json";
    private static final String KP_FORECAST_URL =
            "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json";
    private static final String OVATION_URL =
            "https://services.swpc.noaa.gov/json/ovation_aurora_latest.json";
    private static final String SOLAR_WIND_MAG_URL =
            "https://services.swpc.noaa.gov/json/rtsw/rtsw_mag_1m.json";
    private static final String SOLAR_WIND_PLASMA_URL =
            "https://services.swpc.noaa.gov/json/rtsw/rtsw_wind_1m.json";
    private static final String ALERTS_URL =
            "https://services.swpc.noaa.gov/products/alerts.json";

    /** Latitude band the OVATION nowcast is averaged over, mirroring {@code OVATION_TARGET_LAT}. */
    private static final double OVATION_TARGET_LAT = 55.0;

    /** Fixed so a cache TTL never expires mid-test and a second call is a genuine cache hit. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-17T12:30:00Z"), ZoneOffset.UTC);

    private RestClient.Builder builder;

    @BeforeEach
    void setUp() {
        // No baseUrl: every NOAA URL is absolute, read from AuroraProperties at call time.
        this.builder = RestClient.builder();
    }

    @Test
    @DisplayName("fetchKp GETs the planetary K-index endpoint — not the forecast endpoint beside it")
    void fetchKpUsesTheKpEndpoint() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NoaaSwpcClient client = client();

        server.expect(requestTo(KP_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("kp.json"), MediaType.APPLICATION_JSON));

        List<KpReading> readings = client.fetchKp();

        assertThat(readings).extracting(KpReading::kp).containsExactly(4.67, 5.33);
        server.verify();
    }

    @Test
    @DisplayName("fetchKpForecast GETs the K-index *forecast* endpoint (a one-word path difference)")
    void fetchKpForecastUsesTheForecastEndpoint() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NoaaSwpcClient client = client();

        server.expect(requestTo(KP_FORECAST_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("kp-forecast.json"), MediaType.APPLICATION_JSON));

        List<KpForecast> forecasts = client.fetchKpForecast();

        assertThat(forecasts).extracting(KpForecast::kp).containsExactly(6.0, 7.0);
        server.verify();
    }

    @Test
    @DisplayName("fetchOvation GETs the OVATION nowcast grid endpoint")
    void fetchOvationUsesTheOvationEndpoint() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NoaaSwpcClient client = client();

        server.expect(requestTo(OVATION_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("ovation.json"), MediaType.APPLICATION_JSON));

        OvationReading reading = client.fetchOvation();

        assertThat(reading).isNotNull();
        // Fixture holds 42 and 38 at latitude 55, plus decoys at 54 and 56 — the mean is 40.
        assertThat(reading.probabilityAtLatitude()).isEqualTo(40.0);
        assertThat(reading.latitude()).isEqualTo(OVATION_TARGET_LAT);
        server.verify();
    }

    @Test
    @DisplayName("fetchAlerts GETs the alerts endpoint")
    void fetchAlertsUsesTheAlertsEndpoint() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NoaaSwpcClient client = client();

        server.expect(requestTo(ALERTS_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("alerts.json"), MediaType.APPLICATION_JSON));

        List<SpaceWeatherAlert> alerts = client.fetchAlerts();

        assertThat(alerts).extracting(SpaceWeatherAlert::messageId).containsExactly("K05A");
        server.verify();
    }

    @Test
    @DisplayName("fetchViewline GETs the OVATION grid — the viewline is derived, not a separate feed")
    void fetchViewlineUsesTheOvationEndpoint() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NoaaSwpcClient client = client();

        server.expect(requestTo(OVATION_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("ovation.json"), MediaType.APPLICATION_JSON));

        assertThat(client.fetchViewline()).isNotNull();
        server.verify();
    }

    /**
     * The order of the two solar-wind GETs is the contract. Bz comes from {@code rtsw_mag_1m.json}
     * ({@code bz_gsm}) and speed/density from {@code rtsw_wind_1m.json} ({@code proton_speed},
     * {@code proton_density}). The fixtures are real samples of the live feeds (trimmed to two
     * minutes), including the inactive IMAP and ACE rows that sit beside the active SOLAR1 row, so
     * the content assertions fail if the parser ever reads a spacecraft NOAA is not using.
     *
     * <p>History: the first version of this test used array-of-arrays fixtures recorded from
     * {@code products/solar-wind/mag-1-day.json}, which NOAA withdrew (404) without anything here
     * noticing. Re-record these fixtures from the live feed if the shape changes again.
     */
    @Test
    @DisplayName("fetchSolarWind GETs mag before plasma, and takes Bz from mag / speed+density from plasma")
    void fetchSolarWindReadsMagThenPlasma() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NoaaSwpcClient client = client();

        server.expect(requestTo(SOLAR_WIND_MAG_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("solar-wind-mag.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(SOLAR_WIND_PLASMA_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("solar-wind-plasma.json"), MediaType.APPLICATION_JSON));

        List<SolarWindReading> readings = client.fetchSolarWind();

        // Active SOLAR1 rows only, oldest first; the IMAP/ACE rows (3.29, 359.85 ...) are ignored.
        assertThat(readings).hasSize(2);
        SolarWindReading reading = readings.get(0);
        assertThat(reading.timestamp().toString()).isEqualTo("2026-10-19T21:51Z");
        assertThat(reading.bzNanoTesla()).isEqualTo(2.78);
        assertThat(reading.speedKmPerSec()).isEqualTo(357.3);
        assertThat(reading.densityPerCm3()).isEqualTo(2.67);
        SolarWindReading latest = readings.get(1);
        assertThat(latest.bzNanoTesla()).isEqualTo(2.78);
        assertThat(latest.speedKmPerSec()).isEqualTo(356.9);
        assertThat(latest.densityPerCm3()).isEqualTo(2.85);
        server.verify();
    }

    /**
     * The copy-paste guard. Each endpoint is expected exactly once, order ignored: if any URL
     * getter were pasted over another, one expectation would go unmet and another would be called
     * twice, and {@code verify()} would fail. Nothing in the current suite can detect that.
     */
    @Test
    @DisplayName("fetchAll hits all six NOAA endpoints exactly once each — no endpoint is duplicated")
    void fetchAllHitsSixDistinctEndpoints() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true)
                .build();
        NoaaSwpcClient client = client();

        expectOnce(server, KP_URL, "kp.json");
        expectOnce(server, KP_FORECAST_URL, "kp-forecast.json");
        expectOnce(server, OVATION_URL, "ovation.json");
        expectOnce(server, SOLAR_WIND_MAG_URL, "solar-wind-mag.json");
        expectOnce(server, SOLAR_WIND_PLASMA_URL, "solar-wind-plasma.json");
        expectOnce(server, ALERTS_URL, "alerts.json");

        assertThat(client.fetchAll()).isNotNull();
        server.verify();
    }

    /**
     * Per-endpoint caching is what keeps the 5-minute aurora poll off NOAA's rate limiter. With a
     * fixed clock the second call is inside the 15-minute Kp TTL, so exactly one HTTP request may
     * be issued; a second would be an unexpected request and fail the test.
     */
    @Test
    @DisplayName("A second fetchKp inside the TTL is served from cache and issues no second request")
    void fetchKpIsCachedWithinItsTtl() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NoaaSwpcClient client = client();

        server.expect(ExpectedCount.once(), requestTo(KP_URL))
                .andRespond(withSuccess(fixture("kp.json"), MediaType.APPLICATION_JSON));

        List<KpReading> first = client.fetchKp();
        List<KpReading> second = client.fetchKp();

        assertThat(second).isEqualTo(first);
        server.verify();
    }

    /**
     * A 404 from NOAA arrives with its whole HTML error page in {@code getMessage()}. It was logged
     * in full, every five minutes, until 2026-10-19. The WARN must carry the failure, bounded.
     */
    @Test
    @DisplayName("A failed fetch logs the status but bounds a large error body to a single short line")
    void failedFetchLogsBoundedMessage() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NoaaSwpcClient client = client();
        server.expect(requestTo(SOLAR_WIND_MAG_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>" + "x".repeat(5_000) + "\nsecond line</html>"));

        Logger logger = (Logger) LoggerFactory.getLogger(NoaaSwpcClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(client.fetchSolarWind()).isEmpty();
        } finally {
            logger.detachAppender(appender);
        }

        List<ILoggingEvent> warnings = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warnings).hasSize(1);
        String message = warnings.get(0).getFormattedMessage();
        assertThat(message).contains("solar wind fetch failed").contains("404");
        assertThat(message.length()).isLessThan(300);
        assertThat(message).doesNotContain("\n");
    }

    // -- Helpers -------------------------------------------------------------

    private NoaaSwpcClient client() {
        return new NoaaSwpcClient(builder.build(), new AuroraProperties(), new ObjectMapper(), FIXED_CLOCK);
    }

    private static void expectOnce(MockRestServiceServer server, String url, String fixtureName) {
        server.expect(ExpectedCount.once(), requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture(fixtureName), MediaType.APPLICATION_JSON));
    }

    private static String fixture(String name) {
        String path = "/contract/noaa/" + name;
        try (InputStream in = NoaaSwpcClientContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                fail("Missing contract fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("Could not read contract fixture: " + path, e);
        }
    }
}
