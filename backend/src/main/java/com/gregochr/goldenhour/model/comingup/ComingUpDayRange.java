package com.gregochr.goldenhour.model.comingup;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * One day's tidal range on a tide-run "Coming up" entry (supermoon-tide-run-plan.md S2), served so
 * the day chips can draw a bar per day without the client deriving a range or deciding whose day is
 * the biggest.
 *
 * @param date        the day, within the entry's span
 * @param rangeMetres that day's tidal range in metres at the run's representative location, to 1 dp
 * @param peak        true only on the run's peak day (the date the entry's {@code peakDate} names). After
 *                    rounding to one decimal place another day may show the same height, so the flag,
 *                    not the height, says which day is the peak
 * @param rangePercentile 1-100, how this day's displayed range ranks among the past year's daily
 *                    ranges at the same port ("top N%", 1 = biggest); absent unless the year sample
 *                    was sufficient (tide-range-rank-plan.md §2), and then present on every day
 * @param shoulder    true on a near-miss day just outside the run (tide-source-integrity-plan.md P3): the
 *                    roster's water fell narrowly short of spring size. Never a run day and never the
 *                    peak; omitted from the JSON when false
 */
public record ComingUpDayRange(LocalDate date, double rangeMetres, boolean peak,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer rangePercentile,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean shoulder) {
}
