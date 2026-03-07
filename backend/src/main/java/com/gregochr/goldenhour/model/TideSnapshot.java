package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TideState;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Tide state snapshot for a solar event at a coastal location.
 *
 * @param tideState                 current tide state at the solar event time
 * @param nextHighTideTime          UTC time of next high tide, or null
 * @param nextHighTideHeightMetres  height of next high tide in metres, or null
 * @param nextLowTideTime           UTC time of next low tide, or null
 * @param nextLowTideHeightMetres   height of next low tide in metres, or null
 * @param tideAligned               true if tide state matches location preference, or null for inland
 */
public record TideSnapshot(
        TideState tideState,
        LocalDateTime nextHighTideTime,
        BigDecimal nextHighTideHeightMetres,
        LocalDateTime nextLowTideTime,
        BigDecimal nextLowTideHeightMetres,
        Boolean tideAligned) {
}
