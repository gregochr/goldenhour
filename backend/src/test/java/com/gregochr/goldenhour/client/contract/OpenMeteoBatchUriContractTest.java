package com.gregochr.goldenhour.client.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.client.OpenMeteoAirQualityApi;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.service.OpenMeteoClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Outbound contract test for the hand-rolled multi-coordinate (batch) Open-Meteo URIs built inside
 * {@code OpenMeteoClient} — the forecast chunk and the air-quality chunk.
 *
 * <p><strong>Why this exists.</strong> These two URIs are assembled in {@code UriBuilder} lambdas
 * with the parameter names written inline as string literals. Every existing test stubs them
 * through {@code RestClientMocks}, whose own Javadoc says it: "Because {@code uri(...)} is matched
 * with {@code any(...)}, these helpers do NOT exercise URL-building." The lambda body still runs —
 * its output is simply thrown away. So the path, every parameter name, the {@code ms} unit value,
 * the timezone and a latitude/longitude transposition are all currently unasserted; only the
 * <em>number</em> of chunks and the parse of the canned JSON are checked.
 *
 * <p><strong>Defects cited.</strong>
 * <ul>
 *   <li>{@code 92cb95b5} — {@code wind_speed_units} (plural) was silently ignored by Open-Meteo,
 *       which returned km/h defaults; the backend stored km/h as m/s and displayed 77 mph instead
 *       of ~21 mph. That was a different call site carrying this same {@code ms} literal.</li>
 *   <li>{@code fddab290} — "chunk batch requests to &le;20 coords per HTTP call to prevent HTTP 414
 *       URI-too-large with 50+ production locations".</li>
 *   <li>{@code b534cf31} — the inter-chunk pause, motivated by "200+ grid cells &rarr; 10+ chunks".
 *       That cloud pre-fetch, not the ~50-location roster, is the shape that actually produced the
 *       414: hundreds of {@code GeoUtils} grid cells stringified at full {@code double}
 *       precision.</li>
 * </ul>
 *
 * <p><strong>Reflection, and why.</strong> {@code OpenMeteoClient} lives in the {@code service}
 * package and exposes its {@code RestClient}-injecting constructor, its {@code BATCH_COORD_LIMIT}
 * and its overridable {@code interChunkDelayMs} at package visibility. Reaching them from this
 * contract package needs reflection. The alternative — re-building the URI in the test — would be
 * self-referential and would assert nothing, so reflection is the honest choice here. See the
 * report notes: making {@code BATCH_COORD_LIMIT} public, or homing this class in the
 * {@code service} package, would remove it.
 */
class OpenMeteoBatchUriContractTest {

    /** Base URL as built in {@code OpenMeteoClient}'s autowired constructor. */
    private static final String FORECAST_BASE_URL = "https://api.open-meteo.com";

    /** Air-quality base URL as built in {@code OpenMeteoClient}'s autowired constructor. */
    private static final String AIR_QUALITY_BASE_URL = "https://air-quality-api.open-meteo.com";

    /**
     * Conservative request-line ceiling. Common front-ends (nginx {@code large_client_header_buffers},
     * Apache {@code LimitRequestLine}) default to 8k; exceeding it is the HTTP 414 of
     * {@code fddab290}.
     */
    private static final int URI_LIMIT_BYTES = 8192;

    /** The cloud pre-fetch shape from {@code b534cf31} — hundreds of grid cells in one call. */
    private static final int GRID_CELL_COUNT = 200;

    private MockRestServiceServer forecastServer;
    private MockRestServiceServer airQualityServer;
    private OpenMeteoClient client;
    private final List<String> requestUris = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder forecastBuilder = RestClient.builder().baseUrl(FORECAST_BASE_URL);
        this.forecastServer = MockRestServiceServer.bindTo(forecastBuilder).build();
        RestClient.Builder airQualityBuilder = RestClient.builder().baseUrl(AIR_QUALITY_BASE_URL);
        this.airQualityServer = MockRestServiceServer.bindTo(airQualityBuilder).build();
        this.client = newClientWithoutInterChunkDelay(forecastBuilder.build(), airQualityBuilder.build());
        this.requestUris.clear();
    }

    @Test
    @DisplayName("Batch forecast URI carries wind_speed_unit=ms — singular, and not the plural typo (92cb95b5)")
    void batchForecastUriCarriesWindSpeedUnitSingular() {
        forecastServer.expect(requestTo(containsString(FORECAST_BASE_URL + "/v1/forecast?")))
                .andExpect(requestTo(not(containsString("wind_speed_units"))))
                .andExpect(queryParam("wind_speed_unit", "ms"))
                .andExpect(queryParam("timezone", "UTC"))
                .andRespond(withSuccess(readFixture("forecast-batch-array.json"), MediaType.APPLICATION_JSON));

        client.fetchForecastBatch(List.of(new double[]{54.7753, -1.5849}, new double[]{55.6089, -1.7099}));

        forecastServer.verify();
    }

    /**
     * The two coordinate lists are comma-joined independently and must stay aligned and the right
     * way round. A transposition would return a valid 200 for points in the North Sea, so the
     * signed longitudes here are what make the assertion bite.
     */
    @Test
    @DisplayName("Batch forecast URI comma-joins latitudes and longitudes in input order, not transposed")
    void batchForecastUriJoinsCoordinatesInOrder() {
        forecastServer.expect(requestTo(containsString("/v1/forecast")))
                .andExpect(captureUri())
                .andRespond(withSuccess(readFixture("forecast-batch-array.json"), MediaType.APPLICATION_JSON));

        client.fetchForecastBatch(List.of(new double[]{54.7753, -1.5849}, new double[]{55.6089, -1.7099}));

        forecastServer.verify();
        String decoded = decodedUri(0);
        assertThat(decoded).contains("latitude=54.7753,55.6089");
        assertThat(decoded).contains("longitude=-1.5849,-1.7099");
    }

    /**
     * {@code fddab290} + {@code b534cf31}. Drives the real cloud pre-fetch shape — 200 grid cells
     * at full {@code double} precision, which is what {@code GeoUtils} offsets actually stringify
     * to — and asserts the chunk guard holds on every individual request line, not just on the
     * happy 20-coord case.
     */
    @Test
    @DisplayName("Batch forecast chunks at BATCH_COORD_LIMIT so every URI stays under 8192 bytes (fddab290)")
    void batchForecastUriStaysUnderTheServerLimitAtTwoHundredGridCells() {
        int coordLimit = batchCoordLimit();
        int expectedChunks = (int) Math.ceil((double) GRID_CELL_COUNT / coordLimit);

        forecastServer.expect(times(expectedChunks), requestTo(containsString("/v1/forecast")))
                .andExpect(captureUri())
                .andRespond(withSuccess(readFixture("forecast-batch-array.json"), MediaType.APPLICATION_JSON));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBatch(fullPrecisionGridCells());

        forecastServer.verify();
        assertThat(results).hasSize(GRID_CELL_COUNT);
        assertThat(requestUris).hasSize(expectedChunks);
        for (int i = 0; i < requestUris.size(); i++) {
            String raw = requestUris.get(i);
            assertThat(raw.length())
                    .as("encoded request-line length of chunk %d", i + 1)
                    .isLessThan(URI_LIMIT_BYTES);
            assertThat(latitudeCount(decodedUri(i)))
                    .as("coordinates carried by chunk %d", i + 1)
                    .isLessThanOrEqualTo(coordLimit);
        }
    }

    /**
     * The air-quality lambda is a near-copy of the forecast one, on a different host and path.
     * A copy-paste that left {@code /v1/forecast} in place would return a well-formed forecast
     * body whose aerosol fields are simply absent — no parse error, just a permanently null
     * Sahara Dust badge.
     */
    @Test
    @DisplayName("Batch air-quality URI uses /v1/air-quality with the three aerosol variables")
    void batchAirQualityUriUsesTheAirQualityPath() {
        airQualityServer.expect(requestTo(containsString(AIR_QUALITY_BASE_URL + "/v1/air-quality?")))
                .andExpect(requestTo(not(containsString("/v1/forecast"))))
                .andExpect(requestTo(containsString("pm2_5")))
                .andExpect(requestTo(containsString("dust")))
                .andExpect(requestTo(containsString("aerosol_optical_depth")))
                .andExpect(queryParam("timezone", "UTC"))
                .andExpect(captureUri())
                .andRespond(withSuccess(readFixture("air-quality-batch-array.json"), MediaType.APPLICATION_JSON));

        List<OpenMeteoAirQualityResponse> results = client.fetchAirQualityBatch(
                List.of(new double[]{54.7753, -1.5849}, new double[]{55.6089, -1.7099}));

        airQualityServer.verify();
        assertThat(results).hasSize(2);
        assertThat(decodedUri(0)).contains("latitude=54.7753,55.6089");
    }

    /**
     * The directional-cloud pre-fetch runs at hundreds of points per cycle, so it deliberately
     * asks for three cloud variables rather than the full forecast set. Sending the full set would
     * not fail — it would quietly multiply the response payload and push the URI towards the 414
     * boundary that {@code fddab290} fixed.
     */
    @Test
    @DisplayName("Batch cloud-only URI requests the three cloud layers only, not the full forecast set")
    void batchCloudOnlyUriRequestsCloudLayersOnly() {
        forecastServer.expect(requestTo(containsString("/v1/forecast")))
                .andExpect(requestTo(containsString("cloud_cover_low")))
                .andExpect(requestTo(containsString("cloud_cover_mid")))
                .andExpect(requestTo(containsString("cloud_cover_high")))
                .andExpect(requestTo(not(containsString("visibility"))))
                .andExpect(requestTo(not(containsString("shortwave_radiation"))))
                .andExpect(queryParam("wind_speed_unit", "ms"))
                .andRespond(withSuccess(readFixture("forecast-batch-array.json"), MediaType.APPLICATION_JSON));

        client.fetchCloudOnlyBatch(List.of(new double[]{54.7753, -1.5849}, new double[]{55.6089, -1.7099}));

        forecastServer.verify();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private RequestMatcher captureUri() {
        return request -> requestUris.add(request.getURI().toString());
    }

    private String decodedUri(int index) {
        return URLDecoder.decode(requestUris.get(index), StandardCharsets.UTF_8);
    }

    private static int latitudeCount(String decodedUri) {
        int start = decodedUri.indexOf("latitude=") + "latitude=".length();
        int end = decodedUri.indexOf('&', start);
        String value = end < 0 ? decodedUri.substring(start) : decodedUri.substring(start, end);
        return value.split(",").length;
    }

    /**
     * Grid cells with the digit count a computed {@code GeoUtils} offset actually produces — the
     * long-decimal shape is what made the unchunked pre-fetch URI overflow, not round numbers.
     */
    private static List<double[]> fullPrecisionGridCells() {
        List<double[]> coords = new ArrayList<>(GRID_CELL_COUNT);
        for (int i = 0; i < GRID_CELL_COUNT; i++) {
            coords.add(new double[]{54.0 + i / 57.29577951308232, -1.0 - i / 91.67324722093171});
        }
        return coords;
    }

    /**
     * Builds an {@code OpenMeteoClient} over the two mock-bound {@link RestClient}s using the
     * package-private test constructor, and zeroes the inter-chunk sleep so the multi-chunk test
     * does not pause 3 seconds per chunk. Both members are package-visible in {@code service} and
     * unreachable from here without reflection.
     */
    private static OpenMeteoClient newClientWithoutInterChunkDelay(RestClient forecastRestClient,
            RestClient airQualityRestClient) {
        try {
            Constructor<OpenMeteoClient> constructor = OpenMeteoClient.class.getDeclaredConstructor(
                    OpenMeteoForecastApi.class, OpenMeteoAirQualityApi.class, ObjectMapper.class,
                    RestClient.class, RestClient.class);
            constructor.setAccessible(true);
            OpenMeteoClient created = constructor.newInstance(
                    mock(OpenMeteoForecastApi.class), mock(OpenMeteoAirQualityApi.class),
                    new ObjectMapper(), forecastRestClient, airQualityRestClient);
            Field delay = OpenMeteoClient.class.getDeclaredField("interChunkDelayMs");
            delay.setAccessible(true);
            delay.setLong(created, 0L);
            return created;
        } catch (ReflectiveOperationException e) {
            return fail("Could not construct OpenMeteoClient for the contract test", e);
        }
    }

    /** Reads the production chunk size rather than hard-coding 20, so the test tracks the guard. */
    private static int batchCoordLimit() {
        try {
            Field field = OpenMeteoClient.class.getDeclaredField("BATCH_COORD_LIMIT");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException e) {
            return fail("Could not read OpenMeteoClient.BATCH_COORD_LIMIT", e);
        }
    }

    private static String readFixture(String name) {
        String path = "/contract/openmeteo/" + name;
        try (InputStream in = OpenMeteoBatchUriContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                fail("Missing contract fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("Could not read contract fixture: " + path, e);
        }
    }
}
