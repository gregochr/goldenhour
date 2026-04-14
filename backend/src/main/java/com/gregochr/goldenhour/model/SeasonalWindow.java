package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.time.MonthDay;

/**
 * Calendar-gated seasonal window. Used to activate/deactivate seasonal
 * features (bluebell forecasting, NLC alerts, autumn colour, etc.)
 * based purely on date — no API calls needed.
 *
 * <p>Handles the simple case where start month-day &lt; end month-day
 * (i.e. does not wrap across year boundary). If a future seasonal
 * feature needs year-wrapping (e.g. winter aurora Oct–Feb), extend
 * {@link #isActive(LocalDate)} accordingly.</p>
 *
 * @param start the first active day of the season (inclusive)
 * @param end   the last active day of the season (inclusive)
 * @param name  the canonical name of this seasonal window (e.g. {@code "BLUEBELL"})
 */
public record SeasonalWindow(MonthDay start, MonthDay end, String name) {

    /** Bluebell season: mid-April to mid-May. */
    public static final SeasonalWindow BLUEBELL =
            new SeasonalWindow(MonthDay.of(4, 18), MonthDay.of(5, 18), "BLUEBELL");

    /**
     * Returns true if the given date falls within this seasonal window
     * (inclusive of both start and end).
     *
     * @param date the date to test
     * @return {@code true} if the date is within the window
     */
    public boolean isActive(LocalDate date) {
        MonthDay today = MonthDay.from(date);
        return !today.isBefore(start) && !today.isAfter(end);
    }
}
