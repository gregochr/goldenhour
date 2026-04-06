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

import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
