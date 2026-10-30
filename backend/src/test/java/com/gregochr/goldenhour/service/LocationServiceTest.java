package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AddLocationRequest;
import com.gregochr.goldenhour.model.UpdateLocationRequest;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.util.GeoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Mock
    private LocationEnrichmentService locationEnrichmentService;

    @Mock
    private NameNamespaceGuard nameGuard;

    @Mock
    private SubRegionMembershipService subRegionMembership;

    private LocationService locationService;
    private final ServedBriefingEpoch epoch = new ServedBriefingEpoch();

    @BeforeEach
    void setUp() {
        locationService = new LocationService(locationRepository, regionRepository,
                tideService, locationEnrichmentService, nameGuard, subRegionMembership, epoch);
    }

    @Test
    @DisplayName("add() refuses a name that clashes with a named area, as a 400, and saves nothing")
    void add_nameClashesWithNamespace_throwsIllegalArgumentException() {
        when(locationRepository.existsByName("Hadrian's Wall")).thenReturn(false);
        when(nameGuard.locationNameClash("Hadrian's Wall", null))
                .thenReturn(Optional.of("'Hadrian's Wall' contains the named area 'Hadrian's Wall'"));
        AddLocationRequest request = new AddLocationRequest(
                "Hadrian's Wall", 55.0, -2.0,
                null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("named area");

        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("update() refuses a rename that clashes with a named area, as a 400, and saves nothing")
    void update_renameClashesWithNamespace_throwsIllegalArgumentException() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.existsByName("Roman Wall Viewpoint")).thenReturn(false);
        when(nameGuard.locationNameClash("Roman Wall Viewpoint", 1L))
                .thenReturn(Optional.of("'Roman Wall Viewpoint' contains 'Roman Wall'"));

        assertThatThrownBy(() -> locationService.update(1L, new UpdateLocationRequest(
                "Roman Wall Viewpoint", null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Roman Wall");

        assertThat(existing.getName()).isEqualTo("Durham UK");
        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("update() renames to a name with no clash, and asks the guard about the trimmed name")
    void update_renameWithNoClash_renames() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.existsByName("Durham Cathedral")).thenReturn(false);
        when(locationRepository.save(existing)).thenAnswer(inv -> inv.getArgument(0));

        locationService.update(1L, new UpdateLocationRequest(
                " Durham Cathedral ", null, null, null, null, null, null));

        verify(nameGuard).locationNameClash("Durham Cathedral", 1L);
        assertThat(existing.getName()).isEqualTo("Durham Cathedral");
    }

    @Test
    @DisplayName("update() does not consult the guard when the name is unchanged")
    void update_nameUnchanged_doesNotConsultGuard() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(existing)).thenAnswer(inv -> inv.getArgument(0));

        locationService.update(1L, new UpdateLocationRequest(
                "Durham UK", null, null, null, null, null, null));

        verifyNoInteractions(nameGuard);
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

    // --- serve epoch (P1b): every roster write invalidates the memoised served briefing ---

    @Test
    @DisplayName("add(), update(), setEnabled() and resetFailures() each bump the serve epoch")
    void everyRosterWrite_bumpsServeEpoch() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        existing.setSolarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)));
        existing.setDisabledReason("auto");
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.findByName("Durham UK")).thenReturn(Optional.of(existing));
        when(locationRepository.existsByName("Bamburgh Castle")).thenReturn(false);
        when(locationRepository.save(org.mockito.ArgumentMatchers.argThat((LocationEntity l) -> l != null
                && ("Bamburgh Castle".equals(l.getName()) || "Durham UK".equals(l.getName())))))
                .thenAnswer(inv -> inv.getArgument(0));

        long e0 = epoch.current();
        locationService.add(new AddLocationRequest(
                "Bamburgh Castle", 55.6090, -1.7099, null, null, null, null, null, null, null, null, null,
                null, null));
        long e1 = epoch.current();
        locationService.update(1L, new UpdateLocationRequest(
                null, null, null, Set.of(SolarEventType.SUNSET), null, null, null));
        long e2 = epoch.current();
        locationService.setEnabled(1L, false);
        long e3 = epoch.current();
        locationService.resetFailures("Durham UK");
        long e4 = epoch.current();

        assertThat(e1).isGreaterThan(e0);
        assertThat(e2).isGreaterThan(e1);
        assertThat(e3).isGreaterThan(e2);
        assertThat(e4).isGreaterThan(e3);
    }

    // --- add ---

    @Test
    @DisplayName("add() saves and returns entity with defaults for valid input")
    void add_validInput_savesAndReturnsEntity() {
        when(locationRepository.existsByName("Bamburgh Castle")).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AddLocationRequest request = new AddLocationRequest(
                "Bamburgh Castle", 55.6090, -1.7099, null, null, null, null, null, null, null, null, null, null, null);

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
                Set.of(LocationType.SEASCAPE), Set.of(TideType.HIGH, TideType.MID, TideType.LOW), null,
                null, null, null, null, null, null, null);

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
                Set.of(LocationType.LANDSCAPE), Set.of(TideType.HIGH), null,
                null, null, null, null, null, null, null);

        locationService.add(request);

        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        assertThat(captor.getValue().getTideType()).isEmpty();
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when name is blank")
    void add_blankName_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest(
                "  ", 54.7753, -1.5849,
                null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when name is null")
    void add_nullName_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest(
                null, 54.7753, -1.5849,
                null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when latitude is below -90")
    void add_latBelowMin_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest(
                "Test", -91.0, 0.0,
                null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when latitude is above 90")
    void add_latAboveMax_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest(
                "Test", 91.0, 0.0,
                null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when longitude is below -180")
    void add_lonBelowMin_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest(
                "Test", 0.0, -181.0,
                null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Longitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when longitude is above 180")
    void add_lonAboveMax_throwsIllegalArgumentException() {
        AddLocationRequest request = new AddLocationRequest(
                "Test", 0.0, 181.0,
                null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Longitude");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when a location with the same name already exists")
    void add_duplicateName_throwsIllegalArgumentException() {
        when(locationRepository.existsByName("Durham UK")).thenReturn(true);
        AddLocationRequest request = new AddLocationRequest(
                "Durham UK", 54.7753, -1.5849,
                null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> locationService.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Durham UK");
    }

    @Test
    @DisplayName("add() accepts boundary latitude values -90 and 90")
    void add_boundaryLatitudes_succeed() {
        when(locationRepository.existsByName(any())).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.add(new AddLocationRequest("South Pole", -90.0, 0.0,
                null, null, null, null, null, null, null, null, null, null, null));
        locationService.add(new AddLocationRequest("North Pole", 90.0, 0.0,
                null, null, null, null, null, null, null, null, null, null, null));

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
                null, null, null, null, Set.of(LocationType.SEASCAPE),
                Set.of(TideType.HIGH, TideType.MID, TideType.LOW), null);
        locationService.update(1L, request);

        assertThat(existing.getLocationType()).containsExactly(LocationType.SEASCAPE);
        assertThat(existing.getTideType()).containsExactlyInAnyOrder(
                TideType.HIGH, TideType.MID, TideType.LOW);
        ArgumentCaptor<LocationEntity> tideCaptor =
                ArgumentCaptor.forClass(LocationEntity.class);
        verify(tideService).fetchAndStoreTideExtremes(tideCaptor.capture());
        assertThat(tideCaptor.getValue().getName()).isEqualTo("Bamburgh");
    }

    @Test
    @DisplayName("update() forces empty tide types when locationType is LANDSCAPE")
    void update_landscape_forcesEmptyTideTypes() {
        LocationEntity existing = buildEntity("Durham", 54.7, -1.5);
        existing.setLocationType(Set.of(LocationType.LANDSCAPE));
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateLocationRequest request = new UpdateLocationRequest(
                null, null, null, null, Set.of(LocationType.LANDSCAPE), Set.of(TideType.HIGH), null);
        locationService.update(1L, request);

        assertThat(existing.getTideType()).isEmpty();
    }

    @Test
    @DisplayName("add() keeps every type, and keeps tides when a coastal site is also something else")
    void add_seascapeCombinedWithAnotherType_keepsTypesAndTides() {
        // add() is where the equality->contains() swap actually happened: update() already read
        // contains() before this change. Captures the built entity rather than asserting against a
        // save() stub, because a stub would pass under either behaviour.
        when(locationRepository.existsByName("Wooded Clifftop")).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.add(new AddLocationRequest(
                "Wooded Clifftop", 55.0, -1.4, Set.of(SolarEventType.SUNRISE),
                Set.of(LocationType.SEASCAPE, LocationType.WOODLAND),
                Set.of(TideType.HIGH), null, null, null, null, null, null, null, null));

        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        assertThat(captor.getValue().getLocationType())
                .as("both types must be persisted — the scalar contract kept only one")
                .containsExactlyInAnyOrder(LocationType.SEASCAPE, LocationType.WOODLAND);
        assertThat(captor.getValue().getTideType())
                .as("an equality test against Set.of(SEASCAPE) would strip these")
                .containsExactly(TideType.HIGH);
    }

    @Test
    @DisplayName("update() clears tide preferences when a type change alone drops SEASCAPE")
    void update_typeChangeAloneDropsSeascape_clearsTides() {
        // Reclassifying a coastal site inland without mentioning tides must not leave it coastal:
        // isCoastal() is a non-empty tide set, so stale tides mean billed WorldTides fetches and
        // unsatisfiable tide-alignment stand-downs for an inland location.
        LocationEntity existing = buildEntity("Was Coastal", 55.6, -1.7);
        existing.setLocationType(new java.util.HashSet<>(Set.of(LocationType.SEASCAPE)));
        existing.setTideType(new java.util.HashSet<>(Set.of(TideType.HIGH, TideType.LOW)));
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.update(1L, new UpdateLocationRequest(
                null, null, null, null, Set.of(LocationType.LANDSCAPE), null, null));

        assertThat(existing.getTideType()).isEmpty();
        verify(tideService, never()).fetchAndStoreTideExtremes(any());
    }

    @Test
    @DisplayName("update() preserves every type in the set, not just the first")
    void update_multipleTypes_keepsAllOfThem() {
        // The defect this pins: locationType was a scalar on the request, expanded with
        // Set.of(...) into a full replace. Editing a wood typed [LANDSCAPE, WOODLAND] — even to
        // change only its name — dropped WOODLAND and put it back on sky scoring. Which element
        // survived was arbitrary: the set arrives from a HashSet, whose order is not stable.
        LocationEntity existing = buildEntity("Bluebell Wood", 54.5, -2.0);
        existing.setLocationType(new java.util.HashSet<>(Set.of(
                LocationType.LANDSCAPE, LocationType.WOODLAND)));
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.update(1L, new UpdateLocationRequest(
                "Bluebell Wood Renamed", null, null, null,
                Set.of(LocationType.LANDSCAPE, LocationType.WOODLAND), null, null));

        assertThat(existing.getLocationType())
                .as("both types must survive an edit — under the scalar contract one was dropped")
                .containsExactlyInAnyOrder(LocationType.LANDSCAPE, LocationType.WOODLAND);
    }

    @Test
    @DisplayName("update() keeps tide preferences when a coastal site is also something else")
    void update_seascapeCombinedWithAnotherType_keepsTides() {
        // Equality against SEASCAPE would strip the tides off a wooded clifftop.
        LocationEntity existing = buildEntity("Wooded Clifftop", 55.0, -1.4);
        existing.setLocationType(new java.util.HashSet<>(Set.of(LocationType.SEASCAPE)));
        existing.setTideType(new java.util.HashSet<>(Set.of(TideType.HIGH)));
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.update(1L, new UpdateLocationRequest(
                null, null, null, null,
                Set.of(LocationType.SEASCAPE, LocationType.WOODLAND),
                Set.of(TideType.HIGH, TideType.LOW), null));

        assertThat(existing.getTideType()).containsExactlyInAnyOrder(TideType.HIGH, TideType.LOW);
    }

    @Test
    @DisplayName("update() rejects an empty type set rather than untyping the location")
    void update_emptyTypeSet_throwsIllegalArgumentException() {
        LocationEntity existing = buildEntity("Durham", 54.7, -1.5);
        existing.setLocationType(new java.util.HashSet<>(Set.of(LocationType.LANDSCAPE)));
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> locationService.update(1L, new UpdateLocationRequest(
                null, null, null, null, Set.of(), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one type");
        assertThat(existing.getLocationType()).containsExactly(LocationType.LANDSCAPE);
    }

    @Test
    @DisplayName("update() leaves types untouched when locationTypes is absent")
    void update_nullTypeSet_leavesTypesUnchanged() {
        LocationEntity existing = buildEntity("Durham", 54.7, -1.5);
        existing.setLocationType(new java.util.HashSet<>(Set.of(
                LocationType.LANDSCAPE, LocationType.WOODLAND)));
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.update(1L, new UpdateLocationRequest(
                "Renamed", null, null, null, null, null, null));

        assertThat(existing.getLocationType())
                .containsExactlyInAnyOrder(LocationType.LANDSCAPE, LocationType.WOODLAND);
    }

    @Test
    @DisplayName("update() leaves tide preferences untouched when tideTypes is absent")
    void update_nullTideTypes_leavesTidesUnchanged() {
        // The record used to coerce a null tideTypes to Set.of() in its canonical constructor,
        // which made the "!= null" guard in update() permanently true — so a caller that simply
        // omitted the field had a coastal location's tide preferences wiped instead of left alone.
        LocationEntity existing = buildEntity("Bamburgh", 55.6, -1.7);
        existing.setLocationType(new java.util.HashSet<>(Set.of(LocationType.SEASCAPE)));
        existing.setTideType(new java.util.HashSet<>(Set.of(TideType.HIGH, TideType.LOW)));
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.update(1L, new UpdateLocationRequest(
                "Bamburgh Beach", null, null, null, null, null, null));

        assertThat(existing.getTideType()).containsExactlyInAnyOrder(TideType.HIGH, TideType.LOW);
    }

    @Test
    @DisplayName("update() rejects rename to an existing name")
    void update_duplicateName_throwsIllegalArgumentException() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        when(locationRepository.findById(1L))
                .thenReturn(Optional.of(existing));
        when(locationRepository.existsByName("Bamburgh")).thenReturn(true);

        assertThatThrownBy(() -> locationService.update(1L,
                new UpdateLocationRequest(
                        "Bamburgh", null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bamburgh");
    }

    @Test
    @DisplayName("update() trims whitespace from new name")
    void update_nameWithWhitespace_trims() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        when(locationRepository.findById(1L))
                .thenReturn(Optional.of(existing));
        when(locationRepository.existsByName("Durham Cathedral"))
                .thenReturn(false);
        when(locationRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        locationService.update(1L, new UpdateLocationRequest(
                "  Durham Cathedral  ", null, null,
                null, null, null, null));

        ArgumentCaptor<LocationEntity> captor =
                ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        assertThat(captor.getValue().getName())
                .isEqualTo("Durham Cathedral");
    }

    @Test
    @DisplayName("update() does not rename when name is blank")
    void update_blankName_doesNotRename() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        when(locationRepository.findById(1L))
                .thenReturn(Optional.of(existing));
        when(locationRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        locationService.update(1L, new UpdateLocationRequest(
                "   ", null, null, null, null, null, null));

        ArgumentCaptor<LocationEntity> captor =
                ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Durham UK");
    }

    @Test
    @DisplayName("update() skips rename when name matches existing")
    void update_sameName_doesNotCallExistsByName() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        when(locationRepository.findById(1L))
                .thenReturn(Optional.of(existing));
        when(locationRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        locationService.update(1L, new UpdateLocationRequest(
                "Durham UK", null, null, null, null, null, null));

        verify(locationRepository, never()).existsByName("Durham UK");
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

        LocationEntity result = locationService.setEnabled(1L, true);

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getConsecutiveFailures()).isZero();
        assertThat(result.getDisabledReason()).isNull();
        assertThat(result.getLastFailureAt()).isNull();
        // enabled and the failure columns are updatable = false on the entity: the writes must be the
        // scoped updates, or they never reach the database, and nothing is saved whole.
        verify(locationRepository).updateEnabled(entity.getId(), true);
        verify(locationRepository).clearFailureState(entity.getId());
        verify(locationRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("setEnabled(false) disables location without clearing failure fields")
    void setEnabled_disable_doesNotClearFailureFields() {
        LocationEntity entity = buildEntity("Durham UK", 54.7753, -1.5849);
        entity.setEnabled(true);
        entity.setConsecutiveFailures(2);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(entity));

        LocationEntity result = locationService.setEnabled(1L, false);

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getConsecutiveFailures()).isEqualTo(2);
        verify(locationRepository).updateEnabled(entity.getId(), false);
        verify(locationRepository, org.mockito.Mockito.never()).clearFailureState(entity.getId());
        verify(locationRepository, org.mockito.Mockito.never()).save(any());
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

    // --- resetFailures ---

    @Test
    @DisplayName("resetFailures() clears failure tracking fields and saves")
    void resetFailures_resetsFieldsAndSaves() {
        LocationEntity entity = buildEntity("Durham UK", 54.7753, -1.5849);
        entity.setConsecutiveFailures(5);
        entity.setDisabledReason("API timeout");
        entity.setLastFailureAt(
                java.time.LocalDateTime.of(2026, 4, 1, 10, 0));
        when(locationRepository.findByName("Durham UK"))
                .thenReturn(Optional.of(entity));

        LocationEntity result =
                locationService.resetFailures("Durham UK");

        assertThat(result).isSameAs(entity);
        assertThat(result.getConsecutiveFailures()).isZero();
        assertThat(result.getDisabledReason()).isNull();
        assertThat(result.getLastFailureAt()).isNull();
        verify(locationRepository).clearFailureState(entity.getId());
        verify(locationRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("resetFailures() re-enables a place that was auto-disabled (it carries a "
            + "disabled reason) and clears its counters")
    void resetFailures_autoDisabledPlace_isReEnabled() {
        LocationEntity entity = buildEntity("Durham UK", 54.7753, -1.5849);
        entity.setEnabled(false);
        entity.setConsecutiveFailures(3);
        entity.setDisabledReason("Auto-disabled after 3 consecutive failed scheduled runs "
                + "(last 2026-10-02: weather data could not be fetched).");
        entity.setLastFailureAt(java.time.LocalDateTime.of(2026, 10, 2, 3, 0));
        when(locationRepository.findByName("Durham UK")).thenReturn(Optional.of(entity));

        LocationEntity result = locationService.resetFailures("Durham UK");

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getConsecutiveFailures()).isZero();
        assertThat(result.getDisabledReason()).isNull();
        assertThat(result.getLastFailureAt()).isNull();
        verify(locationRepository).updateEnabled(entity.getId(), true);
        verify(locationRepository).clearFailureState(entity.getId());
    }

    @Test
    @DisplayName("resetFailures() leaves a place an admin disabled on purpose (no disabled "
            + "reason) disabled, clearing only its counters")
    void resetFailures_manuallyDisabledPlace_staysDisabled() {
        LocationEntity entity = buildEntity("Durham UK", 54.7753, -1.5849);
        entity.setEnabled(false);
        entity.setConsecutiveFailures(1);
        entity.setLastFailureAt(java.time.LocalDateTime.of(2026, 10, 2, 3, 0));
        when(locationRepository.findByName("Durham UK")).thenReturn(Optional.of(entity));

        LocationEntity result = locationService.resetFailures("Durham UK");

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getConsecutiveFailures()).isZero();
        assertThat(result.getLastFailureAt()).isNull();
        verify(locationRepository).clearFailureState(entity.getId());
        verify(locationRepository, org.mockito.Mockito.never()).updateEnabled(entity.getId(), true);
    }

    @Test
    @DisplayName("resetFailures() throws NoSuchElementException for unknown name")
    void resetFailures_notFound_throwsNoSuchElementException() {
        when(locationRepository.findByName("Unknown"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> locationService.resetFailures("Unknown"))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("Unknown");
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

    // --- Enrichment field persistence tests ---

    @Test
    @DisplayName("add() persists enrichment fields from request")
    void add_withEnrichmentFields_persistsAll() {
        when(locationRepository.existsByName("Latrigg")).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AddLocationRequest request = new AddLocationRequest(
                "Latrigg", 54.6124, -3.1179,
                Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET),
                Set.of(LocationType.LANDSCAPE), Set.of(), null,
                3, 21.72, 368, 54.611, -3.121, true, false);

        locationService.add(request);

        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        LocationEntity saved = captor.getValue();

        assertThat(saved.getBortleClass()).isEqualTo(3);
        assertThat(saved.getSkyBrightnessSqm()).isEqualTo(21.72);
        assertThat(saved.getElevationMetres()).isEqualTo(368);
        assertThat(saved.getGridLat()).isEqualTo(54.611);
        assertThat(saved.getGridLng()).isEqualTo(-3.121);
        assertThat(saved.isOverlooksWater()).isTrue();
        assertThat(saved.isCoastalTidal()).isFalse();
    }

    @Test
    @DisplayName("add() with overlooksWater=true and coastalTidal=false — swap killer")
    void add_overlooksWaterTrue_coastalTidalFalse() {
        when(locationRepository.existsByName("Valley")).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AddLocationRequest request = new AddLocationRequest(
                "Valley", 54.0, -3.0,
                Set.of(SolarEventType.SUNRISE), Set.of(LocationType.LANDSCAPE),
                Set.of(), null, null, null, null, null, null, true, false);

        locationService.add(request);

        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        assertThat(captor.getValue().isOverlooksWater()).isTrue();
        assertThat(captor.getValue().isCoastalTidal()).isFalse();
    }

    @Test
    @DisplayName("add() with coastalTidal=true and overlooksWater=false — swap killer")
    void add_coastalTidalTrue_overlooksWaterFalse() {
        when(locationRepository.existsByName("Pier")).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AddLocationRequest request = new AddLocationRequest(
                "Pier", 54.0, -3.0,
                Set.of(SolarEventType.SUNSET), Set.of(LocationType.SEASCAPE),
                Set.of(TideType.HIGH), null, null, null, null, null, null, false, true);

        locationService.add(request);

        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        assertThat(captor.getValue().isOverlooksWater()).isFalse();
        assertThat(captor.getValue().isCoastalTidal()).isTrue();
    }

    @Test
    @DisplayName("add() with null booleans defaults to false, null enrichment fields pass through")
    void add_nullBooleans_defaultToFalse() {
        when(locationRepository.existsByName("Null Test")).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AddLocationRequest request = new AddLocationRequest(
                "Null Test", 54.0, -2.0,
                Set.of(SolarEventType.SUNRISE), Set.of(LocationType.LANDSCAPE),
                Set.of(), null, null, null, null, null, null, null, null);

        locationService.add(request);

        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        LocationEntity saved = captor.getValue();

        assertThat(saved.isOverlooksWater()).isFalse();
        assertThat(saved.isCoastalTidal()).isFalse();
        assertThat(saved.getBortleClass()).isNull();
        assertThat(saved.getSkyBrightnessSqm()).isNull();
        assertThat(saved.getElevationMetres()).isNull();
        assertThat(saved.getGridLat()).isNull();
        assertThat(saved.getGridLng()).isNull();
    }

    // --- update: Open-Meteo grid cell ---

    @Test
    @DisplayName("update() fills in a missing grid cell, which is how every raw-SQL location arrives")
    void update_missingGridCell_isPopulated() {
        LocationEntity existing = buildEntity("Penshaw Monument", 54.8830, -1.4809);
        assertThat(existing.hasGridCell()).isFalse();
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(locationEnrichmentService.fetchGridCell(54.8830, -1.4809))
                .thenReturn(new double[]{54.9, -1.5});

        // A rename — nothing to do with coordinates. The cell is filled because it was ABSENT.
        LocationEntity result = locationService.update(1L,
                new UpdateLocationRequest("Penshaw Hill", null, null, null, null, null, null));

        assertThat(result.getGridLat()).isEqualTo(54.9);
        assertThat(result.getGridLng()).isEqualTo(-1.5);
    }

    @Test
    @DisplayName("update() refetches the grid cell when the coordinates move")
    void update_coordinatesMoved_refetchesGridCell() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        existing.setGridLat(54.8);
        existing.setGridLng(-1.6);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(locationEnrichmentService.fetchGridCell(55.0, -1.0))
                .thenReturn(new double[]{55.1, -1.1});

        LocationEntity result = locationService.update(1L,
                new UpdateLocationRequest(null, 55.0, -1.0, null, null, null, null));

        // Fetched at the NEW position, not the old one — the stub is keyed on the new pair.
        assertThat(result.getGridLat()).isEqualTo(55.1);
        assertThat(result.getGridLng()).isEqualTo(-1.1);
    }

    @Test
    @DisplayName("update() leaves a good grid cell alone when the coordinates did not move")
    void update_unmovedWithGridCell_makesNoLookup() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        existing.setGridLat(54.8);
        existing.setGridLng(-1.6);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Same coordinates, restated. Without the equality check this would still count as a move.
        LocationEntity result = locationService.update(1L,
                new UpdateLocationRequest(null, 54.7753, -1.5849, null, null, null, null));

        assertThat(result.getGridLat()).isEqualTo(54.8);
        assertThat(result.getGridLng()).isEqualTo(-1.6);
        // Every unrelated edit must not become an Open-Meteo call.
        verifyNoInteractions(locationEnrichmentService);
    }

    @Test
    @DisplayName("update() clears a now-stale grid cell when the refetch fails after a move")
    void update_moveWithFailedLookup_clearsStaleCell() {
        LocationEntity existing = buildEntity("Durham UK", 54.7753, -1.5849);
        existing.setGridLat(54.8);
        existing.setGridLng(-1.6);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(locationEnrichmentService.fetchGridCell(55.0, -1.0)).thenReturn(null);

        LocationEntity result = locationService.update(1L,
                new UpdateLocationRequest(null, 55.0, -1.0, null, null, null, null));

        // Absent beats stale: no cell means "excluded from dedup and stability", where the old
        // pair would mean "confidently grouped with the cell around the PREVIOUS position".
        assertThat(result.getGridLat()).isNull();
        assertThat(result.getGridLng()).isNull();
    }

    @Test
    @DisplayName("update() still saves when the grid lookup fails and there was no cell to lose")
    void update_failedLookupWithoutPriorCell_savesAnyway() {
        LocationEntity existing = buildEntity("Penshaw Monument", 54.8830, -1.4809);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(locationEnrichmentService.fetchGridCell(54.8830, -1.4809)).thenReturn(null);

        LocationEntity result = locationService.update(1L,
                new UpdateLocationRequest("Penshaw Hill", null, null, null, null, null, null));

        // The grid cell is an optimisation; an unreachable Open-Meteo must not block the edit.
        assertThat(result.getName()).isEqualTo("Penshaw Hill");
        assertThat(result.hasGridCell()).isFalse();
        verify(locationRepository).save(existing);
    }

    // --- mist_prone (valley-mist-plan M1a) ---

    @Test
    @DisplayName("add() persists mistProne=true, and absent or null reads as false")
    void add_mistProne() {
        when(locationRepository.existsByName(any())).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.add(new AddLocationRequest("Brathay", 54.43, -3.03, null, null, null, null,
                null, null, null, null, null, null, null, true));
        locationService.add(new AddLocationRequest("Unflagged", 54.43, -3.03, null, null, null, null,
                null, null, null, null, null, null, null, null));
        locationService.add(new AddLocationRequest("Legacy", 54.43, -3.03, null, null, null, null,
                null, null, null, null, null, null, null));

        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(LocationEntity::isMistProne)
                .containsExactly(true, false, false);
    }

    @Test
    @DisplayName("add() with mistProne=true leaves overlooksWater false — the two flags are independent")
    void add_mistProne_doesNotImplyOverlooksWater() {
        when(locationRepository.existsByName(any())).thenReturn(false);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        locationService.add(new AddLocationRequest("Brathay", 54.43, -3.03, null, null, null, null,
                null, null, null, null, null, false, false, true));

        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository).save(captor.capture());
        assertThat(captor.getValue().isMistProne()).isTrue();
        assertThat(captor.getValue().isOverlooksWater()).isFalse();
    }

    @Test
    @DisplayName("update() sets mistProne on an existing location — the owner's tick path")
    void update_setsMistProne() {
        LocationEntity existing = buildEntity("Brathay", 54.43, -3.03);
        existing.setGridLat(54.43);
        existing.setGridLng(-3.03);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LocationEntity ticked = locationService.update(1L, new UpdateLocationRequest(
                null, null, null, null, null, null, null, true));
        assertThat(ticked.isMistProne()).isTrue();

        LocationEntity unticked = locationService.update(1L, new UpdateLocationRequest(
                null, null, null, null, null, null, null, false));
        assertThat(unticked.isMistProne()).isFalse();
    }

    @Test
    @DisplayName("update() with mistProne absent leaves the flag as it was")
    void update_nullMistProne_leavesFlagUnchanged() {
        LocationEntity existing = buildEntity("Brathay", 54.43, -3.03);
        existing.setGridLat(54.43);
        existing.setGridLng(-3.03);
        existing.setMistProne(true);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LocationEntity result = locationService.update(1L, new UpdateLocationRequest(
                "Brathay", null, null, null, null, null, null));

        assertThat(result.isMistProne()).isTrue();
    }

    // --- heights: elevation and valley floor (inversion v2 Phase 1-pre) ---

    private LocationEntity existingWithFloor() {
        LocationEntity existing = buildEntity("Latrigg", 54.6, -3.1);
        existing.setGridLat(54.6);
        existing.setGridLng(-3.1);
        existing.setElevationMetres(368);
        existing.setValleyFloorMetres(80);
        existing.setValleyFloorLat(54.58);
        existing.setValleyFloorLng(-3.12);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        return existing;
    }

    /** As {@link #existingWithFloor()}, for tests whose updates are accepted: saving that exact entity is stubbed. */
    private LocationEntity persistedWithFloor() {
        LocationEntity existing = existingWithFloor();
        when(locationRepository.save(existing)).thenReturn(existing);
        return existing;
    }

    private static UpdateLocationRequest heights(Double lat, Double lon, Integer elevation, Integer floor,
            Double floorLat, Double floorLng, Boolean clear) {
        return new UpdateLocationRequest(null, lat, lon, null, null, null, null, null, null,
                elevation, floor, floorLat, floorLng, clear);
    }

    @Test
    @DisplayName("update() sets an in-range elevation and leaves it alone when absent")
    void update_elevation_setAndUnchanged() {
        LocationEntity existing = persistedWithFloor();

        locationService.update(1L, heights(null, null, null, null, null, null, null));
        assertThat(existing.getElevationMetres()).isEqualTo(368);

        locationService.update(1L, heights(null, null, 412, null, null, null, null));
        assertThat(existing.getElevationMetres()).isEqualTo(412);
        locationService.update(1L, heights(null, null, -50, null, null, null, null));
        assertThat(existing.getElevationMetres()).isEqualTo(-50);
        locationService.update(1L, heights(null, null, 1500, null, null, null, null));
        assertThat(existing.getElevationMetres()).isEqualTo(1500);
        verify(locationRepository, atLeastOnce()).save(existing);
    }

    @Test
    @DisplayName("update() rejects an elevation outside -50..1500 as a 400 and saves nothing")
    void update_elevation_outOfRange() {
        existingWithFloor();

        assertThatThrownBy(() -> locationService.update(1L, heights(null, null, 1501, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> locationService.update(1L, heights(null, null, -51, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("update() sets all three floor fields together")
    void update_floor_setsAllThree() {
        LocationEntity existing = persistedWithFloor();

        locationService.update(1L, heights(null, null, null, 95, 54.59, -3.11, null));

        assertThat(existing.getValleyFloorMetres()).isEqualTo(95);
        assertThat(existing.getValleyFloorLat()).isEqualTo(54.59);
        assertThat(existing.getValleyFloorLng()).isEqualTo(-3.11);
        verify(locationRepository, atLeastOnce()).save(existing);
    }

    @Test
    @DisplayName("update() rejects one or two of the three floor fields as a 400")
    void update_floor_partialIsRejected() {
        existingWithFloor();

        assertThatThrownBy(() -> locationService.update(1L, heights(null, null, null, 95, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("together");
        assertThatThrownBy(() -> locationService.update(1L, heights(null, null, null, 95, 54.59, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> locationService.update(1L, heights(null, null, null, null, 54.59, -3.11, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("update() clearValleyFloor alone nulls all three; with any value it is a 400")
    void update_floor_clear() {
        LocationEntity existing = persistedWithFloor();

        assertThatThrownBy(() -> locationService.update(1L, heights(null, null, null, 95, null, null, true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(existing.getValleyFloorMetres()).isEqualTo(80);

        locationService.update(1L, heights(null, null, null, null, null, null, true));
        assertThat(existing.getValleyFloorMetres()).isNull();
        assertThat(existing.getValleyFloorLat()).isNull();
        assertThat(existing.getValleyFloorLng()).isNull();
        verify(locationRepository, atLeastOnce()).save(existing);
    }

    @Test
    @DisplayName("update() clearValleyFloor=false with no values changes nothing")
    void update_floor_clearFalse_noop() {
        LocationEntity existing = persistedWithFloor();

        locationService.update(1L, heights(null, null, null, null, null, null, false));

        assertThat(existing.getValleyFloorMetres()).isEqualTo(80);
        verify(locationRepository, atLeastOnce()).save(existing);
    }

    @Test
    @DisplayName("update() validates a new floor's height and coordinates")
    void update_floor_rangeChecks() {
        existingWithFloor();

        assertThatThrownBy(() -> locationService.update(1L, heights(null, null, null, 1501, 54.59, -3.11, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> locationService.update(1L, heights(null, null, null, -51, 54.59, -3.11, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> locationService.update(1L, heights(null, null, null, 90, 91.0, -3.11, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> locationService.update(1L, heights(null, null, null, 90, 54.59, 181.0, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("update() accepts a floor 6.8 km away and rejects one 10 km+ away")
    void update_floor_distanceLimit() {
        LocationEntity existing = persistedWithFloor();

        // About 6.8 km due south (Keswick-to-Blencathra scale): allowed.
        double[] near = GeoUtils.offsetPoint(54.6, -3.1, 180, 6800);
        locationService.update(1L, heights(null, null, null, 90, near[0], near[1], null));
        assertThat(existing.getValleyFloorMetres()).isEqualTo(90);

        double[] far = GeoUtils.offsetPoint(54.6, -3.1, 180, 10_500);
        assertThatThrownBy(() -> locationService.update(1L,
                heights(null, null, null, 70, far[0], far[1], null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("10 km");
        verify(locationRepository, atLeastOnce()).save(existing);
    }

    @Test
    @DisplayName("update() measures the floor distance from the coordinates this same request leaves")
    void update_floor_distanceUsesRequestsOwnCoordinates() {
        LocationEntity existing = persistedWithFloor();
        double[] floor = GeoUtils.offsetPoint(54.6, -3.1, 180, 4000);
        // Moving the location 20 km north strands a floor that was 4 km away: rejected.
        double[] moved = GeoUtils.offsetPoint(54.6, -3.1, 0, 20_000);
        assertThatThrownBy(() -> locationService.update(1L,
                heights(moved[0], moved[1], null, 60, floor[0], floor[1], null)))
                .isInstanceOf(IllegalArgumentException.class);

        // Moving the location and the floor together is fine.
        double[] movedFloor = GeoUtils.offsetPoint(moved[0], moved[1], 180, 4000);
        locationService.update(1L, heights(moved[0], moved[1], null, 60, movedFloor[0], movedFloor[1], null));
        assertThat(existing.getValleyFloorMetres()).isEqualTo(60);
        verify(locationRepository, atLeastOnce()).save(existing);
    }

    @Test
    @DisplayName("update() does not re-validate an unchanged floor, so a far stored floor survives a re-save")
    void update_floor_unchangedFloorNotRevalidated() {
        LocationEntity existing = persistedWithFloor();
        existing.setValleyFloorLat(55.5);
        existing.setValleyFloorLng(-3.1);

        locationService.update(1L, heights(null, null, null, 80, 55.5, -3.1, null));

        assertThat(existing.getValleyFloorLat()).isEqualTo(55.5);
        verify(locationRepository, atLeastOnce()).save(existing);
    }

    @Test
    @DisplayName("update() leaves both heights alone when the coordinates change")
    void update_coordinateChange_leavesHeightsAlone() {
        LocationEntity existing = persistedWithFloor();
        when(locationEnrichmentService.fetchGridCell(54.61, -3.11))
                .thenReturn(new double[]{54.7, -3.2});

        locationService.update(1L, heights(54.61, -3.11, null, null, null, null, null));

        assertThat(existing.getLat()).isEqualTo(54.61);
        assertThat(existing.getElevationMetres()).isEqualTo(368);
        assertThat(existing.getValleyFloorMetres()).isEqualTo(80);
        assertThat(existing.getValleyFloorLat()).isEqualTo(54.58);
        assertThat(existing.getValleyFloorLng()).isEqualTo(-3.12);
        verify(locationRepository, atLeastOnce()).save(existing);
    }

    // --- sub-regions ---

    private static RegionEntity region(long id, String name) {
        return RegionEntity.builder().id(id).name(name).build();
    }

    /** A save of a location called {@code name}: specific enough that a different location would not match. */
    private static LocationEntity savedAs(String name) {
        return argThat(l -> l != null && name.equals(l.getName()));
    }

    private LocationEntity existingInRegion(RegionEntity region) {
        LocationEntity existing = buildEntity("High Force", 54.65, -2.14);
        existing.setGridLat(54.65);
        existing.setGridLng(-2.14);
        existing.setRegion(region);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(existing));
        return existing;
    }

    @Test
    @DisplayName("update() with a subRegionId checks it against the location's region before saving, then "
            + "assigns the membership")
    void update_subRegionId_validatesThenAssigns() {
        RegionEntity dales = region(4L, "The Yorkshire Dales");
        LocationEntity existing = existingInRegion(dales);
        when(locationRepository.save(existing)).thenReturn(existing);

        LocationEntity saved = locationService.update(1L, new UpdateLocationRequest(
                null, null, null, null, null, null, null, null, 77L));

        InOrder order = inOrder(subRegionMembership, locationRepository);
        order.verify(subRegionMembership).requireAssignable(77L, dales);
        order.verify(locationRepository).save(saved);
        order.verify(subRegionMembership).assign(saved, 77L);
        verify(subRegionMembership, never()).reconcileRegion(any(LocationEntity.class));
    }

    @Test
    @DisplayName("update() refuses a sub-region of another region, and saves and assigns nothing")
    void update_subRegionOfAnotherRegion_isRefused() {
        RegionEntity dales = region(4L, "The Yorkshire Dales");
        existingInRegion(dales);
        when(subRegionMembership.requireAssignable(88L, dales))
                .thenThrow(new IllegalArgumentException("Sub-region 'Keswick & Borrowdale' is in the region"));

        assertThatThrownBy(() -> locationService.update(1L, new UpdateLocationRequest(
                null, null, null, null, null, null, null, null, 88L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Keswick & Borrowdale");

        verify(locationRepository, never()).save(any(LocationEntity.class));
        verify(subRegionMembership, never()).assign(any(LocationEntity.class), anyLong());
    }

    @Test
    @DisplayName("update() that changes the region reconciles the membership, and checks a new subRegionId "
            + "against the NEW region")
    void update_regionChange_reconcilesAndChecksTheNewRegion() {
        LocationEntity existing = existingInRegion(region(4L, "The Yorkshire Dales"));
        when(locationRepository.save(existing)).thenReturn(existing);
        RegionEntity lakes = region(3L, "The Lake District");
        when(regionRepository.findById(3L)).thenReturn(Optional.of(lakes));

        locationService.update(1L, new UpdateLocationRequest(
                null, null, null, null, null, null, 3L, null, 55L));

        assertThat(existing.getRegion()).isSameAs(lakes);
        verify(subRegionMembership).requireAssignable(55L, lakes);
        InOrder order = inOrder(subRegionMembership);
        order.verify(subRegionMembership).reconcileRegion(existing);
        order.verify(subRegionMembership).assign(existing, 55L);
    }

    @Test
    @DisplayName("update() that changes the region but names no sub-region leaves the location with none")
    void update_regionChangeAlone_reconcilesOnly() {
        LocationEntity existing = existingInRegion(region(4L, "The Yorkshire Dales"));
        when(locationRepository.save(existing)).thenReturn(existing);
        when(regionRepository.findById(3L)).thenReturn(Optional.of(region(3L, "The Lake District")));

        locationService.update(1L, new UpdateLocationRequest(
                null, null, null, null, null, null, 3L, null, null));

        verify(subRegionMembership).reconcileRegion(existing);
        verify(subRegionMembership, never()).assign(any(LocationEntity.class), anyLong());
    }

    @Test
    @DisplayName("update() that names the region the location already has does not reconcile")
    void update_sameRegion_doesNotReconcile() {
        RegionEntity dales = region(4L, "The Yorkshire Dales");
        LocationEntity existing = existingInRegion(dales);
        when(locationRepository.save(existing)).thenReturn(existing);
        when(regionRepository.findById(4L)).thenReturn(Optional.of(region(4L, "The Yorkshire Dales")));

        locationService.update(1L, new UpdateLocationRequest(
                null, null, null, null, null, null, 4L, null, null));

        verify(subRegionMembership, never()).reconcileRegion(any(LocationEntity.class));
    }

    @Test
    @DisplayName("update() that gives a location its first region reconciles (nothing to drop) without error")
    void update_firstRegion_reconciles() {
        LocationEntity existing = existingInRegion(null);
        when(locationRepository.save(existing)).thenReturn(existing);
        when(regionRepository.findById(4L)).thenReturn(Optional.of(region(4L, "The Yorkshire Dales")));

        locationService.update(1L, new UpdateLocationRequest(
                null, null, null, null, null, null, 4L, null, null));

        verify(subRegionMembership).reconcileRegion(existing);
    }

    @Test
    @DisplayName("update() without a region or subRegionId never touches membership")
    void update_noSubRegionFields_leavesMembershipAlone() {
        LocationEntity existing = existingInRegion(region(4L, "The Yorkshire Dales"));
        when(locationRepository.save(existing)).thenReturn(existing);

        locationService.update(1L, new UpdateLocationRequest(
                "High Force", null, null, null, null, null, null));

        verifyNoInteractions(subRegionMembership);
    }

    @Test
    @DisplayName("add() with a subRegionId checks it before the location is saved, then assigns it")
    void add_subRegionId_validatesBeforeSaveThenAssigns() {
        RegionEntity dales = region(4L, "The Yorkshire Dales");
        when(regionRepository.findById(4L)).thenReturn(Optional.of(dales));
        when(locationRepository.save(savedAs("High Force"))).thenAnswer(inv -> inv.getArgument(0));

        LocationEntity saved = locationService.add(new AddLocationRequest("High Force", 54.65, -2.14,
                null, null, null, 4L, null, null, null, null, null, null, null, null, 77L));

        InOrder order = inOrder(subRegionMembership, locationRepository);
        order.verify(subRegionMembership).requireAssignable(77L, dales);
        order.verify(locationRepository).save(savedAs("High Force"));
        order.verify(subRegionMembership).assign(saved, 77L);
    }

    @Test
    @DisplayName("add() refuses a mismatched sub-region and saves no location")
    void add_subRegionOfAnotherRegion_savesNothing() {
        RegionEntity dales = region(4L, "The Yorkshire Dales");
        when(regionRepository.findById(4L)).thenReturn(Optional.of(dales));
        when(subRegionMembership.requireAssignable(88L, dales))
                .thenThrow(new IllegalArgumentException("wrong region"));

        assertThatThrownBy(() -> locationService.add(new AddLocationRequest("High Force", 54.65, -2.14,
                null, null, null, 4L, null, null, null, null, null, null, null, null, 88L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("wrong region");

        verify(locationRepository, never()).save(any(LocationEntity.class));
    }

    @Test
    @DisplayName("add() without a subRegionId never touches membership")
    void add_noSubRegionId_leavesMembershipAlone() {
        when(locationRepository.save(savedAs("High Force"))).thenAnswer(inv -> inv.getArgument(0));

        locationService.add(new AddLocationRequest("High Force", 54.65, -2.14,
                null, null, null, null, null, null, null, null, null, null, null));

        verifyNoInteractions(subRegionMembership);
    }

    @Test
    @DisplayName("add() and update() are each one transaction, so a failed sub-region assignment leaves no "
            + "committed location")
    void addAndUpdate_areTransactional() throws NoSuchMethodException {
        assertThat(LocationService.class.getMethod("add", AddLocationRequest.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
        assertThat(LocationService.class.getMethod("update", Long.class, UpdateLocationRequest.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }

    private LocationEntity buildEntity(String name, double lat, double lon) {
        return LocationEntity.builder()
                .id(1L)
                .name(name)
                .lat(lat)
                .lon(lon)
                .build();
    }

    private LocationEntity entityWithTideTypes(TideType... types) {
        return LocationEntity.builder().name("Test").lat(54.0).lon(-1.0)
                .tideType(new java.util.HashSet<>(java.util.Arrays.asList(types))).build();
    }
}
