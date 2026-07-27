package com.gregochr.goldenhour.client.contract;

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
import org.springframework.http.HttpMethod;
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
 * {@code mag-1-day.json} carries Bz, {@code plasma-1-day.json} carries speed and density. Swapping
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
            "https://services.swpc.noaa.gov/products/solar-wind/mag-1-day.json";
    private static final String SOLAR_WIND_PLASMA_URL =
            "https://services.swpc.noaa.gov/products/solar-wind/plasma-1-day.json";
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

        assertThat(client.fetchViewline(6.0)).isNotNull();
        server.verify();
    }

    /**
     * The order of the two solar-wind GETs is the contract. Bz comes from {@code mag-1-day.json}
     * (column 3) and speed/density from {@code plasma-1-day.json} (columns 2 and 1); both payloads
     * are NOAA array-of-arrays, so a swap parses cleanly and silently mis-assigns every field.
     * The fixtures use values no other column could produce, so the content assertions below fail
     * independently of the ordered expectations if the two URLs are ever transposed.
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

        assertThat(readings).hasSize(1);
        SolarWindReading reading = readings.get(0);
        assertThat(reading.bzNanoTesla()).isEqualTo(-8.5);
        assertThat(reading.speedKmPerSec()).isEqualTo(520.0);
        assertThat(reading.densityPerCm3()).isEqualTo(4.5);
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
