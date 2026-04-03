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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
}
