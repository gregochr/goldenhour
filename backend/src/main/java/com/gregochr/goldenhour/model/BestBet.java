package com.gregochr.goldenhour.model;

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
        String eventTime) {
}
