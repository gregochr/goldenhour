package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AddLocationRequest;
import com.gregochr.goldenhour.model.UpdateLocationRequest;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @Mock
    private TideService tideService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private LocationService locationService;

    @BeforeEach
    void setUp() {
        locationService = new LocationService(locationRepository, tideService, jdbcTemplate);
    }

    // --- findByName ---

    @Test
    @DisplayName("findByName() returns the location when found")
    void findByName_found_returnsLocation() {
        LocationEntity entity = buildEntity("Durham UK", 54.7753, -1.5849);
        when(locationRepository.findByName("Durham UK")).thenReturn(Optional.of(entity));

        LocationEntity result = locationService.findByName("Durham UK");

        assertThat(result).isSameAs(entity);
    }

    @Test
    @DisplayName("findByName() throws NoSuchElementException when not found")
    void findByName_notFound_throwsNoSuchElementException() {
        when(locationRepository.findByName("Unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> locationService.findByName("Unknown"))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("Unknown");
    }

    // --- findById ---

    @Test
    @DisplayName("findById() returns the location when found")
    void findById_found_returnsLocation() {
        LocationEntity entity = buildEntity("Durham UK", 54.7753, -1.5849);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(entity));

        LocationEntity result = locationService.findById(1L);

        assertThat(result).isSameAs(entity);
    }

    @Test
    @DisplayName("findById() throws NoSuchElementException when not found")
    void findById_notFound_throwsNoSuchElementException() {
        when(locationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> locationService.findById(99L))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("99");
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

    // --- findAllEnabled ---

    @Test
    @DisplayName("findAllEnabled() delegates to findAllByEnabledTrueOrderByNameAsc")
    void findAllEnabled_returnsEnabledLocations() {
        List<LocationEntity> expected = List.of(buildEntity("Durham UK", 54.7753, -1.5849));
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(expected);

        List<LocationEntity> result = locationService.findAllEnabled();

        assertThat(result).isSameAs(expected);
    }

    // --- add ---

    @Test
    @DisplayName("add() saves and returns entity with defaults for valid input")
    void add_validInput_savesAndReturnsEntity() {
        when(locationRepository.existsByName("Bamburgh Castle")).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AddLocationRequest request = new AddLocationRequest(
                "Bamburgh Castle", 55.6090, -1.7099, null, null, null);

        LocationEntity result = locationService.add(request);

        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Bamburgh Castle");
        assertThat(captor.getValue().getLat()).isEqualTo(55.6090);
        assertThat(captor.getValue().getLon()).isEqualTo(-1.7099);
        assertThat(captor.getValue().getGoldenHourType()).isEqualTo(GoldenHourType.BOTH_TIMES);
        assertThat(captor.getValue().getLocationType()).containsExactly(LocationType.LANDSCAPE);
        assertThat(captor.getValue().getTideType()).containsExactly(TideType.NOT_COASTAL);
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("add() saves SEASCAPE location with tide type and triggers tide fetch")
    void add_seascapeLocation_savesTideTypeAndFetchesTides() {
        when(locationRepository.existsByName("Bamburgh")).thenReturn(false);
        LocationEntity saved = LocationEntity.builder()
                .id(1L).name("Bamburgh").lat(55.6).lon(-1.7)
                .tideType(Set.of(TideType.ANY_TIDE))
                .locationType(Set.of(LocationType.SEASCAPE))
                .build();
        when(locationRepository.save(any())).thenReturn(saved);
        when(tideService.hasStoredExtremes(1L)).thenReturn(false);

        AddLocationRequest request = new AddLocationRequest(
                "Bamburgh", 55.6, -1.7, GoldenHourType.SUNSET,
                LocationType.SEASCAPE, TideType.ANY_TIDE);

        locationService.add(request);

        verify(tideService).fetchAndStoreTideExtremes(saved);
    }

    @Test
    @DisplayName("add() forces NOT_COASTAL when locationType is not SEASCAPE")
    void add_nonSeascape_forcesNotCoastal() {
        when(locationRepository.existsByName("Durham")).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AddLocationRequest request = new AddLocationRequest(
                "Durham", 54.7753, -1.5849, GoldenHourType.BOTH_TIMES,
                LocationType.LANDSCAPE, TideType.HIGH_TIDE);

        locationService.add(request);

        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        assertThat(captor.getValue().getTideType()).containsExactly(TideType.NOT_COASTAL);
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when name is blank")
    void add_blankName_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest("  ", 54.7753, -1.5849, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when name is null")
    void add_nullName_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest(null, 54.7753, -1.5849, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when latitude is below -90")
    void add_latBelowMin_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest("Test", -91.0, 0.0, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when latitude is above 90")
    void add_latAboveMax_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest("Test", 91.0, 0.0, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when longitude is below -180")
    void add_lonBelowMin_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest("Test", 0.0, -181.0, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Longitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when longitude is above 180")
    void add_lonAboveMax_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest("Test", 0.0, 181.0, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Longitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when a location with the same name already exists")
    void add_duplicateName_throwsIllegalArgumentException() {
        when(locationRepository.existsByName("Durham UK")).thenReturn(true);
        AddLocationRequest request = new AddLocationRequest("Durham UK", 54.7753, -1.5849, null, null, null);

        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Durham UK");
    }

    @Test
    @DisplayName("add() accepts boundary latitude values -90 and 90")
    void add_boundaryLatitudes_succeed() {
        when(locationRepository.existsByName(any())).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.add(new AddLocationRequest("South Pole", -90.0, 0.0, null, null, null));
        locationService.add(new AddLocationRequest("North Pole", 90.0, 0.0, null, null, null));

        verify(locationRepository, times(2)).save(any());
    }

    // --- update ---

    @Test
    @DisplayName("update() changes goldenHourType")
    void update_changesGoldenHourType() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        existing.setGoldenHourType(GoldenHourType.BOTH_TIMES);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateLocationRequest request = new UpdateLocationRequest(
                null, GoldenHourType.SUNSET, null, null);
        LocationEntity result = locationService.update(1L, request);

        assertThat(result.getGoldenHourType()).isEqualTo(GoldenHourType.SUNSET);
    }

    @Test
    @DisplayName("update() changes locationType to SEASCAPE and triggers tide fetch")
    void update_changesToSeascape_triggersTideFetch() {
        LocationEntity existing = buildEntity("Bamburgh", 55.6, -1.7);
        existing.setLocationType(Set.of(LocationType.LANDSCAPE));
        existing.setTideType(Set.of(TideType.NOT_COASTAL));
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tideService.hasStoredExtremes(1L)).thenReturn(false);

        UpdateLocationRequest request = new UpdateLocationRequest(
                null, null, LocationType.SEASCAPE, TideType.ANY_TIDE);
        locationService.update(1L, request);

        assertThat(existing.getLocationType()).containsExactly(LocationType.SEASCAPE);
        assertThat(existing.getTideType()).containsExactly(TideType.ANY_TIDE);
        verify(tideService).fetchAndStoreTideExtremes(any());
    }

    @Test
    @DisplayName("update() forces NOT_COASTAL when locationType is LANDSCAPE")
    void update_landscape_forcesNotCoastal() {
        LocationEntity existing = buildEntity("Durham", 54.7, -1.5);
        existing.setLocationType(Set.of(LocationType.LANDSCAPE));
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateLocationRequest request = new UpdateLocationRequest(
                null, null, LocationType.LANDSCAPE, TideType.HIGH_TIDE);
        locationService.update(1L, request);

        assertThat(existing.getTideType()).containsExactly(TideType.NOT_COASTAL);
    }

    @Test
    @DisplayName("update() throws NoSuchElementException when location not found")
    void update_notFound_throwsNoSuchElementException() {
        when(locationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> locationService.update(99L,
                new UpdateLocationRequest(null, null, null, null)))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    // --- setEnabled ---

    @Test
    @DisplayName("setEnabled(true) enables location and clears failure tracking")
    void setEnabled_enable_clearsFailureFields() {
        LocationEntity entity = buildEntity("Durham UK", 54.7753, -1.5849);
        entity.setEnabled(false);
        entity.setConsecutiveFailures(3);
        entity.setDisabledReason("Auto-disabled");
        entity.setLastFailureAt(java.time.LocalDateTime.now());
        when(locationRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LocationEntity result = locationService.setEnabled(1L, true);

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getConsecutiveFailures()).isZero();
        assertThat(result.getDisabledReason()).isNull();
        assertThat(result.getLastFailureAt()).isNull();
    }

    @Test
    @DisplayName("setEnabled(false) disables location without clearing failure fields")
    void setEnabled_disable_doesNotClearFailureFields() {
        LocationEntity entity = buildEntity("Durham UK", 54.7753, -1.5849);
        entity.setEnabled(true);
        entity.setConsecutiveFailures(2);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LocationEntity result = locationService.setEnabled(1L, false);

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getConsecutiveFailures()).isEqualTo(2);
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
    @DisplayName("isCoastal() returns false for empty tideType set")
    void isCoastal_emptySet_returnsFalse() {
        assertThat(locationService.isCoastal(entityWithTideTypes())).isFalse();
    }

    @Test
    @DisplayName("isCoastal() returns false when set contains only NOT_COASTAL")
    void isCoastal_onlyNotCoastal_returnsFalse() {
        assertThat(locationService.isCoastal(entityWithTideTypes(TideType.NOT_COASTAL))).isFalse();
    }

    @Test
    @DisplayName("isCoastal() returns true for HIGH_TIDE")
    void isCoastal_highTide_returnsTrue() {
        assertThat(locationService.isCoastal(entityWithTideTypes(TideType.HIGH_TIDE))).isTrue();
    }

    @Test
    @DisplayName("isCoastal() returns true for LOW_TIDE")
    void isCoastal_lowTide_returnsTrue() {
        assertThat(locationService.isCoastal(entityWithTideTypes(TideType.LOW_TIDE))).isTrue();
    }

    @Test
    @DisplayName("isCoastal() returns true for MID_TIDE")
    void isCoastal_midTide_returnsTrue() {
        assertThat(locationService.isCoastal(entityWithTideTypes(TideType.MID_TIDE))).isTrue();
    }

    @Test
    @DisplayName("isCoastal() returns true for ANY_TIDE")
    void isCoastal_anyTide_returnsTrue() {
        assertThat(locationService.isCoastal(entityWithTideTypes(TideType.ANY_TIDE))).isTrue();
    }

    @Test
    @DisplayName("isCoastal() returns true for multiple tide types")
    void isCoastal_multipleTideTypes_returnsTrue() {
        assertThat(locationService.isCoastal(entityWithTideTypes(TideType.LOW_TIDE, TideType.MID_TIDE))).isTrue();
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
        assertThat(entity.getTideType()).isEmpty();
        assertThat(entity.getLocationType()).isEmpty();
        assertThat(entity.isEnabled()).isTrue();
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

    private LocationEntity entityWithTideTypes(TideType... types) {
        return LocationEntity.builder().name("Test").lat(54.0).lon(-1.0)
                .tideType(new java.util.HashSet<>(java.util.Arrays.asList(types))).build();
    }
}
