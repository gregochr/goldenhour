package com.gregochr.goldenhour.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RegionGroupingUtils}.
 */
class RegionGroupingUtilsTest {

    @Test
    @DisplayName("Groups items by region key")
    void groupsByRegion() {
        List<String> items = List.of("Bamburgh", "Seahouses", "Keswick", "Durham");
        Map<String, String> regionMap = Map.of(
                "Bamburgh", "Northumberland",
                "Seahouses", "Northumberland",
                "Keswick", "Lake District");

        RegionGroupingUtils.GroupResult<String> result =
                RegionGroupingUtils.groupByRegion(items, regionMap::get);

        assertThat(result.grouped()).hasSize(2);
        assertThat(result.grouped().get("Northumberland"))
                .containsExactly("Bamburgh", "Seahouses");
        assertThat(result.grouped().get("Lake District"))
                .containsExactly("Keswick");
        assertThat(result.unregioned()).containsExactly("Durham");
    }

    @Test
    @DisplayName("All items unregioned when key function returns null")
    void allUnregioned() {
        List<String> items = List.of("A", "B");

        RegionGroupingUtils.GroupResult<String> result =
                RegionGroupingUtils.groupByRegion(items, s -> null);

        assertThat(result.grouped()).isEmpty();
        assertThat(result.unregioned()).containsExactly("A", "B");
    }

    @Test
    @DisplayName("Empty input returns empty result")
    void emptyInput() {
        RegionGroupingUtils.GroupResult<String> result =
                RegionGroupingUtils.groupByRegion(List.of(), s -> "Region");

        assertThat(result.grouped()).isEmpty();
        assertThat(result.unregioned()).isEmpty();
    }

    @Test
    @DisplayName("Preserves insertion order of regions")
    void preservesInsertionOrder() {
        List<String> items = List.of("C", "A", "B");
        Map<String, String> regionMap = Map.of(
                "C", "Zulu", "A", "Alpha", "B", "Alpha");

        RegionGroupingUtils.GroupResult<String> result =
                RegionGroupingUtils.groupByRegion(items, regionMap::get);

        List<String> regionOrder = List.copyOf(result.grouped().keySet());
        assertThat(regionOrder).containsExactly("Zulu", "Alpha");
    }
}
