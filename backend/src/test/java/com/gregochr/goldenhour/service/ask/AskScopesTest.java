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
}
