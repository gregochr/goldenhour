package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One shooting window — a single solar event on a single date — as the window-first Plan tab reads
 * it: its verdict, its best rating, its narrative picks, its badges.
 *
 * <p><b>Derived at serve time and never persisted.</b> It rides {@code days}, which
 * {@code BriefingService.getCachedBriefing} passes through untouched, so it needs no carrier and no
 * migration. Under {@code NON_NULL} a null window is omitted entirely, leaving the persisted
 * {@code daily_briefing_cache} JSON byte-identical to before this record existed.
 *
 * <p><b>It carries no date and no target type.</b> Both are the enclosing {@link BriefingDay} and
 * {@link BriefingEventSummary}, and duplicating them would create a second source of truth for the
 * window's own identity.
 *
 * <p><b>What the nullable components mean.</b> Each absence is a deliberate statement, not a gap:
 * <ul>
 *   <li>{@code eventTime} — no slot in the window carries a time. It is the <em>earliest</em> slot
 *       time across every region, never the first in list order: an event belongs to all of its
 *       locations, and list order traces to whichever grid cell answered first.</li>
 *   <li>{@code bestRating} — nothing in the window is rated. A max over non-canopy slots whenever
 *       the window has one, so a woodland score never supplies the {@code best N★} number. Note the
 *       limit of that guarantee: with no rating at all the {@code verdict} falls back to the top
 *       region's, which is computed upstream over canopy-<em>inclusive</em> statistics, so a wood
 *       can still influence the badge. Diverging the two would break the rule that a verdict colour
 *       means the same thing everywhere, so it is recorded rather than fixed here.</li>
 *   <li>{@code confidence} — unknown. Deliberately nullable: an unknown signal must read
 *       provisional rather than falsely confident.</li>
 *   <li>{@code bestBet} — the top region has no usable gloss headline, and the card omits the
 *       block rather than substituting anything.</li>
 *   <li>{@code alsoGood} — no second region cleared the floor. Never a different window: a
 *       cross-day alternative is the Best Bet of the window it belongs to.</li>
 *   <li>{@code topRarityRank} — no badges. Advice for the client's promoted strip; nothing here
 *       enforces the one-strip rule.</li>
 * </ul>
 *
 * <p>{@code verdict} is never null. {@code AWAITING} is reachable and means the window has neither
 * a rating nor a triage signal — it is not a synonym for a poor forecast, and must not render as
 * one.
 *
 * @param eventTime     the window's single header time, or null
 * @param verdict       the window's verdict; never null
 * @param bestRating    the highest rating in the window, or null
 * @param confidence    the top region's confidence, or null
 * @param bestBet       the window's recommendation, or null
 * @param alsoGood      a second region for this same window, or null
 * @param badges        hot topics landing on this window; never null, often empty
 * @param topRarityRank the rarest badge's rarity rank, or null
 */
public record BriefingWindow(
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime eventTime,
        DisplayVerdict verdict,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer bestRating,
        @JsonInclude(JsonInclude.Include.NON_NULL) Confidence confidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) Pick bestBet,
        @JsonInclude(JsonInclude.Include.NON_NULL) Pick alsoGood,
        List<Badge> badges,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer topRarityRank) {

    public BriefingWindow {
        badges = badges == null ? List.of() : List.copyOf(badges);
    }

    /**
     * One region's narrative for this window — the Best Bet, or the Also good beside it.
     *
     * <p>{@code headline} is never null: a Pick exists only when the headline is usable, so the
     * absence of a Pick is the only way this payload says "no narrative". {@code detail} is
     * nullable <em>independently</em> — a headline-present, detail-absent region is a shipped
     * state. {@code averageRating} is the value the ranking and the floor were applied to,
     * published so the selection is auditable from the payload rather than only from the logs.
     *
     * <p><b>Two of these read different slot populations, deliberately.</b> {@code averageRating}
     * is canopy-<em>inclusive</em>, because it must match the statistics behind the region's own
     * displayed verdict or the window would contradict the drill-down beneath it.
     * {@code locationName} is canopy-<em>exclusive</em>, because it must match the slot the header
     * star came from or the card would send someone to a place chosen by the opposite measure of a
     * good morning. Ranking parity and destination parity pull opposite ways; both are honoured.
     *
     * @param regionName    the region this describes; the card names it beside the kicker
     * @param headline      Claude's gloss headline. Never null: a Pick exists only when the
     *                      headline is usable, so the absence of a Pick is the only way to say
     *                      "no narrative"
     * @param detail        the gloss body, nullable independently of the headline
     * @param averageRating the average this region was ranked and floored on, published so the
     *                      selection is auditable from the payload rather than only from the logs
     * @param locationName  the region's highest-rated location, or null when none is rated
     */
    public record Pick(
            String regionName,
            String headline,
            @JsonInclude(JsonInclude.Include.NON_NULL) String detail,
            double averageRating,
            @JsonInclude(JsonInclude.Include.NON_NULL) String locationName) {
    }

    /**
     * A hot topic that lands on this window.
     *
     * <p>Label and detail are copied from the live topic in the same pass that buckets it, so a
     * badge can never disagree with the {@code hotTopics} list in the same response.
     *
     * <p>A badge's own clock time is a <em>different anchor</em> from its window's: the topic's is
     * computed from the first enabled location in its regions, the window's is the earliest slot
     * across every region, so the two can differ by minutes. Render one or the other, never both
     * side by side.
     *
     * @param type       the topic kind, e.g. {@code NLC}
     * @param label      the topic's short label
     * @param detail     the topic's longer line, or null
     * @param eventTime  the topic's own local clock time, or null
     * @param rarityRank the kind's fixed rarity ordinal, 1 = rarest
     */
    public record Badge(
            String type,
            String label,
            @JsonInclude(JsonInclude.Include.NON_NULL) String detail,
            @JsonInclude(JsonInclude.Include.NON_NULL) String eventTime,
            int rarityRank) {
    }
}
