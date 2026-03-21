package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.LocationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuroraTransectFetcher} cloud cover fetching and deduplication.
 */
@ExtendWith(MockitoExtension.class)
class AuroraTransectFetcherTest {

    @Mock
    private RestClient restClient;

    private AuroraTransectFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new AuroraTransectFetcher(restClient);
    }

    @Test
    @DisplayName("fetchTransectCloud returns map keyed by location name")
    void fetchTransectCloud_returnsMapByName() {
        setupGetChain(cloudResponse(30));

        var loc = location("Bamburgh", 55.6, -1.7);
        Map<String, Integer> result = fetcher.fetchTransectCloud(List.of(loc));

        assertThat(result).containsKey("Bamburgh");
    }

    @Test
    @DisplayName("fetchTransectCloud returns averaged cloud cover across three transect points")
    void fetchTransectCloud_averagesThreePoints() {
        // All three transect points resolve to the same mock value
        setupGetChain(cloudResponse(30));

        var loc = location("Dark Peak", 53.4, -1.8);
        Map<String, Integer> result = fetcher.fetchTransectCloud(List.of(loc));

        // Average of 30, 30, 30 = 30
        assertThat(result.get("Dark Peak")).isEqualTo(30);
    }

    @Test
    @DisplayName("fetchTransectCloud falls back to 50 on RestClientException")
    void fetchTransectCloud_networkError_fallsBackTo50() {
        @SuppressWarnings("unchecked")
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(any(Function.class))).thenThrow(new RestClientException("timeout"));

        var loc = location("Test", 54.0, -1.0);
        Map<String, Integer> result = fetcher.fetchTransectCloud(List.of(loc));

        assertThat(result.get("Test")).isEqualTo(50);
    }

    @Test
    @DisplayName("fetchTransectCloud falls back to 50 when API returns null response")
    void fetchTransectCloud_nullResponse_fallsBackTo50() {
        setupGetChain(null);

        var loc = location("Test", 54.0, -1.0);
        Map<String, Integer> result = fetcher.fetchTransectCloud(List.of(loc));

        assertThat(result.get("Test")).isEqualTo(50);
    }

    @Test
    @DisplayName("fetchTransectCloud returns empty map for empty location list")
    void fetchTransectCloud_emptyList_returnsEmptyMap() {
        Map<String, Integer> result = fetcher.fetchTransectCloud(List.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("fetchTransectCloud processes multiple locations independently")
    void fetchTransectCloud_multipleLocations_allProcessed() {
        setupGetChain(cloudResponse(40));

        var loc1 = location("A", 54.0, -1.0);
        var loc2 = location("B", 55.0, -2.0);
        Map<String, Integer> result = fetcher.fetchTransectCloud(List.of(loc1, loc2));

        assertThat(result).containsKeys("A", "B");
    }

    @Test
    @DisplayName("fetchTransectCloud returns cloud value in 0–100 range")
    void fetchTransectCloud_valueInRange() {
        setupGetChain(cloudResponse(85));

        var loc = location("Overcast", 54.0, -1.0);
        Map<String, Integer> result = fetcher.fetchTransectCloud(List.of(loc));

        assertThat(result.get("Overcast")).isBetween(0, 100);
    }

    @Test
    @DisplayName("fetchTransectCloud deduplicates nearby locations at same grid cell")
    void fetchTransectCloud_nearbyLocations_deduplicatesApiCalls() {
        // Two locations that are < 0.1° apart will share some grid cells
        // The RestClient mock just returns the same cloud value for any call
        setupGetChain(cloudResponse(50));

        // Same lat/lon → identical transect points → deduplicated
        var loc1 = location("A", 54.0001, -1.0001);
        var loc2 = location("B", 54.0001, -1.0001);
        Map<String, Integer> result = fetcher.fetchTransectCloud(List.of(loc1, loc2));

        // Both locations get a result
        assertThat(result).containsKeys("A", "B");
        // The RestClient was called only 3 times (3 unique transect points), not 6
        verify(restClient, times(3)).get();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private LocationEntity location(String name, double lat, double lon) {
        return LocationEntity.builder()
                .id(1L)
                .name(name)
                .lat(lat)
                .lon(lon)
                .enabled(true)
                .build();
    }

    /**
     * Builds a {@link AuroraTransectFetcher.CloudResponse} with the given cloud value at
     * the current UTC hour (the time slot the fetcher will look for).
     */
    private AuroraTransectFetcher.CloudResponse cloudResponse(int cloudPercent) {
        String currentHour = ZonedDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.HOURS)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));

        List<String> times = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        // Put the current hour at index 0
        times.add(currentHour);
        values.add(cloudPercent);

        return new AuroraTransectFetcher.CloudResponse(
                new AuroraTransectFetcher.HourlyCloudData(times, values));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setupGetChain(Object responseBody) {
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(any(Function.class))).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(Class.class))).thenReturn(responseBody);
    }
}
