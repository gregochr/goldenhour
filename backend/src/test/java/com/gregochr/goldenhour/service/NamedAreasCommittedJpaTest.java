package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationAliasEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AreaDto;
import com.gregochr.goldenhour.model.AreaLocationsRequest;
import com.gregochr.goldenhour.model.AreaRequest;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationAliasRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The replace operations with REAL transaction boundaries: every service call commits on its own, as in
 * production. Inside one test-wide transaction the persistence context hides a whole class of bug (rows the
 * guard loaded stay managed, so a bulk delete followed by a save of the same keys issues no INSERT).
 */
@DataJpaTest
@Import({AreaService.class, LocationAliasService.class, NameNamespaceGuard.class,
        SubRegionMembershipService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NamedAreasCommittedJpaTest {

    @Autowired
    private AreaService areaService;

    @Autowired
    private LocationAliasService aliasService;

    @Autowired
    private AreaRepository areaRepository;

    @Autowired
    private LocationAliasRepository aliasRepository;

    @Autowired
    private LocationRepository locationRepository;

    @AfterEach
    void cleanUp() {
        aliasRepository.deleteAll();
        areaRepository.deleteAll();
        locationRepository.deleteAll();
    }

    private Long location(String name) {
        return locationRepository.save(LocationEntity.builder().name(name).lat(55.0).lon(-2.0)
                .createdAt(LocalDateTime.of(2026, 2, 22, 12, 0)).build()).getId();
    }

    @Test
    @DisplayName("replacing location aliases KEEPS the ones the request keeps and adds the new one")
    void replaceAliases_keepsKeptAliases() {
        Long headland = location("Hartlepool Headland");

        aliasService.replace(headland, List.of("Alpha One", "Beta Two"));
        List<String> saved = aliasService.replace(headland, List.of("Alpha One", "Beta Two", "Gamma Three"));

        assertThat(saved).containsExactly("Alpha One", "Beta Two", "Gamma Three");
        assertThat(aliasRepository.findAll()).extracting(LocationAliasEntity::getAlias)
                .containsExactlyInAnyOrder("Alpha One", "Beta Two", "Gamma Three");
    }

    @Test
    @DisplayName("replacing location aliases with a subset removes only the dropped ones")
    void replaceAliases_subset() {
        Long headland = location("Hartlepool Headland");
        aliasService.replace(headland, List.of("Alpha One", "Beta Two"));

        aliasService.replace(headland, List.of("Beta Two"));

        assertThat(aliasRepository.findAll()).extracting(LocationAliasEntity::getAlias)
                .containsExactly("Beta Two");
    }

    @Test
    @DisplayName("an area edited in separate transactions keeps the aliases and members the request keeps")
    void areaEdits_keepKeptAliasesAndMembers() {
        Long a = location("Walltown Crags");
        Long b = location("Cawfields");
        Long c = location("Peel Craggs");
        AreaDto created = areaService.create(new AreaRequest("Hadrian's Wall", List.of("Roman Wall")));

        areaService.setLocations(created.id(), new AreaLocationsRequest(List.of(a, b)));
        areaService.update(created.id(), new AreaRequest("Hadrian's Wall", List.of("Roman Wall", "Wall Country")));
        areaService.setLocations(created.id(), new AreaLocationsRequest(List.of(a, b, c)));

        AreaDto read = areaService.findAll().get(0);
        assertThat(read.aliases()).containsExactly("Roman Wall", "Wall Country");
        assertThat(read.locationIds()).containsExactly(a, b, c);
    }
}
