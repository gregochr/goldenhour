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
 * <p>The bluebell window is no longer a hardcoded constant: it is built from
 * {@code photocast.season.bluebell.start/end} config by
 * {@link com.gregochr.goldenhour.config.SeasonConfig#bluebellSeasonWindow} and
 * injected (as the {@code bluebellSeasonWindow} bean) into every site that gates on
 * bluebell season, so the window is tunable without a redeploy and all sites agree.</p>
 *
 * @param start the first active day of the season (inclusive)
 * @param end   the last active day of the season (inclusive)
 * @param name  the canonical name of this seasonal window (e.g. {@code "BLUEBELL"})
 */
public record SeasonalWindow(MonthDay start, MonthDay end, String name) {

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
