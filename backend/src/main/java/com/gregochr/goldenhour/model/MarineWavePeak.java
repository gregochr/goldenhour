package com.gregochr.goldenhour.model;

import java.time.LocalDateTime;

/**
 * The highest-wave hour inside a window of a fetched marine series, with the period values at that
 * hour: what big seas stores per daylight high water.
 *
 * @param peakTime    the UTC hour stamp of the peak
 * @param waveHeight  significant wave height at the peak, metres
 * @param direction   mean wave direction of origin at the peak, degrees, or null
 * @param period      mean wave period at the peak, seconds, or null
 * @param swellHeight swell wave height at the peak, metres, or null
 * @param swellPeriod swell wave period at the peak, seconds, or null
 */
public record MarineWavePeak(
        LocalDateTime peakTime,
        double waveHeight,
        Integer direction,
        Double period,
        Double swellHeight,
        Double swellPeriod) {
}
