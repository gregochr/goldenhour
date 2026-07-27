package com.gregochr.goldenhour.client.contract;

import com.gregochr.goldenhour.client.OpenMeteoAirQualityApi;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
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
 * Outbound contract test for {@link OpenMeteoAirQualityApi} — the {@code @HttpExchange} proxy
 * behind the aerosol proxy signal (AOD + PM2.5), which is the project's stated differentiator.
 *
 * <p><strong>Why this exists.</strong> The only existing coverage,
 * {@code OpenMeteoClientTest}, mocks this interface, so {@code @GetExchange("/v1/air-quality")}
 * and the four {@code @RequestParam} bindings never execute. Pointing this proxy at
 * {@code /v1/forecast} — a live hazard, since {@code AppConfig} builds four near-identical proxy
 * beans differing only in base URL, path and type — would leave every current test green.
 *
 * <p><strong>The unit risk here is an <em>omitted</em> parameter, not a misspelled one.</strong>
 * Unlike the forecast endpoint this call sends no unit parameter at all, so
 * {@code aerosol_optical_depth} (dimensionless), {@code pm2_5} and {@code dust} (both µg/m³) are
 * relied on as server-side defaults. The scoring rules turn on absolute thresholds — the Sahara
 * Dust badge fires at AOD &gt; 0.3 with PM2.5 &lt; 35 — so a defaulted change of unit would move
 * the badge without any parse error. That mirrors {@code 92cb95b5}, where the unit parameter was
 * effectively absent because Open-Meteo silently ignored the plural spelling.
 *
 * <p><strong>Honest limit.</strong> The base URL is re-declared here from
 * {@code AppConfig.openMeteoAirQualityApi()}, so a wrong {@code baseUrl} in {@code AppConfig} is
 * out of reach; the path and parameter names are not.
 */
class OpenMeteoAirQualityApiContractTest {

    /** Base URL as declared in {@code AppConfig.openMeteoAirQualityApi()} — a distinct host. */
    private static final String BASE_URL = "https://air-quality-api.open-meteo.com";

    /** Durham, UK — asymmetric and signed, so a latitude/longitude transposition cannot pass. */
    private static final double LAT = 54.7753;
    private static final double LON = -1.5849;

    /** The hourly variable list as passed by {@code OpenMeteoClient.AIR_QUALITY_PARAMS}. */
    private static final String AIR_QUALITY_PARAMS = "pm2_5,dust,aerosol_optical_depth";

    private MockRestServiceServer server;
    private OpenMeteoAirQualityApi api;
    private String fixture;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.api = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(OpenMeteoAirQualityApi.class);
        this.fixture = readFixture("air-quality-response.json");
    }

    @Test
    @DisplayName("GET /v1/air-quality hits the air-quality path, not the forecast path")
    void airQualityRequestUsesTheAirQualityPath() {
        server.expect(requestTo(containsString(BASE_URL + "/v1/air-quality?")))
                .andExpect(requestTo(not(containsString("/v1/forecast"))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        api.getAirQuality(LAT, LON, AIR_QUALITY_PARAMS, "UTC");

        server.verify();
    }

    @Test
    @DisplayName("GET /v1/air-quality binds latitude/longitude/hourly/timezone by their declared names")
    void airQualityRequestBindsUnnamedParametersByDeclaredName() {
        server.expect(requestTo(containsString("/v1/air-quality")))
                .andExpect(requestTo(not(containsString("arg0"))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("latitude", "54.7753"))
                .andExpect(queryParam("longitude", "-1.5849"))
                .andExpect(queryParam("timezone", "UTC"))
                .andExpect(requestTo(containsString("hourly=pm2_5")))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        api.getAirQuality(LAT, LON, AIR_QUALITY_PARAMS, "UTC");

        server.verify();
    }

    /**
     * The aerosol proxy needs all three variables together — AOD alone cannot tell dust (warm
     * tones) from smoke (haze); it is the AOD-vs-PM2.5 pairing that does. Dropping one from the
     * request would leave a null column and a silently degraded badge, not an error.
     */
    @Test
    @DisplayName("GET /v1/air-quality requests all three aerosol variables, not just AOD")
    void airQualityRequestCarriesAllThreeAerosolVariables() {
        server.expect(requestTo(containsString("/v1/air-quality")))
                .andExpect(requestTo(containsString("pm2_5")))
                .andExpect(requestTo(containsString("dust")))
                .andExpect(requestTo(containsString("aerosol_optical_depth")))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        api.getAirQuality(LAT, LON, AIR_QUALITY_PARAMS, "UTC");

        server.verify();
    }

    @Test
    @DisplayName("A well-formed Open-Meteo air-quality body deserialises into OpenMeteoAirQualityResponse")
    void airQualityResponseDeserialises() {
        server.expect(requestTo(containsString("/v1/air-quality")))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        OpenMeteoAirQualityResponse response = api.getAirQuality(LAT, LON, AIR_QUALITY_PARAMS, "UTC");

        assertThat(response).isNotNull();
        assertThat(response.getHourly().getPm25()).containsExactly(6.4, 5.9);
        assertThat(response.getHourly().getDust()).containsExactly(12.0, 10.5);
        assertThat(response.getHourly().getAerosolOpticalDepth()).containsExactly(0.11, 0.09);
    }

    private static String readFixture(String name) {
        String path = "/contract/openmeteo/" + name;
        try (InputStream in = OpenMeteoAirQualityApiContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                fail("Missing contract fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("Could not read contract fixture: " + path, e);
        }
    }
}
