package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.repository.RegionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AskScopes}: ids to names, and an id that does not resolve fails the scope. */
class AskScopesTest {

    private final RegionRepository regions = mock(RegionRepository.class);

    private static AskQuestion question(List<Long> ids) {
        return new AskQuestion("q", "q", null, ids, "plan");
    }

    private static RegionEntity region(long id, String name) {
        return RegionEntity.builder().id(id).name(name).enabled(true).build();
    }

    @Test
    @DisplayName("no ids means every region: an empty scope, and the repository is not asked")
    void noIdsIsAllRegions() {
        assertThat(AskScopes.resolve(regions, question(List.of()))).contains(Set.of());
        verifyNoInteractions(regions);
    }

    @Test
    @DisplayName("ids resolve to their region names, sorted, duplicates counted once")
    void idsResolveToNames() {
        when(regions.findAllById(Set.of(2L, 1L))).thenReturn(List.of(region(2L, "Teesdale"), region(1L, "Coast")));

        Optional<Set<String>> scope = AskScopes.resolve(regions, question(List.of(2L, 1L, 2L)));

        assertThat(scope).isPresent();
        assertThat(scope.get()).containsExactly("Coast", "Teesdale");
    }

    @Test
    @DisplayName("one id that does not resolve fails the whole scope: it is never dropped, which would widen it")
    void anUnknownIdFailsTheScope() {
        when(regions.findAllById(Set.of(1L, 99L))).thenReturn(List.of(region(1L, "Coast")));

        assertThat(AskScopes.resolve(regions, question(List.of(1L, 99L)))).isEmpty();
    }

    @Test
    @DisplayName("an id the repository finds nothing for fails the scope")
    void nothingFound() {
        when(regions.findAllById(Set.of(5L))).thenReturn(List.of());

        assertThat(AskScopes.resolve(regions, question(List.of(5L)))).isEmpty();
    }

    // -- validRegionIds: the one rule the typed endpoint and the admin dry-run share -------------

    private static List<Long> ids(int count) {
        List<Long> ids = new java.util.ArrayList<>();
        for (long i = 1; i <= count; i++) {
            ids.add(i);
        }
        return ids;
    }

    @Test
    @DisplayName("null or empty ids mean every region and the repository is not asked")
    void validNoIds() {
        assertThat(AskScopes.validRegionIds(regions, null)).contains(List.of());
        assertThat(AskScopes.validRegionIds(regions, List.of())).contains(List.of());
        verifyNoInteractions(regions);
    }

    @Test
    @DisplayName("enabled ids are returned distinct, in the order given")
    void validDistinctInOrder() {
        when(regions.findAllById(Set.of(2L, 1L))).thenReturn(List.of(region(1L, "A"), region(2L, "B")));

        assertThat(AskScopes.validRegionIds(regions, List.of(2L, 1L, 2L))).contains(List.of(2L, 1L));
    }

    @Test
    @DisplayName("the count boundary: 20 ids are accepted, 21 are refused before the repository is asked")
    void validCountBoundary() {
        List<RegionEntity> twenty = new java.util.ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            twenty.add(region(i, "R" + i));
        }
        when(regions.findAllById(new java.util.LinkedHashSet<>(ids(20)))).thenReturn(twenty);

        assertThat(AskScopes.validRegionIds(regions, ids(20))).isPresent();
        assertThat(AskScopes.validRegionIds(regions, ids(21))).isEmpty();
    }

    @Test
    @DisplayName("a null id is refused, in a mutable list and in an immutable one")
    void validNullId() {
        List<Long> mutable = new java.util.ArrayList<>();
        mutable.add(1L);
        mutable.add(null);

        assertThat(AskScopes.validRegionIds(regions, mutable)).isEmpty();
        assertThat(AskScopes.validRegionIds(regions, java.util.Arrays.asList(1L, null))).isEmpty();
    }

    @Test
    @DisplayName("an unknown or a disabled region refuses the whole request")
    void validUnknownOrDisabled() {
        when(regions.findAllById(Set.of(1L, 99L))).thenReturn(List.of(region(1L, "A")));
        when(regions.findAllById(Set.of(1L, 8L))).thenReturn(List.of(region(1L, "A"),
                RegionEntity.builder().id(8L).name("Retired").enabled(false).build()));

        assertThat(AskScopes.validRegionIds(regions, List.of(1L, 99L))).isEmpty();
        assertThat(AskScopes.validRegionIds(regions, List.of(1L, 8L))).isEmpty();
    }
}
