package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenRouteServiceClient;
import com.gregochr.goldenhour.client.OpenRouteServiceClient.OrsUnroutablePointException;
import com.gregochr.goldenhour.client.OpenRouteServiceClient.RouteProbe;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.exception.NoRoadRouteException;
import com.gregochr.goldenhour.model.AddLocationRequest;
import com.gregochr.goldenhour.model.UpdateLocationRequest;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveRoutabilityGuard.Routability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DriveRoutabilityGuard}: refuses only on a clear "no road route", on exactly the three
 * triggers, and lets every kind of "no answer" through.
 */
@ExtendWith(MockitoExtension.class)
class DriveRoutabilityGuardTest {

    private static final long KESWICK_REGION_ID = 9L;
    private static final long OTHER_REGION_ID = 4L;
    private static final long LOCATION_ID = 7L;

    /** Keswick: the Lake District region's base. */
    private static final double KESWICK_LAT = 54.6;
    private static final double KESWICK_LON = -3.13;

    /** Helvellyn's pin, and Glenridding, the village below it. */
    private static final double PIN_LAT = 54.5275;
    private static final double PIN_LON = -3.0162;
    private static final double DP_LAT = 54.5442;
    private static final double DP_LON = -2.9505;

    @Mock
    private OpenRouteServiceClient orsClient;

    @Mock
    private RegionRepository regionRepository;

    @Mock
    private LocationService locationService;

    private DriveRoutabilityGuard guard;

    @BeforeEach
    void setUp() {
        guard = new DriveRoutabilityGuard(orsClient, regionRepository, locationService);
    }

    private static RegionEntity region(long id, String name, Double baseLat, Double baseLon) {
        return RegionEntity.builder().id(id).name(name).enabled(true)
                .baseName(baseLat == null ? null : name + " base").baseLat(baseLat).baseLon(baseLon).build();
    }

    private void keswickRegion() {
        when(regionRepository.findById(KESWICK_REGION_ID))
                .thenReturn(Optional.of(region(KESWICK_REGION_ID, "Lake District", KESWICK_LAT, KESWICK_LON)));
    }

    /** Stubs the probe from Keswick to the control then a target at the given point. */
    private void probeReturns(double targetLat, double targetLon, Optional<RouteProbe> answer) {
        when(orsClient.probe(eq(KESWICK_LAT), eq(KESWICK_LON),
                argThat(dests -> isControlThen(dests, targetLat, targetLon)),
                eq(DriveRoutabilityGuard.PERMIT_TIMEOUT))).thenReturn(answer);
    }

    private static boolean isControlThen(List<double[]> dests, double lat, double lon) {
        return dests != null && dests.size() == 2
                && Arrays.equals(dests.get(0), DriveRoutabilityGuard.CONTROL_DESTINATION)
                && dests.get(1)[0] == lat && dests.get(1)[1] == lon;
    }

    private static Optional<RouteProbe> routed() {
        return Optional.of(new RouteProbe(List.of(5400.0, 2700.0), List.of(true, true)));
    }

    private static Optional<RouteProbe> targetUnroutable() {
        return Optional.of(new RouteProbe(Arrays.asList(5400.0, null), List.of(true, false)));
    }

    private static AddLocationRequest add(Long regionId, String dpName, Double dpLat, Double dpLon) {
        return new AddLocationRequest("Helvellyn", PIN_LAT, PIN_LON, null, null, null, regionId, null, null, null,
                null, null, null, null, null, null, dpName, dpLat, dpLon);
    }

    private static UpdateLocationRequest update(Double lat, Double lon, Long regionId, String dpName, Double dpLat,
            Double dpLon, Boolean clear) {
        return new UpdateLocationRequest(null, lat, lon, null, null, null, regionId, null, null, null, null, null,
                null, null, null, null, null, dpName, dpLat, dpLon, clear);
    }

    private LocationEntity stored(boolean withDrivePoint, Long regionId) {
        LocationEntity.LocationEntityBuilder builder = LocationEntity.builder()
                .id(LOCATION_ID).name("Helvellyn").lat(PIN_LAT).lon(PIN_LON);
        if (regionId != null) {
            builder.region(region(regionId, "Lake District", KESWICK_LAT, KESWICK_LON));
        }
        if (withDrivePoint) {
            builder.drivePointName("Glenridding").drivePointLat(DP_LAT).drivePointLon(DP_LON);
        }
        LocationEntity location = builder.build();
        when(locationService.findById(LOCATION_ID)).thenReturn(location);
        return location;
    }

    @Nested
    @DisplayName("the check itself")
    class Check {

        @Test
        @DisplayName("a routed control and target is ROUTABLE")
        void routed_isRoutable() {
            keswickRegion();
            probeReturns(PIN_LAT, PIN_LON, routed());

            assertThat(guard.check(PIN_LAT, PIN_LON, KESWICK_REGION_ID)).isEqualTo(Routability.ROUTABLE);
        }

        @Test
        @DisplayName("a null target duration, or a target ORS could not snap, is UNROUTABLE")
        void nullTargetOrUnsnapped_isUnroutable() {
            keswickRegion();
            probeReturns(PIN_LAT, PIN_LON, targetUnroutable());
            assertThat(guard.check(PIN_LAT, PIN_LON, KESWICK_REGION_ID)).isEqualTo(Routability.UNROUTABLE);

            probeReturns(PIN_LAT, PIN_LON,
                    Optional.of(new RouteProbe(Arrays.asList(5400.0, null), List.of(true, true))));
            assertThat(guard.check(PIN_LAT, PIN_LON, KESWICK_REGION_ID)).isEqualTo(Routability.UNROUTABLE);

            probeReturns(PIN_LAT, PIN_LON,
                    Optional.of(new RouteProbe(List.of(5400.0, 2700.0), List.of(true, false))));
            assertThat(guard.check(PIN_LAT, PIN_LON, KESWICK_REGION_ID)).isEqualTo(Routability.UNROUTABLE);
        }

        @Test
        @DisplayName("an all-null row (the source is bad), or an unsnapped control, is UNAVAILABLE not UNROUTABLE")
        void badSource_isUnavailable() {
            keswickRegion();
            probeReturns(PIN_LAT, PIN_LON,
                    Optional.of(new RouteProbe(Arrays.asList(null, null), List.of(true, true))));
            assertThat(guard.check(PIN_LAT, PIN_LON, KESWICK_REGION_ID)).isEqualTo(Routability.UNAVAILABLE);

            probeReturns(PIN_LAT, PIN_LON,
                    Optional.of(new RouteProbe(List.of(5400.0, 2700.0), List.of(false, true))));
            assertThat(guard.check(PIN_LAT, PIN_LON, KESWICK_REGION_ID)).isEqualTo(Routability.UNAVAILABLE);
        }

        @Test
        @DisplayName("no answer, or a short one, is UNAVAILABLE")
        void noAnswer_isUnavailable() {
            keswickRegion();
            probeReturns(PIN_LAT, PIN_LON, Optional.empty());
            assertThat(guard.check(PIN_LAT, PIN_LON, KESWICK_REGION_ID)).isEqualTo(Routability.UNAVAILABLE);

            probeReturns(PIN_LAT, PIN_LON, Optional.of(new RouteProbe(List.of(5400.0), List.of(true))));
            assertThat(guard.check(PIN_LAT, PIN_LON, KESWICK_REGION_ID)).isEqualTo(Routability.UNAVAILABLE);
        }

        @Test
        @DisplayName("ORS naming the target as unplaceable is UNROUTABLE; naming the source or control is not")
        void error6010_onlyTheTargetIsAnAnswer() {
            keswickRegion();
            when(orsClient.probe(eq(KESWICK_LAT), eq(KESWICK_LON),
                    argThat(dests -> isControlThen(dests, PIN_LAT, PIN_LON)),
                    eq(DriveRoutabilityGuard.PERMIT_TIMEOUT)))
                    .thenThrow(new OrsUnroutablePointException(2))
                    .thenThrow(new OrsUnroutablePointException(1))
                    .thenThrow(new OrsUnroutablePointException(0))
                    .thenThrow(new OrsUnroutablePointException(-1));

            assertThat(guard.check(PIN_LAT, PIN_LON, KESWICK_REGION_ID)).isEqualTo(Routability.UNROUTABLE);
            assertThat(guard.check(PIN_LAT, PIN_LON, KESWICK_REGION_ID)).isEqualTo(Routability.UNAVAILABLE);
            assertThat(guard.check(PIN_LAT, PIN_LON, KESWICK_REGION_ID)).isEqualTo(Routability.UNAVAILABLE);
            assertThat(guard.check(PIN_LAT, PIN_LON, KESWICK_REGION_ID)).isEqualTo(Routability.UNAVAILABLE);
        }

        @Test
        @DisplayName("any other ORS failure is UNAVAILABLE, never an exception")
        void orsError_isUnavailable() {
            keswickRegion();
            when(orsClient.probe(eq(KESWICK_LAT), eq(KESWICK_LON),
                    argThat(dests -> isControlThen(dests, PIN_LAT, PIN_LON)),
                    eq(DriveRoutabilityGuard.PERMIT_TIMEOUT)))
                    .thenThrow(new ResourceAccessException("timeout"));

            assertThat(guard.check(PIN_LAT, PIN_LON, KESWICK_REGION_ID)).isEqualTo(Routability.UNAVAILABLE);
        }

        @Test
        @DisplayName("a region with no base routes from the nearest base of any region")
        void baselessRegion_usesTheNearestBase() {
            when(regionRepository.findById(OTHER_REGION_ID))
                    .thenReturn(Optional.of(region(OTHER_REGION_ID, "Baseless", null, null)));
            when(regionRepository.findAll()).thenReturn(List.of(
                    region(1L, "Far", 51.5, -0.1),
                    region(OTHER_REGION_ID, "Baseless", null, null),
                    region(KESWICK_REGION_ID, "Lake District", KESWICK_LAT, KESWICK_LON)));
            probeReturns(PIN_LAT, PIN_LON, routed());

            assertThat(guard.check(PIN_LAT, PIN_LON, OTHER_REGION_ID)).isEqualTo(Routability.ROUTABLE);
        }

        @Test
        @DisplayName("no region id, or one that does not exist, also routes from the nearest base")
        void noRegion_usesTheNearestBase() {
            when(regionRepository.findById(99L)).thenReturn(Optional.empty());
            when(regionRepository.findAll()).thenReturn(List.of(
                    region(KESWICK_REGION_ID, "Lake District", KESWICK_LAT, KESWICK_LON)));
            probeReturns(PIN_LAT, PIN_LON, routed());

            assertThat(guard.check(PIN_LAT, PIN_LON, null)).isEqualTo(Routability.ROUTABLE);
            assertThat(guard.check(PIN_LAT, PIN_LON, 99L)).isEqualTo(Routability.ROUTABLE);
        }

        @Test
        @DisplayName("no base anywhere is UNAVAILABLE and ORS is never asked")
        void noBaseAnywhere_isUnavailable() {
            when(regionRepository.findAll()).thenReturn(List.of(region(1L, "Baseless", null, null)));

            assertThat(guard.check(PIN_LAT, PIN_LON, null)).isEqualTo(Routability.UNAVAILABLE);

            verifyNoInteractions(orsClient);
        }
    }

    @Nested
    @DisplayName("creating a location")
    class Create {

        @Test
        @DisplayName("a routable pin is saved")
        void routablePin_passes() {
            keswickRegion();
            probeReturns(PIN_LAT, PIN_LON, routed());

            assertThatCode(() -> guard.requireRoutableOnAdd(add(KESWICK_REGION_ID, null, null, null)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an unroutable pin is refused with the 'add a drive point' sentence")
        void unroutablePin_isRefused() {
            keswickRegion();
            probeReturns(PIN_LAT, PIN_LON, targetUnroutable());

            assertThatThrownBy(() -> guard.requireRoutableOnAdd(add(KESWICK_REGION_ID, null, null, null)))
                    .isInstanceOf(NoRoadRouteException.class)
                    .hasMessage("No road route reaches this point. Add a drive point, e.g. the nearest village "
                            + "or car park.");
        }

        @Test
        @DisplayName("with a drive point the drive point is checked, not the pin")
        void drivePoint_isCheckedInsteadOfThePin() {
            keswickRegion();
            probeReturns(DP_LAT, DP_LON, routed());

            assertThatCode(() -> guard.requireRoutableOnAdd(add(KESWICK_REGION_ID, "Glenridding", DP_LAT, DP_LON)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an unroutable drive point is refused with its own sentence")
        void unroutableDrivePoint_isRefused() {
            keswickRegion();
            probeReturns(DP_LAT, DP_LON, targetUnroutable());

            assertThatThrownBy(() -> guard.requireRoutableOnAdd(add(KESWICK_REGION_ID, "Glenridding", DP_LAT, DP_LON)))
                    .isInstanceOf(NoRoadRouteException.class)
                    .hasMessageContaining("that drive point");
        }

        @Test
        @DisplayName("a half-supplied drive point is a 400 before ORS is asked")
        void partialDrivePoint_isABadRequest() {
            assertThatThrownBy(() -> guard.requireRoutableOnAdd(add(KESWICK_REGION_ID, "Glenridding", null, null)))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(orsClient);
        }

        @Test
        @DisplayName("an ORS outage never refuses a create")
        void outage_neverRefuses() {
            keswickRegion();
            probeReturns(PIN_LAT, PIN_LON, Optional.empty());

            assertThatCode(() -> guard.requireRoutableOnAdd(add(KESWICK_REGION_ID, null, null, null)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("updating a location")
    class Update {

        @Test
        @DisplayName("an edit that moves nothing and touches no drive point reads nothing and asks nothing")
        void unrelatedEdit_asksNothing() {
            assertThatCode(() -> guard.requireRoutableOnUpdate(LOCATION_ID,
                    update(null, null, null, null, null, null, null))).doesNotThrowAnyException();

            verifyNoInteractions(orsClient, locationService, regionRepository);
        }

        @Test
        @DisplayName("restating the same coordinates is not a move")
        void sameCoordinates_isNotAMove() {
            stored(false, KESWICK_REGION_ID);

            assertThatCode(() -> guard.requireRoutableOnUpdate(LOCATION_ID,
                    update(PIN_LAT, PIN_LON, null, null, null, null, null))).doesNotThrowAnyException();

            verifyNoInteractions(orsClient);
        }

        @Test
        @DisplayName("moving the pin of a place with no drive point checks the new pin, and refuses if unroutable")
        void moveWithoutDrivePoint_checksTheNewPin() {
            stored(false, KESWICK_REGION_ID);
            keswickRegion();
            probeReturns(54.6, -3.0, targetUnroutable());

            assertThatThrownBy(() -> guard.requireRoutableOnUpdate(LOCATION_ID,
                    update(54.6, -3.0, null, null, null, null, null)))
                    .isInstanceOf(NoRoadRouteException.class)
                    .hasMessageContaining("Add a drive point");
        }

        @Test
        @DisplayName("moving only the latitude checks the new latitude with the stored longitude")
        void moveOneCoordinate_keepsTheOther() {
            stored(false, KESWICK_REGION_ID);
            keswickRegion();
            probeReturns(54.6, PIN_LON, routed());

            assertThatCode(() -> guard.requireRoutableOnUpdate(LOCATION_ID,
                    update(54.6, null, null, null, null, null, null))).doesNotThrowAnyException();

            verify(orsClient).probe(eq(KESWICK_LAT), eq(KESWICK_LON),
                    argThat(dests -> isControlThen(dests, 54.6, PIN_LON)), eq(DriveRoutabilityGuard.PERMIT_TIMEOUT));
        }

        @Test
        @DisplayName("moving the pin behind a drive point changes the routing point by nothing: no check")
        void moveBehindDrivePoint_asksNothing() {
            stored(true, KESWICK_REGION_ID);

            assertThatCode(() -> guard.requireRoutableOnUpdate(LOCATION_ID,
                    update(54.7, -3.2, null, null, null, null, null))).doesNotThrowAnyException();

            verifyNoInteractions(orsClient);
        }

        @Test
        @DisplayName("clearing a drive point checks the pin it falls back to, and refuses if unroutable")
        void clearDrivePoint_checksThePin() {
            stored(true, KESWICK_REGION_ID);
            keswickRegion();
            probeReturns(PIN_LAT, PIN_LON, targetUnroutable());

            assertThatThrownBy(() -> guard.requireRoutableOnUpdate(LOCATION_ID,
                    update(null, null, null, null, null, null, true)))
                    .isInstanceOf(NoRoadRouteException.class)
                    .hasMessageContaining("Add a drive point");
        }

        @Test
        @DisplayName("clearing while moving checks the pin as this same request leaves it")
        void clearAndMove_checksTheNewPin() {
            stored(true, KESWICK_REGION_ID);
            keswickRegion();
            probeReturns(54.65, -3.1, routed());

            assertThatCode(() -> guard.requireRoutableOnUpdate(LOCATION_ID,
                    update(54.65, -3.1, null, null, null, null, true))).doesNotThrowAnyException();

            verify(orsClient).probe(eq(KESWICK_LAT), eq(KESWICK_LON),
                    argThat(dests -> isControlThen(dests, 54.65, -3.1)), eq(DriveRoutabilityGuard.PERMIT_TIMEOUT));
        }

        @Test
        @DisplayName("clearing a drive point that is not there, without moving, asks nothing")
        void clearNothing_asksNothing() {
            stored(false, KESWICK_REGION_ID);

            assertThatCode(() -> guard.requireRoutableOnUpdate(LOCATION_ID,
                    update(null, null, null, null, null, null, true))).doesNotThrowAnyException();

            verifyNoInteractions(orsClient);
        }

        @Test
        @DisplayName("setting a new drive point checks that point, and refuses if it has no route")
        void setDrivePoint_checksThePoint() {
            stored(false, KESWICK_REGION_ID);
            keswickRegion();
            probeReturns(DP_LAT, DP_LON, targetUnroutable());

            assertThatThrownBy(() -> guard.requireRoutableOnUpdate(LOCATION_ID,
                    update(null, null, null, "Glenridding", DP_LAT, DP_LON, null)))
                    .isInstanceOf(NoRoadRouteException.class)
                    .hasMessageContaining("that drive point");
        }

        @Test
        @DisplayName("restating the stored drive point asks nothing")
        void sameDrivePoint_asksNothing() {
            stored(true, KESWICK_REGION_ID);

            assertThatCode(() -> guard.requireRoutableOnUpdate(LOCATION_ID,
                    update(null, null, null, "Glenridding", DP_LAT, DP_LON, null))).doesNotThrowAnyException();

            verifyNoInteractions(orsClient);
        }

        @Test
        @DisplayName("changing the stored drive point checks the new one, from the region this request names")
        void changeDrivePoint_usesTheRequestsRegion() {
            stored(true, KESWICK_REGION_ID);
            when(regionRepository.findById(OTHER_REGION_ID))
                    .thenReturn(Optional.of(region(OTHER_REGION_ID, "Other", 55.0, -2.0)));
            when(orsClient.probe(eq(55.0), eq(-2.0),
                    argThat(dests -> isControlThen(dests, 54.55, -2.96)),
                    eq(DriveRoutabilityGuard.PERMIT_TIMEOUT))).thenReturn(routed());

            assertThatCode(() -> guard.requireRoutableOnUpdate(LOCATION_ID,
                    update(null, null, OTHER_REGION_ID, "Patterdale", 54.55, -2.96, null)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a stored place with no region routes from the nearest base")
        void noStoredRegion_usesTheNearestBase() {
            stored(false, null);
            when(regionRepository.findAll()).thenReturn(List.of(
                    region(KESWICK_REGION_ID, "Lake District", KESWICK_LAT, KESWICK_LON)));
            probeReturns(54.6, -3.0, routed());

            assertThatCode(() -> guard.requireRoutableOnUpdate(LOCATION_ID,
                    update(54.6, -3.0, null, null, null, null, null))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("setting and clearing the drive point together is a 400")
        void setAndClear_isABadRequest() {
            assertThatThrownBy(() -> guard.requireRoutableOnUpdate(LOCATION_ID,
                    update(null, null, null, "Glenridding", DP_LAT, DP_LON, true)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not both");

            verifyNoInteractions(orsClient, locationService);
        }

        @Test
        @DisplayName("an outage never refuses a move or a clear")
        void outage_neverRefuses() {
            stored(true, KESWICK_REGION_ID);
            keswickRegion();
            probeReturns(PIN_LAT, PIN_LON, Optional.empty());

            assertThatCode(() -> guard.requireRoutableOnUpdate(LOCATION_ID,
                    update(null, null, null, null, null, null, true))).doesNotThrowAnyException();
        }
    }
}
