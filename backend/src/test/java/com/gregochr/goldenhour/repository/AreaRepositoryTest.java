package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.LocationAliasEntity;
import com.gregochr.goldenhour.entity.LocationAliasId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests for {@link AreaRepository} and {@link LocationAliasRepository}.
 * Uses an H2 in-memory database with schema generated from the JPA entities.
 */
@DataJpaTest
class AreaRepositoryTest {

    @Autowired
    private AreaRepository areaRepository;

    @Autowired
    private LocationAliasRepository aliasRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("an area saved with aliases and members reads back with both sets and a creation time")
    void save_areaWithAliasesAndMembers_readsBack() {
        AreaEntity area = new AreaEntity();
        area.setName("Hadrian's Wall");
        area.setAliases(Set.of("Hadrian Wall", "Roman Wall"));
        area.setLocationIds(Set.of(11L, 12L, 13L));
        Long id = areaRepository.save(area).getId();
        entityManager.flush();
        entityManager.clear();

        AreaEntity found = areaRepository.findById(id).orElseThrow();

        assertThat(found.getName()).isEqualTo("Hadrian's Wall");
        assertThat(found.getCreatedAt()).as("@PrePersist stamps createdAt").isNotNull();
        assertThat(found.getAliases()).containsExactlyInAnyOrder("Hadrian Wall", "Roman Wall");
        assertThat(found.getLocationIds()).containsExactlyInAnyOrder(11L, 12L, 13L);
    }

    @Test
    @DisplayName("removing a member and saving leaves the others, and findAll lists every area")
    void save_afterRemovingMember_keepsTheRest() {
        AreaEntity area = new AreaEntity();
        area.setName("Kielder");
        area.getLocationIds().add(1L);
        area.getLocationIds().add(2L);
        Long id = areaRepository.save(area).getId();
        entityManager.flush();
        entityManager.clear();

        AreaEntity loaded = areaRepository.findById(id).orElseThrow();
        loaded.getLocationIds().remove(1L);
        areaRepository.save(loaded);
        entityManager.flush();
        entityManager.clear();

        assertThat(areaRepository.findById(id).orElseThrow().getLocationIds()).containsExactly(2L);
        assertThat(areaRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("location aliases save, read back by composite key, and list through findAll")
    void locationAlias_saveAndRead() {
        aliasRepository.save(new LocationAliasEntity(7L, "The Heugh"));
        aliasRepository.save(new LocationAliasEntity(7L, "Heugh Battery"));
        entityManager.flush();
        entityManager.clear();

        List<LocationAliasEntity> all = aliasRepository.findAll();

        assertThat(all).extracting(LocationAliasEntity::getAlias)
                .containsExactlyInAnyOrder("The Heugh", "Heugh Battery");
        LocationAliasEntity one = aliasRepository.findById(new LocationAliasId(7L, "The Heugh")).orElseThrow();
        assertThat(one.getLocationId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("deleteByLocationId removes that location's aliases and leaves other locations' alone")
    void deleteByLocationId_removesOnlyThatLocation() {
        aliasRepository.save(new LocationAliasEntity(7L, "The Heugh"));
        aliasRepository.save(new LocationAliasEntity(7L, "Heugh Battery"));
        aliasRepository.save(new LocationAliasEntity(8L, "The Cove"));
        entityManager.flush();

        aliasRepository.deleteByLocationId(7L);
        entityManager.clear();

        assertThat(aliasRepository.findAll()).extracting(LocationAliasEntity::getAlias)
                .containsExactly("The Cove");
    }

    @Test
    @DisplayName("LocationAliasId compares by both parts")
    void locationAliasId_equalsAndHashCode() {
        LocationAliasId a = new LocationAliasId(1L, "x");

        assertThat(a).isEqualTo(new LocationAliasId(1L, "x")).hasSameHashCodeAs(new LocationAliasId(1L, "x"));
        assertThat(a).isNotEqualTo(new LocationAliasId(2L, "x"))
                .isNotEqualTo(new LocationAliasId(1L, "y"))
                .isNotEqualTo("x");
    }
}
