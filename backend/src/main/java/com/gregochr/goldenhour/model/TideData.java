package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TideState;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable tide state snapshot for a solar event time at a coastal location.
 *
 * @param tideState                 current tide state at the solar event time
 * @param nearMidPoint              true when the solar event falls within ±60 minutes of the
 *                                  halfway point between a consecutive HIGH and LOW extreme,
 *                                  indicating a precise mid-tide window
 * @param nextHighTideTime          UTC time of the next high tide
 * @param nextHighTideHeightMetres  height of the next high tide in metres
 * @param nextLowTideTime           UTC time of the next low tide
 * @param nextLowTideHeightMetres   height of the next low tide in metres
 * @param nearestHighTideTime       UTC time of the high tide extreme nearest to the event
 *                                  within ±12 hours, or null if none found
 * @param nearestLowTideTime        UTC time of the low tide extreme nearest to the event
 *                                  within ±12 hours, or null if none found
 */
public record TideData(
        TideState tideState,
        boolean nearMidPoint,
        LocalDateTime nextHighTideTime,
        BigDecimal nextHighTideHeightMetres,
        LocalDateTime nextLowTideTime,
        BigDecimal nextLowTideHeightMetres,
        LocalDateTime nearestHighTideTime,
        LocalDateTime nearestLowTideTime) {
}
