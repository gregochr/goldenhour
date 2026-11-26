package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.LightPollutionClient;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.config.AuroraProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LocationEnrichmentService#lookupSurfaceHeights}: chunking at 100 points and a failed chunk
 * failing only its own points. The HTTP call is stubbed with the exact elevation URL and coordinate strings of each
 * chunk.
 */
@ExtendWith(MockitoExtension.class)
class LocationEnrichmentServiceSurfaceHeightsTest {

    private static final String ELEVATION_URL =
            "https://api.open-meteo.com/v1/elevation?latitude={lat}&longitude={lon}";
    private static final double BASE_LAT = 54.0;
    private static final double BASE_LON = -3.0;
    private static final double STEP = 0.01;

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

    @BeforeEach
    void setUp() {
        service = new LocationEnrichmentService(lightPollutionClient, auroraProperties,
                restClient, openMeteoForecastApi, Runnable::run);
    }

    private static List<double[]> points(int count) {
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(new double[]{BASE_LAT + i * STEP, BASE_LON - i * STEP});
        }
        return points;
    }

    private static String joined(List<double[]> points, int from, int to, int axis) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.5f", points.get(i)[axis]));
        }
        return sb.toString();
    }

    private static Map<String, Object> answer(int count, double base) {
        List<Number> heights = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            heights.add(base + i);
        }
        return Map.of("elevation", heights);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubChunk(List<double[]> points, int from, int to) {
        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) getSpec);
        when(((RestClient.RequestHeadersUriSpec) getSpec)
                .uri(ELEVATION_URL, joined(points, from, to, 0), joined(points, from, to, 1)))
                .thenReturn(headersSpec);
        when(((RestClient.RequestHeadersSpec) headersSpec).retrieve()).thenReturn(responseSpec);
    }

    @Test
    @DisplayName("an empty list makes no call")
    void emptyListMakesNoCall() {
        assertThat(service.lookupSurfaceHeights(List.of())).isEmpty();

        verifyNoInteractions(restClient);
    }

    @Test
    @DisplayName("198 points are two calls, of 100 and 98 points, and the heights come back in order")
    void chunksAtOneHundred() {
        List<double[]> points = points(198);
        stubChunk(points, 0, 100);
        stubChunk(points, 100, 198);
        when(responseSpec.body(Map.class)).thenReturn(answer(100, 10.0), answer(98, 500.0));

        List<Double> heights = service.lookupSurfaceHeights(points);

        assertThat(heights).hasSize(198);
        assertThat(heights.get(0)).isEqualTo(10.0);
        assertThat(heights.get(99)).isEqualTo(109.0);
        assertThat(heights.get(100)).isEqualTo(500.0);
        assertThat(heights.get(197)).isEqualTo(597.0);
        verify(responseSpec, org.mockito.Mockito.times(2)).body(Map.class);
    }

    @Test
    @DisplayName("a failed chunk returns no heights for its own points only")
    void failedChunkFailsOnlyItsPoints() {
        List<double[]> points = points(150);
        stubChunk(points, 0, 100);
        stubChunk(points, 100, 150);
        when(responseSpec.body(Map.class))
                .thenThrow(new RestClientException("429 Too Many Requests"))
                .thenReturn(answer(50, 300.0));

        List<Double> heights = service.lookupSurfaceHeights(points);

        assertThat(heights).hasSize(150);
        assertThat(heights.subList(0, 100)).containsOnlyNulls();
        assertThat(heights.get(100)).isEqualTo(300.0);
        assertThat(heights.get(149)).isEqualTo(349.0);
    }

    @Test
    @DisplayName("a wrong-sized answer fails its chunk")
    void wrongSizedAnswerFailsTheChunk() {
        List<double[]> points = points(3);
        stubChunk(points, 0, 3);
        when(responseSpec.body(Map.class)).thenReturn(answer(2, 10.0));

        assertThat(service.lookupSurfaceHeights(points)).isEqualTo(Collections.nCopies(3, null));
    }

    @Test
    @DisplayName("a null-bearing answer fails its chunk")
    void nullBearingAnswerFailsTheChunk() {
        List<double[]> points = points(2);
        stubChunk(points, 0, 2);
        List<Number> heights = new ArrayList<>();
        heights.add(10.0);
        heights.add(null);
        when(responseSpec.body(Map.class)).thenReturn(Map.of("elevation", heights));

        assertThat(service.lookupSurfaceHeights(points)).isEqualTo(Collections.nCopies(2, null));
    }

    @Test
    @DisplayName("a missing answer fails its chunk")
    void nullAnswerFailsTheChunk() {
        List<double[]> points = points(2);
        stubChunk(points, 0, 2);
        when(responseSpec.body(Map.class)).thenReturn(null);

        assertThat(service.lookupSurfaceHeights(points)).isEqualTo(Collections.nCopies(2, null));
    }
}
