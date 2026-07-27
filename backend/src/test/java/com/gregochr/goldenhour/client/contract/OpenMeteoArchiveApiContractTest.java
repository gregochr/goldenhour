package com.gregochr.goldenhour.client.contract;

import com.gregochr.goldenhour.client.OpenMeteoArchiveApi;
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
 * Outbound contract test for {@link OpenMeteoArchiveApi} — the reanalysis endpoint that backs the
 * cloud-verification accuracy channel.
 *
 * <p><strong>Why the archive is the highest-consequence silent-wrong on the Open-Meteo surface.</strong>
 * {@code actual_outcome} is empty, so reanalysis is the only thing that can score a past forecast.
 * A wrong {@code start_date}/{@code end_date} still returns HTTP 200 with a well-formed body — for
 * the wrong day. The verification report then looks plausible and means nothing. Unlike a units
 * bug there is no downstream magnitude that looks obviously off.
 *
 * <p><strong>Known duplication, reported not fixed.</strong> This proxy bean is declared at
 * {@code AppConfig.openMeteoArchiveApi()} but injected nowhere — a grep across {@code src/main}
 * finds only {@code AppConfig} and the interface itself. The archive is actually reached through
 * the hand-rolled {@code OpenMeteoArchiveClient}, which builds the same URI by lambda. Two live
 * declarations of one endpoint contract that nothing keeps in sync is exactly the drift this tier
 * exists to catch; this test pins the declarative half.
 *
 * <p><strong>Honest limit.</strong> The base URL is re-declared here from {@code AppConfig}, so a
 * wrong {@code baseUrl} there is out of reach; the path and parameter names are not.
 */
class OpenMeteoArchiveApiContractTest {

    /** Base URL as declared in {@code AppConfig.openMeteoArchiveApi()} — a distinct host. */
    private static final String BASE_URL = "https://archive-api.open-meteo.com";

    /** Durham, UK — asymmetric and signed, so a latitude/longitude transposition cannot pass. */
    private static final double LAT = 54.7753;
    private static final double LON = -1.5849;

    /** Deliberately different from {@link #END_DATE} so a start/end swap cannot pass. */
    private static final String START_DATE = "2026-07-10";
    private static final String END_DATE = "2026-07-12";

    /** The cloud variables the verification path asks the archive for. */
    private static final String HOURLY_PARAMS = "cloud_cover_low,cloud_cover_mid,cloud_cover_high";

    private MockRestServiceServer server;
    private OpenMeteoArchiveApi api;
    private String fixture;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.api = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(OpenMeteoArchiveApi.class);
        this.fixture = readFixture("archive-response.json");
    }

    @Test
    @DisplayName("GET /v1/archive hits the archive path, not the forecast path")
    void archiveRequestUsesTheArchivePath() {
        server.expect(requestTo(containsString(BASE_URL + "/v1/archive?")))
                .andExpect(requestTo(not(containsString("/v1/forecast"))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        api.getArchive(LAT, LON, START_DATE, END_DATE, HOURLY_PARAMS, "UTC");

        server.verify();
    }

    /**
     * {@code start_date} and {@code end_date} carry explicit {@code @RequestParam} names because
     * the Java parameters are camelCase. Losing either name would send {@code startDate=...},
     * which Open-Meteo ignores — returning its default window rather than failing.
     */
    @Test
    @DisplayName("GET /v1/archive sends snake_case start_date and end_date, in that order, un-swapped")
    void archiveRequestSendsSnakeCaseDatesInOrder() {
        server.expect(requestTo(containsString("/v1/archive")))
                .andExpect(requestTo(not(containsString("startDate"))))
                .andExpect(requestTo(not(containsString("endDate"))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("start_date", START_DATE))
                .andExpect(queryParam("end_date", END_DATE))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        api.getArchive(LAT, LON, START_DATE, END_DATE, HOURLY_PARAMS, "UTC");

        server.verify();
    }

    @Test
    @DisplayName("GET /v1/archive binds latitude/longitude/hourly/timezone by their declared names")
    void archiveRequestBindsUnnamedParametersByDeclaredName() {
        server.expect(requestTo(containsString("/v1/archive")))
                .andExpect(requestTo(not(containsString("arg0"))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("latitude", "54.7753"))
                .andExpect(queryParam("longitude", "-1.5849"))
                .andExpect(queryParam("timezone", "UTC"))
                .andExpect(requestTo(containsString("hourly=cloud_cover_low")))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        api.getArchive(LAT, LON, START_DATE, END_DATE, HOURLY_PARAMS, "UTC");

        server.verify();
    }

    @Test
    @DisplayName("An archive body reuses OpenMeteoForecastResponse and deserialises the cloud layers")
    void archiveResponseDeserialises() {
        server.expect(requestTo(containsString("/v1/archive")))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        OpenMeteoForecastResponse response =
                api.getArchive(LAT, LON, START_DATE, END_DATE, HOURLY_PARAMS, "UTC");

        assertThat(response).isNotNull();
        assertThat(response.getHourly().getTime()).containsExactly("2026-07-10T19:00", "2026-07-10T20:00");
        assertThat(response.getHourly().getCloudCoverLow()).containsExactly(88, 91);
    }

    private static String readFixture(String name) {
        String path = "/contract/openmeteo/" + name;
        try (InputStream in = OpenMeteoArchiveApiContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                fail("Missing contract fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("Could not read contract fixture: " + path, e);
        }
    }
}
