package com.gregochr.goldenhour.service.ask;

import java.util.List;

/**
 * A typed question after sanitising (plan §2.5 step 2).
 *
 * @param sanitised  the trimmed, whitespace-collapsed text Claude receives
 * @param normalised the lower-cased, filler-free form used as the cache key
 * @param windowId   the context window, or null
 * @param regionIds  the regions asked about; empty means all
 * @param view       {@code map}, {@code plan} or {@code coming-up}
 */
public record AskQuestion(String sanitised, String normalised, String windowId,
        List<Long> regionIds, String view) {

    /** Canonical constructor: takes an immutable copy of {@code regionIds}; null reads as empty. */
    public AskQuestion {
        regionIds = regionIds == null ? List.of() : List.copyOf(regionIds);
    }
}
