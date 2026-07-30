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

    /** Cards per WINDOW. Rating-ordered, so this is a "top N" within the window, not a sample. */
    static final int MAX_CARDS_PER_WINDOW = 4;

    /** Windows shown, so the block stays a glance rather than a list. */
    static final int MAX_WINDOWS = 3;

    /** Forecast horizon drawn from, in distinct forecast days. */
    static final int HORIZON_DAYS = 3;

    /** Window past a solar event during which it still counts as current. */
    private static final long AFTERGLOW_MINUTES = 30;

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** Day labels are UK-local, matching how the Best Bet's dayName was built. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

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
                buildWindows(nearby, driveMinutes, briefing.bestBets(), today());

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
                            day.date(), es.targetType(), byId, byName, homeLat, homeLon,
                            radiusMiles);
                }
                // Unregioned slots count too: a location without a region is still within reach.
                collectSlots(out, es.unregioned(), null, null, day.date(), es.targetType(),
                        byId, byName, homeLat, homeLon, radiusMiles);
            }
        }
        return out;
    }

    private void collectSlots(List<Candidate> out, List<BriefingSlot> slots, String briefingRegion,
            Verdict briefingRegionVerdict, LocalDate date, TargetType targetType,
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
                    slot.claudeSummary(), tideLabel(slot), briefingRegion, briefingRegionVerdict));
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
            Map<Long, Integer> driveMinutes, List<BestBet> bestBets, LocalDate today) {
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
                .map(group -> toWindow(group, driveMinutes, bestBets, today))
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
            Map<Long, Integer> driveMinutes, List<BestBet> bestBets, LocalDate today) {
        List<Candidate> sorted = group.stream()
                .sorted(Comparator.comparing(Candidate::rating).reversed()
                        .thenComparing(Candidate::locationName))
                .toList();
        Candidate first = sorted.get(0);

        List<CloseToHomeResponse.Card> cards = sorted.stream()
                .limit(MAX_CARDS_PER_WINDOW)
                .map(c -> new CloseToHomeResponse.Card(
                        c.locationId(), c.locationName(), c.regionName(), c.locationTypes(),
                        c.rating(), (int) Math.round(c.distanceMiles()),
                        c.locationId() == null ? null : driveMinutes.get(c.locationId()),
                        c.tideLabel(), false))
                .toList();

        // NOT IN THE BRIEFING — the signal the region-level grid structurally cannot show. The
        // region stands down for this window as a whole, yet a nearby location still rates well
        // individually. Computed from the region verdict carried alongside each slot, never
        // hardcoded.
        String flaggedRegion = sorted.stream()
                .filter(c -> c.briefingRegionVerdict() == Verdict.STANDDOWN)
                .map(Candidate::briefingRegionName)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        BestBet match = matchingBestBet(bestBets, first.date(), first.targetType(), today);

        return new CloseToHomeResponse.Window(
                first.date(), first.targetType(), first.eventTime(),
                first.rating(), group.size(),
                flaggedRegion != null, flaggedRegion,
                match != null, match == null ? null : match.region(),
                cards);
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
     * <p>{@link BestBet} carries {@code dayName} and {@code eventType} for display but no date, so
     * this GENERATES the same labels for the window and compares, rather than parsing the pick's
     * back into a date. Within a briefing horizon of a few days a weekday name is unambiguous, and
     * generating avoids a parser that would silently stop matching if the label format changed.
     */
    private static BestBet matchingBestBet(List<BestBet> bestBets, LocalDate date,
            TargetType targetType, LocalDate today) {
        if (bestBets == null) {
            return null;
        }
        String wantDay = dayName(date, today);
        String wantEvent = targetType.name().toLowerCase(java.util.Locale.ROOT);
        return bestBets.stream()
                .filter(b -> wantDay.equals(b.dayName()) && wantEvent.equals(b.eventType()))
                .findFirst()
                .orElse(null);
    }

    /** Mirrors {@code BestBetFallbackService.dayName} so the labels compare like for like. */
    private static String dayName(LocalDate date, LocalDate today) {
        if (date.equals(today)) {
            return "Today";
        }
        if (date.equals(today.plusDays(1))) {
            return "Tomorrow";
        }
        return date.getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(LONDON));
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
        LocalDateTime nextTime = forNext.stream()
                .map(Candidate::eventTime).filter(Objects::nonNull).findFirst().orElse(null);

        // The soonest local window worth leaving the house for — the hope that keeps a user
        // coming back on a stay-in night. Chronological FIRST, then best-rated within that event.
        // The event is the primary key deliberately: two locations never share a solar time to the
        // minute, so keying on the per-location time would make the rating term unreachable.
        Candidate window = nearby.stream()
                .filter(this::qualifies)
                .sorted(Comparator.comparing(CloseToHomeService::eventKey)
                        .thenComparing(Comparator.comparing(Candidate::rating).reversed())
                        .thenComparing(Candidate::locationName))
                .findFirst()
                .orElse(null);
        CloseToHomeResponse.NextWindow nextWindow = window == null ? null
                : new CloseToHomeResponse.NextWindow(
                        window.locationId(), window.locationName(), window.rating(),
                        window.date(), window.targetType(), window.eventTime(),
                        window.locationId() == null ? null : driveMinutes.get(window.locationId()));

        Candidate top = forNext.stream()
                .filter(this::qualifies)
                .max(Comparator.comparing(Candidate::rating))
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
                    return new NextEvent(day.date(), es.targetType());
                }
            }
        }
        return null;
    }

    // ── predicates ────────────────────────────────────────────────────────────

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
        LocalDateTime earliest = null;
        for (BriefingRegion region : es.regions()) {
            for (BriefingSlot slot : region.slots()) {
                earliest = earlier(earliest, slot.solarEventTime());
            }
        }
        for (BriefingSlot slot : es.unregioned()) {
            earliest = earlier(earliest, slot.solarEventTime());
        }
        if (earliest == null) {
            return false;
        }
        return earliest.plusMinutes(AFTERGLOW_MINUTES).isBefore(LocalDateTime.now(clock.withZone(UTC)));
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
        if (tide == null || tide.tideState() == null) {
            return null;
        }
        if (Boolean.TRUE.equals(tide.isKingTide())) {
            return "king tide";
        }
        if (Boolean.TRUE.equals(tide.isSpringTide())) {
            return "spring tide";
        }
        return Objects.toString(tide.tideState(), "").toLowerCase(java.util.Locale.ROOT) + " tide";
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
            String tideLabel, String briefingRegionName, Verdict briefingRegionVerdict) {
    }

    private record NextEvent(LocalDate date, TargetType targetType) {
    }
}
