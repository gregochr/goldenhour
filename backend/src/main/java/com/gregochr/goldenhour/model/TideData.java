package com.gregochr.goldenhour.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable tide state snapshot for a solar event time at a coastal location.
 *
 * @param tideState                 Current tide state: HIGH, LOW, RISING, or FALLING
 * @param nextHighTideTime          UTC time of the next high tide
 * @param nextHighTideHeightMetres  Height of the next high tide in metres
 * @param nextLowTideTime           UTC time of the next low tide
 * @param nextLowTideHeightMetres   Height of the next low tide in metres
 */
public record TideData(
        String tideState,
        LocalDateTime nextHighTideTime,
        BigDecimal nextHighTideHeightMetres,
        LocalDateTime nextLowTideTime,
        BigDecimal nextLowTideHeightMetres) {
}
