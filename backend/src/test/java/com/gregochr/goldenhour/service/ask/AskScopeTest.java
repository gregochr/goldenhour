package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link AskScope}: the one case-insensitive region comparison and the two scope keys. */
class AskScopeTest {

    private static final AskScope TEESDALE = AskScope.of(List.of(5L), Set.of("Teesdale"));
    private static final AskScope TWO = AskScope.of(List.of(7L, 3L), Set.of("Teesdale", "Northumberland"));

    @Test
    @DisplayName("contains matches a region name case-insensitively, and only the scope's own regions")
    void containsIsCaseInsensitive() {
        assertThat(TEESDALE.contains("Teesdale")).isTrue();
        assertThat(TEESDALE.contains("TEESDALE")).isTrue();
        assertThat(TEESDALE.contains("teesdale")).isTrue();
        assertThat(TEESDALE.contains("Northumberland")).isFalse();
        assertThat(TWO.contains("northumberland")).isTrue();
        assertThat(TWO.contains("Teesdale")).isTrue();
        assertThat(TWO.contains("Elsewhere")).isFalse();
    }

    @Test
    @DisplayName("a named scope contains no null region; the open scope contains everything, null included")
    void nullRegion() {
        assertThat(TEESDALE.contains(null)).isFalse();
        assertThat(AskScope.ALL.contains(null)).isTrue();
        assertThat(AskScope.ALL.contains("Anywhere")).isTrue();
        assertThat(AskScope.ALL.isEverywhere()).isTrue();
        assertThat(TEESDALE.isEverywhere()).isFalse();
    }

    @Test
    @DisplayName("the key is ALL, or the sorted region ids joined; the ids keep the order asked")
    void keys() {
        assertThat(AskScope.ALL.key()).isEqualTo("ALL").isEqualTo(AskScope.ALL_KEY);
        assertThat(AskScope.of(List.of(), Set.of()).key()).isEqualTo("ALL");
        assertThat(TEESDALE.key()).isEqualTo("5");
        assertThat(TWO.key()).isEqualTo("3,7");
        assertThat(TWO.regionIds()).containsExactly(7L, 3L);
    }

    @Test
    @DisplayName("the names are kept as stored and sorted, for the prompt to print")
    void namesAreSorted() {
        assertThat(TWO.names()).containsExactly("Northumberland", "Teesdale");
    }

    @Test
    @DisplayName("the Ready scope is the region itself for one region, and ALL for none or for several")
    void readyScope() {
        assertThat(TEESDALE.readyScope()).isSameAs(TEESDALE);
        assertThat(TWO.readyScope()).isSameAs(AskScope.ALL);
        assertThat(AskScope.ALL.readyScope()).isSameAs(AskScope.ALL);
    }

    @Test
    @DisplayName("scopes are equal when they name the same regions")
    void value() {
        assertThat(AskScope.of(List.of(5L), Set.of("Teesdale"))).isEqualTo(TEESDALE)
                .hasSameHashCodeAs(TEESDALE).isNotEqualTo(AskScope.ALL).isNotEqualTo(TWO);
        assertThat(TEESDALE).hasToString("AskScope[5 [Teesdale]]");
        assertThat(AskScope.ALL).hasToString("AskScope[ALL]");
    }

    @Test
    @DisplayName("a scope is immutable; no ids is the open scope however it is asked for")
    void immutable() {
        assertThatThrownBy(() -> TEESDALE.names().add("Other")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> TEESDALE.regionIds().add(1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(AskScope.of(null, null)).isSameAs(AskScope.ALL);
        assertThat(AskScope.of(List.of(5L), null).names()).isEmpty();
    }
}
