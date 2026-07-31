package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CloseToHomeResponse;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.util.GeoUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Derives the Close to home panel: what is worth leaving the house for, within driving distance.
 *
 * <p><b>Why this moved off the client.</b> It was ~385 lines of derivation in
 * {@code closeToHome.js}, which was fine while a browser was the only consumer. Two things made
 * it a server concern: the join, and reachability. See
 * {@code docs/engineering/plan-panel-data-contracts.md} Section 5.
 *
 * <p><b>The join is the reason it was worth doing.</b> The client matched briefing slots to the
 * locations roster by NAME, so renaming a location silently emptied the panel for every user
 * inside the radius. This joins on {@code locationId} — added to {@link BriefingSlot} for exactly
 * this — and falls back to the name only for cached payloads written before that field existed,
 * which keep being served until their key ages out.
 *
 * <p><b>Ratings come from the enriched briefing, not a second source.</b> The client preferred a
 * separately-fetched evaluation-score map over the payload's own {@code claudeRating}, which
 * looked like a freshness fix worth reproducing here. It is not needed:
 * {@code BriefingService.getCachedBriefingForApi} already runs {@code enrichWithCachedScores} on
 * the SERVE path, from the same {@code getCachedScores} source the separate endpoint reads. Taking
 * the enriched payload therefore yields exactly the ratings the Plan grid shows — which is the
 * property that matters, since two panels disagreeing about the same location is the failure this
 * whole contract exists to prevent.
 */
@Service
public class CloseToHomeService {

    /**
     * Windows shown, so the block stays a glance rather than a list.
     *
     * <p>There is deliberately no matching cap on cards <em>within</em> a window. There was — four —
     * and it made {@code withinReach} a number the user could read but not act on: a window saying
     * "20 within reach" showed four cards and offered no route to the other sixteen. The client
     * decides how many to show at once (it scrolls, and it can expand the count into a ranked
     * list); the payload's job is to make every qualifying location reachable. The set is already
     * bounded by the radius gate and the location roster.
     */
    static final int MAX_WINDOWS = 3;

    /** Forecast horizon drawn from, in distinct forecast days. */
    static final int HORIZON_DAYS = 3;

    /** Window past a solar event during which it still counts as current. */
    private static final long AFTERGLOW_MINUTES = 30;

    private static final ZoneId UTC = ZoneId.of("UTC");

    private final BriefingService briefingService;
    private final LocationService locationService;
    private final DriveTimeResolver driveTimeResolver;
    private final Clock clock;
    private final int defaultRadiusMiles;

    /**
     * Creates the service.
     *
     * @param briefingService    supplies the enriched, honesty-filtered briefing the Plan tab sees
     * @param locationService    supplies the locations roster (coordinates and regions)
     * @param driveTimeResolver  supplies the caller's own drive times, keyed by location id
     * @param clock              UTC clock, for deciding which events are still upcoming
     * @param defaultRadiusMiles fallback gate for a caller who has never chosen one. The radius
     *                           is a PER-USER setting (V136); this is only what a null column
     *                           means. It began as a client constant, which is what made a user
     *                           setting impossible in the first place.
     */
    public CloseToHomeService(BriefingService briefingService,
            LocationService locationService,
            DriveTimeResolver driveTimeResolver,
            Clock clock,
            @Value("${photocast.close-to-home.radius-miles:22}") int defaultRadiusMiles) {
        this.briefingService = briefingService;
        this.locationService = locationService;
        this.driveTimeResolver = driveTimeResolver;
        this.clock = clock;
        this.defaultRadiusMiles = defaultRadiusMiles;
    }

    /**
     * Builds the panel for one user.
     *
     * @param userId       the caller's id, for their drive times
     * @param homeLatitude the caller's home latitude, or null when no home is set
     * @param homeLongitude the caller's home longitude, or null when no home is set
     * @return the panel; empty (not null, not an error) when no home is set or nothing is in reach
     */
    public CloseToHomeResponse build(Long userId, Double homeLatitude, Double homeLongitude) {
        return build(userId, homeLatitude, homeLongitude, null);
    }

    /**
     * Builds the panel for one user at their chosen radius.
     *
     * @param userId            the caller's id, for their drive times
     * @param homeLatitude      the caller's home latitude, or null when no home is set
     * @param homeLongitude     the caller's home longitude, or null when no home is set
     * @param userRadiusMiles   the caller's chosen radius, or null to use the default
     * @return the panel; empty (not null, not an error) when no home is set or nothing is in reach
     */
    public CloseToHomeResponse build(Long userId, Double homeLatitude, Double homeLongitude,
            Integer userRadiusMiles) {
        int radiusMiles = userRadiusMiles != null ? userRadiusMiles : defaultRadiusMiles;
        if (homeLatitude == null || homeLongitude == null) {
            // No home postcode is the normal state for a new user, not a failure.
            return CloseToHomeResponse.empty(radiusMiles, HORIZON_DAYS);
        }
        DailyBriefingResponse briefing = briefingService.getCachedBriefingForApi();
        if (briefing == null || briefing.days() == null || briefing.days().isEmpty()) {
            return CloseToHomeResponse.empty(radiusMiles, HORIZON_DAYS);
        }

        Map<Long, LocationEntity> byId = new HashMap<>();
        Map<String, LocationEntity> byName = new HashMap<>();
        for (LocationEntity loc : locationService.findAllEnabled()) {
            // lat/lon are primitive doubles on the entity, so there is no unset state to guard.
            if (loc.getId() != null) {
                byId.put(loc.getId(), loc);
            }
            if (loc.getName() != null) {
                byName.put(loc.getName(), loc);
            }
        }
        if (byId.isEmpty() && byName.isEmpty()) {
            return CloseToHomeResponse.empty(radiusMiles, HORIZON_DAYS);
        }

        List<Candidate> nearby = collectNearby(briefing.days(), byId, byName,
                homeLatitude, homeLongitude, radiusMiles);
        if (nearby.isEmpty()) {
            return CloseToHomeResponse.empty(radiusMiles, HORIZON_DAYS);
        }

        Map<Long, Integer> driveMinutes = userId == null
                ? Map.of() : driveTimeResolver.getAllMinutes(userId);

        List<CloseToHomeResponse.Window> windows =
                buildWindows(nearby, driveMinutes, briefing.bestBets());

        return new CloseToHomeResponse(radiusMiles, HORIZON_DAYS, windows,
                buildBreadcrumb(briefing.days(), nearby, driveMinutes));
    }

    // ── collection ────────────────────────────────────────────────────────────

    private List<Candidate> collectNearby(List<BriefingDay> days,
            Map<Long, LocationEntity> byId, Map<String, LocationEntity> byName,
            double homeLat, double homeLon, int radiusMiles) {
        List<Candidate> out = new ArrayList<>();
        Set<LocalDate> dates = new LinkedHashSet<>();

        for (BriefingDay day : days) {
            List<BriefingEventSummary> events = day.eventSummaries().stream()
                    .filter(es -> !isEventPast(es))
                    .toList();
            if (events.isEmpty()) {
                continue;
            }
            if (!dates.contains(day.date())) {
                if (dates.size() >= HORIZON_DAYS) {
                    break;
                }
                dates.add(day.date());
            }
            for (BriefingEventSummary es : events) {
                for (BriefingRegion region : es.regions()) {
                    collectSlots(out, region.slots(), region.regionName(), region.verdict(),
                            region.displayVerdict(), day.date(), es.targetType(), byId, byName,
                            homeLat, homeLon, radiusMiles);
                }
                // Unregioned slots count too: a location without a region is still within reach.
                collectSlots(out, es.unregioned(), null, null, null, day.date(), es.targetType(),
                        byId, byName, homeLat, homeLon, radiusMiles);
            }
        }
        return out;
    }

    private void collectSlots(List<Candidate> out, List<BriefingSlot> slots, String briefingRegion,
            Verdict briefingRegionVerdict, DisplayVerdict briefingRegionDisplayVerdict,
            LocalDate date, TargetType targetType,
            Map<Long, LocationEntity> byId, Map<String, LocationEntity> byName,
            double homeLat, double homeLon, int radiusMiles) {
        for (BriefingSlot slot : slots) {
            // Prefer the FK; fall back to the name only for cached payloads written before
            // BriefingSlot carried an id. Those keep being served until their key ages out, so
            // the fallback is a rollover path rather than dead code.
            LocationEntity loc = slot.locationId() != null ? byId.get(slot.locationId()) : null;
            if (loc == null) {
                loc = byName.get(slot.locationName());
            }
            if (loc == null) {
                continue;
            }
            double miles = GeoUtils.distanceMiles(homeLat, homeLon, loc.getLat(), loc.getLon());
            if (miles > radiusMiles) {
                continue;
            }
            out.add(new Candidate(
                    loc.getId(), slot.locationName(),
                    // The location's OWN region, not the briefing group it was listed under —
                    // the "St Mary's Lighthouse / Northumberland" honesty label. Shown, not
                    // filtered on.
                    loc.getRegion() != null ? loc.getRegion().getName() : briefingRegion,
                    loc.getLocationType(),
                    date, targetType, slot.solarEventTime(), miles, slot.claudeRating(),
                    isPoor(slot), slot.standdownReason(), slot.claudeHeadline(),
                    slot.claudeSummary(), tideLabel(slot), briefingRegion, briefingRegionVerdict,
                    briefingRegionDisplayVerdict));
        }
    }

    // ── ranking ───────────────────────────────────────────────────────────────

    /**
     * Groups qualifying candidates into event windows, per the handoff's selection rules.
     *
     * <p>Order matters and is not interchangeable: windows go CHRONOLOGICALLY because the user is
     * deciding <em>when</em> to go out, while cards WITHIN a window go by rating because at that
     * point they have already chosen the when and are choosing the where.
     *
     * <p>Distance gates but never sorts. It is shown on every card, so letting it also weight the
     * order would double-count it — an explicit product decision. The closest location can and
     * should rank second when something further out rates better.
     */
    private List<CloseToHomeResponse.Window> buildWindows(List<Candidate> nearby,
            Map<Long, Integer> driveMinutes, List<BestBet> bestBets) {
        Map<String, List<Candidate>> byWindow = new LinkedHashMap<>();
        for (Candidate c : nearby) {
            if (qualifies(c)) {
                byWindow.computeIfAbsent(eventKey(c), k -> new ArrayList<>()).add(c);
            }
        }

        List<CloseToHomeResponse.Window> windows = byWindow.values().stream()
                .sorted(Comparator.comparing((List<Candidate> g) -> g.get(0).date())
                        .thenComparing(g -> g.get(0).targetType()))
                .limit(MAX_WINDOWS)
                .map(group -> toWindow(group, driveMinutes, bestBets))
                .toList();

        // Exactly one lead card in the whole block: rank 1 of the FIRST window. Applied here
        // rather than inside toWindow because "first window" is only knowable once they are
        // ordered, and a lead per window would make the accent meaningless.
        if (windows.isEmpty()) {
            return windows;
        }
        return markLead(windows);
    }

    private CloseToHomeResponse.Window toWindow(List<Candidate> group,
            Map<Long, Integer> driveMinutes, List<BestBet> bestBets) {
        List<Candidate> sorted = group.stream()
                .sorted(byRatingThenDrive(driveMinutes))
                .toList();
        Candidate first = sorted.get(0);

        List<CloseToHomeResponse.Card> cards = sorted.stream()
                .map(c -> toCard(c, driveMinutes))
                .toList();

        // NOT IN THE BRIEFING — the signal the region-level grid structurally cannot show. The
        // region stands down for this window as a whole, yet a nearby location still rates well
        // individually.
        //
        // Read from displayVerdict, NOT the triage verdict. The grid renders displayVerdict, which
        // BriefingService re-derives from live Claude ratings on this same serve path, so reading
        // the frozen triage verdict made the chip describe a state the user cannot see — and it
        // was wrong in BOTH directions. False positive: a region triaged STANDDOWN whose nearby
        // slot then scored 4 reads "Worth it" in the grid while the chip insisted it was standing
        // down. False negative, which is worse because it is the case the flag exists for: a
        // region triaged GO whose live ratings average down to STAND_DOWN shows the grid standing
        // down with no chip at all.
        //
        // Falls back to the triage verdict when displayVerdict is absent, mirroring isPoor() one
        // level down — which already preferred displayVerdict, making the old code inconsistent
        // with its own neighbour.
        String flaggedRegion = sorted.stream()
                .filter(CloseToHomeService::regionReadsStandDown)
                .map(Candidate::briefingRegionName)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        BestBet match = matchingBestBet(bestBets, first.date(), first.targetType());

        return new CloseToHomeResponse.Window(
                first.date(), first.targetType(), first.eventTime(),
                first.rating(), group.size(),
                flaggedRegion != null, flaggedRegion,
                match != null, match == null ? null : match.region(),
                cards);
    }

    /**
     * Builds a card from a candidate — the ONE place a {@link CloseToHomeResponse.Card} is made.
     *
     * <p>The window grid and the breadcrumb's "next local window" shortcut render the same facts
     * about a location, and the shortcut is by construction rank 1 of the first window: the two
     * are ordered by the same comparator over the same qualifying set. Two mappings would let one
     * place read differently in two spots on one screen, which is the failure the whole panel
     * contract exists to prevent.
     *
     * @param candidate    the candidate to describe
     * @param driveMinutes per-location drive times for this caller
     * @return the card, never marked lead — {@link #markLead} does that afterwards, because "rank 1
     *         of the first window" is only knowable once the windows are ordered
     */
    private static CloseToHomeResponse.Card toCard(Candidate candidate,
            Map<Long, Integer> driveMinutes) {
        return new CloseToHomeResponse.Card(
                candidate.locationId(), candidate.locationName(), candidate.regionName(),
                candidate.locationTypes(), candidate.rating(),
                (int) Math.round(candidate.distanceMiles()),
                candidate.locationId() == null ? null : driveMinutes.get(candidate.locationId()),
                candidate.tideLabel(), false);
    }

    /**
     * A candidate's drive time for ordering, with "not yet calculated" sorting last rather than
     * first — an unknown drive is not evidence of a short one.
     *
     * @param candidate    the candidate
     * @param driveMinutes per-location drive times for this caller
     * @return the drive time in minutes, or {@link Integer#MAX_VALUE} when unknown
     */
    private static int driveOrFar(Candidate candidate, Map<Long, Integer> driveMinutes) {
        Integer minutes = candidate.locationId() == null
                ? null : driveMinutes.get(candidate.locationId());
        return minutes == null ? Integer.MAX_VALUE : minutes;
    }

    /**
     * The one rule that ranks candidates anywhere on this panel: rating first, then the shorter
     * drive, then the name. The tiebreak is not distance-as-rank — the class contract still holds,
     * a further location outranks a nearer one whenever it rates better — it only orders locations
     * the rating cannot separate, where "closer" beats the alphabet.
     *
     * <p>It is a method rather than three inline sorts because all three of its callers produce a
     * gold element of the same block for the same event — the lead card, "Next local window", and
     * the "Worth it" paragraph. Three copies is three chances to drift, and one of them had:
     * the paragraph kept a bare rating comparison, so a 4★ tie let it name a location that
     * neither of the two elements beside it did.
     *
     * @param driveMinutes per-location drive times for this caller
     * @return the panel's canonical candidate ordering, best first
     */
    private static Comparator<Candidate> byRatingThenDrive(Map<Long, Integer> driveMinutes) {
        return Comparator.comparing(Candidate::rating).reversed()
                .thenComparing(c -> driveOrFar(c, driveMinutes))
                .thenComparing(Candidate::locationName);
    }

    private static List<CloseToHomeResponse.Window> markLead(
            List<CloseToHomeResponse.Window> windows) {
        CloseToHomeResponse.Window head = windows.get(0);
        if (head.cards().isEmpty()) {
            return windows;
        }
        List<CloseToHomeResponse.Card> cards = new ArrayList<>(head.cards());
        CloseToHomeResponse.Card top = cards.get(0);
        cards.set(0, new CloseToHomeResponse.Card(top.locationId(), top.locationName(),
                top.regionName(), top.locationTypes(), top.rating(), top.distanceMiles(),
                top.driveMinutes(), top.tideLabel(), true));
        List<CloseToHomeResponse.Window> out = new ArrayList<>(windows);
        out.set(0, new CloseToHomeResponse.Window(head.date(), head.targetType(), head.eventTime(),
                head.bestRating(), head.withinReach(), head.notInBriefing(),
                head.flaggedRegionName(), head.sameWindowAsBestBet(), head.bestBetRegionName(),
                cards));
        return out;
    }

    /**
     * Finds the Best Bet occupying this window, if any.
     *
     * <p>Joined on {@link BestBet#event()}, which carries the date — {@code "2026-07-28_sunset"}.
     * An earlier version regenerated the pick's {@code dayName} ("Today"/"Tomorrow"/weekday) for
     * the window and compared labels. That was wrong twice over: this class's own javadoc claimed
     * BestBet had no date when it does, and the labels are baked at briefing BUILD time while the
     * comparison happened at REQUEST time. Between midnight and the next pipeline run every cached
     * label is a civil day stale, so the chip appeared on tomorrow's window and vanished from the
     * one that actually was the Best Bet.
     *
     * <p>Matching the event id also excludes aurora picks ({@code "<date>_aurora"}) and stay-home
     * picks (null event) for free, where the label comparison had to special-case them.
     */
    private static BestBet matchingBestBet(List<BestBet> bestBets, LocalDate date,
            TargetType targetType) {
        if (bestBets == null) {
            return null;
        }
        String wantEvent = date + "_" + targetType.name().toLowerCase(java.util.Locale.ROOT);
        return bestBets.stream()
                // Rank 1 only. The chip says "the Best Bet", and the banner directly above labels
                // rank 2 "ALSO GOOD" — flagging a runner-up would have the two screens contradict
                // each other, and "going local is not a compromise" is simply untrue when rank 1
                // is a different window.
                .filter(b -> b.rank() == 1)
                .filter(b -> wantEvent.equals(b.event()))
                .findFirst()
                .orElse(null);
    }

    // ── breadcrumb ────────────────────────────────────────────────────────────

    private CloseToHomeResponse.Breadcrumb buildBreadcrumb(List<BriefingDay> days,
            List<Candidate> nearby, Map<Long, Integer> driveMinutes) {
        NextEvent next = findNextEvent(days);
        if (next == null) {
            return CloseToHomeResponse.Breadcrumb.none();
        }
        List<Candidate> forNext = nearby.stream()
                .filter(c -> c.date().equals(next.date()) && c.targetType() == next.targetType())
                .toList();
        // Falls back to the briefing's own event time. Taking it only from in-radius candidates
        // left it null exactly when the block matters most — a next event carried solely by
        // distant regions — so the breadcrumb read "Tomorrow sunrise" with no time beside it.
        LocalDateTime nextTime = forNext.stream()
                .map(Candidate::eventTime).filter(Objects::nonNull).findFirst()
                .orElseGet(() -> next.eventTime());

        // The soonest local window worth leaving the house for — the hope that keeps a user
        // coming back on a stay-in night. Chronological FIRST, then best-rated within that event.
        // The event is the primary key deliberately: two locations never share a solar time to the
        // minute, so keying on the per-location time would make the rating term unreachable.
        // Tie-breaks on the shorter drive for the same reason toWindow does, and it must be the
        // SAME rule: the earliest window is also windows[0], so a rating tie broken differently
        // here made the gold "Next local window" and the gold lead card name two different
        // locations for one event.
        Candidate window = nearby.stream()
                .filter(this::qualifies)
                .sorted(Comparator.comparing(CloseToHomeService::eventKey)
                        .thenComparing(byRatingThenDrive(driveMinutes)))
                .findFirst()
                .orElse(null);
        CloseToHomeResponse.NextWindow nextWindow = window == null ? null
                : new CloseToHomeResponse.NextWindow(
                        window.date(), window.targetType(), window.eventTime(),
                        toCard(window, driveMinutes));

        // The THIRD gold element, and the same rule again. This one names a location in prose —
        // the "Worth it" paragraph renders its headline, or failing that "X leads the local
        // options at N★" — so a rating tie broken on iteration order (which is all bare
        // Stream.max offers) put the paragraph, the lead card and "Next local window" on two
        // different places for one sunrise.
        Candidate top = forNext.stream()
                .filter(this::qualifies)
                .sorted(byRatingThenDrive(driveMinutes))
                .findFirst()
                .orElse(null);
        if (top != null) {
            return new CloseToHomeResponse.Breadcrumb(true, next.date(), next.targetType(),
                    nextTime, top.locationName(), top.rating(), top.headline(), top.summary(),
                    null, nextWindow);
        }
        // Deliberately does NOT name the best nearby location here. An earlier iteration surfaced
        // one at 1.8 stars on a bad night, which undercut the stay-in verdict it sat beside.
        return new CloseToHomeResponse.Breadcrumb(false, next.date(), next.targetType(),
                nextTime, null, null, null, null, dominantReason(forNext), nextWindow);
    }

    /** The most common stand-down reason nearby, so the client can name a cause. */
    private String dominantReason(List<Candidate> candidates) {
        Map<String, Integer> counts = new HashMap<>();
        for (Candidate c : candidates) {
            if (c.standdownReason() != null && !c.standdownReason().isBlank()) {
                counts.merge(c.standdownReason(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                // Count first, then the reason text, so a tie resolves the same way every call
                // rather than on HashMap iteration order.
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private NextEvent findNextEvent(List<BriefingDay> days) {
        for (BriefingDay day : days) {
            for (BriefingEventSummary es : day.eventSummaries()) {
                if (!isEventPast(es)) {
                    return new NextEvent(day.date(), es.targetType(), earliestTime(es));
                }
            }
        }
        return null;
    }

    // ── predicates ────────────────────────────────────────────────────────────

    /**
     * True when the region cell the user can SEE reads stand-down for this window.
     *
     * <p>AWAITING is deliberately excluded: an unrated region cell does not read "stand down", so
     * the chip must not claim it does.
     */
    private static boolean regionReadsStandDown(Candidate c) {
        if (c.briefingRegionDisplayVerdict() != null) {
            return c.briefingRegionDisplayVerdict() == DisplayVerdict.STAND_DOWN;
        }
        return c.briefingRegionVerdict() == Verdict.STANDDOWN;
    }

    private boolean qualifies(Candidate c) {
        return c.rating() != null && !c.poor();
    }

    /** Mirrors the client's {@code isPoorSlot}: display verdict wins, verdict is the fallback. */
    private static boolean isPoor(BriefingSlot slot) {
        if (slot.displayVerdict() != null) {
            return slot.displayVerdict() == DisplayVerdict.STAND_DOWN
                    || slot.displayVerdict() == DisplayVerdict.AWAITING;
        }
        return slot.verdict() == Verdict.STANDDOWN;
    }

    /**
     * True once an event is far enough past to stop offering it.
     *
     * <p>Uses the earliest slot time in the group, so an event is only past when every location
     * in it is. The afterglow window matches the client's, so the two agree about which event is
     * "next" — they would otherwise disagree for 30 minutes after every sunrise.
     */
    private boolean isEventPast(BriefingEventSummary es) {
        LocalDateTime earliest = earliestTime(es);
        if (earliest == null) {
            return false;
        }
        return earliest.plusMinutes(AFTERGLOW_MINUTES).isBefore(LocalDateTime.now(clock.withZone(UTC)));
    }

    /** The earliest slot time in an event group, across regioned and unregioned slots. */
    private static LocalDateTime earliestTime(BriefingEventSummary es) {
        LocalDateTime earliest = null;
        for (BriefingRegion region : es.regions()) {
            for (BriefingSlot slot : region.slots()) {
                earliest = earlier(earliest, slot.solarEventTime());
            }
        }
        for (BriefingSlot slot : es.unregioned()) {
            earliest = earlier(earliest, slot.solarEventTime());
        }
        return earliest;
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        if (b == null) {
            return a;
        }
        return a == null || b.isBefore(a) ? b : a;
    }

    /** The tide fact worth a chip, most notable first; null when there is nothing to say. */
    private static String tideLabel(BriefingSlot slot) {
        BriefingSlot.TideInfo tide = slot.tide();
        if (tide == null) {
            return null;
        }
        // King and spring do NOT depend on tideState — a notable tide is worth saying even when
        // the state is unknown. The first port gated them on a non-null state and dropped them.
        if (Boolean.TRUE.equals(tide.isKingTide())) {
            return "king tide";
        }
        if (Boolean.TRUE.equals(tide.isSpringTide())) {
            return "spring tide";
        }
        // A plain state is only worth a chip when it MATCHES the location's preference. Without
        // this the card advertised the tide a location is explicitly not wanted at — a low-tide
        // spot sitting at high water read "high tide", which is worse than saying nothing.
        if (Boolean.TRUE.equals(tide.tideAligned()) && tide.tideState() != null) {
            return tide.tideState().toLowerCase(java.util.Locale.ROOT) + " tide";
        }
        return null;
    }

    private static String eventKey(Candidate c) {
        return c.date() + "|" + c.targetType().name();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private record Candidate(
            Long locationId, String locationName, String regionName,
            java.util.Set<com.gregochr.goldenhour.entity.LocationType> locationTypes,
            LocalDate date, TargetType targetType, LocalDateTime eventTime, double distanceMiles,
            Integer rating, boolean poor, String standdownReason, String headline, String summary,
            String tideLabel, String briefingRegionName, Verdict briefingRegionVerdict,
            DisplayVerdict briefingRegionDisplayVerdict) {
    }

    private record NextEvent(LocalDate date, TargetType targetType,
            LocalDateTime eventTime) {
    }
}
