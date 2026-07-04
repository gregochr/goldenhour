package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 * @param bestBetModel       display name of the Claude model used for best-bet picks (e.g. "Opus")
 * @param hotTopics          seasonal and special-interest hot topics for the forecast window
 * @param seasonalFeatures   active seasonal feature keys (e.g. "BLUEBELL") — used by frontend
 *                           to conditionally render seasonal filter chips
 * @param bestBetStatus      explicit best-bet outcome so the UI distinguishes an honest empty
 *                           result from a failure; {@code null} on legacy payloads (frontend
 *                           then infers from {@code bestBets} length)
 */
public record DailyBriefingResponse(
        LocalDateTime generatedAt,
        String headline,
        List<BriefingDay> days,
        @JsonInclude(JsonInclude.Include.ALWAYS) List<BestBet> bestBets,
        AuroraTonightSummary auroraTonight,
        AuroraTomorrowSummary auroraTomorrow,
        boolean stale,
        boolean partialFailure,
        int failedLocationCount,
        String bestBetModel,
        List<HotTopic> hotTopics,
        List<String> seasonalFeatures,
        BestBetStatus bestBetStatus) {

    /** Null-safe compact constructor — defensive copies for list fields only. */
    public DailyBriefingResponse {
        days = List.copyOf(days);
        bestBets = bestBets == null ? List.of() : List.copyOf(bestBets);
        hotTopics = hotTopics == null ? List.of() : List.copyOf(hotTopics);
        seasonalFeatures = seasonalFeatures == null ? List.of() : List.copyOf(seasonalFeatures);
    }

    /**
     * Backward-compatible constructor without an explicit best-bet status (defaults to
     * {@code null}). Lets existing call sites and persisted JSON payloads that predate the
     * status contract continue to work unchanged; callers that know the outcome use the
     * canonical constructor instead.
     *
     * @param generatedAt         UTC generation timestamp
     * @param headline            one-line summary
     * @param days                per-day briefing data
     * @param bestBets            best-bet picks
     * @param auroraTonight       tonight's aurora summary or null
     * @param auroraTomorrow      tomorrow's aurora summary or null
     * @param stale               last-known-good flag
     * @param partialFailure      partial-failure flag
     * @param failedLocationCount failed location count
     * @param bestBetModel        best-bet model display name
     * @param hotTopics           hot topics
     * @param seasonalFeatures    active seasonal feature keys
     */
    public DailyBriefingResponse(
            LocalDateTime generatedAt,
            String headline,
            List<BriefingDay> days,
            List<BestBet> bestBets,
            AuroraTonightSummary auroraTonight,
            AuroraTomorrowSummary auroraTomorrow,
            boolean stale,
            boolean partialFailure,
            int failedLocationCount,
            String bestBetModel,
            List<HotTopic> hotTopics,
            List<String> seasonalFeatures) {
        this(generatedAt, headline, days, bestBets, auroraTonight, auroraTomorrow,
                stale, partialFailure, failedLocationCount, bestBetModel, hotTopics,
                seasonalFeatures, null);
    }

    /**
     * Returns a copy of this response with the day hierarchy replaced. All other fields
     * (headline, best bets, aurora, hot topics, status flags) are preserved. Used by the
     * serve-time re-enrichment path to swap in regions with freshly re-derived verdicts.
     *
     * @param newDays the replacement day hierarchy
     * @return a copy carrying {@code newDays}
     */
    public DailyBriefingResponse withDays(List<BriefingDay> newDays) {
        return new DailyBriefingResponse(generatedAt, headline, newDays, bestBets,
                auroraTonight, auroraTomorrow, stale, partialFailure, failedLocationCount,
                bestBetModel, hotTopics, seasonalFeatures, bestBetStatus);
    }
}
