package com.gregochr.goldenhour.model.comingup;

import java.time.LocalDate;

/**
 * One day's tidal range on a tide-run "Coming up" entry (supermoon-tide-run-plan.md S2), served so
 * the day chips can draw a bar per day without the client deriving a range or deciding whose day is
 * the biggest.
 *
 * @param date        the day, within the entry's span
 * @param rangeMetres that day's tidal range in metres at the run's representative location, to 1 dp
 * @param peak        true only on the run's peak day (the date the entry's {@code peakDate} names)
 */
public record ComingUpDayRange(LocalDate date, double rangeMetres, boolean peak) {
}
