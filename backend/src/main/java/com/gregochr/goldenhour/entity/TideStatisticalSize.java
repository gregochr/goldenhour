package com.gregochr.goldenhour.entity;

/**
 * Statistical classification of a high tide based on historical data for the location.
 *
 * <p>Independent of {@link LunarTideType} (astronomical). A tide can be both a King Tide
 * (lunar) and Extra Extra High (statistical) — the combination indicates an exceptionally
 * rare and dramatic water level.
 */
public enum TideStatisticalSize {

    /** Height exceeds the 95th percentile of all recorded high tides at this location. */
    EXTRA_EXTRA_HIGH,

    /** Height exceeds 125% of the average high tide at this location. */
    EXTRA_HIGH
}
