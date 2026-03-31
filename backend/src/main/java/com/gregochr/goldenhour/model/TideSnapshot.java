package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideStatisticalSize;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Tide state snapshot for a solar event at a coastal location.
 *
 * <p>Combines basic tide state, lunar (astronomical) classification, and statistical
 * size classification. The lunar and statistical dimensions are independent — a tide
 * can be a King Tide (lunar perigee + new/full moon) and also Extra Extra High
 * (top 5% historically).
 *
 * @param tideState                 current tide state at the solar event time
 * @param nextHighTideTime          UTC time of next high tide, or null
 * @param nextHighTideHeightMetres  height of next high tide in metres, or null
 * @param nextLowTideTime           UTC time of next low tide, or null
 * @param nextLowTideHeightMetres   height of next low tide in metres, or null
 * @param tideAligned               true if tide state matches location preference, or null for inland
 * @param nearestHighTideTime       UTC time of the high tide extreme nearest to the event
 *                                  within ±12 hours, or null if none found
 * @param nearestLowTideTime        UTC time of the low tide extreme nearest to the event
 *                                  within ±12 hours, or null if none found
 * @param lunarTideType             astronomical tide classification (King/Spring/Regular), or null
 * @param lunarPhase                human-readable moon phase name (e.g. "New Moon"), or null
 * @param moonAtPerigee             true if the Moon is near perigee, or null for inland
 * @param statisticalSize           empirical size classification based on historical data, or null
 */
public record TideSnapshot(
        TideState tideState,
        LocalDateTime nextHighTideTime,
        BigDecimal nextHighTideHeightMetres,
        LocalDateTime nextLowTideTime,
        BigDecimal nextLowTideHeightMetres,
        Boolean tideAligned,
        LocalDateTime nearestHighTideTime,
        LocalDateTime nearestLowTideTime,
        LunarTideType lunarTideType,
        String lunarPhase,
        Boolean moonAtPerigee,
        TideStatisticalSize statisticalSize) {
}
