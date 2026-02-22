package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.ForecastProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LocationService}.
 */
@ExtendWith(MockitoExtension.class)
class LocationServiceTest {

    @Mock
    private LocationRepository locationRepository;

    private ForecastProperties forecastProperties;
    private LocationService locationService;

    @BeforeEach
    void setUp() {
        forecastProperties = new ForecastProperties();
        locationService = new LocationService(locationRepository, forecastProperties);
    }

    // --- seedFromProperties ---

    @Test
    @DisplayName("seedFromProperties() inserts locations not already in the database")
    void seedFromProperties_newLocations_areInserted() {
        ForecastProperties.Location durham = new ForecastProperties.Location();
        durham.setName("Durham UK");
        durham.setLat(54.7753);
        durham.setLon(-1.5849);
        forecastProperties.setLocations(List.of(durham));

        when(locationRepository.existsByName("Durham UK")).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.seedFromProperties();

        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        LocationEntity saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("Durham UK");
        assertThat(saved.getLat()).isEqualTo(54.7753);
        assertThat(saved.getLon()).isEqualTo(-1.5849);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("seedFromProperties() skips locations already present in the database")
    void seedFromProperties_existingLocations_areSkipped() {
        ForecastProperties.Location durham = new ForecastProperties.Location();
        durham.setName("Durham UK");
        durham.setLat(54.7753);
        durham.setLon(-1.5849);
        forecastProperties.setLocations(List.of(durham));

        when(locationRepository.existsByName("Durham UK")).thenReturn(true);

        locationService.seedFromProperties();

        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("seedFromProperties() inserts only locations not yet in the database")
    void seedFromProperties_mixedLocations_insertsOnlyNew() {
        ForecastProperties.Location durham = new ForecastProperties.Location();
        durham.setName("Durham UK");
        durham.setLat(54.7753);
        durham.setLon(-1.5849);
        ForecastProperties.Location keswick = new ForecastProperties.Location();
        keswick.setName("Keswick");
        keswick.setLat(54.6);
        keswick.setLon(-3.13);
        forecastProperties.setLocations(List.of(durham, keswick));

        when(locationRepository.existsByName("Durham UK")).thenReturn(true);
        when(locationRepository.existsByName("Keswick")).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.seedFromProperties();

        verify(locationRepository, times(1)).save(any());
    }

    // --- findAll ---

    @Test
    @DisplayName("findAll() delegates to findAllByOrderByNameAsc")
    void findAll_returnsRepositoryResults() {
        List<LocationEntity> expected = List.of(
                buildEntity("Ambleside", 54.43, -2.96),
                buildEntity("Durham UK", 54.7753, -1.5849));
        when(locationRepository.findAllByOrderByNameAsc()).thenReturn(expected);

        List<LocationEntity> result = locationService.findAll();

        assertThat(result).isSameAs(expected);
    }

    // --- add ---

    @Test
    @DisplayName("add() saves and returns entity for valid input")
    void add_validInput_savesAndReturnsEntity() {
        when(locationRepository.existsByName("Bamburgh Castle")).thenReturn(false);
        LocationEntity saved = buildEntity("Bamburgh Castle", 55.6090, -1.7099);
        when(locationRepository.save(any())).thenReturn(saved);

        LocationEntity result = locationService.add("Bamburgh Castle", 55.6090, -1.7099);

        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Bamburgh Castle");
        assertThat(captor.getValue().getLat()).isEqualTo(55.6090);
        assertThat(captor.getValue().getLon()).isEqualTo(-1.7099);
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
        assertThat(result).isSameAs(saved);
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when name is blank")
    void add_blankName_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> locationService.add("  ", 54.7753, -1.5849))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when name is null")
    void add_nullName_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> locationService.add(null, 54.7753, -1.5849))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when latitude is below -90")
    void add_latBelowMin_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> locationService.add("Test", -91.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when latitude is above 90")
    void add_latAboveMax_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> locationService.add("Test", 91.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when longitude is below -180")
    void add_lonBelowMin_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> locationService.add("Test", 0.0, -181.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Longitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when longitude is above 180")
    void add_lonAboveMax_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> locationService.add("Test", 0.0, 181.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Longitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when a location with the same name already exists")
    void add_duplicateName_throwsIllegalArgumentException() {
        when(locationRepository.existsByName("Durham UK")).thenReturn(true);

        assertThatThrownBy(() -> locationService.add("Durham UK", 54.7753, -1.5849))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Durham UK");
    }

    @Test
    @DisplayName("add() accepts boundary latitude values -90 and 90")
    void add_boundaryLatitudes_succeed() {
        when(locationRepository.existsByName(any())).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.add("South Pole", -90.0, 0.0);
        locationService.add("North Pole", 90.0, 0.0);

        verify(locationRepository, times(2)).save(any());
    }

    private LocationEntity buildEntity(String name, double lat, double lon) {
        return LocationEntity.builder()
                .id(1L)
                .name(name)
                .lat(lat)
                .lon(lon)
                .build();
    }
}
