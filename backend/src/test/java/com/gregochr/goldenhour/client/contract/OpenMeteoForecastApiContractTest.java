package com.gregochr.goldenhour.client.contract;

import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Outbound contract test for {@link OpenMeteoForecastApi} — the {@code @HttpExchange} proxy that
 * every single-point forecast call in the application goes through.
 *
 * <p><strong>Why this exists.</strong> {@code OpenMeteoClientTest} declares
 * {@code @Mock OpenMeteoForecastApi} and asserts the <em>arguments handed to the proxy</em>
 * ({@code verify(...).getForecast(..., eq("ms"), eq("UTC"))}). That pins the values but never the
 * parameter names they land on — the annotation binding never executes, so the URL Spring actually
 * builds is untested. This test rebuilds the proxy with the real
 * {@link HttpServiceProxyFactory} + {@link RestClientAdapter} over a
 * {@link MockRestServiceServer}, so {@code @GetExchange} and every {@code @RequestParam} really run.
 *
 * <p><strong>Defect cited.</strong> {@code 92cb95b5} — "AuroraWeatherEnricher —
 * wind_speed_units → wind_speed_unit (singular). Open-Meteo silently ignored the unknown plural
 * parameter and returned km/h (default). The backend stored km/h values as if m/s, causing
 * msToMph() to inflate wind by x3.6 (77mph displayed instead of ~21mph actual surface wind)."
 * That was a different call site carrying the same literal; this interface has never been
 * URL-asserted at all. Note the contains/does-not-contain pair below is required: asserting
 * {@code wind_speed_unit=ms} alone still passes when the typo'd spelling is <em>also</em> sent.
 *
 * <p><strong>Honest limit.</strong> The base URL is baked into
 * {@code AppConfig.openMeteoForecastApi()} and re-declared here, so this test verifies the path,
 * the parameter names, the parameter values and the ordering of latitude vs longitude — it cannot
 * catch a wrong {@code baseUrl} in {@code AppConfig}. Nor can it catch inventing a parameter
 * Open-Meteo does not serve; the mock returns whatever fixture it is handed.
 */
class OpenMeteoForecastApiContractTest {

    /** Base URL as declared in {@code AppConfig.openMeteoForecastApi()}. */
    private static final String BASE_URL = "https://api.open-meteo.com";

    /** Durham, UK — asymmetric and signed, so a latitude/longitude transposition cannot pass. */
    private static final double LAT = 54.7753;
    private static final double LON = -1.5849;

    /** The hourly variable list as passed by {@code OpenMeteoClient.fetchCloudOnly}. */
    private static final String CLOUD_ONLY_PARAMS = "cloud_cover_low,cloud_cover_mid,cloud_cover_high";

    private MockRestServiceServer server;
    private OpenMeteoForecastApi api;
    private String fixture;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.api = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(OpenMeteoForecastApi.class);
        this.fixture = readFixture("forecast-response.json");
    }

    @Test
    @DisplayName("GET /v1/forecast carries wind_speed_unit=ms — singular, and not the plural typo (92cb95b5)")
    void forecastRequestCarriesWindSpeedUnitSingular() {
        server.expect(requestTo(containsString(BASE_URL + "/v1/forecast?")))
                .andExpect(requestTo(not(containsString("wind_speed_units"))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("wind_speed_unit", "ms"))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        api.getForecast(LAT, LON, CLOUD_ONLY_PARAMS, "ms", "UTC");

        server.verify();
    }

    @Test
    @DisplayName("GET /v1/forecast sends timezone=UTC — omitting it silently returns GMT-anchored timestamps")
    void forecastRequestCarriesTimezoneUtc() {
        server.expect(requestTo(containsString("/v1/forecast")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("timezone", "UTC"))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        api.getForecast(LAT, LON, CLOUD_ONLY_PARAMS, "ms", "UTC");

        server.verify();
    }

    /**
     * The four unnamed {@code @RequestParam}s (latitude, longitude, hourly, timezone) rely on
     * Spring reading parameter names from the {@code -parameters} compiler flag. Lose that flag and
     * they silently become {@code arg0}, {@code arg1}, ... — which Open-Meteo would ignore, falling
     * back to defaults. Asserting the exact names and the exact signed values also pins the
     * latitude/longitude ordering.
     */
    @Test
    @DisplayName("GET /v1/forecast binds latitude/longitude/hourly by their declared names, not arg0/arg1")
    void forecastRequestBindsUnnamedParametersByDeclaredName() {
        server.expect(requestTo(containsString("/v1/forecast")))
                .andExpect(requestTo(not(containsString("arg0"))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("latitude", "54.7753"))
                .andExpect(queryParam("longitude", "-1.5849"))
                .andExpect(requestTo(containsString("hourly=cloud_cover_low")))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        api.getForecast(LAT, LON, CLOUD_ONLY_PARAMS, "ms", "UTC");

        server.verify();
    }

    @Test
    @DisplayName("A well-formed Open-Meteo forecast body deserialises into OpenMeteoForecastResponse")
    void forecastResponseDeserialises() {
        server.expect(requestTo(containsString("/v1/forecast")))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        OpenMeteoForecastResponse response = api.getForecast(LAT, LON, CLOUD_ONLY_PARAMS, "ms", "UTC");

        assertThat(response).isNotNull();
        assertThat(response.getHourly().getTime()).containsExactly("2026-07-26T19:00", "2026-07-26T20:00");
        assertThat(response.getHourly().getCloudCoverLow()).containsExactly(12, 8);
        // 4.2 is a metres-per-second value: the fixture is what the API returns *because*
        // wind_speed_unit=ms was requested. In km/h defaults this would read 4.2 km/h and be
        // stored as 4.2 m/s — the 92cb95b5 failure mode.
        assertThat(response.getHourly().getWindSpeed10m()).containsExactly(4.2, 3.8);
    }

    private static String readFixture(String name) {
        String path = "/contract/openmeteo/" + name;
        try (InputStream in = OpenMeteoForecastApiContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                fail("Missing contract fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("Could not read contract fixture: " + path, e);
        }
    }
}
