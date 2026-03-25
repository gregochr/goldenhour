package com.gregochr.goldenhour.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Top-level daily briefing response served by {@code GET /api/briefing}.
 *
 * @param generatedAt UTC timestamp when this briefing was generated
 * @param headline    one-line summary highlighting the best opportunities
 * @param days        per-day briefing data (today + tomorrow)
 */
public record DailyBriefingResponse(
        LocalDateTime generatedAt,
        String headline,
        List<BriefingDay> days) {
}
