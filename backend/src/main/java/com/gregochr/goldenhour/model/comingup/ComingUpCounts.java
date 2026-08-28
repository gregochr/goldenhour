package com.gregochr.goldenhour.model.comingup;

import java.util.Map;

/**
 * Served counts (plan §13) — the footer copy and the filter chips both read this rather than each
 * counting a filterable list of entries themselves, so the two never disagree.
 *
 * <p>Null on {@link ComingUpResponse} until P2.
 *
 * @param fixed    entries whose date cannot move
 * @param forecast entries driven by a forecast peak
 * @param byFamily entry count per topic family
 */
public record ComingUpCounts(int fixed, int forecast, Map<String, Integer> byFamily) {

    /**
     * Normalises {@code byFamily} so a consumer never has to null-check it.
     */
    public ComingUpCounts {
        byFamily = byFamily == null ? Map.of() : Map.copyOf(byFamily);
    }
}
