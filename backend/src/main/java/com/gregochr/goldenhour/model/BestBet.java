package com.gregochr.goldenhour.model;

/**
 * A Claude-generated "best bet" photography recommendation from the daily briefing triage.
 *
 * @param rank       ranking position — 1 = top pick, 2 = runner-up
 * @param headline   punchy one-sentence headline (≤15 words)
 * @param detail     2–3 sentence explanation with specific conditions and region context
 * @param event      event identifier, e.g. {@code "today_sunset"}, {@code "tomorrow_sunrise"},
 *                   {@code "aurora_tonight"}; null for a stay-home recommendation
 * @param region     recommended region name, e.g. {@code "Northumberland"};
 *                   null for a stay-home recommendation
 * @param confidence confidence level — {@code "high"}, {@code "medium"}, or {@code "low"}
 */
public record BestBet(
        int rank,
        String headline,
        String detail,
        String event,
        String region,
        String confidence) {
}
