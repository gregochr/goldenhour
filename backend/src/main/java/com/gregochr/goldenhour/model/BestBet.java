package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A Claude-generated "best bet" photography recommendation from the daily briefing triage.
 *
 * <p>Claude provides the qualitative fields ({@code headline}, {@code detail}, {@code confidence}).
 * The structured display fields ({@code dayName}, {@code eventType}, {@code eventTime}) are
 * derived server-side from the triage data — they are facts, not Claude's interpretation.
 *
 * @param rank                    ranking position — 1 = top pick, 2 = runner-up
 * @param headline                punchy one-sentence headline (≤15 words) — the "why"
 * @param detail                  2–3 sentence explanation with specific conditions
 * @param event                   event identifier, e.g. {@code "2026-03-30_sunset"},
 *                                {@code "aurora_tonight"}; null for a stay-home recommendation
 * @param region                  recommended region name, e.g. {@code "Northumberland"};
 *                                null for a stay-home recommendation
 * @param confidence              confidence level
 * @param nearestDriveMinutes     nearest location drive time in minutes for the recommended region,
 *                                or {@code null} if drive time data is unavailable
 * @param dayName                 display day name, e.g. {@code "Today"}, {@code "Tomorrow"},
 *                                {@code "Wednesday"}; null for stay-home or aurora picks
 * @param eventType               lowercase event type, e.g. {@code "sunrise"}, {@code "sunset"};
 *                                null for stay-home or aurora picks
 * @param eventTime               UK-local event time as HH:mm, e.g. {@code "18:48"};
 *                                null for stay-home or aurora picks
 * @param relationship            how this pick relates to rank 1 — {@code SAME_SLOT} (tier 1)
 *                                or {@code DIFFERENT_SLOT} (tier 2); null for rank 1
 * @param differsBy               dimensions in which this pick differs from rank 1
 *                                (subset of DATE, EVENT, REGION); null/empty for rank 1 or SAME_SLOT
 */
public record BestBet(
        int rank,
        String headline,
        String detail,
        String event,
        String region,
        Confidence confidence,
        Integer nearestDriveMinutes,
        String dayName,
        String eventType,
        String eventTime,
        @JsonInclude(JsonInclude.Include.NON_NULL) Relationship relationship,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<DiffersBy> differsBy) {

    /**
     * Convenience constructor without relationship/differsBy fields (backward compatible).
     */
    public BestBet(int rank, String headline, String detail, String event, String region,
            Confidence confidence, Integer nearestDriveMinutes,
            String dayName, String eventType, String eventTime) {
        this(rank, headline, detail, event, region, confidence, nearestDriveMinutes,
                dayName, eventType, eventTime, null, List.of());
    }
}
