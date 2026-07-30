package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * The Close to home panel: what is worth leaving the house for, within driving distance.
 *
 * <p><b>Grouped by event window, not a flat list.</b> The first build of this block returned a flat
 * card list with a per-card day chip, and the design handoff retired it for two concrete reasons:
 * four cards all reading "4.0★" never say <em>which event</em> they are for, so the user has to
 * infer the day from the breadcrumb above; and a sunrise-only recommendation is an easy dismissal
 * when the same region's sunset is the Best Bet. Grouping by window makes the question "when do I
 * go out" answerable, and lets an early alarm be weighed against an evening trip rather than being
 * the only thing on offer.
 *
 * <p><b>Structured, not prose.</b> Day chips, formatted times and the breadcrumb sentence stay on
 * the client. What needed one home was the logic, not the wording, so this carries the facts a
 * sentence is built from and lets the renderer phrase them.
 *
 * <p><b>Never ETag-revalidated.</b> Per-user data derived from a home postcode. An ETag forces
 * {@code Cache-Control: private, no-cache}, which persists the body to a browser HTTP cache that
 * JavaScript cannot evict on logout. Pinned by
 * {@code HttpCachingConfigTest.personalDataPathsAreNeverFiltered}.
 *
 * @param radiusMiles the proximity gate actually applied — the caller's own setting, echoed so the
 *                    client can render "Within 22 miles of home" without owning the number
 * @param horizonDays how many distinct forecast days were drawn from
 * @param windows     event windows in CHRONOLOGICAL order, because the user is deciding WHEN to
 *                    go out; capped, and empty when nothing qualifies
 * @param breadcrumb  the "go or stay in" answer for the very next event
 */
public record CloseToHomeResponse(
        int radiusMiles,
        int horizonDays,
        List<Window> windows,
        Breadcrumb breadcrumb) {

    /** Defensive copy. */
    public CloseToHomeResponse {
        windows = List.copyOf(windows);
    }

    /**
     * One event window — a date plus sunrise or sunset — and the nearby locations rating well for
     * it.
     *
     * @param date                 forecast date
     * @param targetType           SUNRISE or SUNSET
     * @param eventTime            representative UTC event time for the header, unformatted
     * @param bestRating           the best rating in this window
     * @param withinReach          how many in-radius locations qualified, before the card cap
     * @param notInBriefing        the region reads STANDDOWN for this window region-wide, yet at
     *                             least one nearby location still rates GO or MAYBE. This is the
     *                             case the region-level briefing structurally CANNOT show, and the
     *                             handoff calls it the highest-value signal in the block
     * @param flaggedRegionName    the region that is standing down, for the tooltip; null unless
     *                             {@code notInBriefing}
     * @param sameWindowAsBestBet  this window is the same day and event as the current Best Bet, so
     *                             the local option is not a compromise
     * @param bestBetRegionName    the Best Bet's region, for the tooltip; null unless
     *                             {@code sameWindowAsBestBet}
     * @param cards                nearby locations, RATING-ordered within the window, capped
     */
    public record Window(
            LocalDate date,
            TargetType targetType,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime eventTime,
            Integer bestRating,
            int withinReach,
            boolean notInBriefing,
            @JsonInclude(JsonInclude.Include.NON_NULL) String flaggedRegionName,
            boolean sameWindowAsBestBet,
            @JsonInclude(JsonInclude.Include.NON_NULL) String bestBetRegionName,
            List<Card> cards) {

        /** Defensive copy. */
        public Window {
            cards = List.copyOf(cards);
        }
    }

    /**
     * One nearby option worth showing.
     *
     * @param locationId     database id of the location
     * @param locationName   human-readable location name
     * @param regionName     the location's OWN region — the "St Mary's Lighthouse /
     *                       Northumberland" honesty label. Displayed, never filtered on
     * @param locationTypes  the location's subject types, so the card can carry the same icons the
     *                       map and admin use. Sourced from the roster rather than the briefing
     *                       slot, which does not carry them
     * @param rating         1-5 rating; never null on a card, since unrated slots cannot qualify
     * @param distanceMiles  great-circle distance from the caller's home, rounded
     * @param driveMinutes   the caller's own drive time, or null when not yet calculated
     * @param tideLabel      short tide note, or null when there is nothing to say
     * @param lead           true for the single best card of the FIRST window only — one per block
     */
    public record Card(
            Long locationId,
            String locationName,
            @JsonInclude(JsonInclude.Include.NON_NULL) String regionName,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Set<LocationType> locationTypes,
            Integer rating,
            int distanceMiles,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer driveMinutes,
            @JsonInclude(JsonInclude.Include.NON_NULL) String tideLabel,
            boolean lead) {
    }

    /**
     * The verdict for the NEXT upcoming event specifically — not the horizon.
     *
     * <p>Shown even when the verdict is poor. An honest "stay in" plus a date to look forward to is
     * more useful than an absent block, and that was an explicit product decision. Note it
     * deliberately does NOT name the best nearby location on a poor night: an earlier iteration
     * surfaced one at 1.8★, which undercut the stay-in verdict.
     *
     * @param worthIt         true when at least one nearby location qualifies for the next event
     * @param date            date of the next event, or null when none is upcoming
     * @param targetType      SUNRISE or SUNSET of the next event, or null
     * @param eventTime       UTC time of the next event, or null
     * @param topLocationName the best-rated qualifying location for that event, or null when the
     *                        verdict is poor — see above, this stays null deliberately
     * @param topRating       its rating, or null
     * @param topHeadline     its Claude headline, or null — preferred copy when present
     * @param topSummary      its Claude summary, or null — the fallback copy
     * @param dominantReason  the most common stand-down reason nearby, or null. Supplied so the
     *                        client can name a cause without the server writing the sentence
     * @param nextWindow      the soonest local window worth leaving the house for — the hope that
     *                        keeps a user coming back on a stay-in night. Null when none qualifies
     */
    public record Breadcrumb(
            boolean worthIt,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate date,
            @JsonInclude(JsonInclude.Include.NON_NULL) TargetType targetType,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime eventTime,
            @JsonInclude(JsonInclude.Include.NON_NULL) String topLocationName,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer topRating,
            @JsonInclude(JsonInclude.Include.NON_NULL) String topHeadline,
            @JsonInclude(JsonInclude.Include.NON_NULL) String topSummary,
            @JsonInclude(JsonInclude.Include.NON_NULL) String dominantReason,
            @JsonInclude(JsonInclude.Include.NON_NULL) NextWindow nextWindow) {

        /** The "nothing upcoming to assess" value. */
        public static Breadcrumb none() {
            return new Breadcrumb(false, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * The soonest local window worth the trip, for the breadcrumb's gold rail.
     *
     * @param locationId     database id
     * @param locationName   location name
     * @param rating         its rating
     * @param date           the date it falls on
     * @param targetType     SUNRISE or SUNSET
     * @param eventTime      UTC event time
     * @param driveMinutes   the caller's drive time, or null
     */
    public record NextWindow(
            Long locationId,
            String locationName,
            Integer rating,
            LocalDate date,
            TargetType targetType,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime eventTime,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer driveMinutes) {
    }

    /** The "no home set, or nothing within reach" value — an empty panel, not an error. */
    public static CloseToHomeResponse empty(int radiusMiles, int horizonDays) {
        return new CloseToHomeResponse(radiusMiles, horizonDays, List.of(), Breadcrumb.none());
    }
}
