package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.LightPollutionClient;
import com.gregochr.goldenhour.client.LightPollutionClient.SkyBrightnessResult;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.LocationEnrichmentResult;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LocationEnrichmentService}.
 */
@ExtendWith(MockitoExtension.class)
class LocationEnrichmentServiceTest {

    private static final double LATRIGG_LAT = 54.6124;
    private static final double LATRIGG_LON = -3.1179;
    private static final double BAMBURGH_LAT = 55.609;
    private static final double BAMBURGH_LON = -1.7099;
    private static final String API_KEY = "test-key";
    private static final String ELEVATION_URL =
            "https://api.open-meteo.com/v1/elevation?latitude={lat}&longitude={lon}";

    @Mock
    private LightPollutionClient lightPollutionClient;

    @Mock
    private AuroraProperties auroraProperties;

    @Mock
    private OpenMeteoForecastApi openMeteoForecastApi;

    private RestClient restClient;
    private LocationEnrichmentService service;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        service = new LocationEnrichmentService(lightPollutionClient, auroraProperties,
                restClient, openMeteoForecastApi);
    }

    // ── Happy path ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("enrich() returns all fields when all APIs succeed")
    void enrich_allApisSucceed_returnsFullResult() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(API_KEY);
        when(lightPollutionClient.querySkyBrightness(eq(LATRIGG_LAT), eq(LATRIGG_LON), eq(API_KEY)))
                .thenReturn(new SkyBrightnessResult(21.72, 3));
        stubElevationResponse(Map.of("elevation", List.of(368.0)));
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.611, -3.121);

        LocationEnrichmentResult result = service.enrich(LATRIGG_LAT, LATRIGG_LON);

        assertThat(result.bortleClass()).isEqualTo(3);
        assertThat(result.skyBrightnessSqm()).isEqualTo(21.72);
        assertThat(result.elevationMetres()).isEqualTo(368);
        assertThat(result.gridLat()).isEqualTo(54.611);
        assertThat(result.gridLng()).isEqualTo(-3.121);
    }

    @Test
    @DisplayName("enrich() passes exact coordinates and params to forecast API")
    void enrich_passesExactArgsToForecastApi() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(null);
        stubElevationResponse(Map.of("elevation", List.of(0.0)));
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.611, -3.121);

        service.enrich(LATRIGG_LAT, LATRIGG_LON);

        verify(openMeteoForecastApi).getForecast(
                eq(LATRIGG_LAT), eq(LATRIGG_LON),
                eq("temperature_2m"), eq("ms"), eq("UTC"));
    }

    @Test
    @DisplayName("enrich() passes exact coordinates and API key to light pollution client")
    void enrich_passesExactArgsToLightPollutionClient() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(API_KEY);
        when(lightPollutionClient.querySkyBrightness(eq(LATRIGG_LAT), eq(LATRIGG_LON), eq(API_KEY)))
                .thenReturn(new SkyBrightnessResult(21.72, 3));
        stubElevationResponse(Map.of("elevation", List.of(0.0)));
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.0, -3.0);

        service.enrich(LATRIGG_LAT, LATRIGG_LON);

        verify(lightPollutionClient).querySkyBrightness(
                eq(LATRIGG_LAT), eq(LATRIGG_LON), eq(API_KEY));
    }

    @Test
    @DisplayName("enrich() works with a different coordinate (Bamburgh)")
    void enrich_bamburghCoordinates_returnsCorrectResult() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(API_KEY);
        when(lightPollutionClient.querySkyBrightness(eq(BAMBURGH_LAT), eq(BAMBURGH_LON), eq(API_KEY)))
                .thenReturn(new SkyBrightnessResult(21.92, 2));
        stubElevationResponse(BAMBURGH_LAT, BAMBURGH_LON,
                Map.of("elevation", List.of(5.0)));
        stubForecastResponse(BAMBURGH_LAT, BAMBURGH_LON, 55.6, -1.71);

        LocationEnrichmentResult result = service.enrich(BAMBURGH_LAT, BAMBURGH_LON);

        assertThat(result.bortleClass()).isEqualTo(2);
        assertThat(result.skyBrightnessSqm()).isEqualTo(21.92);
        assertThat(result.elevationMetres()).isEqualTo(5);
        assertThat(result.gridLat()).isEqualTo(55.6);
        assertThat(result.gridLng()).isEqualTo(-1.71);

        verify(lightPollutionClient).querySkyBrightness(
                eq(BAMBURGH_LAT), eq(BAMBURGH_LON), eq(API_KEY));
        verify(openMeteoForecastApi).getForecast(
                eq(BAMBURGH_LAT), eq(BAMBURGH_LON),
                eq("temperature_2m"), eq("ms"), eq("UTC"));
    }

    // ── Light pollution failure modes ──────────────────────────────────────

    @Test
    @DisplayName("enrich() returns null bortle/SQM when light pollution client returns null")
    void enrich_lightPollutionReturnsNull_nullBortleAndSqm() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(API_KEY);
        when(lightPollutionClient.querySkyBrightness(eq(LATRIGG_LAT), eq(LATRIGG_LON), eq(API_KEY)))
                .thenReturn(null);
        stubElevationResponse(Map.of("elevation", List.of(10.0)));
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.611, -3.121);

        LocationEnrichmentResult result = service.enrich(LATRIGG_LAT, LATRIGG_LON);

        assertThat(result.bortleClass()).isNull();
        assertThat(result.skyBrightnessSqm()).isNull();
        assertThat(result.elevationMetres()).isEqualTo(10);
        assertThat(result.gridLat()).isEqualTo(54.611);
        assertThat(result.gridLng()).isEqualTo(-3.121);
    }

    @Test
    @DisplayName("enrich() returns null bortle/SQM when light pollution client throws exception")
    void enrich_lightPollutionThrows_nullBortleAndSqm() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(API_KEY);
        when(lightPollutionClient.querySkyBrightness(eq(LATRIGG_LAT), eq(LATRIGG_LON), eq(API_KEY)))
                .thenThrow(new RuntimeException("connection refused"));
        stubElevationResponse(Map.of("elevation", List.of(50.0)));
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.611, -3.121);

        LocationEnrichmentResult result = service.enrich(LATRIGG_LAT, LATRIGG_LON);

        assertThat(result.bortleClass()).isNull();
        assertThat(result.skyBrightnessSqm()).isNull();
        assertThat(result.elevationMetres()).isEqualTo(50);
    }

    @Test
    @DisplayName("enrich() skips bortle when API key is null — does not call client")
    void enrich_nullApiKey_doesNotCallLightPollutionClient() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(null);
        stubElevationResponse(Map.of("elevation", List.of(50.0)));
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.0, -3.0);

        LocationEnrichmentResult result = service.enrich(LATRIGG_LAT, LATRIGG_LON);

        assertThat(result.bortleClass()).isNull();
        assertThat(result.skyBrightnessSqm()).isNull();
        verify(lightPollutionClient, never()).querySkyBrightness(
                eq(LATRIGG_LAT), eq(LATRIGG_LON), anyString());
    }

    @Test
    @DisplayName("enrich() skips bortle when API key is blank — does not call client")
    void enrich_blankApiKey_doesNotCallLightPollutionClient() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn("   ");
        stubElevationResponse(Map.of("elevation", List.of(50.0)));
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.0, -3.0);

        LocationEnrichmentResult result = service.enrich(LATRIGG_LAT, LATRIGG_LON);

        assertThat(result.bortleClass()).isNull();
        assertThat(result.skyBrightnessSqm()).isNull();
        verify(lightPollutionClient, never()).querySkyBrightness(
                eq(LATRIGG_LAT), eq(LATRIGG_LON), anyString());
    }

    // ── Elevation failure modes ───────────────────────────────────────────

    @Test
    @DisplayName("enrich() returns null elevation when Open-Meteo elevation API throws")
    void enrich_elevationApiThrows_nullElevation() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(API_KEY);
        when(lightPollutionClient.querySkyBrightness(eq(LATRIGG_LAT), eq(LATRIGG_LON), eq(API_KEY)))
                .thenReturn(new SkyBrightnessResult(21.72, 3));
        stubElevationFailure();
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.611, -3.121);

        LocationEnrichmentResult result = service.enrich(LATRIGG_LAT, LATRIGG_LON);

        assertThat(result.elevationMetres()).isNull();
        assertThat(result.bortleClass()).isEqualTo(3);
        assertThat(result.skyBrightnessSqm()).isEqualTo(21.72);
        assertThat(result.gridLat()).isEqualTo(54.611);
        assertThat(result.gridLng()).isEqualTo(-3.121);
    }

    @Test
    @DisplayName("enrich() returns null elevation when response has empty elevation array")
    void enrich_emptyElevationArray_nullElevation() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(null);
        stubElevationResponse(Map.of("elevation", Collections.emptyList()));
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.0, -3.0);

        LocationEnrichmentResult result = service.enrich(LATRIGG_LAT, LATRIGG_LON);

        assertThat(result.elevationMetres()).isNull();
    }

    @Test
    @DisplayName("enrich() returns null elevation when response has no elevation key")
    void enrich_noElevationKey_nullElevation() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(null);
        stubElevationResponse(Map.of("other_key", "value"));
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.0, -3.0);

        LocationEnrichmentResult result = service.enrich(LATRIGG_LAT, LATRIGG_LON);

        assertThat(result.elevationMetres()).isNull();
    }

    @Test
    @DisplayName("enrich() returns null elevation when response body is null")
    @SuppressWarnings("unchecked")
    void enrich_nullElevationBody_nullElevation() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(null);
        when(restClient.get()
                .uri(eq(ELEVATION_URL), eq(LATRIGG_LAT), eq(LATRIGG_LON))
                .retrieve().body(Map.class)).thenReturn(null);
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.0, -3.0);

        LocationEnrichmentResult result = service.enrich(LATRIGG_LAT, LATRIGG_LON);

        assertThat(result.elevationMetres()).isNull();
    }

    // ── Elevation rounding ────────────────────────────────────────────────

    @Test
    @DisplayName("enrich() rounds elevation 123.7 up to 124")
    void enrich_elevationRoundsUp() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(null);
        stubElevationResponse(Map.of("elevation", List.of(123.7)));
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.0, -3.0);

        assertThat(service.enrich(LATRIGG_LAT, LATRIGG_LON).elevationMetres()).isEqualTo(124);
    }

    @Test
    @DisplayName("enrich() rounds elevation 123.4 down to 123")
    void enrich_elevationRoundsDown() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(null);
        stubElevationResponse(Map.of("elevation", List.of(123.4)));
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.0, -3.0);

        assertThat(service.enrich(LATRIGG_LAT, LATRIGG_LON).elevationMetres()).isEqualTo(123);
    }

    @Test
    @DisplayName("enrich() rounds elevation 0.5 to 1 (half-up)")
    void enrich_elevationRoundsHalfUp() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(null);
        stubElevationResponse(Map.of("elevation", List.of(0.5)));
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.0, -3.0);

        assertThat(service.enrich(LATRIGG_LAT, LATRIGG_LON).elevationMetres()).isEqualTo(1);
    }

    @Test
    @DisplayName("enrich() returns 0 for sea-level elevation")
    void enrich_seaLevelElevation_returnsZero() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(null);
        stubElevationResponse(Map.of("elevation", List.of(0.0)));
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.0, -3.0);

        assertThat(service.enrich(LATRIGG_LAT, LATRIGG_LON).elevationMetres()).isEqualTo(0);
    }

    @Test
    @DisplayName("enrich() uses first element when elevation array has multiple values")
    void enrich_multipleElevationValues_usesFirst() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(null);
        stubElevationResponse(Map.of("elevation", List.of(368.0, 999.0)));
        stubForecastResponse(LATRIGG_LAT, LATRIGG_LON, 54.0, -3.0);

        assertThat(service.enrich(LATRIGG_LAT, LATRIGG_LON).elevationMetres())
                .isEqualTo(368);
    }

    // ── Grid cell failure modes ───────────────────────────────────────────

    @Test
    @DisplayName("enrich() returns null grid when forecast API throws")
    void enrich_forecastApiThrows_nullGrid() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(API_KEY);
        when(lightPollutionClient.querySkyBrightness(eq(LATRIGG_LAT), eq(LATRIGG_LON), eq(API_KEY)))
                .thenReturn(new SkyBrightnessResult(21.72, 3));
        stubElevationResponse(Map.of("elevation", List.of(368.0)));
        when(openMeteoForecastApi.getForecast(eq(LATRIGG_LAT), eq(LATRIGG_LON),
                eq("temperature_2m"), eq("ms"), eq("UTC")))
                .thenThrow(new RestClientException("timeout"));

        LocationEnrichmentResult result = service.enrich(LATRIGG_LAT, LATRIGG_LON);

        assertThat(result.gridLat()).isNull();
        assertThat(result.gridLng()).isNull();
        assertThat(result.bortleClass()).isEqualTo(3);
        assertThat(result.elevationMetres()).isEqualTo(368);
    }

    @Test
    @DisplayName("enrich() returns null grid when forecast response has null latitude")
    void enrich_forecastNullLatitude_nullGrid() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(null);
        stubElevationResponse(Map.of("elevation", List.of(0.0)));

        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setLatitude(null);
        response.setLongitude(-3.121);
        when(openMeteoForecastApi.getForecast(eq(LATRIGG_LAT), eq(LATRIGG_LON),
                eq("temperature_2m"), eq("ms"), eq("UTC")))
                .thenReturn(response);

        LocationEnrichmentResult result = service.enrich(LATRIGG_LAT, LATRIGG_LON);

        assertThat(result.gridLat()).isNull();
        assertThat(result.gridLng()).isNull();
    }

    @Test
    @DisplayName("enrich() returns null grid when forecast response has null longitude")
    void enrich_forecastNullLongitude_nullGrid() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(null);
        stubElevationResponse(Map.of("elevation", List.of(0.0)));

        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setLatitude(54.611);
        response.setLongitude(null);
        when(openMeteoForecastApi.getForecast(eq(LATRIGG_LAT), eq(LATRIGG_LON),
                eq("temperature_2m"), eq("ms"), eq("UTC")))
                .thenReturn(response);

        LocationEnrichmentResult result = service.enrich(LATRIGG_LAT, LATRIGG_LON);

        assertThat(result.gridLat()).isNull();
        assertThat(result.gridLng()).isNull();
    }

    @Test
    @DisplayName("enrich() returns null grid when forecast returns null response")
    void enrich_forecastNullResponse_nullGrid() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(null);
        stubElevationResponse(Map.of("elevation", List.of(0.0)));
        when(openMeteoForecastApi.getForecast(eq(LATRIGG_LAT), eq(LATRIGG_LON),
                eq("temperature_2m"), eq("ms"), eq("UTC")))
                .thenReturn(null);

        LocationEnrichmentResult result = service.enrich(LATRIGG_LAT, LATRIGG_LON);

        assertThat(result.gridLat()).isNull();
        assertThat(result.gridLng()).isNull();
    }

    // ── Total failure ─────────────────────────────────────────────────────

    @Test
    @DisplayName("enrich() returns all nulls when every API fails simultaneously")
    void enrich_allFail_returnsAllNulls() {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(API_KEY);
        when(lightPollutionClient.querySkyBrightness(eq(LATRIGG_LAT), eq(LATRIGG_LON), eq(API_KEY)))
                .thenThrow(new RuntimeException("light pollution down"));
        stubElevationFailure();
        when(openMeteoForecastApi.getForecast(eq(LATRIGG_LAT), eq(LATRIGG_LON),
                eq("temperature_2m"), eq("ms"), eq("UTC")))
                .thenThrow(new RuntimeException("forecast down"));

        LocationEnrichmentResult result = service.enrich(LATRIGG_LAT, LATRIGG_LON);

        assertThat(result.bortleClass()).isNull();
        assertThat(result.skyBrightnessSqm()).isNull();
        assertThat(result.elevationMetres()).isNull();
        assertThat(result.gridLat()).isNull();
        assertThat(result.gridLng()).isNull();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Stubs the elevation API for the default LATRIGG coordinates.
     */
    private void stubElevationResponse(Map<String, Object> body) {
        stubElevationResponse(LATRIGG_LAT, LATRIGG_LON, body);
    }

    /**
     * Stubs the elevation API with exact coordinate matchers — a lat/lon
     * swap in production code causes the stub to not match, returning null.
     */
    @SuppressWarnings("unchecked")
    private void stubElevationResponse(double lat, double lon,
                                        Map<String, Object> body) {
        when(restClient.get()
                .uri(eq(ELEVATION_URL), eq(lat), eq(lon))
                .retrieve().body(Map.class)).thenReturn(body);
    }

    private void stubElevationFailure() {
        stubElevationFailure(LATRIGG_LAT, LATRIGG_LON);
    }

    @SuppressWarnings("unchecked")
    private void stubElevationFailure(double lat, double lon) {
        when(restClient.get()
                .uri(eq(ELEVATION_URL), eq(lat), eq(lon))
                .retrieve().body(Map.class))
                .thenThrow(new RestClientException("elevation down"));
    }

    private void stubForecastResponse(double inputLat, double inputLon,
                                      double gridLat, double gridLng) {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setLatitude(gridLat);
        response.setLongitude(gridLng);
        when(openMeteoForecastApi.getForecast(
                eq(inputLat), eq(inputLon),
                eq("temperature_2m"), eq("ms"), eq("UTC")))
                .thenReturn(response);
    }
}
