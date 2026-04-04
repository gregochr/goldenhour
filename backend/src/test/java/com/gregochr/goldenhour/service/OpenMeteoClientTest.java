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

import java.util.List;

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
