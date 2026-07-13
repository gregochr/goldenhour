package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.OrsProperties;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenRouteServiceClient}.
 *
 * <p>Happy-path durations testing is covered at the integration level via
 * {@link com.gregochr.goldenhour.service.DriveDurationServiceTest} which stubs
 * {@code fetchDurations()} directly. These tests cover the guard and error paths.
 */
@ExtendWith(MockitoExtension.class)
class OpenRouteServiceClientTest {

    @Mock
    private RestClient restClient;

    @Mock
    private OrsProperties properties;

    private OpenRouteServiceClient client;

    @BeforeEach
    void setUp() {
        client = new OpenRouteServiceClient(restClient, properties);
    }

    @Test
    @DisplayName("returns empty list when ORS is not configured")
    void fetchDurations_orsNotConfigured_returnsEmptyList() {
        when(properties.isConfigured()).thenReturn(false);

        List<Double> result = client.fetchDurations(54.77, -1.60, List.of(new double[]{54.78, -1.58}));

        assertThat(result).isEmpty();
        verify(restClient, never()).post();
    }

    @Test
    @DisplayName("returns empty list when destinations are empty")
    void fetchDurations_noDestinations_returnsEmptyList() {
        when(properties.isConfigured()).thenReturn(true);

        List<Double> result = client.fetchDurations(54.77, -1.60, List.of());

        assertThat(result).isEmpty();
        verify(restClient, never()).post();
    }

    @Test
    @DisplayName("returns empty list when ORS returns null response body")
    void fetchDurations_nullResponseBody_returnsEmptyList() {
        when(properties.isConfigured()).thenReturn(true);
        when(properties.getApiKey()).thenReturn("test-key");
        RestClientMocks.stubPost(restClient, OpenRouteServiceClient.OrsMatrixResponse.class, null);

        List<Double> result = client.fetchDurations(54.77, -1.60,
                List.of(new double[]{54.78, -1.58}));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("strips source→source entry; returns one duration per destination")
    void fetchDurations_validResponse_stripsSelfEntry() {
        when(properties.isConfigured()).thenReturn(true);
        when(properties.getApiKey()).thenReturn("test-key");

        // ORS durations[0] = [0.0 (self), 2700.0 (dest1), 3600.0 (dest2)]
        OpenRouteServiceClient.OrsMatrixResponse response =
                new OpenRouteServiceClient.OrsMatrixResponse(
                        List.of(List.of(0.0, 2700.0, 3600.0)));
        RestClientMocks.stubPost(restClient, OpenRouteServiceClient.OrsMatrixResponse.class, response);

        List<double[]> destinations = List.of(
                new double[]{54.78, -1.58},
                new double[]{55.04, -1.44});
        List<Double> result = client.fetchDurations(54.77, -1.60, destinations);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(2700.0);
        assertThat(result.get(1)).isEqualTo(3600.0);
    }

    @Test
    @DisplayName("returns empty list when ORS row is shorter than expected")
    void fetchDurations_rowTooShort_returnsEmptyList() {
        when(properties.isConfigured()).thenReturn(true);
        when(properties.getApiKey()).thenReturn("test-key");

        // Only source→source, no destination entries
        OpenRouteServiceClient.OrsMatrixResponse response =
                new OpenRouteServiceClient.OrsMatrixResponse(List.of(List.of(0.0)));
        RestClientMocks.stubPost(restClient, OpenRouteServiceClient.OrsMatrixResponse.class, response);

        List<Double> result = client.fetchDurations(54.77, -1.60,
                List.of(new double[]{54.78, -1.58}));

        assertThat(result).isEmpty();
    }
}
