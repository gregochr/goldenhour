package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenRouteServiceClient;
import com.gregochr.goldenhour.config.OrsProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionDriveTimeEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionDriveTimeRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RegionDriveDurationService}.
 *
 * <p><b>What breaks if these fail.</b> This matrix is what the Plan tab's drive figures, reach
 * gate and leave-by line all switch onto when the reader moves the origin off home. A wrong figure
 * here does not render as an error — it renders as a departure time, which is the one thing on the
 * screen a reader acts on without checking.
 */
@ExtendWith(MockitoExtension.class)
class RegionDriveDurationServiceTest {

    @Mock
    private OpenRouteServiceClient orsClient;

    @Mock
    private OrsProperties orsProperties;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private RegionDriveTimeRepository driveTimeRepository;

    @Mock
    private RegionDriveTimeWriter driveTimeWriter;

    private RegionDriveDurationService service;

    private static final Long REGION_ID = 7L;
    private static final double BASE_LAT = 54.601;
    private static final double BASE_LON = -3.135;

    @BeforeEach
    void setUp() {
        service = new RegionDriveDurationService(orsClient, orsProperties,
                locationRepository, driveTimeRepository, driveTimeWriter);
    }

    private static RegionEntity based() {
        RegionEntity region = new RegionEntity();
        region.setId(REGION_ID);
        region.setName("Lake District");
        region.setBaseName("Keswick");
        region.setBaseLat(BASE_LAT);
        region.setBaseLon(BASE_LON);
        return region;
    }

    private static LocationEntity location(Long id, double lat, double lon) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setLat(lat);
        loc.setLon(lon);
        return loc;
    }

    // --- hasBase: the gate that decides whether a region may be an origin at all ---

    @Test
    @DisplayName("a region with all three base fields can be an origin")
    void hasBase_complete_isTrue() {
        assertThat(RegionDriveDurationService.hasBase(based())).isTrue();
    }

    @Test
    @DisplayName("a null region cannot be an origin")
    void hasBase_null_isFalse() {
        assertThat(RegionDriveDurationService.hasBase(null)).isFalse();
    }

    @Test
    @DisplayName("a base name with no coordinates cannot be an origin — there is nothing to route from")
    void hasBase_nameOnly_isFalse() {
        RegionEntity region = based();
        region.setBaseLat(null);
        region.setBaseLon(null);
        assertThat(RegionDriveDurationService.hasBase(region)).isFalse();
    }

    @Test
    @DisplayName("coordinates with no name cannot be an origin — the chip would be unlabelled")
    void hasBase_coordinatesOnly_isFalse() {
        RegionEntity region = based();
        region.setBaseName(null);
        assertThat(RegionDriveDurationService.hasBase(region)).isFalse();
    }

    @Test
    @DisplayName("a blank base name is treated as absent, not as an empty label")
    void hasBase_blankName_isFalse() {
        RegionEntity region = based();
        region.setBaseName("   ");
        assertThat(RegionDriveDurationService.hasBase(region)).isFalse();
    }

    @Test
    @DisplayName("a missing longitude alone is enough to disqualify a base")
    void hasBase_missingLongitude_isFalse() {
        RegionEntity region = based();
        region.setBaseLon(null);
        assertThat(RegionDriveDurationService.hasBase(region)).isFalse();
    }

    // --- refreshForRegion ---

    @Test
    @DisplayName("returns 0 and calls nothing when ORS is not configured")
    void refreshForRegion_orsNotConfigured_returnsZero() {
        when(orsProperties.isConfigured()).thenReturn(false);

        assertThat(service.refreshForRegion(based())).isZero();

        verifyNoInteractions(orsClient, locationRepository, driveTimeWriter);
    }

    @Test
    @DisplayName("returns 0 for a region with no base — and does NOT clear its rows")
    void refreshForRegion_noBase_returnsZeroWithoutClearing() {
        when(orsProperties.isConfigured()).thenReturn(true);
        RegionEntity baseless = based();
        baseless.setBaseName(null);
        baseless.setBaseLat(null);
        baseless.setBaseLon(null);

        assertThat(service.refreshForRegion(baseless)).isZero();

        // Clearing is `setBase`'s job when a base actually moves. A sweep that skips a region must
        // leave whatever it has alone, or every nightly run would wipe the regions it cannot serve.
        verifyNoInteractions(orsClient, driveTimeWriter);
    }

    @Test
    @DisplayName("returns 0 when the roster is empty")
    void refreshForRegion_noLocations_returnsZero() {
        when(orsProperties.isConfigured()).thenReturn(true);
        when(locationRepository.findAll()).thenReturn(List.of());

        assertThat(service.refreshForRegion(based())).isZero();

        verifyNoInteractions(orsClient, driveTimeWriter);
    }

    @Test
    @DisplayName("routes from the BASE coordinates, in [lat, lon] order, to every location")
    void refreshForRegion_routesFromTheBase() {
        when(orsProperties.isConfigured()).thenReturn(true);
        when(locationRepository.findAll()).thenReturn(List.of(
                location(11L, 54.52, -3.30), location(12L, 54.43, -3.01)));
        when(orsClient.fetchDurations(eq(BASE_LAT), eq(BASE_LON), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(1500.0, 2400.0));

        assertThat(service.refreshForRegion(based())).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> destinations = ArgumentCaptor.forClass(List.class);
        verify(orsClient).fetchDurations(eq(BASE_LAT), eq(BASE_LON), destinations.capture());
        assertThat(destinations.getValue()).hasSize(2);
        assertThat(destinations.getValue().get(0)).containsExactly(54.52, -3.30);
        assertThat(destinations.getValue().get(1)).containsExactly(54.43, -3.01);
    }

    @Test
    @DisplayName("stores seconds verbatim and keys each row on its own location")
    void refreshForRegion_storesSecondsPerLocation() {
        when(orsProperties.isConfigured()).thenReturn(true);
        when(locationRepository.findAll()).thenReturn(List.of(
                location(11L, 54.52, -3.30), location(12L, 54.43, -3.01)));
        when(orsClient.fetchDurations(eq(BASE_LAT), eq(BASE_LON), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(1500.4, 2400.0));

        service.refreshForRegion(based());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegionDriveTimeEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(driveTimeWriter).replaceForRegion(eq(REGION_ID), rows.capture());
        assertThat(rows.getValue()).hasSize(2);
        assertThat(rows.getValue().get(0).getLocationId()).isEqualTo(11L);
        assertThat(rows.getValue().get(0).getDriveDurationSeconds()).isEqualTo(1500);
        assertThat(rows.getValue().get(1).getLocationId()).isEqualTo(12L);
        assertThat(rows.getValue().get(1).getDriveDurationSeconds()).isEqualTo(2400);
    }

    @Test
    @DisplayName("drops an unreachable location rather than storing it as a zero-minute drive")
    void refreshForRegion_nullDuration_isDropped() {
        when(orsProperties.isConfigured()).thenReturn(true);
        when(locationRepository.findAll()).thenReturn(List.of(
                location(11L, 54.52, -3.30), location(12L, 54.43, -3.01)));
        when(orsClient.fetchDurations(eq(BASE_LAT), eq(BASE_LON), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Arrays.asList(null, 2400.0));

        assertThat(service.refreshForRegion(based())).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegionDriveTimeEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(driveTimeWriter).replaceForRegion(eq(REGION_ID), rows.capture());
        assertThat(rows.getValue()).singleElement()
                .extracting(RegionDriveTimeEntity::getLocationId).isEqualTo(12L);
    }

    @Test
    @DisplayName("keeps a zero-second drive — a location inside the base town is not a missing row")
    void refreshForRegion_zeroDuration_isKept() {
        // The client side of this rule is pinned in `planOrigin.test.js`; without this the two ends
        // of one rule are asymmetrically protected, and `>= 0` mutating to `> 0` would drop the
        // base town's own location from every matrix with a green suite.
        when(orsProperties.isConfigured()).thenReturn(true);
        when(locationRepository.findAll()).thenReturn(List.of(location(11L, 54.60, -3.13)));
        when(orsClient.fetchDurations(eq(BASE_LAT), eq(BASE_LON), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(0.0));

        assertThat(service.refreshForRegion(based())).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegionDriveTimeEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(driveTimeWriter).replaceForRegion(eq(REGION_ID), rows.capture());
        assertThat(rows.getValue()).singleElement()
                .extracting(RegionDriveTimeEntity::getDriveDurationSeconds).isEqualTo(0);
    }

    @Test
    @DisplayName("drops a negative duration rather than storing a drive that runs backwards")
    void refreshForRegion_negativeDuration_isDropped() {
        when(orsProperties.isConfigured()).thenReturn(true);
        when(locationRepository.findAll()).thenReturn(List.of(
                location(11L, 54.52, -3.30), location(12L, 54.43, -3.01)));
        when(orsClient.fetchDurations(eq(BASE_LAT), eq(BASE_LON), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(-1.0, 2400.0));

        assertThat(service.refreshForRegion(based())).isEqualTo(1);
    }

    @Test
    @DisplayName("⚠️ an all-null durations row leaves the previous matrix in place, never wiping it")
    void refreshForRegion_allNullDurations_doesNotWipe() {
        // ORS answers an unroutable SOURCE — a base mistyped onto water, which is exactly what the
        // nullable base columns exist to guard against — with a non-empty row of nulls. That clears
        // the empty-response guard, and replacing with an empty list would DELETE a working matrix
        // and insert nothing while the job logged that it had been left alone.
        when(orsProperties.isConfigured()).thenReturn(true);
        when(locationRepository.findAll()).thenReturn(List.of(
                location(11L, 54.52, -3.30), location(12L, 54.43, -3.01)));
        when(orsClient.fetchDurations(eq(BASE_LAT), eq(BASE_LON), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Arrays.asList(null, null));

        assertThat(service.refreshForRegion(based())).isZero();

        verifyNoInteractions(driveTimeWriter);
    }

    @Test
    @DisplayName("a short ORS row writes only the locations it answered for")
    void refreshForRegion_shortRow_writesWhatItHas() {
        when(orsProperties.isConfigured()).thenReturn(true);
        when(locationRepository.findAll()).thenReturn(List.of(
                location(11L, 54.52, -3.30), location(12L, 54.43, -3.01)));
        when(orsClient.fetchDurations(eq(BASE_LAT), eq(BASE_LON), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(1500.0));

        assertThat(service.refreshForRegion(based())).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegionDriveTimeEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(driveTimeWriter).replaceForRegion(eq(REGION_ID), rows.capture());
        assertThat(rows.getValue()).singleElement()
                .extracting(RegionDriveTimeEntity::getLocationId).isEqualTo(11L);
    }

    @Test
    @DisplayName("an empty ORS response leaves the previous matrix in place")
    void refreshForRegion_emptyDurations_doesNotWrite() {
        when(orsProperties.isConfigured()).thenReturn(true);
        when(locationRepository.findAll()).thenReturn(List.of(location(11L, 54.52, -3.30)));
        when(orsClient.fetchDurations(eq(BASE_LAT), eq(BASE_LON), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());

        assertThat(service.refreshForRegion(based())).isZero();

        verifyNoInteractions(driveTimeWriter);
    }

    // --- getMatrix: the serve shape ---

    @Test
    @DisplayName("groups rows by region and serves whole minutes, not seconds")
    void getMatrix_groupsByRegionInMinutes() {
        when(driveTimeRepository.findAll()).thenReturn(List.of(
                new RegionDriveTimeEntity(7L, 11L, 1500),
                new RegionDriveTimeEntity(7L, 12L, 2400),
                new RegionDriveTimeEntity(9L, 11L, 600)));

        Map<Long, Map<Long, Integer>> matrix = service.getMatrix();

        assertThat(matrix).containsOnlyKeys(7L, 9L);
        assertThat(matrix.get(7L)).containsExactlyInAnyOrderEntriesOf(Map.of(11L, 25, 12L, 40));
        assertThat(matrix.get(9L)).containsExactlyInAnyOrderEntriesOf(Map.of(11L, 10));
    }

    @Test
    @DisplayName("rounds seconds to the nearest minute, at the half-minute boundary and either side")
    void getMatrix_roundsToNearestMinute() {
        when(driveTimeRepository.findAll()).thenReturn(List.of(
                new RegionDriveTimeEntity(7L, 1L, 1_770),   // 29.5 min — half rounds up
                new RegionDriveTimeEntity(7L, 2L, 1_769),   // 29.483 min
                new RegionDriveTimeEntity(7L, 3L, 1_771)));  // 29.517 min

        Map<Long, Integer> region = service.getMatrix().get(7L);

        assertThat(region.get(1L)).isEqualTo(30);
        assertThat(region.get(2L)).isEqualTo(29);
        assertThat(region.get(3L)).isEqualTo(30);
    }

    @Test
    @DisplayName("an empty table serves an empty map, never a map of empty regions")
    void getMatrix_noRows_isEmpty() {
        when(driveTimeRepository.findAll()).thenReturn(List.of());

        assertThat(service.getMatrix()).isEmpty();
    }
}
