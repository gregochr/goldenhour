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
 *   <li>{@code eventTime} — the window has no time from any source. It is the <em>earliest</em>
 *       slot time across every region, never the first in list order: an event belongs to all of
 *       its locations, and list order traces to whichever grid cell answered first. With no timed
 *       slot at all it falls back to the enclosing summary's own {@code solarEventTime}, which is
 *       the only clock a window whose slots the honesty filter withdrew still has — and since that
 *       instant now decides which events are <em>rendered</em>, not merely which may hold a pick, a
 *       window reading as timeless would spend one of the six rendered slots on an event that may
 *       be hours past.</li>
 *   <li>{@code bestRating} — nothing in the window is rated. A max over non-canopy slots whenever
 *       the window has one, so a woodland score never supplies the {@code best spot N★} number.
 *       <b>It is a labelled spot signal and never a verdict.</b> Since 2026-08-17 the
 *       {@code verdict} is its top region's own (an average), so the two can disagree by design:
 *       {@code Poor · best spot 4★} states one region's average and one location's score, both
 *       true, and the client is required to render this number with its own label rather than in
 *       verdict vocabulary.
 *
 *       <p><b>The badge can no longer outrank it — that gap is closed.</b> The region average was
 *       canopy-<em>inclusive</em> while this field and the card's spot strip both exclude canopy
 *       slots, so a rated wood could lift the badge <em>above every rating the card renders</em>:
 *       {@code ◎ Worth it} over {@code best spot 3★}, with the 5★ wood shown nowhere. Fixed at the
 *       source rather than here — {@code BriefingService.enrichWithCachedScores} now derives the
 *       region's verdict and mean from {@link BriefingSlot#votingSlots}, the same non-canopy rule
 *       (with its all-canopy fallback) that the hierarchy builder's own verdict and the confidence
 *       roster already applied. Diverging <em>this</em> verdict from the region's would have broken
 *       the rule that a verdict colour means the same thing everywhere, which is why the fix had to
 *       move the grid cell and the day card with it. Canopy was the only route to the state: with
 *       no canopy slot a mean ≥ 3.5 forces some slot ≥ 4 and a mean ≥ 2.5 forces some slot ≥ 3, and
 *       those slots reach both this field and the strip.</li>
 *   <li>{@code confidence} — unknown. Deliberately nullable: an unknown signal must read
 *       provisional rather than falsely confident.</li>
 *   <li>{@code pick} — this window is neither of the forecast's two picks, which is the normal
 *       case: exactly two windows in the whole forecast carry one. It is <em>not</em> a statement
 *       that the window has no good region.</li>
 *   <li>{@code topRarityRank} — no badges. Advice for the client's promoted strip; nothing here
 *       enforces the one-strip rule.</li>
 *   <li>{@code tide} — no coastal location could be resolved, or the one that was has no day with
 *       both a high and a low water on this date. The row falls back to {@code BriefingSlot.tide}'s
 *       per-location fact line. It is <em>not</em> a statement that the tide is unremarkable, and
 *       nothing is ever synthesised to fill the gap.</li>
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
 * @param pick          this window's forecast-wide pick, or null on the windows that are neither
 * @param badges        hot topics landing on this window; never null, often empty
 * @param topRarityRank the rarest badge's rarity rank, or null
 * @param tide          the window's tide rollup at one named coastal location, or null
 */
public record BriefingWindow(
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime eventTime,
        DisplayVerdict verdict,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer bestRating,
        @JsonInclude(JsonInclude.Include.NON_NULL) Confidence confidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) Pick pick,
        List<Badge> badges,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer topRarityRank,
        @JsonInclude(JsonInclude.Include.NON_NULL) BriefingWindowTide tide) {

    public BriefingWindow {
        badges = badges == null ? List.of() : List.copyOf(badges);
    }

    /** Which of the forecast's two picks this is. */
    public enum PickKind {
        /** The single window worth planning for across the whole forecast. */
        BEST,
        /** The runner-up, which may fall on a different day entirely. */
        ALSO
    }

    /**
     * A forecast-wide pick — one of exactly two across the whole briefing, carried on the window it
     * falls on.
     *
     * <p><b>Authored once.</b> The rail's pick chip and the window card's header badge both read
     * this same object; nothing duplicates the prose. The two picks may land on the same day or on
     * different days, and either may be a sunrise or a sunset.
     *
     * <p>{@code headline} is never null: a Pick exists only when the headline is usable, so the
     * absence of a Pick is the only way this payload says "no narrative". {@code detail} is
     * nullable <em>independently</em> — a headline-present, detail-absent region is a shipped
     * state. {@code averageRating} is the value the ranking and the floor were applied to,
     * published so the selection is auditable from the payload rather than only from the logs.
     *
     * <p><b>Two of these read different slot populations, deliberately.</b> {@code averageRating}
     * is over the region's <em>voting</em> slots ({@link BriefingSlot#votingSlots}), because it must
     * match the statistics behind the region's own displayed verdict — and behind the number the
     * grid cell prints as its star — or the window would contradict the drill-down beneath it. It
     * was canopy-inclusive until the canopy fix, along with the verdict it has to match; a rated
     * wood moved both. {@code locationName} is canopy-<em>exclusive</em> for a different reason:
     * it must match the slot the header star came from, or the card would send someone to a place
     * chosen by the opposite measure of a good morning. The two rules now coincide for a mixed
     * region and still differ for an all-canopy one, where the average falls back to the woods and
     * the destination is one of them.
     *
     * <p><b>The id travels with the name, from the same slot.</b> The client joins per-user reach
     * data by {@code locationId} — that contract carries no name at all — so publishing only a
     * name would force a join through the locations roster, which is the join this project has
     * already paid for once: a rename silently empties the block for every user. Null is possible
     * on a slot cached before slots carried an id; prefer the id, fall back to the name.
     *
     * @param kind          whether this is the Best Bet or the Also good
     * @param regionName    the region this describes; the card names it beside the kicker
     * @param headline      Claude's gloss headline. Never null: a Pick exists only when the
     *                      headline is usable, so the absence of a Pick is the only way to say
     *                      "no narrative"
     * @param detail        the gloss body, nullable independently of the headline
     * @param averageRating the average this region was ranked and floored on, published so the
     *                      selection is auditable from the payload rather than only from the logs
     * @param locationName  the region's highest-rated location, or null when none is rated
     * @param locationId    that location's id, or null on a slot cached before slots carried one
     */
    public record Pick(
            PickKind kind,
            String regionName,
            String headline,
            @JsonInclude(JsonInclude.Include.NON_NULL) String detail,
            double averageRating,
            @JsonInclude(JsonInclude.Include.NON_NULL) String locationName,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long locationId) {

        /**
         * Returns this pick stamped with the kind it was awarded.
         *
         * <p>A candidate is built before anything knows whether it won, and only two of them ever
         * do — so the kind is applied at award time rather than guessed at construction.
         *
         * @param awarded BEST or ALSO
         * @return a copy carrying the kind
         */
        public Pick withKind(PickKind awarded) {
            return new Pick(awarded, regionName, headline, detail, averageRating,
                    locationName, locationId);
        }
    }

    /**
     * A hot topic that lands on this window.
     *
     * <p>Label, detail and facts are copied from the live topic in the same pass that buckets it,
     * so a badge can never disagree with the {@code hotTopics} list in the same response.
     *
     * <p>A badge's own clock time is a <em>different anchor</em> from its window's: the topic's is
     * computed from the first enabled location in its regions, the window's is the earliest slot
     * across every region, so the two can differ by minutes. Render one or the other, never both
     * side by side.
     *
     * <p><b>{@code facts} is what lets a topic become an attribute row rather than a chip.</b> The
     * Plan card draws two kinds of surface for a topic: a header badge, which has room for the
     * label alone, and a full-width row, which has room for measured quantities. A topic carrying
     * facts has something the badge structurally cannot show, so the row is its expanded form and
     * the badge is dropped; a topic carrying none would render a row whose whole content is its own
     * label repeated, which is the duplication the design brief bans. Empty rather than null, so
     * the client's rule is a length check and never a null check.
     *
     * @param type       the topic kind, e.g. {@code NLC}
     * @param label      the topic's short label
     * @param detail     the topic's longer line, or null
     * @param facts      the topic's "science showing" chips, verbatim; never null, often empty
     * @param eventTime  the topic's own local clock time, or null
     * @param rarityRank the kind's fixed rarity ordinal, 1 = rarest
     * @param note       the topic's editorial "where to look" cue, or null. Carried so the promoted
     *                   strip has an aside to render that is not a restatement of the figure beside
     *                   it — {@code detail} summarises the same measurements the headline fact
     *                   already shows, and printing both put the same two numbers on one strip twice
     * @param rarityNote the topic's recurrence line, or null. On the badge for the same reason as
     *                   the warning: the promoted strip renders from badges and never joins back
     * @param safetyNote a warning every surface raising this topic must show, or null. Carried on
     *                   the badge rather than looked up from the topic list because the window card
     *                   and the promoted strip render from badges alone and never join back to
     *                   {@code hotTopics} — without it here they structurally cannot raise it
     */
    public record Badge(
            String type,
            String label,
            @JsonInclude(JsonInclude.Include.NON_NULL) String detail,
            List<HotTopicFact> facts,
            @JsonInclude(JsonInclude.Include.NON_NULL) String eventTime,
            int rarityRank,
            @JsonInclude(JsonInclude.Include.NON_NULL) String note,
            @JsonInclude(JsonInclude.Include.NON_NULL) String rarityNote,
            @JsonInclude(JsonInclude.Include.NON_NULL) String safetyNote) {

        public Badge {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }
    }
}
