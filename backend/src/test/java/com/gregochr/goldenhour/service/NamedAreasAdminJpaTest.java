package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.LocationAliasEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.exception.NameClashException;
import com.gregochr.goldenhour.model.AreaDto;
import com.gregochr.goldenhour.model.AreaLocationsRequest;
import com.gregochr.goldenhour.model.AreaRequest;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationAliasRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The area and alias services and the shared name guard over real repositories (H2): the validation rules,
 * the normalised comparison, both shadowing rules, and the trap that a rename must keep the members.
 */
@DataJpaTest
@Import({AreaService.class, LocationAliasService.class, NameNamespaceGuard.class,
        SubRegionMembershipService.class})
class NamedAreasAdminJpaTest {

    @Autowired
    private AreaService areaService;

    @Autowired
    private LocationAliasService aliasService;

    @Autowired
    private NameNamespaceGuard guard;

    @Autowired
    private AreaRepository areaRepository;

    @Autowired
    private LocationAliasRepository aliasRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Long walltown;
    private Long headland;

    @BeforeEach
    void plantLocations() {
        walltown = location("Walltown Crags");
        headland = location("Hartlepool Headland");
    }

    private Long location(String name) {
        LocationEntity entity = LocationEntity.builder().name(name).lat(55.0).lon(-2.0)
                .createdAt(LocalDateTime.of(2026, 2, 22, 12, 0)).build();
        return entityManager.persistAndFlush(entity).getId();
    }

    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }

    private AreaDto wall() {
        return areaService.create(new AreaRequest("Hadrian's Wall", List.of("Roman Wall")));
    }

    // --- areas ---

    @Test
    @DisplayName("create() saves an area with no members and lists it")
    void create_savesAreaWithNoMembers() {
        AreaDto created = wall();
        reload();

        assertThat(created.id()).isNotNull();
        assertThat(created.locationIds()).isEmpty();
        assertThat(areaService.findAll()).singleElement().satisfies(a -> {
            assertThat(a.name()).isEqualTo("Hadrian's Wall");
            assertThat(a.aliases()).containsExactly("Roman Wall");
        });
    }

    @Test
    @DisplayName("update() renames and replaces aliases but KEEPS the members")
    void update_renameKeepsMembers() {
        AreaDto created = wall();
        areaService.setLocations(created.id(), new AreaLocationsRequest(List.of(walltown, headland)));
        reload();

        areaService.update(created.id(), new AreaRequest("Hadrians Wall Country", List.of("Wall Country")));
        reload();

        AreaEntity found = areaRepository.findById(created.id()).orElseThrow();
        assertThat(found.getName()).isEqualTo("Hadrians Wall Country");
        assertThat(found.getAliases()).containsExactly("Wall Country");
        assertThat(found.getLocationIds()).containsExactlyInAnyOrder(walltown, headland);
    }

    @Test
    @DisplayName("update() may keep its own name and aliases (an area never clashes with itself)")
    void update_sameNames_isNotAClash() {
        AreaDto created = wall();
        reload();

        AreaDto updated = areaService.update(created.id(), new AreaRequest("Hadrians Wall", List.of("Roman Wall")));

        assertThat(updated.name()).isEqualTo("Hadrians Wall");
    }

    @Test
    @DisplayName("update() and delete() of an unknown area are NoSuchElementException (404)")
    void unknownArea_throwsNoSuchElement() {
        assertThatThrownBy(() -> areaService.update(999L, new AreaRequest("Kielder", List.of())))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> areaService.delete(999L)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> areaService.setLocations(999L, new AreaLocationsRequest(List.of())))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("setLocations() replaces membership, and an unknown location id is a 400 that changes nothing")
    void setLocations_replacesAndRejectsUnknown() {
        AreaDto created = wall();
        areaService.setLocations(created.id(), new AreaLocationsRequest(List.of(walltown)));
        reload();

        assertThatThrownBy(() -> areaService.setLocations(created.id(),
                new AreaLocationsRequest(List.of(headland, 98765L))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("98765");
        reload();
        assertThat(areaRepository.findById(created.id()).orElseThrow().getLocationIds())
                .containsExactly(walltown);

        areaService.setLocations(created.id(), new AreaLocationsRequest(List.of(headland)));
        reload();
        assertThat(areaRepository.findById(created.id()).orElseThrow().getLocationIds())
                .containsExactly(headland);
    }

    @Test
    @DisplayName("delete() removes the area")
    void delete_removesArea() {
        AreaDto created = wall();
        reload();

        areaService.delete(created.id());
        reload();

        assertThat(areaRepository.findAll()).isEmpty();
    }

    // --- area name and alias rules ---

    @Test
    @DisplayName("an area name must be 2 to 80 characters and an alias at least two words")
    void area_shapeRules() {
        assertThatThrownBy(() -> areaService.create(new AreaRequest("K", List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 to 80");
        assertThatThrownBy(() -> areaService.create(new AreaRequest(null, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> areaService.create(new AreaRequest("x".repeat(81), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> areaService.create(new AreaRequest("Kielder", List.of("Kielder"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 2 words");
    }

    @Test
    @DisplayName("a single-word area NAME is allowed; blank and null aliases are dropped; repeats are one")
    void area_cleansAliases() {
        AreaDto created = areaService.create(new AreaRequest("Kielder", Arrays.asList(
                "Kielder Forest", " ", null, "kielder forest")));

        assertThat(created.aliases()).containsExactly("Kielder Forest");
    }

    @Test
    @DisplayName("an area name equal to another area's name or alias is a 409, compared normalised")
    void area_clashWithAnotherArea() {
        wall();
        reload();

        assertThatThrownBy(() -> areaService.create(new AreaRequest("HADRIANS WALL", List.of())))
                .isInstanceOf(NameClashException.class).hasMessageContaining("Hadrian's Wall");
        assertThatThrownBy(() -> areaService.create(new AreaRequest("Kielder", List.of("roman wall"))))
                .isInstanceOf(NameClashException.class).hasMessageContaining("already a name for the named area");
    }

    @Test
    @DisplayName("the same name twice in one request is a 409")
    void area_nameAndAliasTheSame() {
        assertThatThrownBy(() -> areaService.create(new AreaRequest("Kielder Forest", List.of("kielders forest",
                "Kielder  Forest"))))
                .isInstanceOf(NameClashException.class).hasMessageContaining("twice");
    }

    @Test
    @DisplayName("an area name or alias may not equal or sit inside a location name (shadowing rule 1)")
    void area_shadowsLocationName() {
        assertThatThrownBy(() -> areaService.create(new AreaRequest("Walltown Crags", List.of())))
                .isInstanceOf(NameClashException.class).hasMessageContaining("Walltown Crags");
        assertThatThrownBy(() -> areaService.create(new AreaRequest("The Crags", List.of("Walltown Crags"))))
                .isInstanceOf(NameClashException.class);
        assertThatThrownBy(() -> areaService.create(new AreaRequest("Hartlepool", List.of())))
                .isInstanceOf(NameClashException.class).hasMessageContaining("Hartlepool Headland");
    }

    @Test
    @DisplayName("an area name may not sit inside a location alias")
    void area_shadowsLocationAlias() {
        aliasService.replace(headland, List.of("The Heugh Lighthouse"));
        reload();

        assertThatThrownBy(() -> areaService.create(new AreaRequest("Heugh Lighthouse", List.of())))
                .isInstanceOf(NameClashException.class).hasMessageContaining("The Heugh Lighthouse");
    }

    @Test
    @DisplayName("an area name that merely shares one word with a location is free")
    void area_partialWordOverlapIsFree() {
        AreaDto created = areaService.create(new AreaRequest("Crags Country", List.of("North Crags")));

        assertThat(created.name()).isEqualTo("Crags Country");
    }

    // --- location alias rules ---

    @Test
    @DisplayName("aliases(): replace() overwrites, deletes the old rows, and findAll() groups by location")
    void locationAlias_replaceAndList() {
        aliasService.replace(headland, List.of("The Heugh", "Heugh Battery"));
        reload();
        List<String> second = aliasService.replace(headland, List.of("Heugh Lighthouse"));
        reload();

        assertThat(second).containsExactly("Heugh Lighthouse");
        assertThat(aliasRepository.findAll()).extracting(LocationAliasEntity::getAlias)
                .containsExactly("Heugh Lighthouse");
        assertThat(aliasService.findAll()).isEqualTo(Map.of(headland, List.of("Heugh Lighthouse")));

        aliasService.replace(headland, List.of());
        reload();
        assertThat(aliasService.findAll()).isEmpty();
    }

    @Test
    @DisplayName("replace() on an unknown location is NoSuchElementException (404) and leaves aliases alone")
    void locationAlias_unknownLocation() {
        assertThatThrownBy(() -> aliasService.replace(4242L, List.of("The Heugh")))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(aliasRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a location alias may be one word, but not blank-ish or over 80 characters")
    void locationAlias_shape() {
        assertThat(aliasService.replace(headland, List.of("Heugh"))).containsExactly("Heugh");
        assertThatThrownBy(() -> aliasService.replace(headland, List.of("x".repeat(81))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> aliasService.replace(headland, List.of("!!")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a location alias may not equal another location's name or alias, normalised")
    void locationAlias_clashWithOtherLocation() {
        aliasService.replace(walltown, List.of("The Wall Gap"));
        reload();

        assertThatThrownBy(() -> aliasService.replace(headland, List.of("walltown crags")))
                .isInstanceOf(NameClashException.class).hasMessageContaining("Walltown Crags");
        assertThatThrownBy(() -> aliasService.replace(headland, List.of("The Wall Gap")))
                .isInstanceOf(NameClashException.class).hasMessageContaining("The Wall Gap");
        // its own other names are not a clash
        assertThat(aliasService.replace(walltown, List.of("The Wall Gap", "Walltown"))).hasSize(2);
    }

    @Test
    @DisplayName("a location alias may not equal an area name or alias, nor contain one (shadowing rule 2)")
    void locationAlias_clashWithAreas() {
        wall();
        reload();

        assertThatThrownBy(() -> aliasService.replace(headland, List.of("Hadrians Wall")))
                .isInstanceOf(NameClashException.class);
        assertThatThrownBy(() -> aliasService.replace(headland, List.of("Roman Wall Viewpoint")))
                .isInstanceOf(NameClashException.class).hasMessageContaining("Roman Wall");
        assertThat(aliasService.replace(headland, List.of("Wall Viewpoint"))).hasSize(1);
    }

    // --- location names (called by LocationService) ---

    @Test
    @DisplayName("locationNameClash(): equal to a location alias, an area name or alias, or containing one")
    void locationName_rules() {
        wall();
        aliasService.replace(headland, List.of("The Heugh"));
        reload();

        assertThat(guard.locationNameClash("the heugh", null)).isPresent();
        assertThat(guard.locationNameClash("Hadrians Wall", null)).isPresent();
        assertThat(guard.locationNameClash("Roman Wall Viewpoint", null)).hasValueSatisfying(
                m -> assertThat(m).contains("Roman Wall"));
        assertThat(guard.locationNameClash("Wall Viewpoint", null)).isEmpty();
        assertThat(guard.locationNameClash("Bamburgh Castle", null)).isEmpty();
        assertThat(guard.locationNameClash("!!", null)).isEmpty();
    }

    @Test
    @DisplayName("a location may be renamed to one of ITS OWN aliases, but not to another location's alias")
    void locationName_ownAliasIsNotAClash() {
        aliasService.replace(headland, List.of("The Heugh"));
        reload();

        assertThat(guard.locationNameClash("The Heugh", headland)).isEmpty();
        assertThat(guard.locationNameClash("The Heugh", walltown)).isPresent();
        assertThat(guard.locationNameClash("The Heugh", null)).isPresent();
    }

    @Test
    @DisplayName("a location name equal to ANOTHER location's name in normalised form is a clash; its own is not")
    void locationName_normalisedOtherLocationName() {
        Long cuddys = location("Cuddy's Crags");

        assertThat(guard.locationNameClash("Cuddys Crags", null)).hasValueSatisfying(
                m -> assertThat(m).contains("Cuddy's Crags"));
        assertThat(guard.locationNameClash("CUDDYS  CRAGS", walltown)).isPresent();
        assertThat(guard.locationNameClash("Cuddys Crags", cuddys)).isEmpty();
        assertThat(guard.locationNameClash("cuddy's crags", cuddys)).isEmpty();
    }

    @Test
    @DisplayName("a missing aliases list or locationIds list is a 400, never a silent clear; [] still clears")
    void nullListsAreRejected() {
        AreaDto created = wall();
        areaService.setLocations(created.id(), new AreaLocationsRequest(List.of(walltown)));
        aliasService.replace(headland, List.of("The Heugh"));
        reload();

        assertThatThrownBy(() -> areaService.setLocations(created.id(), new AreaLocationsRequest(null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("locationIds");
        assertThatThrownBy(() -> aliasService.replace(headland, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("aliases");
        reload();
        assertThat(areaRepository.findById(created.id()).orElseThrow().getLocationIds()).containsExactly(walltown);
        assertThat(aliasService.findAll()).containsKey(headland);

        areaService.setLocations(created.id(), new AreaLocationsRequest(List.of()));
        aliasService.replace(headland, List.of());
        reload();
        assertThat(areaRepository.findById(created.id()).orElseThrow().getLocationIds()).isEmpty();
        assertThat(aliasService.findAll()).isEmpty();
    }

    @Test
    @DisplayName("the guard treats a name with no words as free (shape is the caller's job)")
    void guard_emptyWords() {
        assertThat(guard.areaNameClash("!!", null)).isEmpty();
        assertThat(guard.locationAliasClash("!!", 1L)).isEmpty();
    }
}
