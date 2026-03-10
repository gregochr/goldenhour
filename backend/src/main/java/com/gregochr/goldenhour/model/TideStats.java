package com.gregochr.goldenhour.model;

import java.math.BigDecimal;

/**
 * Aggregate tide height statistics for a coastal location, computed from
 * accumulated historical {@code tide_extreme} data.
 *
 * @param avgHighMetres average height of all stored HIGH extremes
 * @param maxHighMetres maximum height of all stored HIGH extremes
 * @param avgLowMetres  average height of all stored LOW extremes
 * @param minLowMetres  minimum height of all stored LOW extremes
 * @param dataPoints    total number of extremes (HIGH + LOW) used in the calculation
 */
public record TideStats(
        BigDecimal avgHighMetres,
        BigDecimal maxHighMetres,
        BigDecimal avgLowMetres,
        BigDecimal minLowMetres,
        long dataPoints) {
}
