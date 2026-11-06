package com.gregochr.goldenhour.client.contract;

import com.gregochr.goldenhour.client.OpenRouteServiceClient;
import com.gregochr.goldenhour.client.OpenRouteServiceClient.OrsUnroutablePointException;
import com.gregochr.goldenhour.client.OpenRouteServiceClient.RouteProbe;
import com.gregochr.goldenhour.client.OrsRateLimiter;
import com.gregochr.goldenhour.config.OrsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Outbound contract test for {@link OpenRouteServiceClient#probe}, the one-row routability check made while an admin
 * saves a location (reach-scoped-card-plan R1c).
 *
 * <p><strong>Where the fixtures come from.</strong> They are written from ORS's documented matrix behaviour, not
 * recorded from a live call: there is no ORS key in the test environment and no test may call the real service. The
 * documented shape of an unplaceable point is a {@code null} duration and a {@code null} entry in {@code
 * destinations[]}; the error form is HTTP 404 with code 6010 naming the coordinate index. The client handles both,
 * so whichever way production ORS answers, the point reads as unroutable. If a live probe ever shows a third form,
 * add its recording here.
 *
 * <p><strong>What a regression looks like.</strong> A coordinate transposition or a missing control destination makes
 * every target read as unroutable or every source as bad, and the admin form refuses (or silently allows) the wrong
 * saves. Both are silent, so each is pinned on the wire.
 */
class OpenRouteServiceClientProbeContractTest {

    private static final String MATRIX_URL = "https://api.openrouteservice.org/v2/matrix/driving-car";
    private static final String PLACEHOLDER_KEY = "placeholder-not-a-real-key";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** Keswick: the source. Signed and asymmetric so a transposition cannot pass. */
    private static final double SOURCE_LAT = 54.5970;
    private static final double SOURCE_LON = -3.1347;

    /** The control (a known road point) then the target, as {@code [lat, lon]}. */
    private static final List<double[]> DESTINATIONS = List.of(
            new double[] {52.4776, -1.8932},
            new double[] {54.5442, -2.9505});

    private MockRestServiceServer server;
    private OrsProperties properties;
    private OrsRateLimiter limiter;
    private OpenRouteServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.properties = new OrsProperties();
        this.properties.setEnabled(true);
        this.properties.setApiKey(PLACEHOLDER_KEY);
        this.limiter = new OrsRateLimiter();
        this.client = new OpenRouteServiceClient(builder.build(), properties, limiter);
    }

    @Test
    @DisplayName("POSTs source then control then target as [lon, lat] with the duration metric and a Bearer key")
    void bodyCarriesSourceControlAndTargetInGeoJsonOrder() {
        server.expect(requestTo(MATRIX_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + PLACEHOLDER_KEY))
                .andExpect(jsonPath("$.locations[0][0]").value(SOURCE_LON))
                .andExpect(jsonPath("$.locations[0][1]").value(SOURCE_LAT))
                .andExpect(jsonPath("$.locations[1][0]").value(-1.8932))
                .andExpect(jsonPath("$.locations[1][1]").value(52.4776))
                .andExpect(jsonPath("$.locations[2][0]").value(-2.9505))
                .andExpect(jsonPath("$.locations[2][1]").value(54.5442))
                .andExpect(jsonPath("$.metrics[0]").value("duration"))
                .andExpect(jsonPath("$.sources[0]").value(0))
                .andRespond(withSuccess(fixture("probe-routable.json"), MediaType.APPLICATION_JSON));

        client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT);

        server.verify();
    }

    @Test
    @DisplayName("a routable target comes back with both durations and both destinations snapped")
    void routableTarget_isSnappedWithADuration() {
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withSuccess(fixture("probe-routable.json"), MediaType.APPLICATION_JSON));

        Optional<RouteProbe> probe = client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT);

        assertThat(probe).isPresent();
        assertThat(probe.get().durations()).containsExactly(5400.0, 2700.5);
        assertThat(probe.get().snapped()).containsExactly(true, true);
        server.verify();
    }

    @Test
    @DisplayName("an unsnappable target (null duration, null destination metadata) reads as not snapped")
    void unsnappableTarget_hasNullDurationAndIsNotSnapped() {
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withSuccess(fixture("probe-unsnappable-destination.json"), MediaType.APPLICATION_JSON));

        Optional<RouteProbe> probe = client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT);

        assertThat(probe).isPresent();
        assertThat(probe.get().durations()).containsExactly(5400.0, null);
        assertThat(probe.get().snapped()).containsExactly(true, false);
        server.verify();
    }

    @Test
    @DisplayName("a bad source comes back as an all-null row, which the caller must read as 'source is bad'")
    void badSource_isAnAllNullRow() {
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withSuccess(fixture("probe-bad-source.json"), MediaType.APPLICATION_JSON));

        Optional<RouteProbe> probe = client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT);

        assertThat(probe).isPresent();
        assertThat(probe.get().durations()).containsExactly(null, null);
        assertThat(probe.get().snapped()).containsExactly(true, true);
        server.verify();
    }

    @Test
    @DisplayName("a response with no destinations metadata relies on the durations alone")
    void noDestinationMetadata_readsAsSnapped() {
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withSuccess("{\"durations\":[[0.0,100.0,200.0]]}", MediaType.APPLICATION_JSON));

        Optional<RouteProbe> probe = client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT);

        assertThat(probe).isPresent();
        assertThat(probe.get().snapped()).containsExactly(true, true);
        server.verify();
    }

    @Test
    @DisplayName("a 404 with code 6010 names the unplaceable coordinate")
    void error6010_throwsWithTheCoordinateIndex() {
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("probe-error-6010.json")));

        assertThatThrownBy(() -> client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT))
                .isInstanceOfSatisfying(OrsUnroutablePointException.class,
                        e -> assertThat(e.getCoordinateIndex()).isEqualTo(2));
        server.verify();
    }

    @Test
    @DisplayName("a 6010 that names no coordinate reports index -1")
    void error6010WithoutAnIndex_reportsMinusOne() {
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":6010,\"message\":\"Could not find routable point\"}}"));

        assertThatThrownBy(() -> client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT))
                .isInstanceOfSatisfying(OrsUnroutablePointException.class,
                        e -> assertThat(e.getCoordinateIndex()).isEqualTo(-1));
    }

    @Test
    @DisplayName("a non-6010 404 whose message merely contains '6010' (an echoed coordinate) is not unroutable")
    void echoedCoordinateContaining6010_isNotUnroutable() {
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":2099,\"message\":\"Bad value at coordinate 2: -2.6010 54.5\"}}"));

        assertThatThrownBy(() -> client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT))
                .isNotInstanceOf(OrsUnroutablePointException.class);
    }

    @Test
    @DisplayName("a 404 whose body is not JSON, or carries no error object, propagates untouched")
    void unparseableOrErrorlessBody_propagates() {
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("<html>6010 coordinate 2</html>"));
        assertThatThrownBy(() -> client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT))
                .isNotInstanceOf(OrsUnroutablePointException.class);

        server.reset();
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"6010 coordinate 2\"}"));
        assertThatThrownBy(() -> client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT))
                .isNotInstanceOf(OrsUnroutablePointException.class);
    }

    @Test
    @DisplayName("any other HTTP failure propagates untouched, for the caller to treat as unavailable")
    void otherHttpFailures_propagate() {
        server.expect(requestTo(MATRIX_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    @DisplayName("a 6010 whose coordinate index overflows an int reports index -1, not a NumberFormatException")
    void error6010WithAnOverflowingIndex_reportsMinusOne() {
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":6010,\"message\":\"Could not find routable point of "
                                + "specified coordinate 99999999999999999999: 1 2\"}}"));

        assertThatThrownBy(() -> client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT))
                .isInstanceOfSatisfying(OrsUnroutablePointException.class,
                        e -> assertThat(e.getCoordinateIndex()).isEqualTo(-1));
    }

    @Test
    @DisplayName("a 404 that is not code 6010 propagates untouched")
    void notFoundWithoutTheCode_propagates() {
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":2099,\"message\":\"Unknown\"}}"));

        assertThatThrownBy(() -> client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT))
                .isNotInstanceOf(OrsUnroutablePointException.class);
    }

    @Test
    @DisplayName("an empty body, a missing durations row and a short row are all 'no answer'")
    void unusableAnswers_areEmpty() {
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withSuccess("{\"durations\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT)).isEmpty();

        server.reset();
        server.expect(requestTo(MATRIX_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT)).isEmpty();

        server.reset();
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withSuccess("{\"durations\":[[0.0,100.0]]}", MediaType.APPLICATION_JSON));
        assertThat(client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT)).isEmpty();

        server.reset();
        server.expect(requestTo(MATRIX_URL))
                .andRespond(withSuccess("{\"durations\":[null]}", MediaType.APPLICATION_JSON));
        assertThat(client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT)).isEmpty();
    }

    @Test
    @DisplayName("an unconfigured ORS, or no destinations, issues no request")
    void unconfiguredOrNoDestinations_issueNoRequest() {
        assertThat(client.probe(SOURCE_LAT, SOURCE_LON, List.of(), TIMEOUT)).isEmpty();
        properties.setApiKey("");

        assertThat(client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, TIMEOUT)).isEmpty();

        server.verify();
    }

    @Test
    @DisplayName("with both permits held, the probe gives up after its timeout and issues no request")
    void permitsHeld_probeGivesUpWithoutARequest() throws Exception {
        CountDownLatch twoInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable holder = () -> limiter.withPermit(() -> {
            twoInside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        Thread first = new Thread(holder);
        Thread second = new Thread(holder);
        first.start();
        second.start();
        assertThat(twoInside.await(5, TimeUnit.SECONDS)).isTrue();

        Optional<RouteProbe> probe = client.probe(SOURCE_LAT, SOURCE_LON, DESTINATIONS, Duration.ofMillis(100));

        assertThat(probe).isEmpty();
        release.countDown();
        first.join();
        second.join();
        server.verify();
    }

    private static String fixture(String name) {
        String path = "/contract/ors/" + name;
        try (InputStream in = OpenRouteServiceClientProbeContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                fail("Missing contract fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("Could not read contract fixture: " + path, e);
        }
    }
}
