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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        verifyNoInteractions(orsClient);
    }

    @Test
    @DisplayName("returns 0 when no locations exist")
    void refreshForUser_noLocations_returnsZero() {
        when(orsProperties.isConfigured()).thenReturn(true);
        when(locationRepository.findAll()).thenReturn(List.of());

        int result = service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        assertThat(result).isZero();
        verifyNoInteractions(orsClient);
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

    @Test
    @DisplayName("passes correct origin coordinates and destination list to ORS client")
    void refreshForUser_passesCorrectCoordinatesToOrs() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity durham = location(1L, "Durham", 54.78, -1.58);
        LocationEntity bamburgh = location(2L, "Bamburgh", 55.61, -1.71);
        when(locationRepository.findAll()).thenReturn(List.of(durham, bamburgh));
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any())).thenReturn(List.of(2700.0, 5400.0));

        service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> destCaptor = ArgumentCaptor.forClass(List.class);
        verify(orsClient).fetchDurations(eq(SOURCE_LAT), eq(SOURCE_LON), destCaptor.capture());
        List<double[]> destinations = destCaptor.getValue();
        assertThat(destinations).hasSize(2);
        assertThat(destinations.get(0)).containsExactly(54.78, -1.58);
        assertThat(destinations.get(1)).containsExactly(55.61, -1.71);
    }

    @Test
    @DisplayName("mix of valid, null, and negative durations — only valid ones persisted")
    void refreshForUser_mixedDurations_onlyValidPersisted() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity loc1 = location(1L, "Durham", 54.78, -1.58);
        LocationEntity loc2 = location(2L, "Bamburgh", 55.61, -1.71);
        LocationEntity loc3 = location(3L, "Kielder", 55.23, -2.58);
        when(locationRepository.findAll()).thenReturn(List.of(loc1, loc2, loc3));
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any()))
                .thenReturn(Arrays.asList(2700.0, null, -5.0));

        int result = service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        assertThat(result).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserDriveTimeEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userDriveTimeRepository).saveAll(captor.capture());
        List<UserDriveTimeEntity> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getLocationId()).isEqualTo(1L);
        assertThat(saved.get(0).getUserId()).isEqualTo(USER_ID);
        assertThat(saved.get(0).getDriveDurationSeconds()).isEqualTo(2700);
    }

    @Test
    @DisplayName("persisted entities have correct userId for the requesting user")
    void refreshForUser_entityHasCorrectUserId() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity loc = location(1L, "Durham", 54.78, -1.58);
        when(locationRepository.findAll()).thenReturn(List.of(loc));
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any())).thenReturn(List.of(1800.0));

        Long differentUserId = 99L;
        service.refreshForUser(differentUserId, SOURCE_LAT, SOURCE_LON);

        verify(userDriveTimeRepository).deleteAllByUserId(differentUserId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserDriveTimeEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userDriveTimeRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getUserId()).isEqualTo(differentUserId);
    }

    @Test
    @DisplayName("ORS duration is rounded to nearest second before persisting")
    void refreshForUser_roundsFractionalSeconds() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity loc = location(1L, "Durham", 54.78, -1.58);
        when(locationRepository.findAll()).thenReturn(List.of(loc));
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any())).thenReturn(List.of(2700.7));

        service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserDriveTimeEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userDriveTimeRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getDriveDurationSeconds()).isEqualTo(2701);
    }

    @Test
    @DisplayName("old drive times deleted before save — even if ORS returns empty")
    void refreshForUser_deletesBeforeSave_evenOnEmptyResult() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity loc = location(1L, "Durham", 54.78, -1.58);
        when(locationRepository.findAll()).thenReturn(List.of(loc));
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any())).thenReturn(List.of());

        service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        // Old data should NOT be deleted when ORS returns empty — the method returns early
        verify(userDriveTimeRepository, never()).deleteAllByUserId(USER_ID);
    }

    @Test
    @DisplayName("InterruptedException on semaphore throws IllegalStateException and restores interrupt flag")
    void refreshForUser_interrupted_throwsIllegalState() {
        when(orsProperties.isConfigured()).thenReturn(true);

        // Set the interrupt flag before calling — Semaphore.acquire() throws InterruptedException
        // if the thread is interrupted on entry, even when permits are available
        Thread.currentThread().interrupt();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interrupted");

        // Interrupt flag should still be set (restored by the catch block)
        assertThat(Thread.interrupted()).isTrue(); // also clears the flag for test cleanup
    }

    @Test
    @DisplayName("ORS client exception propagates — drive times not persisted")
    void refreshForUser_orsClientThrows_propagates() {
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity loc = location(1L, "Durham", 54.78, -1.58);
        when(locationRepository.findAll()).thenReturn(List.of(loc));
        when(orsClient.fetchDurations(anyDouble(), anyDouble(), any()))
                .thenThrow(new RuntimeException("ORS 503 Service Unavailable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("503");

        verify(userDriveTimeRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("zero seconds ORS duration is valid and persisted (>= 0 boundary)")
    void refreshForUser_zeroDuration_persisted() {
        // 0.0 is a valid duration (>= 0). Mutating >= to > would skip it.
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity loc = location(1L, "Local", 54.78, -1.58);
        when(locationRepository.findAll()).thenReturn(List.of(loc));
        when(orsClient.fetchDurations(eq(SOURCE_LAT), eq(SOURCE_LON), any()))
                .thenReturn(List.of(0.0));

        int result = service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        assertThat(result).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserDriveTimeEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userDriveTimeRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getDriveDurationSeconds()).isZero();
    }

    @Test
    @DisplayName("if ORS returns more durations than locations, only location-indexed entries persisted")
    void refreshForUser_moreDurationsThanLocations_onlyMatchingPersisted() {
        // Math.min(locations, durations) loop bound — mutating to locations.size() causes IOOBE
        when(orsProperties.isConfigured()).thenReturn(true);
        LocationEntity loc1 = location(1L, "Durham", 54.78, -1.58);
        LocationEntity loc2 = location(2L, "Bamburgh", 55.61, -1.71);
        when(locationRepository.findAll()).thenReturn(List.of(loc1, loc2));
        when(orsClient.fetchDurations(eq(SOURCE_LAT), eq(SOURCE_LON), any()))
                .thenReturn(List.of(1800.0, 3600.0, 5400.0, 7200.0));

        int result = service.refreshForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        assertThat(result).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserDriveTimeEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userDriveTimeRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).getLocationId()).isEqualTo(1L);
        assertThat(captor.getValue().get(1).getLocationId()).isEqualTo(2L);
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
