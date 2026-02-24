package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TideState;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable tide state snapshot for a solar event time at a coastal location.
 *
 * @param tideState                 current tide state at the solar event time
 * @param nextHighTideTime          UTC time of the next high tide
 * @param nextHighTideHeightMetres  height of the next high tide in metres
 * @param nextLowTideTime           UTC time of the next low tide
 * @param nextLowTideHeightMetres   height of the next low tide in metres
 */
public record TideData(
        TideState tideState,
        LocalDateTime nextHighTideTime,
        BigDecimal nextHighTideHeightMetres,
        LocalDateTime nextLowTideTime,
        BigDecimal nextLowTideHeightMetres) {
}
