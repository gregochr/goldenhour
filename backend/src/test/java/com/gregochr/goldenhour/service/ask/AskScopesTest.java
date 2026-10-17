package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.repository.RegionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AskScopes}: region ids become an {@link AskScope} in one repository read, and
 * an id that does not resolve, or is disabled, fails the scope rather than widening it.
 */
class AskScopesTest {

    private final RegionRepository regions = mock(RegionRepository.class);

    private static RegionEntity region(long id, String name) {
        return RegionEntity.builder().id(id).name(name).enabled(true).build();
    }

    private static List<Long> ids(int count) {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= count; i++) {
            ids.add(i);
        }
        return ids;
    }

    @Test
    @DisplayName("no ids means every region: the open scope, and the repository is not asked")
    void noIdsIsAllRegions() {
        assertThat(AskScopes.resolve(regions, List.of())).contains(AskScope.ALL);
        assertThat(AskScopes.resolve(regions, null)).contains(AskScope.ALL);
        assertThat(AskScope.ALL.isEverywhere()).isTrue();
        verifyNoInteractions(regions);
    }

    @Test
    @DisplayName("ids resolve to a scope: distinct ids in the order given, the sorted key, the sorted names, "
            + "in one repository read")
    void idsResolveToAScope() {
        when(regions.findAllById(Set.of(2L, 1L))).thenReturn(List.of(region(2L, "Teesdale"), region(1L, "Coast")));

        Optional<AskScope> scope = AskScopes.resolve(regions, List.of(2L, 1L, 2L));

        assertThat(scope).isPresent();
        assertThat(scope.get().regionIds()).containsExactly(2L, 1L);
        assertThat(scope.get().key()).isEqualTo("1,2");
        assertThat(scope.get().names()).containsExactly("Coast", "Teesdale");
        assertThat(scope.get().isEverywhere()).isFalse();
        verify(regions, times(1)).findAllById(Set.of(1L, 2L));
    }

    @Test
    @DisplayName("one id that does not resolve fails the whole scope: it is never dropped, which would widen it")
    void anUnknownIdFailsTheScope() {
        when(regions.findAllById(Set.of(1L, 99L))).thenReturn(List.of(region(1L, "Coast")));

        assertThat(AskScopes.resolve(regions, List.of(1L, 99L))).isEmpty();
    }

    @Test
    @DisplayName("an id the repository finds nothing for fails the scope")
    void nothingFound() {
        when(regions.findAllById(Set.of(5L))).thenReturn(List.of());

        assertThat(AskScopes.resolve(regions, List.of(5L))).isEmpty();
    }

    @Test
    @DisplayName("a repository that answers with the wrong regions fails the scope, though the count agrees")
    void aWrongRegionWithTheRightCount() {
        when(regions.findAllById(Set.of(5L))).thenReturn(List.of(region(6L, "Elsewhere")));

        assertThat(AskScopes.resolve(regions, List.of(5L))).isEmpty();
    }

    @Test
    @DisplayName("the count boundary: 20 ids are accepted, 21 are refused before the repository is asked")
    void countBoundary() {
        List<RegionEntity> twenty = new ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            twenty.add(region(i, "R" + i));
        }
        when(regions.findAllById(new LinkedHashSet<>(ids(20)))).thenReturn(twenty);

        assertThat(AskScopes.resolve(regions, ids(20))).isPresent();
        assertThat(AskScopes.resolve(regions, ids(21))).isEmpty();
        verify(regions, times(1)).findAllById(new LinkedHashSet<>(ids(20)));
    }

    @Test
    @DisplayName("a null id is refused, in a mutable list and in an immutable one")
    void nullId() {
        List<Long> mutable = new ArrayList<>();
        mutable.add(1L);
        mutable.add(null);

        assertThat(AskScopes.resolve(regions, mutable)).isEmpty();
        assertThat(AskScopes.resolve(regions, Arrays.asList(1L, null))).isEmpty();
    }

    @Test
    @DisplayName("an unknown or a disabled region refuses the whole request")
    void unknownOrDisabled() {
        when(regions.findAllById(Set.of(1L, 99L))).thenReturn(List.of(region(1L, "A")));
        when(regions.findAllById(Set.of(1L, 8L))).thenReturn(List.of(region(1L, "A"),
                RegionEntity.builder().id(8L).name("Retired").enabled(false).build()));

        assertThat(AskScopes.resolve(regions, List.of(1L, 99L))).isEmpty();
        assertThat(AskScopes.resolve(regions, List.of(1L, 8L))).isEmpty();
    }

    @Test
    @DisplayName("the scope parameter: 'all' in any case with whitespace is the open scope and asks nothing; a "
            + "number is an enabled region; anything else is empty")
    void fromParameter() {
        when(regions.findAllById(Set.of(3L))).thenReturn(List.of(region(3L, "Northumberland")));
        when(regions.findAllById(Set.of(4L))).thenReturn(List.of(
                RegionEntity.builder().id(4L).name("Retired").enabled(false).build()));

        assertThat(AskScopes.fromParameter(regions, " ALL ")).contains(AskScope.ALL);
        verifyNoInteractions(regions);
        assertThat(AskScopes.fromParameter(regions, "3")).isPresent();
        assertThat(AskScopes.fromParameter(regions, " 3 ")).isPresent();
        assertThat(AskScopes.fromParameter(regions, "4")).isEmpty();
        assertThat(AskScopes.fromParameter(regions, "northumberland")).isEmpty();
        assertThat(AskScopes.fromParameter(regions, "")).isEmpty();
    }
}
