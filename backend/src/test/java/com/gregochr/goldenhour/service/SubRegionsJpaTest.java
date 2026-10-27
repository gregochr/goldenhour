package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.AreaConflictException;
import com.gregochr.goldenhour.exception.NameClashException;
import com.gregochr.goldenhour.model.AreaDto;
import com.gregochr.goldenhour.model.AreaLocationsRequest;
import com.gregochr.goldenhour.model.AreaRequest;
import com.gregochr.goldenhour.model.UncoveredLocationDto;
import com.gregochr.goldenhour.repository.AreaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sub-regions over real repositories (H2): the membership service as the only writer of
 * {@code location_sub_region} (move, region mismatch, region change), and the area service's sub-region rules
 * (a region is required, one-word aliases, members are not set on the area, delete is refused with members).
 * The table's keys and CHECKs are proven by {@code SubRegionsMigrationTest} against Postgres, not here.
 */
@DataJpaTest
@Import({AreaService.class, NameNamespaceGuard.class, SubRegionMembershipService.class})
class SubRegionsJpaTest {

    @Autowired
    private AreaService areaService;

    @Autowired
    private SubRegionMembershipService membership;

    @Autowired
    private NameNamespaceGuard guard;

    @Autowired
    private AreaRepository areaRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private RegionEntity dales;
    private RegionEntity lakes;

    @BeforeEach
    void plantRegions() {
        dales = entityManager.persistAndFlush(RegionEntity.builder().name("The Yorkshire Dales")
                .createdAt(LocalDateTime.of(2026, 2, 22, 12, 0)).build());
        lakes = entityManager.persistAndFlush(RegionEntity.builder().name("The Lake District")
                .createdAt(LocalDateTime.of(2026, 2, 22, 12, 0)).build());
    }

    private LocationEntity location(String name, RegionEntity region) {
        return entityManager.persistAndFlush(LocationEntity.builder().name(name).lat(54.4).lon(-2.1).region(region)
                .createdAt(LocalDateTime.of(2026, 2, 22, 12, 0)).build());
    }

    private AreaDto subRegion(String name, RegionEntity region, String... aliases) {
        return areaService.create(new AreaRequest(name, List.of(aliases), AreaKind.SUB_REGION, region.getId()));
    }

    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }

    // --- creating ---

    @Test
    @DisplayName("a sub-region needs a region, and a named area must not have one")
    void create_kindAndRegionMustAgree() {
        assertThatThrownBy(() -> areaService.create(new AreaRequest("Swaledale", List.of(), AreaKind.SUB_REGION, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a region");
        assertThatThrownBy(() -> areaService.create(
                new AreaRequest("Swaledale", List.of(), AreaKind.SUB_REGION, 9_999L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No region with id");
        assertThatThrownBy(() -> areaService.create(
                new AreaRequest("Hadrian's Wall", List.of(), AreaKind.NAMED, dales.getId())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has no region");
    }

    @Test
    @DisplayName("a sub-region is created with its kind and region, and a one-word alias")
    void create_subRegion_keepsKindRegionAndOneWordAlias() {
        AreaDto created = subRegion("Swaledale", dales, "Teesdale", "Upper Teesdale");

        assertThat(created.kind()).isEqualTo(AreaKind.SUB_REGION);
        assertThat(created.regionId()).isEqualTo(dales.getId());
        assertThat(created.regionName()).isEqualTo("The Yorkshire Dales");
        assertThat(created.aliases()).containsExactly("Teesdale", "Upper Teesdale");
        assertThat(created.locationIds()).isEmpty();
    }

    @Test
    @DisplayName("a named area still refuses a one-word alias")
    void create_namedArea_oneWordAliasStillRefused() {
        assertThatThrownBy(() -> areaService.create(new AreaRequest("Hadrian's Wall", List.of("Wall"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 2 words");
    }

    @Test
    @DisplayName("an existing area reads as a named area with no region")
    void existingAreas_readAsNamed() {
        AreaDto wall = areaService.create(new AreaRequest("Hadrian's Wall", List.of("Roman Wall")));

        assertThat(wall.kind()).isEqualTo(AreaKind.NAMED);
        assertThat(wall.regionId()).isNull();
        assertThat(wall.regionName()).isNull();
    }

    @Test
    @DisplayName("a sub-region's name clashes with another area's alias, as a named area's does")
    void create_subRegion_nameClashStillApplies() {
        subRegion("Swaledale", dales, "Teesdale");

        assertThatThrownBy(() -> subRegion("Upper Dales", dales, "Teesdale"))
                .isInstanceOf(NameClashException.class).hasMessageContaining("Teesdale");
    }

    @Test
    @DisplayName("a sub-region's location name clash: a location may not contain a sub-region's one-word alias")
    void oneWordAlias_blocksLaterLocationNames() {
        subRegion("Swaledale", dales, "Teesdale");

        assertThat(guard.locationNameClash("Teesdale Cottage", null))
                .hasValueSatisfying(clash -> assertThat(clash).contains("Teesdale"));
        assertThat(guard.locationNameClash("Cottage", null)).isEmpty();
    }

    @Test
    @DisplayName("the alias minimum is one word for a sub-region and two for a named area")
    void aliasMinimum_byKind() {
        assertThat(NameNamespaceGuard.areaAliasMinWords(AreaKind.SUB_REGION)).isEqualTo(1);
        assertThat(NameNamespaceGuard.areaAliasMinWords(AreaKind.NAMED)).isEqualTo(2);
    }

    // --- the extra name rules for a sub-region (mirroring V191's pre-flight) ---

    @Test
    @DisplayName("a sub-region name or alias inside an ENABLED region name is refused; inside a disabled one is not")
    void subRegionName_insideARegionName() {
        assertThatThrownBy(() -> subRegion("Yorkshire Dales", dales))
                .isInstanceOf(NameClashException.class).hasMessageContaining("region name 'The Yorkshire Dales'");
        assertThatThrownBy(() -> subRegion("Swaledale", dales, "Lake District"))
                .isInstanceOf(NameClashException.class).hasMessageContaining("The Lake District");
        entityManager.persistAndFlush(RegionEntity.builder().name("Old Moorland").enabled(false)
                .createdAt(LocalDateTime.of(2026, 2, 22, 12, 0)).build());

        assertThat(subRegion("Moorland", dales).name()).isEqualTo("Moorland");
    }

    @Test
    @DisplayName("a sub-region name or alias may not sit inside another area's name or alias")
    void subRegionName_insideAnotherArea() {
        subRegion("Upper Swaledale Country", dales, "Swale Valley");

        assertThatThrownBy(() -> subRegion("Wensleydale", dales, "Swale Valley Walk"))
                .isInstanceOf(NameClashException.class).hasMessageContaining("contains 'Swale Valley'");
        assertThatThrownBy(() -> subRegion("Nidderdale", dales, "Swale Valley"))
                .isInstanceOf(NameClashException.class);
        assertThatThrownBy(() -> subRegion("Swaledale Country", dales))
                .isInstanceOf(NameClashException.class).hasMessageContaining("is part of");
    }

    @Test
    @DisplayName("a sub-region name that contains another area's name is refused, for a named area's name and "
            + "a sub-region's alias alike")
    void subRegionName_containingAnotherArea() {
        subRegion("Wensleydale", dales, "Upper Wensleydale");
        areaService.create(new AreaRequest("Roman Wall", List.of("Wall Country")));

        assertThatThrownBy(() -> subRegion("Wensleydale Cheese Country", dales))
                .isInstanceOf(NameClashException.class).hasMessageContaining("contains 'Wensleydale'");
        assertThatThrownBy(() -> subRegion("Swaledale", dales, "Upper Wensleydale Way"))
                .isInstanceOf(NameClashException.class).hasMessageContaining("Upper Wensleydale");
        assertThatThrownBy(() -> subRegion("Roman Wall Walks", dales))
                .isInstanceOf(NameClashException.class).hasMessageContaining("named area 'Roman Wall'");
        assertThatThrownBy(() -> subRegion("Wall Country Walks", dales))
                .isInstanceOf(NameClashException.class).hasMessageContaining("Wall Country");
    }

    @Test
    @DisplayName("the one exception: \"Hadrian's Wall & Tynedale\" may contain the NAMED area \"Hadrian's Wall\", "
            + "and nothing else like it")
    void subRegionName_theWallException() {
        areaService.create(new AreaRequest("Hadrian's Wall", List.of("Roman Wall")));

        assertThat(subRegion("Hadrian's Wall & Tynedale", lakes, "Tynedale").name())
                .isEqualTo("Hadrian's Wall & Tynedale");
        assertThatThrownBy(() -> subRegion("Hadrian's Wall Path", dales))
                .isInstanceOf(NameClashException.class).hasMessageContaining("contains 'Hadrian's Wall'");
        assertThatThrownBy(() -> subRegion("Hadrian's Wall & Tynedale Way", dales))
                .isInstanceOf(NameClashException.class);
    }

    @Test
    @DisplayName("the exception needs the NAMED Wall area: a sub-region called \"Hadrian's Wall\" does not allow it")
    void subRegionName_exceptionNeedsTheNamedArea() {
        subRegion("Hadrian's Wall", lakes);

        assertThatThrownBy(() -> subRegion("Hadrian's Wall & Tynedale", dales))
                .isInstanceOf(NameClashException.class);
    }

    @Test
    @DisplayName("renaming a sub-region is not a clash with its own old names")
    void subRegionName_ownNamesAreNotAClash() {
        AreaDto created = subRegion("Swaledale", dales, "Teesdale");

        AreaDto renamed = areaService.update(created.id(), new AreaRequest("Swaledale Dale Country",
                List.of("Teesdale", "Swaledale")));

        assertThat(renamed.aliases()).containsExactly("Swaledale", "Teesdale");
    }

    // --- the persistence context after a native write ---

    @Test
    @DisplayName("a move followed by a read in the same transaction sees the move, with no reload in between")
    void move_thenRead_withoutReload() {
        AreaDto swale = subRegion("Swaledale", dales);
        AreaDto wensley = subRegion("Wensleydale", dales);
        LocationEntity force = location("High Force", dales);
        membership.assign(force, swale.id());
        assertThat(membership.membersByArea()).containsEntry(swale.id(), List.of(force.getId()));
        assertThat(membership.subRegionOf(force.getId())).contains(swale.id());

        membership.assign(force, wensley.id());

        assertThat(membership.membersByArea()).doesNotContainKey(swale.id())
                .containsEntry(wensley.id(), List.of(force.getId()));
        assertThat(membership.membersOf(swale.id())).isEmpty();
        assertThat(membership.subRegionOf(force.getId())).contains(wensley.id());
        assertThat(areaService.findAll()).filteredOn(a -> a.id().equals(wensley.id()))
                .singleElement().satisfies(a -> assertThat(a.locationIds()).containsExactly(force.getId()));

        membership.clear(force.getId());
        assertThat(membership.membersByArea()).isEmpty();
        assertThat(membership.reconcileRegion(force)).as("already cleared").isFalse();
    }

    // --- updating ---

    @Test
    @DisplayName("update keeps a sub-region's kind, region and members, and refuses to change the kind or region")
    void update_keepsAndRefusesToChangeKindAndRegion() {
        AreaDto created = subRegion("Swaledale", dales, "Teesdale");
        LocationEntity force = location("High Force", dales);
        membership.assign(force, created.id());
        reload();

        AreaDto renamed = areaService.update(created.id(), new AreaRequest("Swaledale", List.of("Teesdale", "Keld")));

        assertThat(renamed.kind()).isEqualTo(AreaKind.SUB_REGION);
        assertThat(renamed.regionName()).isEqualTo("The Yorkshire Dales");
        assertThat(renamed.locationIds()).containsExactly(force.getId());
        assertThat(renamed.aliases()).containsExactly("Keld", "Teesdale");
        assertThatThrownBy(() -> areaService.update(created.id(),
                new AreaRequest("Swaledale", List.of(), AreaKind.NAMED, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kind cannot be changed");
        assertThatThrownBy(() -> areaService.update(created.id(),
                new AreaRequest("Swaledale", List.of(), AreaKind.SUB_REGION, lakes.getId())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("region cannot be changed");
        assertThat(areaService.update(created.id(),
                new AreaRequest("Swaledale", List.of(), AreaKind.SUB_REGION, dales.getId())).regionId())
                .isEqualTo(dales.getId());
    }

    // --- setLocations ---

    @Test
    @DisplayName("setLocations refuses a sub-region (400) and leaves its members alone")
    void setLocations_refusesSubRegion() {
        AreaDto swale = subRegion("Swaledale", dales);
        LocationEntity force = location("High Force", dales);
        membership.assign(force, swale.id());
        reload();

        assertThatThrownBy(() -> areaService.setLocations(swale.id(), new AreaLocationsRequest(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sub-region")
                .hasMessageContaining("on the location");
        assertThat(membership.membersOf(swale.id())).containsExactly(force.getId());
    }

    @Test
    @DisplayName("setLocations still writes a named area's members")
    void setLocations_namedAreaUnchanged() {
        LocationEntity force = location("High Force", dales);
        AreaDto wall = areaService.create(new AreaRequest("Hadrian's Wall", List.of("Roman Wall")));

        AreaDto saved = areaService.setLocations(wall.id(), new AreaLocationsRequest(List.of(force.getId())));

        assertThat(saved.locationIds()).containsExactly(force.getId());
    }

    // --- deleting ---

    @Test
    @DisplayName("deleting a sub-region with members is a conflict that counts them; an empty one is deleted")
    void delete_subRegion() {
        AreaDto swale = subRegion("Swaledale", dales);
        LocationEntity force = location("High Force", dales);
        membership.assign(force, swale.id());
        reload();

        assertThatThrownBy(() -> areaService.delete(swale.id()))
                .isInstanceOf(AreaConflictException.class)
                .hasMessageContaining("still has 1 location:");
        assertThat(areaRepository.existsById(swale.id())).isTrue();

        membership.clear(force.getId());
        reload();
        areaService.delete(swale.id());

        assertThat(areaRepository.existsById(swale.id())).isFalse();
    }

    @Test
    @DisplayName("the conflict message pluralises")
    void delete_subRegion_pluralMessage() {
        AreaDto swale = subRegion("Swaledale", dales);
        membership.assign(location("High Force", dales), swale.id());
        membership.assign(location("Low Force", dales), swale.id());
        reload();

        assertThatThrownBy(() -> areaService.delete(swale.id()))
                .isInstanceOf(AreaConflictException.class).hasMessageContaining("still has 2 locations:");
    }

    @Test
    @DisplayName("deleting a named area is unchanged")
    void delete_namedArea() {
        AreaDto wall = areaService.create(new AreaRequest("Hadrian's Wall", List.of("Roman Wall")));

        areaService.delete(wall.id());

        assertThat(areaRepository.existsById(wall.id())).isFalse();
    }

    // --- membership ---

    @Test
    @DisplayName("assign puts a location in a sub-region, and moving it replaces the membership")
    void assign_movesTheLocation() {
        AreaDto swale = subRegion("Swaledale", dales);
        AreaDto wensley = subRegion("Wensleydale", dales);
        LocationEntity force = location("High Force", dales);

        membership.assign(force, swale.id());
        reload();
        assertThat(membership.subRegionOf(force.getId())).contains(swale.id());

        membership.assign(force.getId(), wensley.id());
        reload();
        assertThat(membership.subRegionOf(force.getId())).contains(wensley.id());
        assertThat(membership.membersOf(swale.id())).isEmpty();
        assertThat(membership.membersOf(wensley.id())).containsExactly(force.getId());
        assertThat(membership.countMembers(wensley.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("assigning the sub-region a location is already in changes nothing")
    void assign_sameSubRegion_isANoOp() {
        AreaDto swale = subRegion("Swaledale", dales);
        LocationEntity force = location("High Force", dales);
        membership.assign(force, swale.id());
        reload();

        membership.assign(force, swale.id());
        reload();

        assertThat(membership.membersOf(swale.id())).containsExactly(force.getId());
    }

    @Test
    @DisplayName("assign refuses a sub-region of another region, a named area, an unknown area and a location "
            + "with no region")
    void assign_refusals() {
        AreaDto lakeland = subRegion("Keswick & Borrowdale", lakes);
        AreaDto wall = areaService.create(new AreaRequest("Hadrian's Wall", List.of("Roman Wall")));
        AreaDto swale = subRegion("Swaledale", dales);
        LocationEntity force = location("High Force", dales);
        LocationEntity lost = location("Nowhere", null);

        assertThatThrownBy(() -> membership.assign(force, lakeland.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Keswick & Borrowdale")
                .hasMessageContaining("The Lake District")
                .hasMessageContaining("The Yorkshire Dales");
        assertThatThrownBy(() -> membership.assign(force, wall.id()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is not a sub-region");
        assertThatThrownBy(() -> membership.assign(force, 9_999L))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> membership.assign(lost, swale.id()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a region");
        assertThatThrownBy(() -> membership.assign(9_999L, swale.id()))
                .isInstanceOf(NoSuchElementException.class).hasMessageContaining("No location");
        assertThat(membership.membersByArea()).isEmpty();
    }

    @Test
    @DisplayName("subRegionsByLocation labels every member with its sub-region's id and name, in one read")
    void subRegionsByLocation_labelsEveryMember() {
        AreaDto swale = subRegion("Swaledale", dales);
        AreaDto lakeland = subRegion("Keswick & Borrowdale", lakes);
        LocationEntity forceFall = location("High Force", dales);
        LocationEntity catBells = location("Cat Bells", lakes);
        location("Unplaced", dales);
        membership.assign(forceFall, swale.id());
        membership.assign(catBells, lakeland.id());
        reload();

        assertThat(membership.subRegionsByLocation()).containsOnlyKeys(forceFall.getId(), catBells.getId());
        assertThat(membership.subRegionsByLocation().get(forceFall.getId()).areaId()).isEqualTo(swale.id());
        assertThat(membership.subRegionsByLocation().get(forceFall.getId()).areaName()).isEqualTo("Swaledale");
        assertThat(membership.subRegionsByLocation().get(catBells.getId()).areaName())
                .isEqualTo("Keswick & Borrowdale");
    }

    @Test
    @DisplayName("membersByArea groups every sub-region's members, ascending")
    void membersByArea_groupsAndSorts() {
        AreaDto swale = subRegion("Swaledale", dales);
        AreaDto lakeland = subRegion("Keswick & Borrowdale", lakes);
        LocationEntity first = location("High Force", dales);
        LocationEntity second = location("Low Force", dales);
        LocationEntity third = location("Cat Bells", lakes);
        membership.assign(second, swale.id());
        membership.assign(first, swale.id());
        membership.assign(third, lakeland.id());
        reload();

        assertThat(membership.membersByArea()).containsOnlyKeys(swale.id(), lakeland.id())
                .containsEntry(swale.id(), List.of(first.getId(), second.getId()))
                .containsEntry(lakeland.id(), List.of(third.getId()));
        assertThat(areaService.findAll()).filteredOn(a -> a.kind() == AreaKind.SUB_REGION)
                .extracting(AreaDto::name, a -> a.locationIds().size())
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("Swaledale", 2),
                        org.assertj.core.groups.Tuple.tuple("Keswick & Borrowdale", 1));
    }

    @Test
    @DisplayName("a region change drops a membership that no longer fits and keeps one that still does")
    void reconcileRegion_dropsOnlyAMismatch() {
        AreaDto swale = subRegion("Swaledale", dales);
        LocationEntity force = location("High Force", dales);
        membership.assign(force, swale.id());
        reload();

        LocationEntity stillDales = entityManager.find(LocationEntity.class, force.getId());
        assertThat(membership.reconcileRegion(stillDales)).isFalse();
        assertThat(membership.subRegionOf(force.getId())).contains(swale.id());

        LocationEntity moved = entityManager.find(LocationEntity.class, force.getId());
        moved.setRegion(entityManager.find(RegionEntity.class, lakes.getId()));
        assertThat(membership.reconcileRegion(moved)).isTrue();
        reload();
        assertThat(membership.subRegionOf(force.getId())).isEmpty();
        assertThat(membership.reconcileRegion(moved)).as("nothing left to drop").isFalse();
    }

    @Test
    @DisplayName("a location that loses its region loses its membership")
    void reconcileRegion_noRegionDrops() {
        AreaDto swale = subRegion("Swaledale", dales);
        LocationEntity force = location("High Force", dales);
        membership.assign(force, swale.id());
        reload();

        LocationEntity unregioned = entityManager.find(LocationEntity.class, force.getId());
        unregioned.setRegion(null);

        assertThat(membership.reconcileRegion(unregioned)).isTrue();
    }

    @Test
    @DisplayName("uncovered lists enabled, regioned locations with no sub-region, not disabled or unregioned ones")
    void findUncovered_countsOnlyEnabledRegionedLocations() {
        AreaDto swale = subRegion("Swaledale", dales);
        LocationEntity covered = location("High Force", dales);
        LocationEntity uncovered = location("Low Force", dales);
        location("Nowhere", null);
        LocationEntity disabled = location("Old Quarry", lakes);
        membership.assign(covered, swale.id());
        entityManager.getEntityManager().createNativeQuery("UPDATE locations SET enabled = FALSE WHERE id = :id")
                .setParameter("id", disabled.getId()).executeUpdate();
        reload();

        List<UncoveredLocationDto> found = areaService.findUncoveredLocations();

        assertThat(found).containsExactly(
                new UncoveredLocationDto(uncovered.getId(), "Low Force", dales.getId(), "The Yorkshire Dales"));
    }

    @Test
    @DisplayName("clearing a membership that does not exist is harmless")
    void clear_missing_isHarmless() {
        membership.clear(9_999L);

        assertThat(membership.membersByArea()).isEmpty();
    }
    // --- readers' picks (V192) ---

    private Long reader(String name) {
        AppUserEntity user = AppUserEntity.builder().username(name).password("hash").role(UserRole.LITE_USER)
                .enabled(true).createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build();
        Long id = entityManager.persistAndGetId(user, Long.class);
        entityManager.flush();
        return id;
    }

    @Test
    @DisplayName("deleting a sub-region removes the readers' picks of it and clears THOSE readers' change "
            + "locks, leaving other readers' picks and locks alone")
    void deleteSubRegion_clearsPickersLocksAndPicks() {
        AreaDto doomed = subRegion("Doomed Dale", dales);
        AreaDto kept = subRegion("Kept Dale", dales);
        Long picker = reader("picker");
        Long bystander = reader("bystander");
        jdbc.update("UPDATE app_user SET sub_regions_changed_at = ?", LocalDateTime.of(2026, 10, 20, 10, 0));
        jdbc.update("INSERT INTO app_user_sub_region (user_id, area_id, created_at) VALUES (?, ?, now())",
                picker, doomed.id());
        jdbc.update("INSERT INTO app_user_sub_region (user_id, area_id, created_at) VALUES (?, ?, now())",
                picker, kept.id());
        jdbc.update("INSERT INTO app_user_sub_region (user_id, area_id, created_at) VALUES (?, ?, now())",
                bystander, kept.id());
        reload();

        areaService.delete(doomed.id());

        assertThat(jdbc.queryForList("SELECT area_id FROM app_user_sub_region WHERE user_id = ?", Long.class,
                picker)).containsExactly(kept.id());
        assertThat(jdbc.queryForList("SELECT area_id FROM app_user_sub_region WHERE user_id = ?", Long.class,
                bystander)).containsExactly(kept.id());
        assertThat(jdbc.queryForObject("SELECT sub_regions_changed_at FROM app_user WHERE id = ?",
                LocalDateTime.class, picker)).as("the picker's re-pick is free").isNull();
        assertThat(jdbc.queryForObject("SELECT sub_regions_changed_at FROM app_user WHERE id = ?",
                LocalDateTime.class, bystander)).isEqualTo(LocalDateTime.of(2026, 10, 20, 10, 0));
    }

    @Test
    @DisplayName("a refused delete (the sub-region still has members) leaves picks and locks as they were")
    void deleteSubRegionWithMembers_isRefusedBeforeTouchingPicks() {
        AreaDto occupied = subRegion("Occupied Dale", dales);
        membership.assign(location("Some Force", dales), occupied.id());
        Long picker = reader("holder");
        jdbc.update("UPDATE app_user SET sub_regions_changed_at = ?", LocalDateTime.of(2026, 10, 20, 10, 0));
        jdbc.update("INSERT INTO app_user_sub_region (user_id, area_id, created_at) VALUES (?, ?, now())",
                picker, occupied.id());
        reload();

        assertThatThrownBy(() -> areaService.delete(occupied.id())).isInstanceOf(AreaConflictException.class);

        assertThat(jdbc.queryForList("SELECT area_id FROM app_user_sub_region WHERE user_id = ?", Long.class,
                picker)).containsExactly(occupied.id());
        assertThat(jdbc.queryForObject("SELECT sub_regions_changed_at FROM app_user WHERE id = ?",
                LocalDateTime.class, picker)).isEqualTo(LocalDateTime.of(2026, 10, 20, 10, 0));
    }
}
