package com.gregochr.goldenhour.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Top-level daily briefing response served by {@code GET /api/briefing}.
 *
 * @param generatedAt        UTC timestamp when this briefing was generated
 * @param headline           one-line summary highlighting the best opportunities
 * @param days               per-day briefing data (today + tomorrow)
 * @param bestBets           Claude-generated "best bet" picks (empty if the advisory call failed)
 * @param auroraTonight      tonight's aurora summary, or {@code null} when the state machine is idle
 * @param auroraTomorrow     tomorrow night's Kp forecast summary, or {@code null} if unavailable
 * @param stale              true when this is the last-known-good briefing, not freshly generated
 * @param partialFailure     true when some (but not all) location fetches failed this run
 * @param failedLocationCount number of locations that failed to fetch weather data this run
 */
public record DailyBriefingResponse(
        LocalDateTime generatedAt,
        String headline,
        List<BriefingDay> days,
        List<BestBet> bestBets,
        AuroraTonightSummary auroraTonight,
        AuroraTomorrowSummary auroraTomorrow,
        boolean stale,
        boolean partialFailure,
        int failedLocationCount) {

    /** Null-safe compact constructor — defensive copies for list fields only. */
    public DailyBriefingResponse {
        days = List.copyOf(days);
        bestBets = bestBets == null ? List.of() : List.copyOf(bestBets);
    }
}
