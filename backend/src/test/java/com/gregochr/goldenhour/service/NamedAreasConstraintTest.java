package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.LocationAliasEntity;
import com.gregochr.goldenhour.exception.NameClashException;
import com.gregochr.goldenhour.model.AreaLocationsRequest;
import com.gregochr.goldenhour.model.AreaRequest;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserSubRegionRepository;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationAliasRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.when;

/**
 * A database unique or primary-key violation (two admins racing) is a 409 naming the clash, not a 500.
 */
@ExtendWith(MockitoExtension.class)
class NamedAreasConstraintTest {

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private LocationAliasRepository aliasRepository;

    @Mock
    private NameNamespaceGuard guard;

    @Mock
    private RegionRepository regionRepository;

    @Mock
    private SubRegionMembershipService membershipService;

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private AppUserSubRegionRepository pickRepository;

    private AreaService areaService;
    private LocationAliasService aliasService;

    @BeforeEach
    void setUp() {
        areaService = new AreaService(areaRepository, locationRepository, regionRepository, guard,
                membershipService, userRepository, pickRepository);
        aliasService = new LocationAliasService(aliasRepository, locationRepository, guard);
    }

    @Test
    @DisplayName("create() turns a unique-index violation at flush into a NameClashException (409)")
    void create_uniqueViolation_isAClash() {
        when(areaRepository.saveAndFlush(argThat(a -> a != null && a.getId() == null
                && "Kielder".equals(a.getName()) && a.getAliases().equals(Set.of("Kielder Forest"))
                && a.getLocationIds().isEmpty())))
                .thenThrow(new DataIntegrityViolationException("uq_area_name_lower"));

        assertThatThrownBy(() -> areaService.create(new AreaRequest("Kielder", List.of("Kielder Forest"))))
                .isInstanceOf(NameClashException.class);
    }

    @Test
    @DisplayName("update() turns a violation at flush into a NameClashException (409)")
    void update_violation_isAClash() {
        AreaEntity loaded = new AreaEntity();
        when(areaRepository.findById(3L)).thenReturn(Optional.of(loaded));
        when(areaRepository.saveAndFlush(same(loaded)))
                .thenThrow(new DataIntegrityViolationException("area_alias_pkey"));

        assertThatThrownBy(() -> areaService.update(3L, new AreaRequest("Kielder", List.of("Kielder Forest"))))
                .isInstanceOf(NameClashException.class);
    }

    @Test
    @DisplayName("delete() turns a foreign-key violation at flush (a member added in the gap) into a 409")
    void delete_violation_isAConflict() {
        AreaEntity loaded = new AreaEntity();
        loaded.setName("Swaledale");
        when(areaRepository.findById(3L)).thenReturn(Optional.of(loaded));
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("location_sub_region_area_id_fkey"))
                .when(areaRepository).flush();

        assertThatThrownBy(() -> areaService.delete(3L))
                .isInstanceOf(com.gregochr.goldenhour.exception.AreaConflictException.class)
                .hasMessageContaining("Swaledale");
    }

    @Test
    @DisplayName("setLocations() turns a violation at flush into a NameClashException (409)")
    void setLocations_violation_isAClash() {
        AreaEntity loaded = new AreaEntity();
        when(areaRepository.findById(3L)).thenReturn(Optional.of(loaded));
        when(areaRepository.saveAndFlush(same(loaded)))
                .thenThrow(new DataIntegrityViolationException("area_location_pkey"));

        assertThatThrownBy(() -> areaService.setLocations(3L, new AreaLocationsRequest(List.of())))
                .isInstanceOf(NameClashException.class);
    }

    @Test
    @DisplayName("replace() turns a violation while saving aliases into a NameClashException (409)")
    void replaceAliases_violation_isAClash() {
        when(locationRepository.existsById(5L)).thenReturn(true);
        when(aliasRepository.saveAll(argThat((List<LocationAliasEntity> rows) -> rows != null && rows.size() == 1
                && Long.valueOf(5L).equals(rows.get(0).getLocationId())
                && "The Heugh".equals(rows.get(0).getAlias()))))
                .thenThrow(new DataIntegrityViolationException("location_alias_pkey"));

        assertThatThrownBy(() -> aliasService.replace(5L, List.of("The Heugh")))
                .isInstanceOf(NameClashException.class);
    }
}
