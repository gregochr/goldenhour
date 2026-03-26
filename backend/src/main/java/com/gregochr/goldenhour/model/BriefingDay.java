package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.util.List;

/**
 * One day's briefing data containing sunrise and sunset event summaries.
 *
 * @param date           the calendar date (UTC)
 * @param eventSummaries per-event-type summaries (typically sunrise + sunset)
 */
public record BriefingDay(
        LocalDate date,
        List<BriefingEventSummary> eventSummaries) {

    public BriefingDay {
        eventSummaries = List.copyOf(eventSummaries);
    }
}
