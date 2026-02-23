package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.ForecastProperties;
import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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

        when(locationRepository.findByName("Durham UK")).thenReturn(Optional.empty());
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
    @DisplayName("seedFromProperties() does not save when existing location metadata is unchanged")
    void seedFromProperties_existingLocationUnchanged_doesNotSave() {
        ForecastProperties.Location durham = new ForecastProperties.Location();
        durham.setName("Durham UK");
        durham.setLat(54.7753);
        durham.setLon(-1.5849);
        // Defaults: BOTH_TIMES, NOT_COASTAL, empty locationType
        forecastProperties.setLocations(List.of(durham));

        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        when(locationRepository.findByName("Durham UK")).thenReturn(Optional.of(existing));

        locationService.seedFromProperties();

        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("seedFromProperties() saves when existing location metadata differs from config")
    void seedFromProperties_existingLocationMetadataChanged_savesUpdate() {
        ForecastProperties.Location durham = new ForecastProperties.Location();
        durham.setName("Durham UK");
        durham.setLat(54.7753);
        durham.setLon(-1.5849);
        durham.setLocationType(Set.of(LocationType.LANDSCAPE));
        forecastProperties.setLocations(List.of(durham));

        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        // existing has empty locationType — differs from config
        when(locationRepository.findByName("Durham UK")).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.seedFromProperties();

        verify(locationRepository, times(1)).save(any());
        assertThat(existing.getLocationType()).containsExactly(LocationType.LANDSCAPE);
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

        LocationEntity existingDurham = buildEntity("Durham UK", 54.7753, -1.5849);
        when(locationRepository.findByName("Durham UK")).thenReturn(Optional.of(existingDurham));
        when(locationRepository.findByName("Keswick")).thenReturn(Optional.empty());
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.seedFromProperties();

        // Only Keswick inserted (Durham unchanged → no save)
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

    // --- shouldEvaluateSunrise ---

    @Test
    @DisplayName("shouldEvaluateSunrise() returns true for SUNRISE")
    void shouldEvaluateSunrise_sunrise_returnsTrue() {
        assertThat(locationService.shouldEvaluateSunrise(entityWithType(GoldenHourType.SUNRISE))).isTrue();
    }

    @Test
    @DisplayName("shouldEvaluateSunrise() returns false for SUNSET")
    void shouldEvaluateSunrise_sunset_returnsFalse() {
        assertThat(locationService.shouldEvaluateSunrise(entityWithType(GoldenHourType.SUNSET))).isFalse();
    }

    @Test
    @DisplayName("shouldEvaluateSunrise() returns true for BOTH_TIMES")
    void shouldEvaluateSunrise_bothTimes_returnsTrue() {
        assertThat(locationService.shouldEvaluateSunrise(entityWithType(GoldenHourType.BOTH_TIMES))).isTrue();
    }

    @Test
    @DisplayName("shouldEvaluateSunrise() returns true for ANYTIME")
    void shouldEvaluateSunrise_anytime_returnsTrue() {
        assertThat(locationService.shouldEvaluateSunrise(entityWithType(GoldenHourType.ANYTIME))).isTrue();
    }

    // --- shouldEvaluateSunset ---

    @Test
    @DisplayName("shouldEvaluateSunset() returns false for SUNRISE")
    void shouldEvaluateSunset_sunrise_returnsFalse() {
        assertThat(locationService.shouldEvaluateSunset(entityWithType(GoldenHourType.SUNRISE))).isFalse();
    }

    @Test
    @DisplayName("shouldEvaluateSunset() returns true for SUNSET")
    void shouldEvaluateSunset_sunset_returnsTrue() {
        assertThat(locationService.shouldEvaluateSunset(entityWithType(GoldenHourType.SUNSET))).isTrue();
    }

    @Test
    @DisplayName("shouldEvaluateSunset() returns true for BOTH_TIMES")
    void shouldEvaluateSunset_bothTimes_returnsTrue() {
        assertThat(locationService.shouldEvaluateSunset(entityWithType(GoldenHourType.BOTH_TIMES))).isTrue();
    }

    @Test
    @DisplayName("shouldEvaluateSunset() returns true for ANYTIME")
    void shouldEvaluateSunset_anytime_returnsTrue() {
        assertThat(locationService.shouldEvaluateSunset(entityWithType(GoldenHourType.ANYTIME))).isTrue();
    }

    // --- isCoastal ---

    @Test
    @DisplayName("isCoastal() returns false for NOT_COASTAL")
    void isCoastal_notCoastal_returnsFalse() {
        assertThat(locationService.isCoastal(entityWithTideType(TideType.NOT_COASTAL))).isFalse();
    }

    @Test
    @DisplayName("isCoastal() returns true for HIGH_TIDE")
    void isCoastal_highTide_returnsTrue() {
        assertThat(locationService.isCoastal(entityWithTideType(TideType.HIGH_TIDE))).isTrue();
    }

    @Test
    @DisplayName("isCoastal() returns true for LOW_TIDE")
    void isCoastal_lowTide_returnsTrue() {
        assertThat(locationService.isCoastal(entityWithTideType(TideType.LOW_TIDE))).isTrue();
    }

    @Test
    @DisplayName("isCoastal() returns true for ANY_TIDE")
    void isCoastal_anyTide_returnsTrue() {
        assertThat(locationService.isCoastal(entityWithTideType(TideType.ANY_TIDE))).isTrue();
    }

    // --- isSeascape ---

    @Test
    @DisplayName("isSeascape() returns true when locationType contains SEASCAPE")
    void isSeascape_withSeascape_returnsTrue() {
        LocationEntity entity = LocationEntity.builder()
                .name("Bamburgh").lat(55.6).lon(-1.7)
                .locationType(Set.of(LocationType.SEASCAPE))
                .build();
        assertThat(locationService.isSeascape(entity)).isTrue();
    }

    @Test
    @DisplayName("isSeascape() returns false when locationType contains only other types")
    void isSeascape_withoutSeascape_returnsFalse() {
        LocationEntity entity = LocationEntity.builder()
                .name("Durham").lat(54.7).lon(-1.5)
                .locationType(Set.of(LocationType.LANDSCAPE))
                .build();
        assertThat(locationService.isSeascape(entity)).isFalse();
    }

    @Test
    @DisplayName("isSeascape() returns false when locationType is empty")
    void isSeascape_emptyTypes_returnsFalse() {
        LocationEntity entity = LocationEntity.builder()
                .name("Durham").lat(54.7).lon(-1.5)
                .build();
        assertThat(locationService.isSeascape(entity)).isFalse();
    }

    @Test
    @DisplayName("isSeascape() returns true when locationType contains SEASCAPE alongside other types")
    void isSeascape_withSeascapeAndOtherTypes_returnsTrue() {
        LocationEntity entity = LocationEntity.builder()
                .name("Holy Island").lat(55.67).lon(-1.8)
                .locationType(Set.of(LocationType.SEASCAPE, LocationType.LANDSCAPE))
                .build();
        assertThat(locationService.isSeascape(entity)).isTrue();
    }

    // --- defaults ---

    @Test
    @DisplayName("new location entity defaults to BOTH_TIMES, NOT_COASTAL, and empty locationType")
    void locationEntity_defaults_areBothTimesAndNotCoastal() {
        LocationEntity entity = LocationEntity.builder()
                .name("Test")
                .lat(54.0)
                .lon(-1.0)
                .build();
        assertThat(entity.getGoldenHourType()).isEqualTo(GoldenHourType.BOTH_TIMES);
        assertThat(entity.getTideType()).isEqualTo(TideType.NOT_COASTAL);
        assertThat(entity.getLocationType()).isEmpty();
    }

    private LocationEntity buildEntity(String name, double lat, double lon) {
        return LocationEntity.builder()
                .id(1L)
                .name(name)
                .lat(lat)
                .lon(lon)
                .build();
    }

    private LocationEntity entityWithType(GoldenHourType type) {
        return LocationEntity.builder().name("Test").lat(54.0).lon(-1.0).goldenHourType(type).build();
    }

    private LocationEntity entityWithTideType(TideType type) {
        return LocationEntity.builder().name("Test").lat(54.0).lon(-1.0).tideType(type).build();
    }
}
