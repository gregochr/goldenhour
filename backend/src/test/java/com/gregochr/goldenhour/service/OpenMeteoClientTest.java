package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenMeteoAirQualityApi;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
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

    @Test
    @DisplayName("fetchCloudOnly() delegates to forecastApi with cloud-only parameters")
    void fetchCloudOnly_delegatesToApi() {
        OpenMeteoForecastResponse expected = new OpenMeteoForecastResponse();
        when(forecastApi.getForecast(anyDouble(), anyDouble(), anyString(), anyString(), anyString()))
                .thenReturn(expected);

        OpenMeteoForecastResponse result = openMeteoClient.fetchCloudOnly(54.77, -1.57);

        assertThat(result).isSameAs(expected);
        verify(forecastApi).getForecast(
                eq(54.77), eq(-1.57),
                eq(OpenMeteoClient.CLOUD_ONLY_PARAMS), eq("ms"), eq("UTC"));
    }

    @Test
    @DisplayName("fetchForecastBriefing() delegates to forecastApi with briefing parameters")
    void fetchForecastBriefing_delegatesToApi() {
        OpenMeteoForecastResponse expected = new OpenMeteoForecastResponse();
        when(forecastApi.getForecast(anyDouble(), anyDouble(), anyString(), anyString(), anyString()))
                .thenReturn(expected);

        OpenMeteoForecastResponse result = openMeteoClient.fetchForecastBriefing(54.77, -1.57);

        assertThat(result).isSameAs(expected);
        verify(forecastApi).getForecast(
                eq(54.77), eq(-1.57),
                eq(OpenMeteoClient.FORECAST_PARAMS), eq("ms"), eq("UTC"));
    }

    // ── Batch parsing tests ────────────────────────────────────────────────────

    @Test
    @DisplayName("parseArrayResponse handles single-object JSON (1 location)")
    void batchParsing_singleObject_returnsSingleElement() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);

        String singleJson = "{\"latitude\":55.0,\"longitude\":-1.5}";
        when(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class)).thenReturn(singleJson);

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(
                List.of(new double[]{55.0, -1.5}));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getLatitude()).isEqualTo(55.0);
    }

    @Test
    @DisplayName("parseArrayResponse handles JSON array (2+ locations)")
    void batchParsing_array_returnsMultipleElements() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);

        String arrayJson = "[{\"latitude\":55.0,\"longitude\":-1.5},"
                + "{\"latitude\":54.0,\"longitude\":-2.0}]";
        when(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class)).thenReturn(arrayJson);

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
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);
        client.interChunkDelayMs = 0;

        // 25 coords → chunk of 20 + chunk of 5; mock returns chunk-sized arrays on successive calls.
        // If chunking is absent, only 20 results are returned (the first return value).
        // If chunking works, both return values are consumed and 25 results combined.
        List<double[]> coords = buildCoords(25);
        when(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class))
                .thenReturn(buildJsonArray(20, 50.0), buildJsonArray(5, 51.0));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBatch(coords);

        assertThat(results).hasSize(25);
        assertThat(results.stream().filter(r -> r.getLatitude() >= 51.0).count()).isEqualTo(5);
        assertThat(results.stream().filter(r -> r.getLatitude() < 51.0).count()).isEqualTo(20);
    }

    @Test
    @DisplayName("fetchAirQualityBatch with 25 coords combines results from two chunks (20 + 5)")
    void fetchAirQualityBatch_oversizeInput_combinesTwoChunks() {
        RestClient mockAirQualityClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                null, mockAirQualityClient);
        client.interChunkDelayMs = 0;

        List<double[]> coords = buildCoords(25);
        when(mockAirQualityClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class))
                .thenReturn(buildAirQualityJsonArray(20), buildAirQualityJsonArray(5));

        List<OpenMeteoAirQualityResponse> results = client.fetchAirQualityBatch(coords);

        assertThat(results).hasSize(25);
    }

    @Test
    @DisplayName("fetchForecastBatch with exactly BATCH_COORD_LIMIT coords returns all in one call")
    void fetchForecastBatch_exactLimit_returnsAll() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);

        List<double[]> coords = buildCoords(OpenMeteoClient.BATCH_COORD_LIMIT);
        when(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class))
                .thenReturn(buildJsonArray(OpenMeteoClient.BATCH_COORD_LIMIT, 50.0));

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
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);
        client.interChunkDelayMs = 0;
        client.rateLimitBackoffMs = 0;

        // 25 coords → 2 chunks; both throw
        List<double[]> coords = buildCoords(25);
        when(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class))
                .thenThrow(new RuntimeException("service unavailable"));

        List<OpenMeteoForecastResponse> results = client.fetchForecastBriefingBatch(coords);

        assertThat(results).hasSize(25);
        assertThat(results).allSatisfy(r -> assertThat(r).isNull());
    }

    @Test
    @DisplayName("fetchForecastBriefingBatch: all chunks succeed, returns full list with no null entries")
    void fetchForecastBriefingBatch_allChunksSucceed_noNullEntries() {
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);
        client.interChunkDelayMs = 0;

        // 25 coords → 2 chunks: [0–19] lat≥50.0, [20–24] lat≥51.0
        List<double[]> coords = buildCoords(25);
        when(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class))
                .thenReturn(buildJsonArray(20, 50.0), buildJsonArray(5, 51.0));

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
        RestClient mockForecastClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        OpenMeteoClient client = new OpenMeteoClient(
                forecastApi, airQualityApi, new ObjectMapper(),
                mockForecastClient, null);

        when(mockForecastClient.get().uri(org.mockito.ArgumentMatchers.<java.util.function.Function
                <org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve().body(String.class)).thenReturn("not json at all");

        assertThatThrownBy(() -> client.fetchForecastBriefingBatch(
                List.of(new double[]{55.0, -1.5})))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse");
    }
}
