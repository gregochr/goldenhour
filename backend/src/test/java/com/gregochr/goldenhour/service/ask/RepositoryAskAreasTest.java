package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.LocationAliasEntity;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationAliasRepository;
import com.gregochr.goldenhour.service.SubRegionMembershipService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RepositoryAskAreas}: entities are mapped into the snapshot's immutable records with aliases in the one
 * deterministic order, and a failure reads as no names. Also the small value types around it.
 */
class RepositoryAskAreasTest {

    private final AreaRepository areaRepository = mock(AreaRepository.class);
    private final LocationAliasRepository aliasRepository = mock(LocationAliasRepository.class);
    private final SubRegionMembershipService subRegionMembership = mock(SubRegionMembershipService.class);
    private final RepositoryAskAreas areas = new RepositoryAskAreas(areaRepository, aliasRepository,
            subRegionMembership);

    private static AreaEntity area(String name, Set<String> aliases, Set<Long> locations) {
        AreaEntity entity = new AreaEntity();
        entity.setName(name);
        entity.setAliases(new HashSet<>(aliases));
        entity.setLocationIds(new HashSet<>(locations));
        return entity;
    }

    @Test
    @DisplayName("areas and aliases are mapped, with aliases fewest-characters-first then alphabetical")
    void mapsEntitiesDeterministically() {
        when(areaRepository.findAll()).thenReturn(List.of(
                area("Kielder", Set.of(), Set.of(9L)),
                area("Hadrian's Wall", Set.of("Roman Wall", "Hadrian Wall", "Wall Path"), Set.of(1L, 2L))));
        when(aliasRepository.findAll()).thenReturn(List.of(
                new LocationAliasEntity(5L, "Heugh Lighthouse"), new LocationAliasEntity(5L, "The Heugh"),
                new LocationAliasEntity(5L, "Heugh Battery"), new LocationAliasEntity(6L, "Nab")));

        AskAreas.Names names = areas.load();

        assertThat(names.areas()).extracting(AskSnapshot.Area::name).containsExactly("Hadrian's Wall", "Kielder");
        assertThat(names.areas().getFirst().aliases()).containsExactly("Wall Path", "Roman Wall", "Hadrian Wall");
        assertThat(names.areas().getFirst().locationIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(names.locationAliases().get(5L)).containsExactly("The Heugh", "Heugh Battery",
                "Heugh Lighthouse");
        assertThat(names.locationAliases().get(6L)).containsExactly("Nab");
    }

    @Test
    @DisplayName("a sub-region's members come from location_sub_region, not area_location, and a named area's "
            + "stay as they were")
    void subRegionMembersComeFromTheMembershipTable() {
        AreaEntity swaledale = area("Swaledale", Set.of("Teesdale"), Set.of(99L));
        swaledale.setId(11L);
        swaledale.setKind(AreaKind.SUB_REGION);
        AreaEntity empty = area("Nidderdale", Set.of(), Set.of());
        empty.setId(12L);
        empty.setKind(AreaKind.SUB_REGION);
        AreaEntity wall = area("Hadrian's Wall", Set.of("Roman Wall"), Set.of(1L, 2L));
        wall.setId(13L);
        when(areaRepository.findAll()).thenReturn(List.of(swaledale, empty, wall));
        when(subRegionMembership.membersByArea()).thenReturn(Map.of(11L, List.of(5L, 6L)));

        AskAreas.Names names = areas.load();

        assertThat(names.areas()).extracting(AskSnapshot.Area::name)
                .containsExactly("Hadrian's Wall", "Nidderdale", "Swaledale");
        assertThat(names.areas().get(0).locationIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(names.areas().get(1).locationIds()).isEmpty();
        assertThat(names.areas().get(2).locationIds()).as("not the stale area_location row 99")
                .containsExactlyInAnyOrder(5L, 6L);
        assertThat(names.areas().get(2).aliases()).containsExactly("Teesdale");
    }

    @Test
    @DisplayName("any failure is read as no names, never thrown")
    void failureIsNone() {
        when(areaRepository.findAll()).thenThrow(new IllegalStateException("no database"));

        assertThat(areas.load()).isSameAs(AskAreas.Names.NONE);
    }

    @Test
    @DisplayName("Names and Area copy their collections, and null reads as empty")
    void valueTypesAreImmutableAndNullSafe() {
        AskAreas.Names nothing = new AskAreas.Names(null, null);
        AskSnapshot.Area bare = new AskSnapshot.Area("X", null, null);

        assertThat(nothing.areas()).isEmpty();
        assertThat(nothing.locationAliases()).isEmpty();
        assertThat(bare.aliases()).isEmpty();
        assertThat(bare.locationIds()).isEmpty();
        assertThat(AskAreas.sortAliases(List.of("b b", "A", "a", "ccc"))).containsExactly("A", "a", "b b", "ccc");
    }

    @Test
    @DisplayName("a snapshot copies its aliases, reads null as none, and withNames changes only the names")
    void snapshotCarriesTheNames() {
        AskSnapshot base = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()));
        Map<Long, List<String>> aliases = new java.util.HashMap<>();
        aliases.put(1L, List.of("A"));
        aliases.put(2L, null);

        AskSnapshot named = base.withNames(null, aliases);

        assertThat(base.areas()).isEmpty();
        assertThat(base.locationAliases()).isEmpty();
        assertThat(named.areas()).isEmpty();
        assertThat(named.locationAliases()).containsEntry(1L, List.of("A")).containsEntry(2L, List.of());
        assertThat(named.today()).isEqualTo(base.today());
    }
}
