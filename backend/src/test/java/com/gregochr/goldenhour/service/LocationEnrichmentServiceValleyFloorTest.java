package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.LightPollutionClient;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.exception.UpstreamLookupException;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.ValleyFloorCandidate;
import com.gregochr.goldenhour.model.ValleyFloorCandidates;
import com.gregochr.goldenhour.model.ValleyFloorCell;
import com.gregochr.goldenhour.util.GeoUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the valley-floor lookups of {@link LocationEnrichmentService}: the 97-point candidate
 * sampling and the model-cell lookup. The HTTP call is stubbed explicitly (the elevation URL and its two
 * coordinate strings), and the request the service built is captured from that stub.
 */
@ExtendWith(MockitoExtension.class)
class LocationEnrichmentServiceValleyFloorTest {

    private static final double LAT = 54.6124;
    private static final double LON = -3.1179;
    private static final String ELEVATION_URL =
            "https://api.open-meteo.com/v1/elevation?latitude={lat}&longitude={lon}";
    private static final int POINTS = 97;
    private static final int PER_SECTOR = 12;
    private static final double SITE = 300.0;
    private static final double FLAT = 290.0;
    private static final Locale ORIGINAL_LOCALE = Locale.getDefault();

    @Mock
    private LightPollutionClient lightPollutionClient;

    @Mock
    private AuroraProperties auroraProperties;

    @Mock
    private OpenMeteoForecastApi openMeteoForecastApi;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestHeadersUriSpec<?> getSpec;

    @Mock
    private RestClient.RequestHeadersSpec<?> headersSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private LocationEnrichmentService service;
    private final List<String> sentLats = new ArrayList<>();
    private final List<String> sentLons = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new LocationEnrichmentService(lightPollutionClient, auroraProperties,
                restClient, openMeteoForecastApi, Runnable::run);
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(ORIGINAL_LOCALE);
    }

    /** Stubs the elevation call to answer {@code body}, capturing the two coordinate strings it was sent. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubElevation(Map<String, Object> body) {
        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) getSpec);
        when(((RestClient.RequestHeadersUriSpec) getSpec).uri(eq(ELEVATION_URL), anyString(), anyString()))
                .thenAnswer(inv -> {
                    sentLats.add(inv.getArgument(1));
                    sentLons.add(inv.getArgument(2));
                    return headersSpec;
                });
        when(((RestClient.RequestHeadersSpec) headersSpec).retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(body);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubElevationThrows(RuntimeException failure) {
        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) getSpec);
        when(((RestClient.RequestHeadersUriSpec) getSpec).uri(eq(ELEVATION_URL), anyString(), anyString()))
                .thenReturn(headersSpec);
        when(((RestClient.RequestHeadersSpec) headersSpec).retrieve()).thenThrow(failure);
    }

    /** 97 heights: the site first, then every sampled point at {@link #FLAT} unless overridden by index. */
    private static List<Number> heights(Map<Integer, Double> overrides) {
        List<Number> out = new ArrayList<>();
        out.add(SITE);
        for (int i = 1; i < POINTS; i++) {
            out.add(overrides.getOrDefault(i, FLAT));
        }
        return out;
    }

    private static int index(int sector, int k) {
        return 1 + sector * PER_SECTOR + k;
    }

    @Test
    @DisplayName("requests 97 points in one call: the site first, then 8 sectors of 12, all within 5 km")
    void candidates_requestsNinetySevenPoints() {
        stubElevation(Map.of("elevation", heights(Map.of())));

        service.valleyFloorCandidates(LAT, LON);

        assertThat(sentLats).hasSize(1);
        String[] lats = sentLats.getFirst().split(",");
        String[] lons = sentLons.getFirst().split(",");
        assertThat(lats).hasSize(POINTS);
        assertThat(lons).hasSize(POINTS);
        assertThat(Double.parseDouble(lats[0])).isEqualTo(LAT);
        assertThat(Double.parseDouble(lons[0])).isEqualTo(LON);
        for (int i = 1; i < POINTS; i++) {
            double distance = GeoUtils.distanceKm(LAT, LON,
                    Double.parseDouble(lats[i]), Double.parseDouble(lons[i]));
            assertThat(distance).as("point %d", i).isBetween(0.9, 5.1);
        }
    }

    @Test
    @DisplayName("coordinates are formatted with five decimals and a dot whatever the default locale")
    void candidates_coordinatesUseLocaleRootFormatting() {
        Locale.setDefault(Locale.GERMANY);
        stubElevation(Map.of("elevation", heights(Map.of())));

        service.valleyFloorCandidates(LAT, LON);

        for (String value : (sentLats.getFirst() + "," + sentLons.getFirst()).split(",")) {
            assertThat(value).matches("-?\\d+\\.\\d{5}");
        }
    }

    @Test
    @DisplayName("keeps the lowest point per sector, drops sectors under the 30 m margin, sorts by height")
    void candidates_lowestPerSectorWithMarginAndSorted() {
        stubElevation(Map.of("elevation", heights(Map.of(
                index(2, 5), 120.0,
                index(2, 7), 200.0,
                index(6, 3), 100.0,
                index(0, 4), SITE - 30.0,
                index(5, 1), SITE - 29.0))));

        ValleyFloorCandidates result = service.valleyFloorCandidates(LAT, LON);

        assertThat(result.siteSampleMetres()).isEqualTo(300);
        assertThat(result.candidates()).extracting(ValleyFloorCandidate::metres)
                .containsExactly(100, 120, 270);
        String[] lats = sentLats.getFirst().split(",");
        String[] lons = sentLons.getFirst().split(",");
        ValleyFloorCandidate east = result.candidates().get(1);
        assertThat(east.lat()).isCloseTo(Double.parseDouble(lats[index(2, 5)]), within(1e-5));
        assertThat(east.lng()).isCloseTo(Double.parseDouble(lons[index(2, 5)]), within(1e-5));
        assertThat(east.bearing()).isEqualTo("E");
        assertThat(east.distanceKm()).isBetween(0.9, 5.1);
        assertThat(result.candidates().getFirst().bearing()).isEqualTo("W");
    }

    @Test
    @DisplayName("an empty candidate list (not an error) when nothing is 30 m below the site")
    void candidates_noneQualify_emptyList() {
        stubElevation(Map.of("elevation", heights(Map.of(index(1, 0), SITE - 29.0))));

        ValleyFloorCandidates result = service.valleyFloorCandidates(LAT, LON);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.siteSampleMetres()).isEqualTo(300);
    }

    @Test
    @DisplayName("a list that is not 97 long is an upstream failure")
    void candidates_wrongSize_failure() {
        stubElevation(Map.of("elevation", heights(Map.of()).subList(0, POINTS - 1)));

        assertThatThrownBy(() -> service.valleyFloorCandidates(LAT, LON))
                .isInstanceOf(UpstreamLookupException.class);
    }

    @Test
    @DisplayName("a null entry in the heights is an upstream failure")
    void candidates_nullEntry_failure() {
        List<Number> withNull = heights(Map.of());
        withNull.set(10, null);
        stubElevation(Map.of("elevation", withNull));

        assertThatThrownBy(() -> service.valleyFloorCandidates(LAT, LON))
                .isInstanceOf(UpstreamLookupException.class);
    }

    @Test
    @DisplayName("an empty or missing body is an upstream failure")
    void candidates_missingBody_failure() {
        stubElevation(Map.of());

        assertThatThrownBy(() -> service.valleyFloorCandidates(LAT, LON))
                .isInstanceOf(UpstreamLookupException.class);
    }

    @Test
    @DisplayName("a transport failure is an upstream failure, whatever the exception type")
    void candidates_transportFailure_failure() {
        stubElevationThrows(new RestClientException("boom"));

        assertThatThrownBy(() -> service.valleyFloorCandidates(LAT, LON))
                .isInstanceOf(UpstreamLookupException.class)
                .hasMessageContaining("try again");
    }

    @Test
    @DisplayName("out-of-range coordinates are rejected before any call")
    void candidates_badCoordinates_rejected() {
        assertThatThrownBy(() -> service.valleyFloorCandidates(91.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.valleyFloorCandidates(0.0, 181.0))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(restClient);
    }

    @Test
    @DisplayName("valleyFloorCell() returns the snapped cell and the response's elevation")
    void cell_returnsCellAndElevation() {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setLatitude(54.575);
        response.setLongitude(-3.152);
        response.setElevation(80.0);
        when(openMeteoForecastApi.getForecastAtElevation(
                eq(54.58), eq(-3.15), eq("temperature_2m"), eq("ms"), eq("UTC"), eq(80.0)))
                .thenReturn(response);

        ValleyFloorCell cell = service.valleyFloorCell(54.58, -3.15, 80.0);

        assertThat(cell.cellLat()).isEqualTo(54.575);
        assertThat(cell.cellLng()).isEqualTo(-3.152);
        assertThat(cell.cellElevationMetres()).isEqualTo(80.0);
        assertThat(cell.cellDistanceKm())
                .isCloseTo(GeoUtils.distanceKm(54.58, -3.15, 54.575, -3.152), within(1e-9));
        verify(openMeteoForecastApi, never()).getForecast(
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("valleyFloorCell() fails as an upstream failure on an exception or an incomplete response")
    void cell_failures() {
        when(openMeteoForecastApi.getForecastAtElevation(
                eq(54.0), eq(-3.0), anyString(), anyString(), anyString(), eq(10.0)))
                .thenThrow(new RuntimeException("down"));
        OpenMeteoForecastResponse noElevation = new OpenMeteoForecastResponse();
        noElevation.setLatitude(54.1);
        noElevation.setLongitude(-3.1);
        when(openMeteoForecastApi.getForecastAtElevation(
                eq(54.1), eq(-3.1), anyString(), anyString(), anyString(), eq(10.0)))
                .thenReturn(noElevation);
        when(openMeteoForecastApi.getForecastAtElevation(
                eq(54.2), eq(-3.2), anyString(), anyString(), anyString(), eq(10.0)))
                .thenReturn(null);

        assertThatThrownBy(() -> service.valleyFloorCell(54.0, -3.0, 10.0))
                .isInstanceOf(UpstreamLookupException.class);
        assertThatThrownBy(() -> service.valleyFloorCell(54.1, -3.1, 10.0))
                .isInstanceOf(UpstreamLookupException.class);
        assertThatThrownBy(() -> service.valleyFloorCell(54.2, -3.2, 10.0))
                .isInstanceOf(UpstreamLookupException.class);
    }

    @Test
    @DisplayName("valleyFloorCell() rejects out-of-range inputs without calling Open-Meteo")
    void cell_badInputs_rejected() {
        assertThatThrownBy(() -> service.valleyFloorCell(95.0, 0.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.valleyFloorCell(54.0, -3.0, 2000.0))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(openMeteoForecastApi);
    }
}
