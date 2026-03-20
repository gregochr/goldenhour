package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenRouteServiceClient;
import com.gregochr.goldenhour.config.OrsProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DriveDurationService}.
 */
@ExtendWith(MockitoExtension.class)
class DriveDurationServiceTest {

    @Mock
    private OpenRouteServiceClient orsClient;

    @Mock
    private OrsProperties orsProperties;

    @Mock
    private LocationRepository locationRepository;

    private DriveDurationService service;

    private static final double SOURCE_LAT = 54.778;
    private static final double SOURCE_LON = -1.600;

    @BeforeEach
    void setUp() {
        service = new DriveDurationService(orsClient, orsProperties, locationRepository);
    }

    @Test
    @DisplayName("returns empty map when ORS is not configured")
    void refreshDriveTimes_orsNotConfigured_returnsEmptyMap() {
        when(orsProperties.isConfigured()).thenReturn(false);

        Map<String, Integer> result = service.refreshDriveTimes(SOURCE_LAT, SOURCE_LON);

        assertThat(result).isEmpty();
        verify(orsClient, never()).fetchDurations(anyDouble(), anyDouble(), any());
    }

    @Test
    @DisplayName("returns empty map when no locations exist")
    void refreshDriveTimes_noLocations_returnsEmptyMap() {
        when(orsProperties.isConfigured()).thenReturn(true);
        when(locationRepository.findAll()).thenReturn(List.of());

        Map<String, Integer> result = service.refreshDriveTimes(SOURCE_LAT, SOURCE_LON);

        assertThat(result).isEmpty();
        verify(orsClient, never()).fetchDurations(anyDouble(), anyDouble(), any());
    }

    @Test
    @DisplayName("converts ORS seconds to minutes (rounded) and persists results")
    void refreshDriveTimes_convertsSecondsToMinutes() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity durham = location(1L, "Durham UK", 54.78, -1.58);
        when(locationRepository.findAll()).thenReturn(List.of(durham));
        // 2700 seconds = 45 minutes exactly
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any())).thenReturn(List.of(2700.0));

        Map<String, Integer> result = service.refreshDriveTimes(SOURCE_LAT, SOURCE_LON);

        assertThat(result).containsEntry("Durham UK", 45);
        assertThat(durham.getDriveDurationMinutes()).isEqualTo(45);
        verify(locationRepository).saveAll(List.of(durham));
    }

    @Test
    @DisplayName("rounds fractional seconds correctly (2729s → 45 min, not 46)")
    void refreshDriveTimes_roundsMinutes() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity durham = location(1L, "Durham UK", 54.78, -1.58);
        when(locationRepository.findAll()).thenReturn(List.of(durham));
        // 2729 seconds = 45.48 minutes → rounds to 45
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any())).thenReturn(List.of(2729.0));

        Map<String, Integer> result = service.refreshDriveTimes(SOURCE_LAT, SOURCE_LON);

        assertThat(result).containsEntry("Durham UK", 45);
    }

    @Test
    @DisplayName("Home location is excluded from ORS and set to 0 minutes")
    void refreshDriveTimes_homeLocation_setToZeroNotSentToOrs() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity durham = location(1L, "Durham UK", 54.78, -1.58);
        LocationEntity home = location(2L, "Home", 54.78, -1.60);
        when(locationRepository.findAll()).thenReturn(List.of(durham, home));
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any())).thenReturn(List.of(2700.0));

        Map<String, Integer> result = service.refreshDriveTimes(SOURCE_LAT, SOURCE_LON);

        assertThat(result).containsEntry("Home", 0);
        assertThat(result).containsEntry("Durham UK", 45);
        assertThat(home.getDriveDurationMinutes()).isEqualTo(0);

        // ORS should only receive Durham, not Home
        ArgumentCaptor<List<double[]>> coordCaptor = ArgumentCaptor.forClass(List.class);
        verify(orsClient).fetchDurations(anyDouble(), anyDouble(), coordCaptor.capture());
        assertThat(coordCaptor.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("Home location exclusion is case-insensitive (only Home present → no ORS call, empty result)")
    void refreshDriveTimes_homeLocationCaseInsensitive() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity home = location(1L, "HOME", 54.78, -1.60);
        when(locationRepository.findAll()).thenReturn(List.of(home));

        // Service returns early when only Home exists (no non-Home destinations)
        Map<String, Integer> result = service.refreshDriveTimes(SOURCE_LAT, SOURCE_LON);

        assertThat(result).isEmpty();
        verify(orsClient, never()).fetchDurations(anyDouble(), anyDouble(), any());
    }

    @Test
    @DisplayName("null ORS duration for a destination stores null on entity")
    void refreshDriveTimes_nullDuration_storesNull() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity durham = location(1L, "Durham UK", 54.78, -1.58);
        when(locationRepository.findAll()).thenReturn(List.of(durham));
        // Arrays.asList allows null elements (List.of does not)
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any()))
                .thenReturn(Arrays.asList((Double) null));

        Map<String, Integer> result = service.refreshDriveTimes(SOURCE_LAT, SOURCE_LON);

        assertThat(result).containsEntry("Durham UK", null);
        assertThat(durham.getDriveDurationMinutes()).isNull();
    }

    @Test
    @DisplayName("multiple locations are all persisted")
    void refreshDriveTimes_multipleLocations_allPersisted() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity durham = location(1L, "Durham UK", 54.78, -1.58);
        LocationEntity whitley = location(2L, "Whitley Bay", 55.04, -1.44);
        when(locationRepository.findAll()).thenReturn(List.of(durham, whitley));
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any()))
                .thenReturn(List.of(2700.0, 3600.0));

        Map<String, Integer> result = service.refreshDriveTimes(SOURCE_LAT, SOURCE_LON);

        assertThat(result).hasSize(2);
        assertThat(result).containsEntry("Durham UK", 45);
        assertThat(result).containsEntry("Whitley Bay", 60);
        verify(locationRepository).saveAll(List.of(durham, whitley));
    }

    private static LocationEntity location(Long id, String name, double lat, double lon) {
        return LocationEntity.builder()
                .id(id)
                .name(name)
                .lat(lat)
                .lon(lon)
                .build();
    }
}
