package com.gregochr.goldenhour.client.contract;

import com.gregochr.goldenhour.client.OpenRouteServiceClient;
import com.gregochr.goldenhour.config.OrsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Outbound contract test for {@link OpenRouteServiceClient} — the only outbound call in the tree
 * whose contract lives in a request <em>body</em> rather than a query string.
 *
 * <p><strong>Why this exists.</strong> {@code OpenRouteServiceClientTest} stubs through
 * {@code RestClientMocks}, whose {@code stubPost} matches {@code body(any(Object.class))}: the
 * request body is never inspected. Today the matrix URL, the transport profile, the
 * {@code Authorization} header, {@code metrics}, {@code sources} and a full coordinate
 * transposition could all be wrong and every test would stay green.
 *
 * <p><strong>Coordinate order (the {@code 92cb95b5} shape, again).</strong> ORS speaks GeoJSON
 * {@code [longitude, latitude]}; the client inverts each caller-supplied {@code [lat, lon]} pair on
 * the way out. Transposed, every UK point lands in the Indian Ocean off Somalia, ORS returns nulls,
 * {@code fetchDurations} logs a warning and returns an empty list, and
 * {@code DriveDurationService} treats that as "ORS returned empty durations" and moves on. The
 * failure mode is a <em>silent absence</em> of drive times, not an error — precisely the
 * {@code 8fb3ad26} lesson that empty is indistinguishable from an honest decline.
 *
 * <p><strong>Enum risk.</strong> {@code metrics: ["duration"]} versus {@code ["distance"]} returns
 * the same response schema in entirely different units (seconds versus metres), and
 * {@code DriveDurationService} rounds the number straight into a drive-time field. The
 * {@code driving-car} profile is baked into the URL path, and {@code foot-walking} /
 * {@code cycling-regular} would each return a well-formed, plausible, useless matrix.
 */
class OpenRouteServiceClientContractTest {

    private static final String MATRIX_URL = "https://api.openrouteservice.org/v2/matrix/driving-car";

    /** Obvious placeholder. Never a real key. */
    private static final String PLACEHOLDER_KEY = "placeholder-not-a-real-key";

    /** Durham, UK — the source point. Signed and asymmetric so a transposition cannot pass. */
    private static final double SOURCE_LAT = 54.7753;
    private static final double SOURCE_LON = -1.5849;

    /** Two destinations as {@code [lat, lon]}, the order {@code fetchDurations} accepts. */
    private static final List<double[]> DESTINATIONS = List.of(
            new double[] {55.0383, -1.6100},
            new double[] {54.4500, -3.2100});

    private MockRestServiceServer server;
    private OpenRouteServiceClient client;
    private OrsProperties properties;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.properties = new OrsProperties();
        this.properties.setEnabled(true);
        this.properties.setApiKey(PLACEHOLDER_KEY);
        this.client = new OpenRouteServiceClient(builder.build(), properties);
    }

    @Test
    @DisplayName("POSTs to the matrix endpoint with the driving-car profile in the path")
    void postsToTheDrivingCarMatrixEndpoint() {
        server.expect(requestTo(MATRIX_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));

        client.fetchDurations(SOURCE_LAT, SOURCE_LON, DESTINATIONS);

        server.verify();
    }

    /**
     * The assertion that catches the inversion. Element {@code [0]} of each coordinate pair must be
     * the longitude — negative for the UK — and element {@code [1]} the latitude. Asserting both
     * halves with signed values means no transposition can satisfy the pair.
     */
    @Test
    @DisplayName("Body sends every coordinate as GeoJSON [longitude, latitude], source at index 0")
    void bodySendsCoordinatesInGeoJsonLongitudeLatitudeOrder() {
        server.expect(requestTo(MATRIX_URL))
                .andExpect(jsonPath("$.locations[0][0]").value(SOURCE_LON))
                .andExpect(jsonPath("$.locations[0][1]").value(SOURCE_LAT))
                .andExpect(jsonPath("$.locations[1][0]").value(-1.6100))
                .andExpect(jsonPath("$.locations[1][1]").value(55.0383))
                .andExpect(jsonPath("$.locations[2][0]").value(-3.2100))
                .andExpect(jsonPath("$.locations[2][1]").value(54.4500))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));

        client.fetchDurations(SOURCE_LAT, SOURCE_LON, DESTINATIONS);

        server.verify();
    }

    @Test
    @DisplayName("Body asks for metrics=duration (seconds) — not distance (metres), same schema")
    void bodyAsksForDurationMetric() {
        server.expect(requestTo(MATRIX_URL))
                .andExpect(jsonPath("$.metrics[0]").value("duration"))
                .andExpect(jsonPath("$.sources[0]").value(0))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));

        client.fetchDurations(SOURCE_LAT, SOURCE_LON, DESTINATIONS);

        server.verify();
    }

    /**
     * Placement and scheme only — the key's value is never asserted, and the one used here is a
     * placeholder. ORS authenticates with a bare {@code Authorization} header; a key moved to a
     * query parameter would be rejected with a 403 in production and silently pass every mock test.
     */
    @Test
    @DisplayName("The API key rides an Authorization: Bearer header, not the URL")
    void apiKeyIsSentAsABearerHeader() {
        server.expect(requestTo(MATRIX_URL))
                .andExpect(header("Authorization", startsWith("Bearer ")))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));

        client.fetchDurations(SOURCE_LAT, SOURCE_LON, DESTINATIONS);

        server.verify();
    }

    @Test
    @DisplayName("The source-to-source zero is stripped, leaving one duration per destination in order")
    void responseStripsTheSourceToSourceEntry() {
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));

        List<Double> durations = client.fetchDurations(SOURCE_LAT, SOURCE_LON, DESTINATIONS);

        assertThat(durations).containsExactly(1800.5, 3600.25);
        server.verify();
    }

    /**
     * {@code isConfigured()} gates the whole method. Asserting that <em>no</em> request is issued
     * matters because an unconfigured ORS must not burn a request (or leak a blank key) — and
     * because a mock-stubbed test cannot tell "returned empty because unconfigured" from "returned
     * empty because the call failed".
     */
    @Test
    @DisplayName("An unconfigured ORS issues no HTTP request at all")
    void unconfiguredOrsIssuesNoRequest() {
        properties.setApiKey("");

        List<Double> durations = client.fetchDurations(SOURCE_LAT, SOURCE_LON, DESTINATIONS);

        assertThat(durations).isEmpty();
        server.verify();
    }

    private static String fixture() {
        String path = "/contract/ors/matrix-response.json";
        try (InputStream in = OpenRouteServiceClientContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                fail("Missing contract fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("Could not read contract fixture: " + path, e);
        }
    }
}
