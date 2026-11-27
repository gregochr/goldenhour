package com.gregochr.goldenhour.model.runs;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The Day view of the Runs screen: one UK civil day's rows in chronological order, plus, for today, what is
 * still to run.
 *
 * @param date         the UK civil date served
 * @param isToday      whether it is today (UK)
 * @param earliestDay  the earliest selectable day: the first day anything ran, at most 400 days back
 * @param sunrise      UK {@code HH:mm} of sunrise at the scheduler's reference point
 * @param sunset       UK {@code HH:mm} of sunset
 * @param sunriseAt    the sunrise instant
 * @param sunsetAt     the sunset instant
 * @param dayStartUtc  midnight UK at the start of the day (a UK day is 23, 24 or 25 hours)
 * @param dayEndUtc    midnight UK at the end of the day
 * @param now          the server's current instant, only when the day is today
 * @param runs         the rows, ascending by start
 * @param summary      the summary strip
 * @param filterCounts rows per chip: ALL, CYCLES, ASK_READY, BRIEFING, SKY_EVAL, OTHER and ATTENTION
 * @param promptTestRuns the CI prompt-regression runs that started on the day (never in {@code runs}, the summary or
 *                     the filter counts), oldest first
 */
public record RunDayResponse(
        LocalDate date,
        boolean isToday,
        LocalDate earliestDay,
        String sunrise,
        String sunset,
        Instant sunriseAt,
        Instant sunsetAt,
        Instant dayStartUtc,
        Instant dayEndUtc,
        Instant now,
        List<RunDayRow> runs,
        RunDaySummary summary,
        Map<String, Integer> filterCounts,
        List<PromptTestRunRow> promptTestRuns) {

    /**
     * Builds an app-only day (no prompt test runs).
     *
     * @param date         the day
     * @param isToday      whether it is today
     * @param earliestDay  the earliest selectable day
     * @param sunrise      sunrise {@code HH:mm}
     * @param sunset       sunset {@code HH:mm}
     * @param sunriseAt    the sunrise instant
     * @param sunsetAt     the sunset instant
     * @param dayStartUtc  the start of the day
     * @param dayEndUtc    the end of the day
     * @param now          the current instant, only for today
     * @param runs         the rows
     * @param summary      the summary
     * @param filterCounts the filter counts
     */
    public RunDayResponse(LocalDate date, boolean isToday, LocalDate earliestDay, String sunrise, String sunset,
            Instant sunriseAt, Instant sunsetAt, Instant dayStartUtc, Instant dayEndUtc, Instant now,
            List<RunDayRow> runs, RunDaySummary summary, Map<String, Integer> filterCounts) {
        this(date, isToday, earliestDay, sunrise, sunset, sunriseAt, sunsetAt, dayStartUtc, dayEndUtc, now, runs,
                summary, filterCounts, List.of());
    }

    /**
     * The same day with its prompt test runs attached; every other field is carried over untouched.
     *
     * @param rows the CI runs of the day
     * @return the response
     */
    public RunDayResponse withPromptTestRuns(List<PromptTestRunRow> rows) {
        return new RunDayResponse(date, isToday, earliestDay, sunrise, sunset, sunriseAt, sunsetAt, dayStartUtc,
                dayEndUtc, now, runs, summary, filterCounts, rows);
    }
}
