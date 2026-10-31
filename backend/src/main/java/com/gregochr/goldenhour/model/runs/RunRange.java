package com.gregochr.goldenhour.model.runs;

import java.util.Locale;

/**
 * The UK-day-aligned window the Runs list covers, counted back from the start of today's UK civil day.
 */
public enum RunRange {

    /** From the start of today's UK day. */
    TODAY(0),

    /** From the start of the UK day six days before today (seven days including today). */
    WEEK(6),

    /** From the start of the UK day twenty-nine days before today (thirty days including today). */
    MONTH(29);

    private final int daysBack;

    RunRange(int daysBack) {
        this.daysBack = daysBack;
    }

    /**
     * How many UK days before today the window starts.
     *
     * @return the day offset from today, zero for {@link #TODAY}
     */
    public int daysBack() {
        return daysBack;
    }

    /**
     * Parses a request value, case-insensitively.
     *
     * @param value the request value
     * @return the matching range
     * @throws IllegalArgumentException if the value names no range
     */
    public static RunRange parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
