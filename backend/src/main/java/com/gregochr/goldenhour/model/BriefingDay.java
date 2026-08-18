package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;

/**
 * One day's briefing data containing sunrise and sunset event summaries.
 *
 * @param date           the calendar date (UTC)
 * @param eventSummaries per-event-type summaries (typically sunrise + sunset)
 * @param peak           the day card's roll-up over the events of this day that the client
 *                       actually <em>renders</em>, or null.
 *                       Null on every internal path and in the persisted cache — like
 *                       {@link BriefingEventSummary#window()} it is derived at serve time by
 *                       {@code PlanWindowProjector}, and omitted from JSON when absent so the stored
 *                       payload is unchanged. Also null for a day outside the render horizon, which
 *                       is a statement that the day is not drawn rather than that it is poor
 */
public record BriefingDay(
        LocalDate date,
        List<BriefingEventSummary> eventSummaries,
        @JsonInclude(JsonInclude.Include.NON_NULL) BriefingDayPeak peak) {

    public BriefingDay {
        eventSummaries = List.copyOf(eventSummaries);
    }

    /**
     * Builds a day with no peak roll-up.
     *
     * <p>The form every producer uses: the peak is a serve-time projection, so nothing that builds a
     * day hierarchy has one. Retained as a constructor rather than pushed onto callers because it is
     * what the ~140 existing construction sites mean, and defaulting it here keeps a later component
     * from being silently dropped by a positional rebuild — the reason
     * {@link BriefingEventSummary#withWindow} exists.
     *
     * @param date           the calendar date
     * @param eventSummaries per-event-type summaries
     */
    public BriefingDay(LocalDate date, List<BriefingEventSummary> eventSummaries) {
        this(date, eventSummaries, null);
    }

    /**
     * Returns a copy carrying the given peak, leaving date and summaries untouched.
     *
     * @param newPeak the day's rendered-event roll-up, or null when the day is not rendered
     * @return a copy carrying the peak
     */
    public BriefingDay withPeak(BriefingDayPeak newPeak) {
        return new BriefingDay(date, eventSummaries, newPeak);
    }
}
