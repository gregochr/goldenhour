package com.gregochr.goldenhour.model.runs;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One month for the Day view's calendar popover: per UK day, how many runs, what they cost and how many need attention.
 *
 * @param month        the month served, {@code YYYY-MM}
 * @param days         one entry per day of the month that is neither in the future nor before the earliest day
 * @param maxSpendGbp  the largest day spend in this month, the scale of the spend bars; zero when none spent
 * @param earliestDay  the earliest selectable day
 */
public record RunCalendarResponse(
        String month,
        List<CalendarDay> days,
        BigDecimal maxSpendGbp,
        LocalDate earliestDay) {

    /**
     * One calendar day.
     *
     * @param date          the UK civil date
     * @param runs          rows on the day (cycles, top-level jobs and sky evals)
     * @param spendGbp      spend in pounds; runs with an unknown cost add nothing
     * @param spendComplete false when a run that day has an unknown or still-accruing cost
     * @param attention     rows that are partial or failed
     */
    public record CalendarDay(LocalDate date, int runs, BigDecimal spendGbp, boolean spendComplete, int attention) {
    }
}
