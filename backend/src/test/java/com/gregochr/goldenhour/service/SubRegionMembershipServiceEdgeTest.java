package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.exception.AreaConflictException;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.LocationSubRegionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Edge cases of {@link SubRegionMembershipService}: a sub-region with no region, a lost write race, and
 * reconciling a membership whose area is gone, has no region or still matches.
 */
class SubRegionMembershipServiceEdgeTest {

    private LocationSubRegionRepository membership;
    private AreaRepository areas;
    private SubRegionMembershipService service;

    @BeforeEach
    void setUp() {
        membership = mock(LocationSubRegionRepository.class);
        areas = mock(AreaRepository.class);
        service = new SubRegionMembershipService(membership, areas, mock(LocationRepository.class));
    }

    private static RegionEntity region(long id, String name) {
        RegionEntity region = new RegionEntity();
        region.setId(id);
        region.setName(name);
        return region;
    }

    private static AreaEntity subRegion(long id, String name, RegionEntity region) {
        AreaEntity area = new AreaEntity();
        area.setId(id);
        area.setName(name);
        area.setKind(AreaKind.SUB_REGION);
        area.setRegion(region);
        return area;
    }

    private static LocationEntity location(long id, RegionEntity region) {
        return LocationEntity.builder().id(id).name("Bamburgh").region(region).build();
    }

    @Test
    @DisplayName("a sub-region with no region of its own is refused, naming 'no region'")
    void requireAssignable_areaWithoutRegion_refused() {
        when(areas.findById(5L)).thenReturn(Optional.of(subRegion(5L, "Coast", null)));

        assertThatThrownBy(() -> service.requireAssignable(5L, region(1L, "Northumberland")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Sub-region 'Coast' is in no region, not 'Northumberland'");
    }

    @Test
    @DisplayName("a sub-region of another region is refused, naming both regions")
    void requireAssignable_otherRegion_refused() {
        when(areas.findById(5L)).thenReturn(Optional.of(subRegion(5L, "Coast", region(2L, "Lakes"))));

        assertThatThrownBy(() -> service.requireAssignable(5L, region(1L, "Northumberland")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Sub-region 'Coast' is in the region 'Lakes', not 'Northumberland'");
    }

    @Test
    @DisplayName("a concurrent write that wins the race surfaces as an area conflict")
    void assign_lostRace_throwsAreaConflict() {
        RegionEntity northumberland = region(1L, "Northumberland");
        LocationEntity bamburgh = location(9L, northumberland);
        when(areas.findById(5L)).thenReturn(Optional.of(subRegion(5L, "Coast", northumberland)));
        when(membership.findAreaIdsByLocationId(9L)).thenReturn(List.of());
        when(membership.updateArea(9L, 5L)).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.assign(bamburgh, 5L))
                .isInstanceOf(AreaConflictException.class)
                .hasMessage("'Bamburgh' was changed by someone else at the same moment; reload and try again");
        verify(membership, never()).insertMembership(9L, 5L);
    }

    @Test
    @DisplayName("a membership whose area no longer exists is dropped")
    void reconcileRegion_areaGone_dropped() {
        when(membership.findAreaIdsByLocationId(9L)).thenReturn(List.of(5L));
        when(areas.findById(5L)).thenReturn(Optional.empty());

        boolean dropped = service.reconcileRegion(location(9L, region(1L, "Northumberland")));

        assertThat(dropped).isTrue();
        verify(membership).deleteMembership(9L);
    }

    @Test
    @DisplayName("a membership whose area has no region is dropped")
    void reconcileRegion_areaWithoutRegion_dropped() {
        when(membership.findAreaIdsByLocationId(9L)).thenReturn(List.of(5L));
        when(areas.findById(5L)).thenReturn(Optional.of(subRegion(5L, "Coast", null)));

        assertThat(service.reconcileRegion(location(9L, region(1L, "Northumberland")))).isTrue();
        verify(membership).deleteMembership(9L);
    }

    @Test
    @DisplayName("a location that lost its region leaves its sub-region")
    void reconcileRegion_locationWithoutRegion_dropped() {
        when(membership.findAreaIdsByLocationId(9L)).thenReturn(List.of(5L));
        when(areas.findById(5L)).thenReturn(Optional.of(subRegion(5L, "Coast", region(1L, "Northumberland"))));

        assertThat(service.reconcileRegion(location(9L, null))).isTrue();
        verify(membership).deleteMembership(9L);
    }

    @Test
    @DisplayName("a membership still in the location's region is kept")
    void reconcileRegion_sameRegion_kept() {
        when(membership.findAreaIdsByLocationId(9L)).thenReturn(List.of(5L));
        when(areas.findById(5L)).thenReturn(Optional.of(subRegion(5L, "Coast", region(1L, "Northumberland"))));

        assertThat(service.reconcileRegion(location(9L, region(1L, "Northumberland")))).isFalse();
        verify(membership, never()).deleteMembership(9L);
    }
}
