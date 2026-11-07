package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.model.SubRegionLocationRow;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PickScope}: the unrestricted and nothing singletons, building from picked rows,
 * and the id and name gates including null arguments.
 */
class PickScopeTest {

    private static final List<SubRegionLocationRow> ROWS = List.of(
            new SubRegionLocationRow(100L, 9L, "Craster", 55.4, -1.6),
            new SubRegionLocationRow(100L, 7L, "Bamburgh Castle", 55.6, -1.7));

    @Test
    @DisplayName("unrestricted allows everything, null ids and names included")
    void unrestrictedAllowsEverything() {
        PickScope scope = PickScope.unrestricted();

        assertThat(scope.shaped()).isFalse();
        assertThat(scope.allowsId(42L)).isTrue();
        assertThat(scope.allowsId(null)).isTrue();
        assertThat(scope.allowsName("Anywhere")).isTrue();
        assertThat(scope.allowsName(null)).isTrue();
    }

    @Test
    @DisplayName("nothing is shaped and allows no id or name")
    void nothingAllowsNothing() {
        PickScope scope = PickScope.nothing();

        assertThat(scope.shaped()).isTrue();
        assertThat(scope.allowsId(7L)).isFalse();
        assertThat(scope.allowsName("Craster")).isFalse();
        assertThat(scope.sortedIds()).isEmpty();
    }

    @Test
    @DisplayName("of(null) and of(empty) are the nothing scope")
    void ofNullOrEmptyIsNothing() {
        assertThat(PickScope.of(null)).isSameAs(PickScope.nothing());
        assertThat(PickScope.of(List.of())).isSameAs(PickScope.nothing());
    }

    @Test
    @DisplayName("of(rows) allows exactly the picked ids and names, never a null, and sorts the ids")
    void ofRowsAllowsOnlyPicked() {
        PickScope scope = PickScope.of(ROWS);

        assertThat(scope.shaped()).isTrue();
        assertThat(scope.locationIds()).isEqualTo(Set.of(7L, 9L));
        assertThat(scope.locationNames()).isEqualTo(Set.of("Craster", "Bamburgh Castle"));
        assertThat(scope.allowsId(7L)).isTrue();
        assertThat(scope.allowsId(8L)).isFalse();
        assertThat(scope.allowsId(null)).isFalse();
        assertThat(scope.allowsName("Craster")).isTrue();
        assertThat(scope.allowsName("Seahouses")).isFalse();
        assertThat(scope.allowsName(null)).isFalse();
        assertThat(scope.sortedIds()).containsExactly(7L, 9L);
    }
}
