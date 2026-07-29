package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The Close to home panel: what is worth leaving the house for within driving distance.
 *
 * <p><b>Structured, not prose.</b> The client derivation this replaces also produced the copy —
 * day chips ("🌅 Tomorrow sunrise"), formatted event times, and a breadcrumb sentence. Those stay
 * on the client. Porting them would move user-facing English into Java for no benefit: the reason
 * this moved server-side is that the <em>logic</em> needed one home (correctness, a single source
 * of truth, and reachability from a non-web consumer), not that the wording did. So this carries
 * the facts a sentence is built from — {@link Breadcrumb#dominantReason()}, the leading
 * location, the ratings — and lets the renderer phrase them.
 *
 * <p><b>Never ETag-revalidated.</b> This is per-user data derived from a home postcode. Enabling
 * an ETag forces {@code Cache-Control: private, no-cache}, which persists the body to the
 * browser's on-disk HTTP cache — a cache JavaScript cannot evict on logout. See
 * {@code HttpCachingConfig}, whose whitelist is exact-match precisely so a new path cannot drift
 * into it, and {@code HttpCachingConfigTest.personalDataPathsAreNeverFiltered}.
 *
 * @param radiusMiles  the proximity gate actually applied, echoed so the client can say "within
 *                     22 miles" without hardcoding a number the server owns
 * @param horizonDays  how many distinct forecast days were drawn from
 * @param cards        ranked nearby options, best first, capped; empty when nothing qualifies
 * @param breadcrumb   the "go or stay in" answer for the next upcoming event
 */
public record CloseToHomeResponse(
        int radiusMiles,
        int horizonDays,
        List<Card> cards,
        Breadcrumb breadcrumb) {

    /** Defensive copy. */
    public CloseToHomeResponse {
        cards = List.copyOf(cards);
    }

    /**
     * One nearby option worth showing.
     *
     * @param locationId       database id of the location
     * @param locationName     human-readable location name
     * @param regionName       the location's own region, shown for honesty when a slot's briefing
     *                         region differs (the "St Mary's Lighthouse / Northumberland" case);
     *                         displayed, never filtered on
     * @param date             forecast date
     * @param targetType       SUNRISE or SUNSET
     * @param solarEventTime   UTC time of the event, unformatted — the client owns the format
     * @param rating           1-5 Claude rating; never null on a card, since unrated slots cannot
     *                         qualify
     * @param distanceMiles    great-circle distance from the caller's home, rounded
     * @param driveMinutes     the caller's own drive time, or null when not yet calculated
     * @param tideLabel        short tide note, or null when there is nothing to say
     * @param lead             true for the single best card
     */
    public record Card(
            Long locationId,
            String locationName,
            @JsonInclude(JsonInclude.Include.NON_NULL) String regionName,
            LocalDate date,
            TargetType targetType,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime solarEventTime,
            Integer rating,
            int distanceMiles,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer driveMinutes,
            @JsonInclude(JsonInclude.Include.NON_NULL) String tideLabel,
            boolean lead) {
    }

    /**
     * The verdict for the NEXT upcoming event specifically — not the horizon.
     *
     * <p>Separate from {@link #cards()} because it answers a different question. The cards say
     * "here is the best of the next few days"; this says "should I go out for the very next one".
     * A user can have a 5-star option on Thursday and still be told to stay in tonight.
     *
     * @param worthIt        true when at least one nearby location qualifies for the next event
     * @param date           date of the next event, or null when there is none upcoming
     * @param targetType     SUNRISE or SUNSET of the next event, or null
     * @param topLocationName the best-rated qualifying location for that event, or null
     * @param topRating      its rating, or null
     * @param topHeadline    its Claude headline, or null — preferred copy when present
     * @param topSummary     its Claude summary, or null — the fallback copy
     * @param dominantReason the most common stand-down reason among nearby slots for that event,
     *                       or null. Supplied so the client can say "Heavy cloud nearby — nothing
     *                       within reach is worth the trip tonight" without the server writing
     *                       that sentence
     */
    public record Breadcrumb(
            boolean worthIt,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate date,
            @JsonInclude(JsonInclude.Include.NON_NULL) TargetType targetType,
            @JsonInclude(JsonInclude.Include.NON_NULL) String topLocationName,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer topRating,
            @JsonInclude(JsonInclude.Include.NON_NULL) String topHeadline,
            @JsonInclude(JsonInclude.Include.NON_NULL) String topSummary,
            @JsonInclude(JsonInclude.Include.NON_NULL) String dominantReason) {

        /** The "nothing upcoming to assess" value. */
        public static Breadcrumb none() {
            return new Breadcrumb(false, null, null, null, null, null, null, null);
        }
    }

    /** The "no home set, or nothing within reach" value — an empty panel, not an error. */
    public static CloseToHomeResponse empty(int radiusMiles, int horizonDays) {
        return new CloseToHomeResponse(radiusMiles, horizonDays, List.of(), Breadcrumb.none());
    }
}
