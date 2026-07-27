package com.gregochr.goldenhour.client.contract;

import com.gregochr.goldenhour.client.OpenMeteoMarineApi;
import com.gregochr.goldenhour.model.OpenMeteoMarineResponse;
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
 * Outbound contract test for {@link OpenMeteoMarineApi} — sea state for coastal briefing triage.
 *
 * <p><strong>Why this exists.</strong> {@code MarineClientTest} mocks this interface, so the
 * annotation binding never executes. The marine API lives on a <em>fourth</em> host
 * ({@code marine-api.open-meteo.com}) while {@code AppConfig} declares four near-identical proxy
 * beans that differ only in base URL, path and type — the most likely mistake in that block is
 * cross-wiring one of them, and no current test would notice.
 *
 * <p><strong>The unit risk is an omitted parameter.</strong> {@code wave_height} and
 * {@code swell_wave_height} are metres and {@code wave_direction} is degrees only because
 * Open-Meteo's defaults say so — this call sends no {@code length_unit}. That is the mirror image
 * of {@code 92cb95b5}: there the unit parameter was present but misspelled and therefore ignored,
 * here it is absent by design. Either way the number arrives in whatever unit the server picks,
 * with no parse error. This test pins the variables actually requested so a change of requested
 * variable (which would change the unit) is visible.
 *
 * <p><strong>Honest limit.</strong> The base URL is re-declared here from
 * {@code AppConfig.openMeteoMarineApi()}, so a wrong {@code baseUrl} there is out of reach; the
 * path and parameter names are not. Note also that an empty {@code hourly} array is a legitimate
 * marine response for a land grid cell — indistinguishable at the wire level from a failure.
 */
class OpenMeteoMarineApiContractTest {

    /** Base URL as declared in {@code AppConfig.openMeteoMarineApi()} — a distinct host. */
    private static final String BASE_URL = "https://marine-api.open-meteo.com";

    /** Bamburgh, UK — asymmetric and signed, so a latitude/longitude transposition cannot pass. */
    private static final double LAT = 55.6089;
    private static final double LON = -1.7099;

    /** Hourly variables and horizon as passed by {@code MarineClient.fetchMarine}. */
    private static final String HOURLY_PARAMS = "wave_height,swell_wave_height,wave_direction";
    private static final int FORECAST_DAYS = 7;

    private MockRestServiceServer server;
    private OpenMeteoMarineApi api;
    private String fixture;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.api = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(OpenMeteoMarineApi.class);
        this.fixture = readFixture("marine-response.json");
    }

    @Test
    @DisplayName("GET /v1/marine hits the marine path on the marine host, not the forecast path")
    void marineRequestUsesTheMarinePath() {
        server.expect(requestTo(containsString(BASE_URL + "/v1/marine?")))
                .andExpect(requestTo(not(containsString("/v1/forecast"))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        api.getMarine(LAT, LON, HOURLY_PARAMS, "UTC", FORECAST_DAYS);

        server.verify();
    }

    /**
     * {@code forecast_days} carries an explicit {@code @RequestParam} name because the Java
     * parameter is camelCase. Sending {@code forecastDays} instead would be silently ignored and
     * Open-Meteo would return its default horizon — a shorter series that still parses, so the
     * briefing would simply find no sample at a high water beyond the default window.
     */
    @Test
    @DisplayName("GET /v1/marine sends snake_case forecast_days=7, not camelCase forecastDays")
    void marineRequestSendsSnakeCaseForecastDays() {
        server.expect(requestTo(containsString("/v1/marine")))
                .andExpect(requestTo(not(containsString("forecastDays"))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("forecast_days", "7"))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        api.getMarine(LAT, LON, HOURLY_PARAMS, "UTC", FORECAST_DAYS);

        server.verify();
    }

    @Test
    @DisplayName("GET /v1/marine binds latitude/longitude/hourly/timezone by their declared names")
    void marineRequestBindsUnnamedParametersByDeclaredName() {
        server.expect(requestTo(containsString("/v1/marine")))
                .andExpect(requestTo(not(containsString("arg0"))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("latitude", "55.6089"))
                .andExpect(queryParam("longitude", "-1.7099"))
                .andExpect(queryParam("timezone", "UTC"))
                .andExpect(requestTo(containsString("hourly=wave_height")))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        api.getMarine(LAT, LON, HOURLY_PARAMS, "UTC", FORECAST_DAYS);

        server.verify();
    }

    @Test
    @DisplayName("A well-formed Open-Meteo marine body deserialises into OpenMeteoMarineResponse")
    void marineResponseDeserialises() {
        server.expect(requestTo(containsString("/v1/marine")))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        OpenMeteoMarineResponse response = api.getMarine(LAT, LON, HOURLY_PARAMS, "UTC", FORECAST_DAYS);

        assertThat(response).isNotNull();
        // Metres and degrees — the defaults this call relies on by omitting any unit parameter.
        assertThat(response.getHourly().getWaveHeight()).containsExactly(1.24, 1.31);
        assertThat(response.getHourly().getSwellWaveHeight()).containsExactly(0.82, 0.87);
        assertThat(response.getHourly().getWaveDirection()).containsExactly(48, 52);
    }

    private static String readFixture(String name) {
        String path = "/contract/openmeteo/" + name;
        try (InputStream in = OpenMeteoMarineApiContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                fail("Missing contract fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("Could not read contract fixture: " + path, e);
        }
    }
}
