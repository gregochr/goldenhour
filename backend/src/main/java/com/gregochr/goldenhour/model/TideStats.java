package com.gregochr.goldenhour.model;

import java.math.BigDecimal;

/**
 * Aggregate tide height statistics for a coastal location, computed from
 * accumulated historical {@code tide_extreme} data.
 *
 * @param avgHighMetres       average height of all stored HIGH extremes
 * @param maxHighMetres       maximum height of all stored HIGH extremes
 * @param avgLowMetres        average height of all stored LOW extremes
 * @param minLowMetres        minimum height of all stored LOW extremes
 * @param dataPoints          total number of extremes (HIGH + LOW) used in the calculation
 * @param avgRangeMetres      average tidal range (avgHigh - avgLow)
 * @param p75HighMetres       75th percentile of HIGH tide heights
 * @param p90HighMetres       90th percentile of HIGH tide heights
 * @param p95HighMetres       95th percentile of HIGH tide heights (king tide threshold)
 * @param springTideCount     number of HIGH tides exceeding 125% of average high
 * @param springTideFrequency proportion of HIGH tides that are spring tides (0.0–1.0)
 * @param springTideThreshold height in metres above which a HIGH tide is classified as spring (125% of avg)
 * @param kingTideThreshold   height in metres above which a HIGH tide is classified as king (P95)
 * @param kingTideCount       number of HIGH tides exceeding the P95 threshold
 */
public record TideStats(
        BigDecimal avgHighMetres,
        BigDecimal maxHighMetres,
        BigDecimal avgLowMetres,
        BigDecimal minLowMetres,
        long dataPoints,
        BigDecimal avgRangeMetres,
        BigDecimal p75HighMetres,
        BigDecimal p90HighMetres,
        BigDecimal p95HighMetres,
        long springTideCount,
        BigDecimal springTideFrequency,
        BigDecimal springTideThreshold,
        BigDecimal kingTideThreshold,
        long kingTideCount) {
}
