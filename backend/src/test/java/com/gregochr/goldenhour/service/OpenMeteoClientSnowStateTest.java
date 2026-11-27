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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Outbound contract and fetch discipline of {@link OpenMeteoClient#fetchSnowStateBatch}
 * (snow-topics-truth-plan §3.2, S2).
 *
 * <p><b>Honest limit.</b> The mock server shows the request this client BUILDS and how it behaves when refused. It
 * cannot show that Open-Meteo accepts it; the evidence for the request shape is the fixture fetch of 2026-11-24
 * ({@code docs/engineering/snow-evidence}) and the inversion profile's {@code elevation=nan} request.
 */
class OpenMeteoClientSnowStateTest {

    private static final String BASE_URL = "http://open-meteo.test";
    private static final long T0 = Instant.parse("2024-01-15T13:47:00Z").toEpochMilli();
    private static final String DAILY_LIMIT =
            "{\"error\":true,\"reason\":\"Daily API request limit exceeded. Please try again tomorrow.\"}";
    private static final String HOURLY_LIMIT =
            "{\"error\":true,\"reason\":\"Hourly API request limit exceeded. Please try again in the next hour.\"}";
    private static final String MINUTELY_LIMIT =
            "{\"error\":true,\"reason\":\"Minutely API request limit exceeded. Please try again in one minute.\"}";
    private static final String CONCURRENT = "{\"error\":true,\"reason\":\"Too many concurrent requests\"}";

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
        client.elapsedClockMs = () -> T0;
    }

    /** [lat, lon, height] triples: latitudes 54.0, 54.01 ..., heights 100, 101 ... */
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

    private void expectRefusal(String body) {
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private static String param(URI uri, String name) {
        String raw = UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
        return raw == null ? null : URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the snow request is the five snowpack inputs plus low cloud, and the main fetch gained none of it")
    void paramsAreTheSixAndForecastParamsIsUntouched() {
        assertThat(OpenMeteoClient.SNOW_STATE_PARAMS.split(",")).containsExactly("precipitation", "temperature_2m",
                "relative_humidity_2m", "wind_speed_10m", "shortwave_radiation", "cloud_cover_low");
        assertThat(OpenMeteoClient.SNOW_STATE_PAST_DAYS).isEqualTo(7);
        assertThat(OpenMeteoClient.SNOW_STATE_FETCH_BUDGET.toSeconds()).isEqualTo(150);
        assertThat(OpenMeteoClient.FORECAST_PARAMS).doesNotContain("past_days");
    }

    @Test
    @DisplayName("a request carries past_days=7, wind in m/s, and one elevation per point (nan when unknown)")
    void requestShape() {
        List<URI> seen = new ArrayList<>();
        server.expect(request -> seen.add(request.getURI()))
                .andRespond(withSuccess(jsonArray(3), MediaType.APPLICATION_JSON));
        List<double[]> points = List.of(new double[]{54.5, -3.0, 950}, new double[]{54.6, -3.1, Double.NaN},
                new double[]{54.7, -3.2, 59.6});

        List<OpenMeteoForecastResponse> results = client.fetchSnowStateBatch(points);

        server.verify();
        assertThat(results).hasSize(3).doesNotContainNull();
        URI uri = seen.get(0);
        assertThat(uri.getPath()).isEqualTo("/v1/forecast");
        assertThat(param(uri, "latitude")).isEqualTo("54.5,54.6,54.7");
        assertThat(param(uri, "longitude")).isEqualTo("-3.0,-3.1,-3.2");
        assertThat(param(uri, "elevation")).isEqualTo("950,nan,60");
        assertThat(param(uri, "hourly")).isEqualTo(OpenMeteoClient.SNOW_STATE_PARAMS);
        assertThat(param(uri, "past_days")).isEqualTo("7");
        assertThat(param(uri, "wind_speed_unit")).isEqualTo("ms");
        assertThat(param(uri, "timezone")).isEqualTo("UTC");
        assertThat(param(uri, "forecast_days")).isNull();
    }

    @Test
    @DisplayName("45 points make three requests of 20, 20 and 5, each with its own elevation list")
    void chunksAtTwenty() {
        List<String> elevations = new ArrayList<>();
        for (int n : new int[]{20, 20, 5}) {
            server.expect(request -> elevations.add(param(request.getURI(), "elevation")))
                    .andRespond(withSuccess(jsonArray(n), MediaType.APPLICATION_JSON));
        }

        List<OpenMeteoForecastResponse> results = client.fetchSnowStateBatch(points(45));

        server.verify();
        assertThat(results).hasSize(45).doesNotContainNull();
        assertThat(elevations.get(0).split(",")).hasSize(20).startsWith("100").endsWith("119");
        assertThat(elevations.get(1).split(",")).hasSize(20).startsWith("120").endsWith("139");
        assertThat(elevations.get(2).split(",")).hasSize(5).startsWith("140").endsWith("144");
    }

    @Test
    @DisplayName("each point weighs one call: six variables over fourteen days is max(1, 0.6)")
    void weightedCostIsOnePerPoint() {
        assertThat(OpenMeteoClient.snowStatePointCost()).isEqualTo(1.0);
        assertThat(OpenMeteoClient.OPEN_METEO_MINUTE_LIMIT).isEqualTo(600);
        assertThat(OpenMeteoClient.OPEN_METEO_DAILY_LIMIT).isEqualTo(10_000);
    }

    // ── Fetch discipline ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a daily-limit refusal starts the shared cool-off, so the next snow run fetches nothing")
    void dailyLimitStartsTheCoolOff() {
        expectRefusal(DAILY_LIMIT);

        List<OpenMeteoForecastResponse> first = client.fetchSnowStateBatch(points(25));
        List<OpenMeteoForecastResponse> second = client.fetchSnowStateBatch(points(25));

        assertThat(first).hasSize(25).containsOnlyNulls();
        assertThat(second).hasSize(25).containsOnlyNulls();
        server.verify();
    }

    @Test
    @DisplayName("an hourly-limit refusal starts the shared cool-off too")
    void hourlyLimitStartsTheCoolOff() {
        expectRefusal(HOURLY_LIMIT);

        client.fetchSnowStateBatch(points(3));
        List<OpenMeteoForecastResponse> second = client.fetchSnowStateBatch(points(3));

        assertThat(second).containsOnlyNulls();
        server.verify();
    }

    @Test
    @DisplayName("a cool-off already running means nothing is requested, whoever started it")
    void runningCoolOffFetchesNothing() {
        expectRefusal(DAILY_LIMIT);
        assertThatThrownBy(() -> client.fetchForecastBatch(List.of(new double[]{54.0, -3.0})))
                .isInstanceOf(HttpClientErrorException.class);

        List<OpenMeteoForecastResponse> results = client.fetchSnowStateBatch(points(3));

        assertThat(results).hasSize(3).containsOnlyNulls();
        server.verify();
    }

    @Test
    @DisplayName("isCoolingOff is false at rest, true after a daily-limit refusal, and asking it changes nothing")
    void isCoolingOffReportsTheCoolOffReadOnly() {
        assertThat(client.isCoolingOff()).isFalse();
        expectRefusal(DAILY_LIMIT);

        client.fetchSnowStateBatch(points(3));

        assertThat(client.isCoolingOff()).isTrue();
        assertThat(client.isCoolingOff()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("a minute-limit 429 ends the snow run at that chunk and starts no cool-off")
    void minuteLimitEndsTheRunWithoutACoolOff() {
        expectOk(20);
        expectRefusal(MINUTELY_LIMIT);
        expectOk(2);

        List<OpenMeteoForecastResponse> results = client.fetchSnowStateBatch(points(45));

        assertThat(results.subList(0, 20)).doesNotContainNull();
        assertThat(results.subList(20, 45)).containsOnlyNulls();
        // no cool-off: the next run's first request is made (and answered)
        assertThat(client.fetchSnowStateBatch(points(2))).doesNotContainNull();
        server.verify();
    }

    @Test
    @DisplayName("a concurrent-requests 429 ends the snow run and does NOT silence the main fetch for ten minutes")
    void concurrentRefusalEndsTheRunWithoutACoolOff() {
        expectRefusal(CONCURRENT);
        expectOk(1);

        List<OpenMeteoForecastResponse> results = client.fetchSnowStateBatch(points(3));

        assertThat(results).containsOnlyNulls();
        // the main fetch still goes out
        assertThat(client.fetchForecastBatch(List.of(new double[]{54.0, -3.0}))).doesNotContainNull();
        server.verify();
    }

    @Test
    @DisplayName("a failing first chunk gives all nulls and no further request")
    void firstChunkFailsStopsEverything() {
        server.expect(anything()).andRespond(withServerError());

        List<OpenMeteoForecastResponse> results = client.fetchSnowStateBatch(points(45));

        assertThat(results).hasSize(45).containsOnlyNulls();
        server.verify();
    }

    @Test
    @DisplayName("stops at its own 150 s budget and leaves the remaining points null")
    void budgetExhausted() {
        long[] readings = {0, 0, 0, OpenMeteoClient.SNOW_STATE_FETCH_BUDGET.toMillis()};
        int[] index = {0};
        client.elapsedClockMs = () -> readings[Math.min(index[0]++, readings.length - 1)];
        expectOk(20);
        expectOk(20);

        List<OpenMeteoForecastResponse> results = client.fetchSnowStateBatch(points(45));

        assertThat(results.subList(0, 40)).doesNotContainNull();
        assertThat(results.subList(40, 45)).containsOnlyNulls();
        server.verify();
    }

    @Test
    @DisplayName("a response shorter than its chunk fills what it can and never overruns")
    void shortResponseDoesNotOverrun() {
        expectOk(2);

        List<OpenMeteoForecastResponse> results = client.fetchSnowStateBatch(points(5));

        assertThat(results).hasSize(5);
        assertThat(results.subList(0, 2)).doesNotContainNull();
        assertThat(results.subList(2, 5)).containsOnlyNulls();
    }

    @Test
    @DisplayName("no points make no request")
    void emptyInputMakesNoRequest() {
        assertThat(client.fetchSnowStateBatch(List.of())).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("carries the shared rate limiter only: no retry and no open-meteo circuit breaker")
    void isolatedFromRetryAndBreaker() throws NoSuchMethodException {
        Method m = OpenMeteoClient.class.getMethod("fetchSnowStateBatch", List.class);

        assertThat(m.getAnnotation(RateLimiter.class).name()).isEqualTo("open-meteo");
        assertThat(m.isAnnotationPresent(Retry.class)).isFalse();
        assertThat(m.isAnnotationPresent(CircuitBreaker.class)).isFalse();
    }
}
