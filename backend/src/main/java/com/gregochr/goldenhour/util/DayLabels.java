package com.gregochr.goldenhour.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Shared relative-day labelling for hot topics ("today", "tomorrow", or a weekday name).
 *
 * <p>Extracted from the per-strategy {@code formatDayLabel} copies so every hot-topic pill labels
 * days the same way, and so the multi-day enumeration (a phenomenon spanning several forecast
 * days) has one place to build its "today, tomorrow and Saturday" style prose.
 */
public final class DayLabels {

    private DayLabels() {
    }

    /**
     * Returns the relative label for a date: {@code "today"}, {@code "tomorrow"}, or the full
     * UK weekday name (e.g. {@code "Saturday"}) for anything further out.
     *
     * @param date  the date to label
     * @param today the reference "today"
     * @return the relative day label
     */
    public static String relative(LocalDate date, LocalDate today) {
        if (date.equals(today)) {
            return "today";
        }
        if (date.equals(today.plusDays(1))) {
            return "tomorrow";
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow.getDisplayName(TextStyle.FULL, Locale.UK);
    }

    /**
     * Joins relative day labels into a natural list — {@code "today"}, {@code "today and
     * tomorrow"}, or {@code "today, tomorrow and Saturday"}. Dates are labelled in the order
     * given (callers should pass them ascending). Duplicate labels are not collapsed.
     *
     * @param dates ascending list of dates, non-empty
     * @param today the reference "today"
     * @return the joined relative labels
     */
    public static String joinRelative(List<LocalDate> dates, LocalDate today) {
        List<String> labels = dates.stream().map(d -> relative(d, today)).toList();
        int n = labels.size();
        if (n == 1) {
            return labels.get(0);
        }
        if (n == 2) {
            return labels.get(0) + " and " + labels.get(1);
        }
        return String.join(", ", labels.subList(0, n - 1)) + " and " + labels.get(n - 1);
    }
}
