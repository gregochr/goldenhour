package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenMeteoAirQualityApi;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.PrefetchCall;
import com.gregochr.goldenhour.util.RestClientMocks;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenMeteoClient}.
 */
@ExtendWith(MockitoExtension.class)
class OpenMeteoClientTest {

    @Mock
    private OpenMeteoForecastApi forecastApi;

    @Mock
    private OpenMeteoAirQualityApi airQualityApi;

    private OpenMeteoClient openMeteoClient;

    @BeforeEach
    void setUp() {
        openMeteoClient = new OpenMeteoClient(forecastApi, airQualityApi, new ObjectMapper());
    }

    @Test
    @DisplayName("fetchForecast() delegates to forecastApi with correct parameters")
    void fetchForecast_delegatesToApi() {
        OpenMeteoForecastResponse expected = new OpenMeteoForecastResponse();
        when(forecastApi.getForecast(anyDouble(), anyDouble(), anyString(), anyString(), anyString()))
                .thenReturn(expected);

        OpenMeteoForecastResponse result = openMeteoClient.fetchForecast(54.77, -1.57);

        assertThat(result).isSameAs(expected);
        verify(forecastApi).getForecast(
                eq(54.77), eq(-1.57),
                eq(OpenMeteoClient.FORECAST_PARAMS), eq("ms"), eq("UTC"));
    }

    @Test
    @DisplayName("FORECAST_PARAMS requests the pressure-level temperatures inversion detection needs")
    void forecastParams_requestPressureLevelTemperatures() {
        // The only vertical temperature profile in the pipeline. If these ever drop out of the
        // request, InversionScoreCalculator can no longer confirm a reversal and silently caps
        // every inversion below the STRONG band — a feature that stops working without an error.
        assertThat(OpenMeteoClient.FORECAST_PARAMS)
                .contains("temperature_925hPa")
                .contains("temperature_850hPa")
                .contains("temperature_2m");
    }

    @Test
    @DisplayName("FORECAST_PARAMS requests total cloud_cover as its own variable, for the valley-mist trend")
    void forecastParams_requestTotalCloudCover() {
        // Split on the comma: "cloud_cover_low" contains "cloud_cover", so a contains() check
        // would pass on the layer series alone.
        assertThat(OpenMeteoClient.FORECAST_PARAMS.split(","))
                .contains("cloud_cover", "wind_speed_10m", "dew_point_2m", "temperature_2m");
    }

    @Test
    @DisplayName("fetchAirQuality() delegates to airQualityApi with correct parameters")
    void fetchAirQuality_delegatesToApi() {
        OpenMeteoAirQualityResponse expected = new OpenMeteoAirQualityResponse();
        when(airQualityApi.getAirQuality(anyDouble(), anyDouble(), anyString(), anyString()))
                .thenReturn(expected);

        OpenMeteoAirQualityResponse result = openMeteoClient.fetchAirQuality(54.77, -1.57);

        assertThat(result).isSameAs(expected);
        verify(airQualityApi).getAirQuality(
                eq(54.77), eq(-1.57),
                eq(OpenMeteoClient.AIR_QUALITY_PARAMS), eq("UTC"));
    }

    // ── Batch parsing tests ────────────────────────────────────────────────────

    @Test
    @DisplayName("parseArrayResponse handles single-object JSON (1 location)")
    void batchParsing_singleObject_returnsSingleElement() {
        RestClient mockForecastClient = mock(RestClient.class);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);

        String singleJson = "{\"latitude\":55.0,\"longitude\":-1.5}";
        RestClientMocks.stubGet(mockForecastClient, String.class, singleJson);

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(
                List.of(new double[]{55.0, -1.5}));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getLatitude()).isEqualTo(55.0);
    }

    @Test
    @DisplayName("parseArrayResponse handles JSON array (2+ locations)")
    void batchParsing_array_returnsMultipleElements() {
        RestClient mockForecastClient = mock(RestClient.class);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);

        String arrayJson = "[{\"latitude\":55.0,\"longitude\":-1.5},"
                + "{\"latitude\":54.0,\"longitude\":-2.0}]";
        RestClientMocks.stubGet(mockForecastClient, String.class, arrayJson);

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(
                List.of(new double[]{55.0, -1.5}, new double[]{54.0, -2.0}));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getLatitude()).isEqualTo(55.0);
        assertThat(results.get(1).getLatitude()).isEqualTo(54.0);
    }

    // ── Chunking tests ────────────────────────────────────────────────────────

    @Test
    @DisplayName("fetchForecastBatch with 25 coords combines results from two chunks (20 + 5)")
    void fetchForecastBatch_oversizeInput_combinesTwoChunks() {
        RestClient mockForecastClient = mock(RestClient.class);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);
        client.interChunkDelayMs = 0;

        // 25 coords → chunk of 20 + chunk of 5; mock returns chunk-sized arrays on successive calls.
        // If chunking is absent, only 20 results are returned (the first return value).
        // If chunking works, both return values are consumed and 25 results combined.
        List<double[]> coords = buildCoords(25);
        RestClientMocks.stubGetSequence(mockForecastClient, String.class,
                buildJsonArray(20, 50.0), buildJsonArray(5, 51.0));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBatch(coords);

        assertThat(results).hasSize(25);
        assertThat(results.stream().filter(r -> r.getLatitude() >= 51.0).count()).isEqualTo(5);
        assertThat(results.stream().filter(r -> r.getLatitude() < 51.0).count()).isEqualTo(20);
    }

    @Test
    @DisplayName("fetchAirQualityBatch with 25 coords combines results from two chunks (20 + 5)")
    void fetchAirQualityBatch_oversizeInput_combinesTwoChunks() {
        RestClient mockAirQualityClient = mock(RestClient.class);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                null, mockAirQualityClient);
        client.interChunkDelayMs = 0;

        List<double[]> coords = buildCoords(25);
        RestClientMocks.stubGetSequence(mockAirQualityClient, String.class,
                buildAirQualityJsonArray(20), buildAirQualityJsonArray(5));

        List<OpenMeteoAirQualityResponse> results = client.fetchAirQualityBatch(coords);

        assertThat(results).hasSize(25);
    }

    @Test
    @DisplayName("fetchForecastBatch with exactly BATCH_COORD_LIMIT coords returns all in one call")
    void fetchForecastBatch_exactLimit_returnsAll() {
        RestClient mockForecastClient = mock(RestClient.class);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);

        List<double[]> coords = buildCoords(OpenMeteoClient.BATCH_COORD_LIMIT);
        RestClientMocks.stubGet(mockForecastClient, String.class,
                buildJsonArray(OpenMeteoClient.BATCH_COORD_LIMIT, 50.0));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBatch(coords);

        assertThat(results).hasSize(OpenMeteoClient.BATCH_COORD_LIMIT);
    }

    // ── fetchForecastBriefingBatch chunk isolation ────────────────────────────

    @Test
    @DisplayName("fetchForecastBriefingBatch: middle chunk fails, first and last chunks' data is preserved")
    void fetchForecastBriefingBatch_middleChunkFails_preservesOtherChunks() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);
        client.interChunkDelayMs = 0;
        client.rateLimitBackoffMs = 0;

        // 50 coords → 3 chunks: [0–19], [20–39], [40–49]
        List<double[]> coords = buildCoords(50);
        when(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class))
                .thenReturn(buildJsonArray(20, 50.0))
                .thenThrow(new RuntimeException("429 Too Many Requests"))
                .thenReturn(buildJsonArray(10, 52.0));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(coords);

        assertThat(results).hasSize(50);
        assertThat(results.subList(0, 20)).allSatisfy(r -> assertThat(r).isNotNull());
        assertThat(results.subList(20, 40)).allSatisfy(r -> assertThat(r).isNull());
        assertThat(results.subList(40, 50)).allSatisfy(r -> assertThat(r).isNotNull());
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch: all chunks fail, returns all-null list without throwing")
    void fetchForecastBriefingBatch_allChunksFail_returnsAllNullsWithoutThrowing() {
        RestClient mockForecastClient = mock(RestClient.class);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);
        client.interChunkDelayMs = 0;
        client.rateLimitBackoffMs = 0;

        // 25 coords → 2 chunks; both throw
        List<double[]> coords = buildCoords(25);
        RestClientMocks.stubGetThrows(mockForecastClient, String.class,
                new RuntimeException("service unavailable"));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(coords);

        assertThat(results).hasSize(25);
        assertThat(results).allSatisfy(r -> assertThat(r).isNull());
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch: all chunks succeed, returns full list with no null entries")
    void fetchForecastBriefingBatch_allChunksSucceed_noNullEntries() {
        RestClient mockForecastClient = mock(RestClient.class);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);
        client.interChunkDelayMs = 0;

        // 25 coords → 2 chunks: [0–19] lat≥50.0, [20–24] lat≥51.0
        List<double[]> coords = buildCoords(25);
        RestClientMocks.stubGetSequence(mockForecastClient, String.class,
                buildJsonArray(20, 50.0), buildJsonArray(5, 51.0));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(coords);

        assertThat(results).hasSize(25);
        assertThat(results).allSatisfy(r -> assertThat(r).isNotNull());
    }

    // ── fetchForecastBriefingBatch transient retry ────────────────────────────

    @Test
    @DisplayName("fetchForecastBriefingBatch: transient timeout succeeds on retry")
    void fetchForecastBriefingBatch_transientTimeout_succeedsOnRetry() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);
        client.interChunkDelayMs = 0;
        client.chunkRetryBackoffMs = 0;

        // 25 coords → 2 chunks; first chunk times out once then succeeds
        List<double[]> coords = buildCoords(25);
        when(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class))
                .thenThrow(new ResourceAccessException("I/O error: Read timed out",
                        new IOException("Read timed out")))
                .thenReturn(buildJsonArray(20, 50.0))
                .thenReturn(buildJsonArray(5, 51.0));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(coords);

        assertThat(results).hasSize(25);
        assertThat(results).allSatisfy(r -> assertThat(r).isNotNull());
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch: transient 502 exhausts retries, other chunks preserved")
    void fetchForecastBriefingBatch_transientExhausted_otherChunksPreserved() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);
        client.interChunkDelayMs = 0;
        client.chunkRetryBackoffMs = 0;

        // 40 coords → 2 chunks; first chunk fails all 3 attempts, second succeeds
        List<double[]> coords = buildCoords(40);
        when(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class))
                .thenThrow(new HttpServerErrorException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway"))
                .thenThrow(new HttpServerErrorException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway"))
                .thenThrow(new HttpServerErrorException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway"))
                .thenReturn(buildJsonArray(20, 51.0));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(coords);

        assertThat(results).hasSize(40);
        // First chunk failed after 3 attempts → nulls
        assertThat(results.subList(0, 20)).allSatisfy(r -> assertThat(r).isNull());
        // Second chunk succeeded
        assertThat(results.subList(20, 40)).allSatisfy(r -> assertThat(r).isNotNull());
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch: 429 is not retried, propagates to rate-limit handling")
    void fetchForecastBriefingBatch_rateLimitNotRetried() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);
        client.interChunkDelayMs = 0;
        client.rateLimitBackoffMs = 0;
        client.chunkRetryBackoffMs = 0;

        // 40 coords → 2 chunks; first chunk returns 429 — should NOT be retried
        List<double[]> coords = buildCoords(40);
        when(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class))
                .thenThrow(new RuntimeException("429 Too Many Requests"))
                .thenReturn(buildJsonArray(20, 51.0));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(coords);

        // 429 chunk failed immediately (no retry), second chunk succeeded
        assertThat(results).hasSize(40);
        assertThat(results.subList(0, 20)).allSatisfy(r -> assertThat(r).isNull());
        assertThat(results.subList(20, 40)).allSatisfy(r -> assertThat(r).isNotNull());
        // Only 2 HTTP calls: one 429 + one success (no retry attempts)
        verify(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve(), times(2)).body(String.class);
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch: non-transient error is not retried")
    void fetchForecastBriefingBatch_nonTransientNotRetried() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);
        client.interChunkDelayMs = 0;
        client.chunkRetryBackoffMs = 0;

        // 40 coords → 2 chunks; first chunk gets a parse error — not retried
        List<double[]> coords = buildCoords(40);
        when(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class))
                .thenThrow(new RuntimeException("Failed to parse JSON"))
                .thenReturn(buildJsonArray(20, 51.0));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(coords);

        assertThat(results).hasSize(40);
        assertThat(results.subList(0, 20)).allSatisfy(r -> assertThat(r).isNull());
        assertThat(results.subList(20, 40)).allSatisfy(r -> assertThat(r).isNotNull());
        // Only 2 HTTP calls: one parse error + one success (no retry attempts)
        verify(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve(), times(2)).body(String.class);
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch: connection reset retried then succeeds on second attempt")
    void fetchForecastBriefingBatch_connectionReset_succeedsOnSecondAttempt() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);
        client.interChunkDelayMs = 0;
        client.chunkRetryBackoffMs = 0;

        // Single chunk (≤20 coords) hits connection reset once, then succeeds
        List<double[]> coords = buildCoords(15);
        when(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class))
                .thenThrow(new ResourceAccessException("I/O error: Connection reset",
                        new IOException("Connection reset")))
                .thenReturn(buildJsonArray(15, 50.0));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(coords);

        assertThat(results).hasSize(15);
        assertThat(results).allSatisfy(r -> assertThat(r).isNotNull());
    }

    // ── Pre-fetch call reporting (batch-weather-prefetch-logging-plan.md) ──────

    @SuppressWarnings("unchecked")
    private static void stubForecastBodies(RestClient client, Object... outcomes) {
        var stubbing = when(client.get().uri(org.mockito.ArgumentMatchers.<Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class));
        for (Object outcome : outcomes) {
            stubbing = outcome instanceof Throwable t
                    ? stubbing.thenThrow(t) : stubbing.thenReturn((String) outcome);
        }
    }

    private OpenMeteoClient clientWithNoDelays(RestClient forecastClient, RestClient airQualityClient) {
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(), forecastClient, airQualityClient);
        client.interChunkDelayMs = 0;
        client.rateLimitBackoffMs = 0;
        client.chunkRetryBackoffMs = 0;
        return client;
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch(sink): 45 coords with chunk 2 returning 429 reports exactly "
            + "three rows, the middle one a failed 429")
    void fetchForecastBriefingBatch_sink_middleChunk429_threeRows() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = clientWithNoDelays(mockForecastClient, null);
        stubForecastBodies(mockForecastClient, buildJsonArray(20, 50.0),
                new RuntimeException("429 Too Many Requests"), buildJsonArray(5, 52.0));
        List<PrefetchCall> calls = new ArrayList<>();

        List<OpenMeteoForecastResponse> results =
                client.fetchForecastBriefingBatch(buildCoords(45), calls::add);

        assertThat(results).hasSize(45);
        assertThat(calls).hasSize(3);
        assertThat(calls).extracting(PrefetchCall::label).containsExactly(
                "batch-prefetch forecast chunk 1/3", "batch-prefetch forecast chunk 2/3",
                "batch-prefetch forecast chunk 3/3");
        assertThat(calls).extracting(PrefetchCall::service)
                .containsOnly(ServiceName.OPEN_METEO_FORECAST);
        assertThat(calls).extracting(PrefetchCall::coordsRequested).containsExactly(20, 20, 5);
        assertThat(calls).extracting(PrefetchCall::coordsReturned).containsExactly(20, 0, 5);
        assertThat(calls).extracting(PrefetchCall::succeeded).containsExactly(true, false, true);
        PrefetchCall failed = calls.get(1);
        assertThat(failed.statusCode()).isEqualTo(429);
        assertThat(failed.error()).isEqualTo("429 Too Many Requests");
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.calledAt()).isNotNull();
        assertThat(failed.durationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(calls.get(0).statusCode()).isEqualTo(200);
        assertThat(calls.get(0).error()).isNull();
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch(sink): a single-chunk failure records one failed row and still throws")
    void fetchForecastBriefingBatch_sink_singleChunkFailure_oneRowAndRethrows() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = clientWithNoDelays(mockForecastClient, null);
        stubForecastBodies(mockForecastClient, new RuntimeException("429 Too Many Requests"));
        List<PrefetchCall> calls = new ArrayList<>();
        List<double[]> coords = buildCoords(10);

        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(coords, calls::add))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("429 Too Many Requests");

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).label()).isEqualTo("batch-prefetch forecast chunk 1/1");
        assertThat(calls.get(0).coordsRequested()).isEqualTo(10);
        assertThat(calls.get(0).coordsReturned()).isZero();
        assertThat(calls.get(0).succeeded()).isFalse();
        assertThat(calls.get(0).statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch(sink): a single chunk that succeeds reports one clean row")
    void fetchForecastBriefingBatch_sink_singleChunkSuccess_oneRow() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = clientWithNoDelays(mockForecastClient, null);
        stubForecastBodies(mockForecastClient, buildJsonArray(10, 50.0));
        List<PrefetchCall> calls = new ArrayList<>();

        client.fetchForecastBriefingBatch(buildCoords(10), calls::add);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).coordsReturned()).isEqualTo(10);
        assertThat(calls.get(0).succeeded()).isTrue();
        assertThat(calls.get(0).attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch(sink): a retry that succeeds records attempts 2")
    void fetchForecastBriefingBatch_sink_retrySucceeds_attemptsTwo() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = clientWithNoDelays(mockForecastClient, null);
        stubForecastBodies(mockForecastClient,
                new ResourceAccessException("I/O error: Read timed out", new IOException("Read timed out")),
                buildJsonArray(15, 50.0));
        List<PrefetchCall> calls = new ArrayList<>();

        client.fetchForecastBriefingBatch(buildCoords(15), calls::add);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).succeeded()).isTrue();
        assertThat(calls.get(0).attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch(sink): exhausted transient retries record attempts 3 and a null status")
    void fetchForecastBriefingBatch_sink_retriesExhausted_attemptsThree() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = clientWithNoDelays(mockForecastClient, null);
        ResourceAccessException timeout =
                new ResourceAccessException("I/O error: Read timed out", new IOException("Read timed out"));
        stubForecastBodies(mockForecastClient, timeout, timeout, timeout);
        List<PrefetchCall> calls = new ArrayList<>();
        List<double[]> coords = buildCoords(15);

        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(coords, calls::add))
                .isInstanceOf(RuntimeException.class);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).succeeded()).isFalse();
        assertThat(calls.get(0).attempts()).isEqualTo(3);
        assertThat(calls.get(0).statusCode()).isNull();
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch(sink): the status comes from a response exception in the cause chain")
    void fetchForecastBriefingBatch_sink_statusFromCauseChain() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = clientWithNoDelays(mockForecastClient, null);
        stubForecastBodies(mockForecastClient, new IllegalStateException("wrapped",
                new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden")));
        List<PrefetchCall> calls = new ArrayList<>();
        List<double[]> coords = buildCoords(5);

        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(coords, calls::add))
                .isInstanceOf(IllegalStateException.class);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch: the one-argument method returns the same list and needs no sink")
    void fetchForecastBriefingBatch_oneArg_unchanged() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = clientWithNoDelays(mockForecastClient, null);
        stubForecastBodies(mockForecastClient, buildJsonArray(20, 50.0),
                new RuntimeException("429 Too Many Requests"), buildJsonArray(5, 52.0));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(buildCoords(45));

        assertThat(results).hasSize(45);
        assertThat(results.subList(0, 20)).allSatisfy(r -> assertThat(r).isNotNull());
        assertThat(results.subList(20, 40)).allSatisfy(r -> assertThat(r).isNull());
        assertThat(results.subList(40, 45)).allSatisfy(r -> assertThat(r).isNotNull());
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch(sink): a sink that throws changes neither the result nor the exception")
    void fetchForecastBriefingBatch_sink_throwingSinkIgnored() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = clientWithNoDelays(mockForecastClient, null);
        stubForecastBodies(mockForecastClient, buildJsonArray(20, 50.0),
                new RuntimeException("429 Too Many Requests"));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(buildCoords(40), call -> {
            throw new IllegalStateException("sink broke");
        });

        assertThat(results).hasSize(40);
        assertThat(results.subList(20, 40)).allSatisfy(r -> assertThat(r).isNull());
    }

    @Test
    @DisplayName("fetchAirQualityBatchResilient(sink): 45 coords with chunk 2 failing reports three air-quality rows")
    void fetchAirQualityBatchResilient_sink_middleChunkFails_threeRows() {
        RestClient mockAirClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = clientWithNoDelays(null, mockAirClient);
        when(mockAirClient.get().uri(org.mockito.ArgumentMatchers.<Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class))
                .thenReturn(buildAirQualityJsonArray(20))
                .thenThrow(new RuntimeException("429 Too Many Requests"))
                .thenReturn(buildAirQualityJsonArray(5));
        List<PrefetchCall> calls = new ArrayList<>();

        List<OpenMeteoAirQualityResponse> results =
                client.fetchAirQualityBatchResilient(buildCoords(45), calls::add);

        assertThat(results).hasSize(45);
        assertThat(calls).extracting(PrefetchCall::service)
                .containsOnly(ServiceName.OPEN_METEO_AIR_QUALITY);
        assertThat(calls).extracting(PrefetchCall::label).containsExactly(
                "batch-prefetch air-quality chunk 1/3", "batch-prefetch air-quality chunk 2/3",
                "batch-prefetch air-quality chunk 3/3");
        assertThat(calls).extracting(PrefetchCall::coordsReturned).containsExactly(20, 0, 5);
        assertThat(calls).extracting(PrefetchCall::succeeded).containsExactly(true, false, true);
        assertThat(calls.get(1).statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("fetchAirQualityBatchResilient(sink): a single-chunk failure records one failed row and still throws")
    void fetchAirQualityBatchResilient_sink_singleChunkFailure_oneRowAndRethrows() {
        RestClient mockAirClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = clientWithNoDelays(null, mockAirClient);
        when(mockAirClient.get().uri(org.mockito.ArgumentMatchers.<Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class))
                .thenThrow(new RuntimeException("429 Too Many Requests"));
        List<PrefetchCall> calls = new ArrayList<>();
        List<double[]> coords = buildCoords(8);

        assertThatThrownBy(() -> client.fetchAirQualityBatchResilient(coords, calls::add))
                .isInstanceOf(RuntimeException.class);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).label()).isEqualTo("batch-prefetch air-quality chunk 1/1");
        assertThat(calls.get(0).succeeded()).isFalse();
        assertThat(calls.get(0).statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("fetchAirQualityBatchResilient(sink): a single chunk that succeeds reports one clean row")
    void fetchAirQualityBatchResilient_sink_singleChunkSuccess() {
        RestClient mockAirClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = clientWithNoDelays(null, mockAirClient);
        when(mockAirClient.get().uri(org.mockito.ArgumentMatchers.<Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class))
                .thenReturn(buildAirQualityJsonArray(8))
                .thenReturn(buildAirQualityJsonArray(8));
        List<PrefetchCall> calls = new ArrayList<>();

        client.fetchAirQualityBatchResilient(buildCoords(8), calls::add);
        List<OpenMeteoAirQualityResponse> plain = client.fetchAirQualityBatchResilient(buildCoords(8));

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).coordsReturned()).isEqualTo(8);
        assertThat(calls.get(0).succeeded()).isTrue();
        assertThat(plain).hasSize(8);
    }

    // ── Requested hourly variables ────────────────────────────────────────────

    @Test
    @DisplayName("fetchPressureLevelCloudBatch requests exactly the eight pressure-level cloud variables")
    void fetchPressureLevelCloudBatch_requestsExactlyEightLevels() {
        String hourly = hourlyParamSentBy(c -> c.fetchPressureLevelCloudBatch(buildCoords(2)));

        assertThat(hourly).isEqualTo("cloud_cover_850hPa,cloud_cover_700hPa,cloud_cover_600hPa,"
                + "cloud_cover_500hPa,cloud_cover_400hPa,cloud_cover_300hPa,"
                + "cloud_cover_250hPa,cloud_cover_200hPa");
        assertThat(hourly).isEqualTo(OpenMeteoClient.PRESSURE_LEVEL_CLOUD_PARAMS);
        assertThat(hourly.split(",")).hasSize(8);
    }

    @Test
    @DisplayName("fetchCloudOnlyBatch still requests exactly low, mid and high cloud")
    void fetchCloudOnlyBatch_stillRequestsOnlyCloudOnlyParams() {
        String hourly = hourlyParamSentBy(c -> c.fetchCloudOnlyBatch(buildCoords(2)));

        assertThat(hourly).isEqualTo("cloud_cover_low,cloud_cover_mid,cloud_cover_high");
        assertThat(hourly).isEqualTo(OpenMeteoClient.CLOUD_ONLY_PARAMS);
    }

    private OpenMeteoClient pressureClient(MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://open-meteo.test");
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        OpenMeteoClient client = new OpenMeteoClient(forecastApi, airQualityApi, new ObjectMapper(),
                builder.build(), null);
        client.interChunkDelayMs = 0;
        return client;
    }

    private void expectOk(MockRestServiceServer server, int n) {
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess(buildJsonArray(n, 50.0),
                                org.springframework.http.MediaType.APPLICATION_JSON));
    }

    private static void expectError(MockRestServiceServer server) {
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withServerError());
    }

    @Test
    @DisplayName("fetchPressureLevelCloudBatch: a failing first chunk gives all nulls and no further request")
    void fetchPressureLevelCloudBatch_firstChunkFails_allNullNoMoreRequests() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        OpenMeteoClient client = pressureClient(server);
        expectError(server[0]);

        List<OpenMeteoForecastResponse> results = client.fetchPressureLevelCloudBatch(buildCoords(45));

        assertThat(results).hasSize(45).containsOnlyNulls();
        server[0].verify();
    }

    @Test
    @DisplayName("fetchPressureLevelCloudBatch: a failing second chunk keeps the first, rest null, no third request")
    void fetchPressureLevelCloudBatch_secondChunkFails_keepsFirstNoThirdRequest() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        OpenMeteoClient client = pressureClient(server);
        expectOk(server[0], 20);
        expectError(server[0]);

        List<OpenMeteoForecastResponse> results = client.fetchPressureLevelCloudBatch(buildCoords(45));

        assertThat(results).hasSize(45);
        assertThat(results.subList(0, 20)).allSatisfy(r -> assertThat(r).isNotNull());
        assertThat(results.subList(20, 45)).containsOnlyNulls();
        server[0].verify();
    }

    @Test
    @DisplayName("fetchPressureLevelCloudBatch: stops at the time budget and leaves the remaining cells null")
    void fetchPressureLevelCloudBatch_budgetExhausted_remainingNull() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        OpenMeteoClient client = pressureClient(server);
        // start at t=0; budget checks before chunks one and two at t=0, before chunk three past it
        long[] readings = {0, 0, 0, OpenMeteoClient.PRESSURE_LEVEL_FETCH_BUDGET.toMillis()};
        int[] idx = {0};
        client.elapsedClockMs = () -> readings[Math.min(idx[0]++, readings.length - 1)];
        expectOk(server[0], 20);
        expectOk(server[0], 20);

        List<OpenMeteoForecastResponse> results = client.fetchPressureLevelCloudBatch(buildCoords(45));

        assertThat(results).hasSize(45);
        assertThat(results.subList(0, 40)).allSatisfy(r -> assertThat(r).isNotNull());
        assertThat(results.subList(40, 45)).containsOnlyNulls();
        server[0].verify();
    }

    @Test
    @DisplayName("fetchPressureLevelCloudBatch carries the rate limiter only: no retry, no circuit breaker")
    void fetchPressureLevelCloudBatch_isolatedFromRetryAndBreaker() throws NoSuchMethodException {
        java.lang.reflect.Method m =
                OpenMeteoClient.class.getMethod("fetchPressureLevelCloudBatch", List.class);

        assertThat(m.getAnnotation(io.github.resilience4j.ratelimiter.annotation.RateLimiter.class).name())
                .isEqualTo("open-meteo");
        assertThat(m.isAnnotationPresent(io.github.resilience4j.retry.annotation.Retry.class)).isFalse();
        assertThat(m.isAnnotationPresent(
                io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker.class)).isFalse();
    }

    private String hourlyParamSentBy(Function<OpenMeteoClient, List<OpenMeteoForecastResponse>> call) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://open-meteo.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenMeteoClient client = new OpenMeteoClient(forecastApi, airQualityApi, new ObjectMapper(),
                builder.build(), null);
        AtomicReference<String> hourly = new AtomicReference<>();
        server.expect(request -> hourly.set(URLDecoder.decode(
                        UriComponentsBuilder.fromUri(request.getURI()).build()
                                .getQueryParams().getFirst("hourly"), StandardCharsets.UTF_8)))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess(buildJsonArray(2, 50.0),
                                org.springframework.http.MediaType.APPLICATION_JSON));

        call.apply(client);

        server.verify();
        return hourly.get();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<double[]> buildCoords(int count) {
        List<double[]> coords = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            coords.add(new double[]{50.0 + i * 0.01, -1.5});
        }
        return coords;
    }

    private String buildJsonArray(int count, double latBase) {
        return IntStream.range(0, count)
                .mapToObj(i -> String.format("{\"latitude\":%.2f,\"longitude\":-1.5}",
                        latBase + i * 0.01))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String buildAirQualityJsonArray(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "{}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    @DisplayName("parseArrayResponse throws RuntimeException on malformed JSON")
    void batchParsing_malformedJson_throwsRuntimeException() {
        RestClient mockForecastClient = mock(RestClient.class);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);

        RestClientMocks.stubGet(mockForecastClient, String.class, "not json at all");

        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(
                List.of(new double[]{55.0, -1.5})))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse");
    }

    // ── Cool-off and timeout backoff (open-meteo-cool-off-plan.md) ────────────

    private static final long COOL_OFF_T0 = Instant.parse("2026-11-02T13:47:00Z").toEpochMilli();
    private static final long TEN_MINUTES_MS = 10 * 60 * 1000L;
    private static final long RATE_LIMIT_BACKOFF_MS = 61_000L;
    private static final String CONCURRENT_BODY = "{\"error\":true,\"reason\":\"Too many concurrent requests\"}";

    /** A client with a fake clock at {@link #COOL_OFF_T0}, recorded sleeps and no real waiting. */
    private OpenMeteoClient coolOffClient(RestClient forecastClient, RestClient airQualityClient,
            List<Long> sleeps, long[] now) {
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(), forecastClient, airQualityClient);
        client.interChunkDelayMs = 0;
        client.sleeper = sleeps::add;
        client.elapsedClockMs = () -> now[0];
        return client;
    }

    /** A 429 as production logs it: the message carries the response body after the status text. */
    private static HttpClientErrorException tooManyRequests(String body) {
        return HttpClientErrorException.create("429 Too Many Requests: \"" + body + "\"",
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static ResourceAccessException readTimeout() {
        return new ResourceAccessException("I/O error: Read timed out",
                new SocketTimeoutException("Read timed out"));
    }

    private static void verifyRequests(RestClient client, int count) {
        verify(client.get().uri(org.mockito.ArgumentMatchers.<Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve(), times(count)).body(String.class);
    }

    @Test
    @DisplayName("a read timeout is retried after 30 s then 60 s, not 2 s and 4 s")
    void readTimeout_retriesAfterThirtyThenSixtySeconds() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = coolOffClient(mockClient, null, sleeps, new long[]{COOL_OFF_T0});
        stubForecastBodies(mockClient, readTimeout(), readTimeout(), readTimeout());

        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(buildCoords(15)))
                .isInstanceOf(RuntimeException.class);

        assertThat(sleeps).containsExactly(30_000L, 60_000L);
        verifyRequests(mockClient, 3);
    }

    @Test
    @DisplayName("a body-read timeout (a plain RestClientException) is retried like a status-line one, after 30 s")
    void bodyReadTimeout_isRetriedAfterThirtySeconds() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = coolOffClient(mockClient, null, sleeps, new long[]{COOL_OFF_T0});
        stubForecastBodies(mockClient,
                new RestClientException("Error while extracting response",
                        new SocketTimeoutException("Read timed out")),
                buildJsonArray(5, 50.0));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(buildCoords(5));

        assertThat(results).hasSize(5).allSatisfy(r -> assertThat(r).isNotNull());
        assertThat(sleeps).containsExactly(30_000L);
    }

    @Test
    @DisplayName("a 5xx and a connection reset keep the short 2 s then 4 s backoff")
    void serverErrorAndConnectionReset_keepShortBackoff() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = coolOffClient(mockClient, null, sleeps, new long[]{COOL_OFF_T0});
        stubForecastBodies(mockClient,
                new HttpServerErrorException(HttpStatus.BAD_GATEWAY, "Bad Gateway"),
                new ResourceAccessException("I/O error: Connection reset", new IOException("Connection reset")),
                new HttpServerErrorException(HttpStatus.BAD_GATEWAY, "Bad Gateway"));

        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(buildCoords(15)))
                .isInstanceOf(RuntimeException.class);

        assertThat(sleeps).containsExactly(2_000L, 4_000L);
    }

    @Test
    @DisplayName("too many concurrent requests on chunk 2 of 5: chunks 3-5 send nothing, chunk 1 survives, "
            + "no 61 s backoff")
    void concurrentRefusal_stopsTheLoopWithoutBackoff() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = coolOffClient(mockClient, null, sleeps, new long[]{COOL_OFF_T0});
        stubForecastBodies(mockClient, buildJsonArray(20, 50.0), tooManyRequests(CONCURRENT_BODY));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(buildCoords(100));

        assertThat(results).hasSize(100);
        assertThat(results.subList(0, 20)).allSatisfy(r -> assertThat(r).isNotNull());
        assertThat(results.subList(20, 100)).containsOnlyNulls();
        verifyRequests(mockClient, 2);
        assertThat(sleeps).doesNotContain(RATE_LIMIT_BACKOFF_MS);
    }

    @Test
    @DisplayName("a second batch during the cool-off sends nothing: nulls for many chunks, "
            + "a cool-off exception for one, with no retry and no '429' in the message")
    void duringCoolOff_noRequestIsSent() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        List<Long> sleeps = new ArrayList<>();
        long[] now = {COOL_OFF_T0};
        OpenMeteoClient client = coolOffClient(mockClient, null, sleeps, now);
        stubForecastBodies(mockClient, tooManyRequests(CONCURRENT_BODY));
        client.fetchForecastBriefingBatch(buildCoords(100));
        verifyRequests(mockClient, 1);
        sleeps.clear();
        now[0] = COOL_OFF_T0 + TEN_MINUTES_MS - 1;
        List<PrefetchCall> calls = new ArrayList<>();

        List<OpenMeteoForecastResponse> many = client.fetchForecastBriefingBatch(buildCoords(100), calls::add);

        assertThat(many).hasSize(100).containsOnlyNulls();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).succeeded()).isFalse();
        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(buildCoords(5)))
                .isInstanceOf(OpenMeteoCoolOffException.class)
                .hasMessageContaining("too many concurrent requests")
                .hasMessageContaining("until 13:57 UTC")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("429"));
        verifyRequests(mockClient, 1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("requests flow again once the clock passes the end of the cool-off")
    void afterCoolOff_requestsFlowAgain() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        long[] now = {COOL_OFF_T0};
        OpenMeteoClient client = coolOffClient(mockClient, null, new ArrayList<>(), now);
        stubForecastBodies(mockClient, tooManyRequests(CONCURRENT_BODY), buildJsonArray(5, 50.0));
        client.fetchForecastBriefingBatch(buildCoords(100));
        now[0] = COOL_OFF_T0 + TEN_MINUTES_MS;

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(buildCoords(5));

        assertThat(results).hasSize(5).allSatisfy(r -> assertThat(r).isNotNull());
        verifyRequests(mockClient, 2);
    }

    @ParameterizedTest(name = "minutely or unrecognised 429 body: {0}")
    @ValueSource(strings = {
            "{\"error\":true,\"reason\":\"Minutely API request limit exceeded. Please try again in one minute.\"}",
            "", "{\"error\":true,\"reason\":\"something new\"}"})
    @DisplayName("a minutely or unrecognised 429 keeps the 61 s backoff and the next chunk is still tried")
    void minutelyRefusal_keepsBackoffAndContinues(String body) {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = coolOffClient(mockClient, null, sleeps, new long[]{COOL_OFF_T0});
        stubForecastBodies(mockClient, tooManyRequests(body), buildJsonArray(20, 51.0));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(buildCoords(40));

        assertThat(results.subList(0, 20)).containsOnlyNulls();
        assertThat(results.subList(20, 40)).allSatisfy(r -> assertThat(r).isNotNull());
        assertThat(sleeps).containsExactly(RATE_LIMIT_BACKOFF_MS);
        verifyRequests(mockClient, 2);
    }

    @Test
    @DisplayName("a 429 whose message lacks the body is still classified from the response body")
    void refusalReason_readFromResponseBodyWhenMessageLacksIt() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = coolOffClient(mockClient, null, new ArrayList<>(), new long[]{COOL_OFF_T0});
        stubForecastBodies(mockClient, HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests", HttpHeaders.EMPTY, CONCURRENT_BODY.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
        client.fetchForecastBriefingBatch(buildCoords(40));

        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(buildCoords(5)))
                .isInstanceOf(OpenMeteoCoolOffException.class);
        verifyRequests(mockClient, 1);
    }

    @Test
    @DisplayName("an hourly-limit refusal cools off until the next whole UTC hour")
    void hourlyRefusal_endsAtNextWholeHour() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        long[] now = {COOL_OFF_T0};
        OpenMeteoClient client = coolOffClient(mockClient, null, new ArrayList<>(), now);
        stubForecastBodies(mockClient, tooManyRequests(
                "{\"error\":true,\"reason\":\"Hourly API request limit exceeded. Try again next hour.\"}"),
                buildJsonArray(5, 50.0));
        client.fetchForecastBriefingBatch(buildCoords(40));

        now[0] = Instant.parse("2026-11-02T13:59:59Z").toEpochMilli();
        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(buildCoords(5)))
                .isInstanceOf(OpenMeteoCoolOffException.class)
                .hasMessageContaining("until 14:00 UTC")
                .hasMessageContaining("hourly limit");
        now[0] = Instant.parse("2026-11-02T14:00:00Z").toEpochMilli();

        assertThat(client.fetchForecastBriefingBatch(buildCoords(5))).hasSize(5);
    }

    @Test
    @DisplayName("a daily-limit refusal cools off until the next UTC midnight")
    void dailyRefusal_endsAtNextMidnight() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        long[] now = {COOL_OFF_T0};
        OpenMeteoClient client = coolOffClient(mockClient, null, new ArrayList<>(), now);
        stubForecastBodies(mockClient, tooManyRequests(
                "{\"error\":true,\"reason\":\"Daily API request limit exceeded. Please try again tomorrow.\"}"),
                buildJsonArray(5, 50.0));
        client.fetchForecastBriefingBatch(buildCoords(40));

        now[0] = Instant.parse("2026-11-02T23:59:59Z").toEpochMilli();
        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(buildCoords(5)))
                .isInstanceOf(OpenMeteoCoolOffException.class)
                .hasMessageContaining("until 00:00 UTC")
                .hasMessageContaining("daily limit");
        now[0] = Instant.parse("2026-11-03T00:00:00Z").toEpochMilli();

        assertThat(client.fetchForecastBriefingBatch(buildCoords(5))).hasSize(5);
    }

    @Test
    @DisplayName("a later, shorter refusal never shortens a cool-off already in force")
    void laterShorterRefusal_neverShortensTheCoolOff() {
        RestClient forecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        RestClient airClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        long[] now = {COOL_OFF_T0};
        OpenMeteoClient client = coolOffClient(forecastClient, airClient, new ArrayList<>(), now);
        stubForecastBodies(airClient, tooManyRequests(
                "{\"error\":true,\"reason\":\"Daily API request limit exceeded. Please try again tomorrow.\"}"));
        // The forecast request is already in flight when the daily refusal lands; it then fails hourly.
        when(forecastClient.get().uri(org.mockito.ArgumentMatchers.<Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class)).thenAnswer(invocation -> {
                    assertThatThrownBy(() -> client.fetchAirQualityBatchResilient(buildCoords(5)))
                            .isInstanceOf(RuntimeException.class);
                    throw tooManyRequests("{\"reason\":\"Hourly API request limit exceeded.\"}");
                });

        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(buildCoords(5)))
                .isInstanceOf(RuntimeException.class);

        now[0] = Instant.parse("2026-11-02T14:30:00Z").toEpochMilli();
        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(buildCoords(5)))
                .isInstanceOf(OpenMeteoCoolOffException.class)
                .hasMessageContaining("until 00:00 UTC")
                .hasMessageContaining("daily limit");
    }

    @Test
    @DisplayName("a chunk whose three attempts all read-time-out starts a 10 minute cool-off")
    void exhaustedTimeouts_startCoolOff() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        long[] now = {COOL_OFF_T0};
        OpenMeteoClient client = coolOffClient(mockClient, null, new ArrayList<>(), now);
        stubForecastBodies(mockClient, readTimeout(), readTimeout(), readTimeout());
        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(buildCoords(15)))
                .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(buildCoords(15)))
                .isInstanceOf(OpenMeteoCoolOffException.class)
                .hasMessageContaining("repeated timeouts")
                .hasMessageContaining("until 13:57 UTC");
        verifyRequests(mockClient, 3);
    }

    @Test
    @DisplayName("a chunk that times out then succeeds does not start a cool-off")
    void timeoutThenSuccess_startsNoCoolOff() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = coolOffClient(mockClient, null, new ArrayList<>(),
                new long[]{COOL_OFF_T0});
        stubForecastBodies(mockClient, readTimeout(), buildJsonArray(5, 50.0), buildJsonArray(5, 50.0));

        client.fetchForecastBriefingBatch(buildCoords(5));
        List<OpenMeteoForecastResponse> second = client.fetchForecastBriefingBatch(buildCoords(5));

        assertThat(second).hasSize(5).allSatisfy(r -> assertThat(r).isNotNull());
        verifyRequests(mockClient, 3);
    }

    @Test
    @DisplayName("a timeout exhausted on a connection reset (not a socket timeout) starts no cool-off")
    void exhaustedNonTimeoutTransient_startsNoCoolOff() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = coolOffClient(mockClient, null, new ArrayList<>(),
                new long[]{COOL_OFF_T0});
        ResourceAccessException reset =
                new ResourceAccessException("I/O error: Connection reset", new IOException("Connection reset"));
        stubForecastBodies(mockClient, reset, reset, reset, buildJsonArray(5, 50.0));
        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(buildCoords(15)))
                .isInstanceOf(RuntimeException.class);

        assertThat(client.fetchForecastBriefingBatch(buildCoords(5))).hasSize(5);
    }

    @Test
    @DisplayName("fetchForecastBatch (no in-method retry): two consecutive timed-out chunks start the cool-off "
            + "and the other chunks send nothing")
    void forecastBatch_twoConsecutiveTimeouts_startCoolOffAndStop() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = coolOffClient(mockClient, null, sleeps, new long[]{COOL_OFF_T0});
        stubForecastBodies(mockClient, readTimeout(), readTimeout());

        List<OpenMeteoForecastResponse> results = client.fetchForecastBatch(buildCoords(100));

        assertThat(results).hasSize(100).containsOnlyNulls();
        verifyRequests(mockClient, 2);
        assertThat(sleeps).doesNotContain(RATE_LIMIT_BACKOFF_MS);
    }

    @Test
    @DisplayName("fetchForecastBatch: timeouts separated by a success never start a cool-off")
    void forecastBatch_nonConsecutiveTimeouts_startNoCoolOff() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = coolOffClient(mockClient, null, new ArrayList<>(),
                new long[]{COOL_OFF_T0});
        stubForecastBodies(mockClient, readTimeout(), buildJsonArray(20, 50.0), readTimeout(),
                buildJsonArray(20, 51.0));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBatch(buildCoords(80));

        assertThat(results.subList(0, 20)).containsOnlyNulls();
        assertThat(results.subList(20, 40)).allSatisfy(r -> assertThat(r).isNotNull());
        assertThat(results.subList(40, 60)).containsOnlyNulls();
        assertThat(results.subList(60, 80)).allSatisfy(r -> assertThat(r).isNotNull());
        verifyRequests(mockClient, 4);
    }

    @Test
    @DisplayName("fetchCloudOnlyBatch: a concurrent-requests refusal on chunk 1 of 3 sends nothing further")
    void cloudOnlyBatch_concurrentRefusal_stops() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = coolOffClient(mockClient, null, new ArrayList<>(),
                new long[]{COOL_OFF_T0});
        stubForecastBodies(mockClient, tooManyRequests(CONCURRENT_BODY));

        List<OpenMeteoForecastResponse> results = client.fetchCloudOnlyBatch(buildCoords(50));

        assertThat(results).hasSize(50).containsOnlyNulls();
        verifyRequests(mockClient, 1);
    }

    @Test
    @DisplayName("the pressure-level fetch sends nothing while cooling off")
    void pressureLevelBatch_duringCoolOff_sendsNothing() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = coolOffClient(mockClient, null, new ArrayList<>(),
                new long[]{COOL_OFF_T0});
        stubForecastBodies(mockClient, tooManyRequests(CONCURRENT_BODY));
        client.fetchCloudOnlyBatch(buildCoords(50));

        List<OpenMeteoForecastResponse> results = client.fetchPressureLevelCloudBatch(buildCoords(45));

        assertThat(results).hasSize(45).containsOnlyNulls();
        verifyRequests(mockClient, 1);
    }

    @Test
    @DisplayName("fetchAirQualityBatchResilient: a concurrent-requests refusal on chunk 2 of 5 stops the loop, "
            + "and a second call sends nothing")
    void airQualityResilient_concurrentRefusal_stopsAndFailsFast() {
        RestClient mockAirClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = coolOffClient(null, mockAirClient, sleeps, new long[]{COOL_OFF_T0});
        stubForecastBodies(mockAirClient, buildAirQualityJsonArray(20), tooManyRequests(CONCURRENT_BODY));

        List<OpenMeteoAirQualityResponse> first = client.fetchAirQualityBatchResilient(buildCoords(100));
        List<OpenMeteoAirQualityResponse> second = client.fetchAirQualityBatchResilient(buildCoords(100));

        assertThat(first.subList(0, 20)).allSatisfy(r -> assertThat(r).isNotNull());
        assertThat(first.subList(20, 100)).containsOnlyNulls();
        assertThat(second).hasSize(100).containsOnlyNulls();
        verifyRequests(mockAirClient, 2);
        assertThat(sleeps).doesNotContain(RATE_LIMIT_BACKOFF_MS);
    }

    @Test
    @DisplayName("fetchAirQualityBatchResilient: a read timeout is retried after 30 s then 60 s")
    void airQualityResilient_readTimeout_retriesAfterThirtyThenSixtySeconds() {
        RestClient mockAirClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        List<Long> sleeps = new ArrayList<>();
        OpenMeteoClient client = coolOffClient(null, mockAirClient, sleeps, new long[]{COOL_OFF_T0});
        stubForecastBodies(mockAirClient, readTimeout(), readTimeout(), readTimeout());

        assertThatThrownBy(() -> client.fetchAirQualityBatchResilient(buildCoords(5)))
                .isInstanceOf(RuntimeException.class);

        assertThat(sleeps).containsExactly(30_000L, 60_000L);
        assertThatThrownBy(() -> client.fetchAirQualityBatchResilient(buildCoords(5)))
                .isInstanceOf(OpenMeteoCoolOffException.class);
        verifyRequests(mockAirClient, 3);
    }

    @Test
    @DisplayName("fetchAirQualityBatch (plain): a refusal classified at the chunk makes the next call fail fast")
    void airQualityBatch_refusalMakesNextCallFailFast() {
        RestClient mockAirClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = coolOffClient(null, mockAirClient, new ArrayList<>(),
                new long[]{COOL_OFF_T0});
        stubForecastBodies(mockAirClient, tooManyRequests(CONCURRENT_BODY));
        assertThatThrownBy(() -> client.fetchAirQualityBatch(buildCoords(5)))
                .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> client.fetchAirQualityBatch(buildCoords(5)))
                .isInstanceOf(OpenMeteoCoolOffException.class);
        verifyRequests(mockAirClient, 1);
    }

    @Test
    @DisplayName("the single-location calls classify a refusal and then fail fast without calling the API")
    void singleLocationCalls_failFastDuringCoolOff() {
        OpenMeteoClient client = coolOffClient(null, null, new ArrayList<>(), new long[]{COOL_OFF_T0});
        when(forecastApi.getForecast(eq(54.77), eq(-1.57), eq(OpenMeteoClient.FORECAST_PARAMS), eq("ms"),
                eq("UTC"))).thenThrow(tooManyRequests(CONCURRENT_BODY));

        assertThatThrownBy(() -> client.fetchForecast(54.77, -1.57))
                .isInstanceOf(HttpClientErrorException.class);

        assertThatThrownBy(() -> client.fetchForecast(54.77, -1.57))
                .isInstanceOf(OpenMeteoCoolOffException.class);
        assertThatThrownBy(() -> client.fetchAirQuality(54.77, -1.57))
                .isInstanceOf(OpenMeteoCoolOffException.class);
        verify(forecastApi, times(1)).getForecast(eq(54.77), eq(-1.57), eq(OpenMeteoClient.FORECAST_PARAMS),
                eq("ms"), eq("UTC"));
        verify(airQualityApi, times(0)).getAirQuality(eq(54.77), eq(-1.57),
                eq(OpenMeteoClient.AIR_QUALITY_PARAMS), eq("UTC"));
    }

    @Test
    @DisplayName("with no cool-off set the clock is never read by a batch")
    void noCoolOff_clockIsNeverRead() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = coolOffClient(mockClient, null, new ArrayList<>(), new long[]{COOL_OFF_T0});
        int[] reads = {0};
        client.elapsedClockMs = () -> {
            reads[0]++;
            return COOL_OFF_T0;
        };
        stubForecastBodies(mockClient, buildJsonArray(20, 50.0), new RuntimeException("429 Too Many Requests"),
                buildJsonArray(5, 52.0));

        client.fetchForecastBriefingBatch(buildCoords(45));

        assertThat(reads[0]).isZero();
    }
}
