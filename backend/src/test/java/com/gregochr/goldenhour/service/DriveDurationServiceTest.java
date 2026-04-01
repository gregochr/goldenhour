package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenRouteServiceClient;
import com.gregochr.goldenhour.config.OrsProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.UserDriveTimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

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

    @Mock
    private UserDriveTimeRepository userDriveTimeRepository;

    private DriveDurationService service;

    private static final Long USER_ID = 42L;
    private static final double SOURCE_LAT = 54.778;
    private static final double SOURCE_LON = -1.600;

    @BeforeEach
    void setUp() {
        service = new DriveDurationService(orsClient, orsProperties,
                locationRepository, userDriveTimeRepository);
    }

    @Test
    @DisplayName("returns 0 when ORS is not configured")
    void refreshForUser_orsNotConfigured_returnsZero() {
        when(orsProperties.isConfigured()).thenReturn(false);

        int result = service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        assertThat(result).isZero();
        verify(orsClient, never()).fetchDurations(anyDouble(), anyDouble(), any());
    }

    @Test
    @DisplayName("returns 0 when no locations exist")
    void refreshForUser_noLocations_returnsZero() {
        when(orsProperties.isConfigured()).thenReturn(true);
        when(locationRepository.findAll()).thenReturn(List.of());

        int result = service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        assertThat(result).isZero();
        verify(orsClient, never()).fetchDurations(anyDouble(), anyDouble(), any());
    }

    @Test
    @DisplayName("converts ORS seconds to rounded seconds and persists UserDriveTimeEntity")
    void refreshForUser_convertsAndPersists() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity durham = location(1L, "Durham UK", 54.78, -1.58);
        when(locationRepository.findAll()).thenReturn(List.of(durham));
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any())).thenReturn(List.of(2700.0));

        int result = service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        assertThat(result).isEqualTo(1);
        verify(userDriveTimeRepository).deleteAllByUserId(USER_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserDriveTimeEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userDriveTimeRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getLocationId()).isEqualTo(1L);
        assertThat(captor.getValue().get(0).getDriveDurationSeconds()).isEqualTo(2700);
    }

    @Test
    @DisplayName("returns 0 when ORS returns empty durations")
    void refreshForUser_emptyDurations_returnsZero() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity durham = location(1L, "Durham UK", 54.78, -1.58);
        when(locationRepository.findAll()).thenReturn(List.of(durham));
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any())).thenReturn(List.of());

        int result = service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        assertThat(result).isZero();
    }

    @Test
    @DisplayName("null ORS duration is skipped (not persisted)")
    void refreshForUser_nullDuration_skipped() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity durham = location(1L, "Durham UK", 54.78, -1.58);
        when(locationRepository.findAll()).thenReturn(List.of(durham));
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any()))
                .thenReturn(Arrays.asList((Double) null));

        int result = service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        assertThat(result).isZero();
    }

    @Test
    @DisplayName("multiple locations are all persisted")
    void refreshForUser_multipleLocations_allPersisted() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity durham = location(1L, "Durham UK", 54.78, -1.58);
        LocationEntity whitley = location(2L, "Whitley Bay", 55.04, -1.44);
        when(locationRepository.findAll()).thenReturn(List.of(durham, whitley));
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any()))
                .thenReturn(List.of(2700.0, 3600.0));

        int result = service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        assertThat(result).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserDriveTimeEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userDriveTimeRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("deletes existing drive times before saving new ones")
    void refreshForUser_deletesExistingFirst() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity durham = location(1L, "Durham UK", 54.78, -1.58);
        when(locationRepository.findAll()).thenReturn(List.of(durham));
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any())).thenReturn(List.of(2700.0));

        service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        verify(userDriveTimeRepository).deleteAllByUserId(USER_ID);
    }

    @Test
    @DisplayName("negative duration is skipped (not persisted)")
    void refreshForUser_negativeDuration_skipped() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity durham = location(1L, "Durham UK", 54.78, -1.58);
        when(locationRepository.findAll()).thenReturn(List.of(durham));
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any())).thenReturn(List.of(-1.0));

        int result = service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        assertThat(result).isZero();
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
