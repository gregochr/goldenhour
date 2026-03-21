package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.client.LightPollutionClient;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BortleEnrichmentService}.
 */
@ExtendWith(MockitoExtension.class)
class BortleEnrichmentServiceTest {

    @Mock private LocationRepository locationRepository;
    @Mock private LightPollutionClient lightPollutionClient;

    private BortleEnrichmentService service;

    @BeforeEach
    void setUp() {
        service = new BortleEnrichmentService(locationRepository, lightPollutionClient);
    }

    @Test
    @DisplayName("enrichAll returns zero counts when no locations are pending")
    void enrichAll_noPendingLocations_returnsZeroCounts() {
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of());

        BortleEnrichmentService.EnrichmentResult result = service.enrichAll("test-key");

        assertThat(result.enriched()).isZero();
        assertThat(result.failed()).isEmpty();
        verify(lightPollutionClient, never()).queryBortleClass(anyDouble(), anyDouble(), anyString());
    }

    @Test
    @DisplayName("enrichAll saves Bortle class when API returns a value")
    void enrichAll_apiReturnsValue_savesAndCounts() {
        var loc = locationEntity("Bamburgh", 55.6, -1.7);
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of(loc));
        when(lightPollutionClient.queryBortleClass(55.6, -1.7, "key")).thenReturn(3);

        BortleEnrichmentService.EnrichmentResult result = service.enrichAll("key");

        assertThat(result.enriched()).isEqualTo(1);
        assertThat(result.failed()).isEmpty();
        assertThat(loc.getBortleClass()).isEqualTo(3);
        verify(locationRepository, times(1)).save(loc);
    }

    @Test
    @DisplayName("enrichAll records failure when API returns null")
    void enrichAll_apiReturnsNull_recordsFailure() {
        var loc = locationEntity("Urban Site", 53.5, -2.2);
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of(loc));
        when(lightPollutionClient.queryBortleClass(anyDouble(), anyDouble(), anyString()))
                .thenReturn(null);

        BortleEnrichmentService.EnrichmentResult result = service.enrichAll("key");

        assertThat(result.enriched()).isZero();
        assertThat(result.failed()).containsExactly("Urban Site");
        verify(locationRepository, never()).save(loc);
    }

    @Test
    @DisplayName("enrichAll handles mixed success and failure across multiple locations")
    void enrichAll_mixedResults_countsCorrectly() {
        var loc1 = locationEntity("Dark Peak", 53.4, -1.8);
        var loc2 = locationEntity("City Centre", 53.5, -2.2);
        var loc3 = locationEntity("Coast", 55.7, -1.5);
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of(loc1, loc2, loc3));
        when(lightPollutionClient.queryBortleClass(eq(53.4), eq(-1.8), anyString())).thenReturn(4);
        when(lightPollutionClient.queryBortleClass(eq(53.5), eq(-2.2), anyString())).thenReturn(null);
        when(lightPollutionClient.queryBortleClass(eq(55.7), eq(-1.5), anyString())).thenReturn(2);

        BortleEnrichmentService.EnrichmentResult result = service.enrichAll("key");

        assertThat(result.enriched()).isEqualTo(2);
        assertThat(result.failed()).containsExactly("City Centre");
    }

    @Test
    @DisplayName("enrichAll calls the API for each pending location")
    void enrichAll_callsApiForEachPendingLocation() {
        var loc1 = locationEntity("A", 54.0, -1.0);
        var loc2 = locationEntity("B", 55.0, -2.0);
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of(loc1, loc2));
        when(lightPollutionClient.queryBortleClass(anyDouble(), anyDouble(), anyString())).thenReturn(5);

        service.enrichAll("key");

        verify(lightPollutionClient, times(1)).queryBortleClass(54.0, -1.0, "key");
        verify(lightPollutionClient, times(1)).queryBortleClass(55.0, -2.0, "key");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private LocationEntity locationEntity(String name, double lat, double lon) {
        return LocationEntity.builder()
                .id(1L)
                .name(name)
                .lat(lat)
                .lon(lon)
                .enabled(true)
                .build();
    }
}
