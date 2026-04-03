package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AddLocationRequest;
import com.gregochr.goldenhour.model.UpdateLocationRequest;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private RegionRepository regionRepository;

    @Mock
    private TideService tideService;

    private LocationService locationService;

    @BeforeEach
    void setUp() {
        locationService = new LocationService(locationRepository, regionRepository,
                tideService);
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
                "Bamburgh Castle", 55.6090, -1.7099, null, null, null, null);

        LocationEntity result = locationService.add(request);

        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Bamburgh Castle");
        assertThat(captor.getValue().getLat()).isEqualTo(55.6090);
        assertThat(captor.getValue().getLon()).isEqualTo(-1.7099);
        assertThat(captor.getValue().getSolarEventType())
                .containsExactlyInAnyOrder(SolarEventType.SUNRISE, SolarEventType.SUNSET);
        assertThat(captor.getValue().getLocationType()).containsExactly(LocationType.LANDSCAPE);
        assertThat(captor.getValue().getTideType()).isEmpty();
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("add() saves SEASCAPE location with tide types and triggers tide fetch")
    void add_seascapeLocation_savesTideTypeAndFetchesTides() {
        when(locationRepository.existsByName("Bamburgh")).thenReturn(false);
        LocationEntity saved = LocationEntity.builder()
                .id(1L).name("Bamburgh").lat(55.6).lon(-1.7)
                .tideType(Set.of(TideType.HIGH, TideType.MID, TideType.LOW))
                .locationType(Set.of(LocationType.SEASCAPE))
                .build();
        when(locationRepository.save(any())).thenReturn(saved);
        when(tideService.hasStoredExtremes(1L)).thenReturn(false);

        AddLocationRequest request = new AddLocationRequest(
                "Bamburgh", 55.6, -1.7, Set.of(SolarEventType.SUNSET),
                LocationType.SEASCAPE, Set.of(TideType.HIGH, TideType.MID, TideType.LOW), null);

        locationService.add(request);

        verify(tideService).fetchAndStoreTideExtremes(saved);
    }

    @Test
    @DisplayName("add() forces empty tide types when locationType is not SEASCAPE")
    void add_nonSeascape_forcesEmptyTideTypes() {
        when(locationRepository.existsByName("Durham")).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AddLocationRequest request = new AddLocationRequest(
                "Durham", 54.7753, -1.5849, Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET),
                LocationType.LANDSCAPE, Set.of(TideType.HIGH), null);

        locationService.add(request);

        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        assertThat(captor.getValue().getTideType()).isEmpty();
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when name is blank")
    void add_blankName_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest("  ", 54.7753, -1.5849, null, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when name is null")
    void add_nullName_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest(null, 54.7753, -1.5849, null, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when latitude is below -90")
    void add_latBelowMin_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest("Test", -91.0, 0.0, null, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when latitude is above 90")
    void add_latAboveMax_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest("Test", 91.0, 0.0, null, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when longitude is below -180")
    void add_lonBelowMin_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest("Test", 0.0, -181.0, null, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Longitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when longitude is above 180")
    void add_lonAboveMax_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest("Test", 0.0, 181.0, null, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Longitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when a location with the same name already exists")
    void add_duplicateName_throwsIllegalArgumentException() {
        when(locationRepository.existsByName("Durham UK")).thenReturn(true);
        AddLocationRequest request = new AddLocationRequest("Durham UK", 54.7753, -1.5849, null, null, null, null);

        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Durham UK");
    }

    @Test
    @DisplayName("add() accepts boundary latitude values -90 and 90")
    void add_boundaryLatitudes_succeed() {
        when(locationRepository.existsByName(any())).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.add(new AddLocationRequest("South Pole", -90.0, 0.0, null, null, null, null));
        locationService.add(new AddLocationRequest("North Pole", 90.0, 0.0, null, null, null, null));

        verify(locationRepository, times(2)).save(any());
    }

    // --- update ---

    @Test
    @DisplayName("update() changes solarEventType")
    void update_changesSolarEventType() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        existing.setSolarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)));
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateLocationRequest request = new UpdateLocationRequest(
                null, null, null, Set.of(SolarEventType.SUNSET), null, null, null);
        LocationEntity result = locationService.update(1L, request);

        assertThat(result.getSolarEventType()).containsExactly(SolarEventType.SUNSET);
    }

    @Test
    @DisplayName("update() changes lat and lon")
    void update_changesLatLon() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateLocationRequest request = new UpdateLocationRequest(
                null, 55.0, -1.0, null, null, null, null);
        LocationEntity result = locationService.update(1L, request);

        assertThat(result.getLat()).isEqualTo(55.0);
        assertThat(result.getLon()).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("update() rejects invalid latitude")
    void update_invalidLat_throwsIllegalArgumentException() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> locationService.update(1L,
                new UpdateLocationRequest(null, 91.0, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude");
    }

    @Test
    @DisplayName("update() rejects invalid longitude")
    void update_invalidLon_throwsIllegalArgumentException() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> locationService.update(1L,
                new UpdateLocationRequest(null, null, 181.0, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Longitude");
    }

    @Test
    @DisplayName("update() changes locationType to SEASCAPE and triggers tide fetch")
    void update_changesToSeascape_triggersTideFetch() {
        LocationEntity existing = buildEntity("Bamburgh", 55.6, -1.7);
        existing.setLocationType(Set.of(LocationType.LANDSCAPE));
        existing.setTideType(new java.util.HashSet<>());
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tideService.hasStoredExtremes(1L)).thenReturn(false);

        UpdateLocationRequest request = new UpdateLocationRequest(
                null, null, null, null, LocationType.SEASCAPE,
                Set.of(TideType.HIGH, TideType.MID, TideType.LOW), null);
        locationService.update(1L, request);

        assertThat(existing.getLocationType()).containsExactly(LocationType.SEASCAPE);
        assertThat(existing.getTideType()).containsExactlyInAnyOrder(
                TideType.HIGH, TideType.MID, TideType.LOW);
        verify(tideService).fetchAndStoreTideExtremes(any());
    }

    @Test
    @DisplayName("update() forces empty tide types when locationType is LANDSCAPE")
    void update_landscape_forcesEmptyTideTypes() {
        LocationEntity existing = buildEntity("Durham", 54.7, -1.5);
        existing.setLocationType(Set.of(LocationType.LANDSCAPE));
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateLocationRequest request = new UpdateLocationRequest(
                null, null, null, null, LocationType.LANDSCAPE, Set.of(TideType.HIGH), null);
        locationService.update(1L, request);

        assertThat(existing.getTideType()).isEmpty();
    }

    @Test
    @DisplayName("update() throws NoSuchElementException when location not found")
    void update_notFound_throwsNoSuchElementException() {
        when(locationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> locationService.update(99L,
                new UpdateLocationRequest(null, null, null, null, null, null, null)))
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

    // --- shouldEvaluateSunrise / shouldEvaluateSunset ---
    // solarEventType is photographer preference metadata, not an evaluation filter.
    // Both methods always return true so every location gets sunrise AND sunset evaluations.

    @Test
    @DisplayName("shouldEvaluateSunrise() returns true for all solar event types")
    void shouldEvaluateSunrise_alwaysTrue() {
        for (SolarEventType type : SolarEventType.values()) {
            assertThat(locationService.shouldEvaluateSunrise(entityWithSolarType(type)))
                    .as("shouldEvaluateSunrise for %s", type)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("shouldEvaluateSunset() returns true for all solar event types")
    void shouldEvaluateSunset_alwaysTrue() {
        for (SolarEventType type : SolarEventType.values()) {
            assertThat(locationService.shouldEvaluateSunset(entityWithSolarType(type)))
                    .as("shouldEvaluateSunset for %s", type)
                    .isTrue();
        }
    }

    // --- isCoastal ---

    @Test
    @DisplayName("isCoastal() returns false for empty tideType set")
    void isCoastal_emptySet_returnsFalse() {
        assertThat(locationService.isCoastal(entityWithTideTypes())).isFalse();
    }

    @Test
    @DisplayName("isCoastal() returns true for HIGH")
    void isCoastal_high_returnsTrue() {
        assertThat(locationService.isCoastal(entityWithTideTypes(TideType.HIGH))).isTrue();
    }

    @Test
    @DisplayName("isCoastal() returns true for LOW")
    void isCoastal_low_returnsTrue() {
        assertThat(locationService.isCoastal(entityWithTideTypes(TideType.LOW))).isTrue();
    }

    @Test
    @DisplayName("isCoastal() returns true for MID")
    void isCoastal_mid_returnsTrue() {
        assertThat(locationService.isCoastal(entityWithTideTypes(TideType.MID))).isTrue();
    }

    @Test
    @DisplayName("isCoastal() returns true for all three tide types")
    void isCoastal_allThree_returnsTrue() {
        assertThat(locationService.isCoastal(entityWithTideTypes(
                TideType.HIGH, TideType.MID, TideType.LOW))).isTrue();
    }

    @Test
    @DisplayName("isCoastal() returns true for multiple tide types")
    void isCoastal_multipleTideTypes_returnsTrue() {
        assertThat(locationService.isCoastal(entityWithTideTypes(TideType.LOW, TideType.MID))).isTrue();
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

    // --- getGridCellSummary ---

    @Test
    @DisplayName("getGridCellSummary() returns correct counts and largest group")
    @SuppressWarnings("unchecked")
    void getGridCellSummary_returnsCorrectCounts() {
        LocationEntity loc1 = LocationEntity.builder()
                .id(1L).name("Bamburgh Castle").lat(55.6).lon(-1.7)
                .gridLat(55.6).gridLng(-1.7).enabled(true).build();
        LocationEntity loc2 = LocationEntity.builder()
                .id(2L).name("Bamburgh Dunes").lat(55.61).lon(-1.71)
                .gridLat(55.6).gridLng(-1.7).enabled(true).build();
        LocationEntity loc3 = LocationEntity.builder()
                .id(3L).name("Durham").lat(54.7).lon(-1.5)
                .gridLat(54.8).gridLng(-1.6).enabled(true).build();
        LocationEntity loc4 = LocationEntity.builder()
                .id(4L).name("NewLoc").lat(53.0).lon(-2.0)
                .enabled(true).build(); // no grid cell

        when(locationRepository.findAllByEnabledTrueOrderByNameAsc())
                .thenReturn(List.of(loc1, loc2, loc3, loc4));

        Map<String, Object> summary = locationService.getGridCellSummary();

        assertThat(summary.get("totalLocations")).isEqualTo(4);
        assertThat(summary.get("locationsWithGridCell")).isEqualTo(3L);
        assertThat(summary.get("locationsWithoutGridCell")).isEqualTo(1L);
        assertThat(summary.get("distinctGridCells")).isEqualTo(2);
        assertThat(summary.get("largestGroupSize")).isEqualTo(2);

        Map<String, Object> example = (Map<String, Object>) summary.get("largestGroupExample");
        assertThat(example).isNotNull();
        assertThat((List<String>) example.get("locations")).hasSize(2);
    }

    @Test
    @DisplayName("getGridCellSummary() returns all zeroes for empty enabled list")
    void getGridCellSummary_emptyList_returnsZeroes() {
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc())
                .thenReturn(List.of());

        Map<String, Object> summary = locationService.getGridCellSummary();

        assertThat(summary.get("totalLocations")).isEqualTo(0);
        assertThat(summary.get("locationsWithGridCell")).isEqualTo(0L);
        assertThat(summary.get("locationsWithoutGridCell")).isEqualTo(0L);
        assertThat(summary.get("distinctGridCells")).isEqualTo(0);
        assertThat(summary.get("largestGroupSize")).isEqualTo(0);
        assertThat(summary).doesNotContainKey("largestGroupExample");
    }

    @Test
    @DisplayName("getGridCellSummary() handles all locations in one grid cell")
    @SuppressWarnings("unchecked")
    void getGridCellSummary_allSameGridCell() {
        List<LocationEntity> locs = List.of(
                LocationEntity.builder().id(1L).name("A").lat(55.0).lon(-1.5)
                        .gridLat(55.0).gridLng(-1.5).enabled(true).build(),
                LocationEntity.builder().id(2L).name("B").lat(55.001).lon(-1.501)
                        .gridLat(55.0).gridLng(-1.5).enabled(true).build(),
                LocationEntity.builder().id(3L).name("C").lat(55.002).lon(-1.502)
                        .gridLat(55.0).gridLng(-1.5).enabled(true).build());

        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(locs);

        Map<String, Object> summary = locationService.getGridCellSummary();

        assertThat(summary.get("totalLocations")).isEqualTo(3);
        assertThat(summary.get("locationsWithGridCell")).isEqualTo(3L);
        assertThat(summary.get("locationsWithoutGridCell")).isEqualTo(0L);
        assertThat(summary.get("distinctGridCells")).isEqualTo(1);
        assertThat(summary.get("largestGroupSize")).isEqualTo(3);

        Map<String, Object> example = (Map<String, Object>) summary.get("largestGroupExample");
        assertThat((List<String>) example.get("locations")).hasSize(3);
    }

    @Test
    @DisplayName("getGridCellSummary() single location with grid cell has group size 1")
    void getGridCellSummary_singleLocationWithGrid() {
        LocationEntity loc = LocationEntity.builder()
                .id(1L).name("Durham").lat(54.7).lon(-1.5)
                .gridLat(54.8).gridLng(-1.6).enabled(true).build();

        when(locationRepository.findAllByEnabledTrueOrderByNameAsc())
                .thenReturn(List.of(loc));

        Map<String, Object> summary = locationService.getGridCellSummary();

        assertThat(summary.get("totalLocations")).isEqualTo(1);
        assertThat(summary.get("locationsWithGridCell")).isEqualTo(1L);
        assertThat(summary.get("distinctGridCells")).isEqualTo(1);
        assertThat(summary.get("largestGroupSize")).isEqualTo(1);
    }

    @Test
    @DisplayName("getGridCellSummary() returns zeroes when no locations have grid cells")
    void getGridCellSummary_noGridCells_returnsZeroes() {
        LocationEntity loc = LocationEntity.builder()
                .id(1L).name("NewLoc").lat(55.0).lon(-1.5)
                .enabled(true).build();

        when(locationRepository.findAllByEnabledTrueOrderByNameAsc())
                .thenReturn(List.of(loc));

        Map<String, Object> summary = locationService.getGridCellSummary();

        assertThat(summary.get("totalLocations")).isEqualTo(1);
        assertThat(summary.get("locationsWithGridCell")).isEqualTo(0L);
        assertThat(summary.get("distinctGridCells")).isEqualTo(0);
        assertThat(summary.get("largestGroupSize")).isEqualTo(0);
        assertThat(summary).doesNotContainKey("largestGroupExample");
    }

    // --- defaults ---

    @Test
    @DisplayName("new location entity defaults to empty solarEventType, empty tide types, and empty locationType")
    void locationEntity_defaults_areEmptySetAndNotCoastal() {
        LocationEntity entity = LocationEntity.builder()
                .name("Test")
                .lat(54.0)
                .lon(-1.0)
                .build();
        assertThat(entity.getSolarEventType()).isEmpty();
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

    private LocationEntity entityWithSolarType(SolarEventType type) {
        return LocationEntity.builder().name("Test").lat(54.0).lon(-1.0)
                .solarEventType(new HashSet<>(Set.of(type))).build();
    }

    private LocationEntity entityWithTideTypes(TideType... types) {
        return LocationEntity.builder().name("Test").lat(54.0).lon(-1.0)
                .tideType(new java.util.HashSet<>(java.util.Arrays.asList(types))).build();
    }
}
