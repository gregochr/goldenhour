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
        Map<String, Integer> filterCounts) {
}
