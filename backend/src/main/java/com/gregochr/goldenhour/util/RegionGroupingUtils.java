package com.gregochr.goldenhour.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Utility for grouping items by region name, preserving insertion order.
 */
public final class RegionGroupingUtils {

    private RegionGroupingUtils() {
    }

    /**
     * Groups items into a region-keyed map using the provided key extractor.
     * Items whose key is null are collected separately.
     *
     * @param items        the items to group
     * @param regionKeyFn function extracting the region name (may return null)
     * @param <T>          the item type
     * @return result containing the grouped map and unregioned items
     */
    public static <T> GroupResult<T> groupByRegion(List<T> items,
            Function<T, String> regionKeyFn) {
        Map<String, List<T>> grouped = new LinkedHashMap<>();
        List<T> unregioned = new ArrayList<>();
        for (T item : items) {
            String region = regionKeyFn.apply(item);
            if (region != null) {
                grouped.computeIfAbsent(region, k -> new ArrayList<>()).add(item);
            } else {
                unregioned.add(item);
            }
        }
        return new GroupResult<>(grouped, unregioned);
    }

    /**
     * Result of region grouping.
     *
     * @param grouped    items keyed by region name (insertion-ordered)
     * @param unregioned items with no region assignment
     * @param <T>        the item type
     */
    public record GroupResult<T>(Map<String, List<T>> grouped, List<T> unregioned) {

        /** Defensive copy preserving insertion order. */
        public GroupResult {
            grouped = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(grouped));
            unregioned = List.copyOf(unregioned);
        }
    }
}
