package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.client.OpenMeteoAirQualityApi;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Outbound contract and resilience tests for {@link OpenMeteoClient#fetchInversionProfileBatch}
 * (inversion-v2-plan Phase 1b).
 *
 * <p><b>Honest limit.</b> The mock server shows the request this client BUILDS: one comma-separated
 * {@code elevation} value per coordinate, in coordinate order, beside the comma-separated latitude and
 * longitude. It cannot show that Open-Meteo accepts it. That evidence is the owner's 1a fetch,
 * {@code docs/engineering/inversion-v2-data/3-catbells-elevation.json}: one request with
 * {@code elevation=nan,451,80} for three coordinates returned three cells at three heights.
 */
class OpenMeteoClientInversionProfileTest {

    private static final String BASE_URL = "http://open-meteo.test";

    private final OpenMeteoForecastApi forecastApi = mock(OpenMeteoForecastApi.class);
    private final OpenMeteoAirQualityApi airQualityApi = mock(OpenMeteoAirQualityApi.class);

    private MockRestServiceServer server;
    private OpenMeteoClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenMeteoClient(forecastApi, airQualityApi, new ObjectMapper(), builder.build(), null);
        client.interChunkDelayMs = 0;
    }

    /** [lat, lon, floor] triples: latitudes 54.0, 54.01 ..., floors 100, 101 ... */
    private static List<double[]> points(int count) {
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(new double[]{54.0 + i * 0.01, -3.0, 100 + i});
        }
        return points;
    }

    private static String jsonArray(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> String.format("{\"latitude\":%.2f,\"longitude\":-3.0,\"elevation\":%d}",
                        54.0 + i * 0.01, 100 + i))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private void expectOk(int n) {
        server.expect(anything()).andRespond(withSuccess(jsonArray(n), MediaType.APPLICATION_JSON));
    }

    private static String param(java.net.URI uri, String name) {
        String raw = UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
        return raw == null ? null : URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("INVERSION_PROFILE_PARAMS is the 15 documented variables, and FORECAST_PARAMS gained none of them")
    void paramsAreExactlyTheFifteenAndForecastParamsIsUntouched() {
        assertThat(OpenMeteoClient.INVERSION_PROFILE_PARAMS.split(",")).containsExactly(
                "temperature_2m", "dew_point_2m", "cloud_cover_low", "visibility", "wind_speed_10m",
                "temperature_975hPa", "temperature_950hPa", "temperature_925hPa", "temperature_900hPa",
                "temperature_850hPa",
                "geopotential_height_975hPa", "geopotential_height_950hPa", "geopotential_height_925hPa",
                "geopotential_height_900hPa", "geopotential_height_850hPa");
        assertThat(OpenMeteoClient.FORECAST_PARAMS)
                .doesNotContain("975hPa").doesNotContain("950hPa").doesNotContain("900hPa")
                .doesNotContain("geopotential");
    }

    @Test
    @DisplayName("a request carries one elevation per coordinate, comma-separated, in coordinate order")
    void multiValueElevationRequestShape() {
        List<java.net.URI> seen = new ArrayList<>();
        server.expect(request -> seen.add(request.getURI()))
                .andRespond(withSuccess(jsonArray(3), MediaType.APPLICATION_JSON));

        List<OpenMeteoForecastResponse> results = client.fetchInversionProfileBatch(points(3));

        server.verify();
        assertThat(results).hasSize(3).doesNotContainNull();
        java.net.URI uri = seen.get(0);
        assertThat(uri.getPath()).isEqualTo("/v1/forecast");
        assertThat(param(uri, "latitude")).isEqualTo("54.0,54.01,54.02");
        assertThat(param(uri, "longitude")).isEqualTo("-3.0,-3.0,-3.0");
        assertThat(param(uri, "elevation")).isEqualTo("100,101,102");
        assertThat(param(uri, "hourly")).isEqualTo(OpenMeteoClient.INVERSION_PROFILE_PARAMS);
        assertThat(param(uri, "wind_speed_unit")).isEqualTo("ms");
        assertThat(param(uri, "timezone")).isEqualTo("UTC");
    }

    @Test
    @DisplayName("45 points make three requests of 20, 20 and 5, each with its own elevation list")
    void chunksAtTwenty() {
        List<String> elevations = new ArrayList<>();
        for (int n : new int[]{20, 20, 5}) {
            server.expect(request -> elevations.add(param(request.getURI(), "elevation")))
                    .andRespond(withSuccess(jsonArray(n), MediaType.APPLICATION_JSON));
        }

        List<OpenMeteoForecastResponse> results = client.fetchInversionProfileBatch(points(45));

        server.verify();
        assertThat(results).hasSize(45).doesNotContainNull();
        assertThat(elevations).hasSize(3);
        assertThat(elevations.get(0).split(",")).hasSize(20).startsWith("100").endsWith("119");
        assertThat(elevations.get(1).split(",")).hasSize(20).startsWith("120").endsWith("139");
        assertThat(elevations.get(2).split(",")).hasSize(5).startsWith("140").endsWith("144");
    }

    @Test
    @DisplayName("a failing first chunk gives all nulls and no further request")
    void firstChunkFailsStopsEverything() {
        server.expect(anything()).andRespond(withServerError());

        List<OpenMeteoForecastResponse> results = client.fetchInversionProfileBatch(points(45));

        assertThat(results).hasSize(45).containsOnlyNulls();
        server.verify();
    }

    @Test
    @DisplayName("a failing second chunk keeps the first, leaves the rest null and makes no third request")
    void secondChunkFailsStopsAtTheFirstFailure() {
        expectOk(20);
        server.expect(anything()).andRespond(withServerError());

        List<OpenMeteoForecastResponse> results = client.fetchInversionProfileBatch(points(45));

        assertThat(results).hasSize(45);
        assertThat(results.subList(0, 20)).doesNotContainNull();
        assertThat(results.subList(20, 45)).containsOnlyNulls();
        server.verify();
    }

    @Test
    @DisplayName("stops at the time budget and leaves the remaining points null")
    void budgetExhausted() {
        long[] readings = {0, 0, 0, OpenMeteoClient.INVERSION_PROFILE_FETCH_BUDGET.toMillis()};
        int[] index = {0};
        client.elapsedClockMs = () -> readings[Math.min(index[0]++, readings.length - 1)];
        expectOk(20);
        expectOk(20);

        List<OpenMeteoForecastResponse> results = client.fetchInversionProfileBatch(points(45));

        assertThat(results.subList(0, 40)).doesNotContainNull();
        assertThat(results.subList(40, 45)).containsOnlyNulls();
        server.verify();
    }

    @Test
    @DisplayName("a response shorter than its chunk fills what it can and never overruns")
    void shortResponseDoesNotOverrun() {
        expectOk(2);

        List<OpenMeteoForecastResponse> results = client.fetchInversionProfileBatch(points(5));

        assertThat(results).hasSize(5);
        assertThat(results.subList(0, 2)).doesNotContainNull();
        assertThat(results.subList(2, 5)).containsOnlyNulls();
    }

    @Test
    @DisplayName("no points make no request")
    void emptyInputMakesNoRequest() {
        assertThat(client.fetchInversionProfileBatch(List.of())).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("carries the shared rate limiter only: no retry and no open-meteo circuit breaker")
    void isolatedFromRetryAndBreaker() throws NoSuchMethodException {
        Method m = OpenMeteoClient.class.getMethod("fetchInversionProfileBatch", List.class);

        assertThat(m.getAnnotation(RateLimiter.class).name()).isEqualTo("open-meteo");
        assertThat(m.isAnnotationPresent(Retry.class)).isFalse();
        assertThat(m.isAnnotationPresent(CircuitBreaker.class)).isFalse();
    }
}
