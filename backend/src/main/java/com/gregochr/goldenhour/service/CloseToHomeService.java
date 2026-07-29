package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
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

    /** Cards shown. Rating-ordered, so this is a "top N", not a sample. */
    static final int MAX_CARDS = 4;

    /** Forecast horizon drawn from, in distinct forecast days. */
    static final int HORIZON_DAYS = 3;

    /** Window past a solar event during which it still counts as current. */
    private static final long AFTERGLOW_MINUTES = 30;

    private static final ZoneId UTC = ZoneId.of("UTC");

    private final BriefingService briefingService;
    private final LocationService locationService;
    private final DriveTimeResolver driveTimeResolver;
    private final Clock clock;
    private final int radiusMiles;

    /**
     * Creates the service.
     *
     * @param briefingService    supplies the enriched, honesty-filtered briefing the Plan tab sees
     * @param locationService    supplies the locations roster (coordinates and regions)
     * @param driveTimeResolver  supplies the caller's own drive times, keyed by location id
     * @param clock              UTC clock, for deciding which events are still upcoming
     * @param radiusMiles        proximity gate. Configurable so the radius can become a user
     *                           setting without a client release — the client used to own this
     *                           constant, which is what made that impossible.
     */
    public CloseToHomeService(BriefingService briefingService,
            LocationService locationService,
            DriveTimeResolver driveTimeResolver,
            Clock clock,
            @Value("${photocast.close-to-home.radius-miles:22}") int radiusMiles) {
        this.briefingService = briefingService;
        this.locationService = locationService;
        this.driveTimeResolver = driveTimeResolver;
        this.clock = clock;
        this.radiusMiles = radiusMiles;
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
                homeLatitude, homeLongitude);
        if (nearby.isEmpty()) {
            return CloseToHomeResponse.empty(radiusMiles, HORIZON_DAYS);
        }

        Map<Long, Integer> driveMinutes = userId == null
                ? Map.of() : driveTimeResolver.getAllMinutes(userId);

        List<Candidate> ranked = rank(nearby);
        List<CloseToHomeResponse.Card> cards = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            Candidate c = ranked.get(i);
            cards.add(new CloseToHomeResponse.Card(
                    c.locationId(), c.locationName(), c.regionName(), c.date(), c.targetType(),
                    c.eventTime(), c.rating(), (int) Math.round(c.distanceMiles()),
                    c.locationId() == null ? null : driveMinutes.get(c.locationId()),
                    c.tideLabel(), i == 0));
        }

        return new CloseToHomeResponse(radiusMiles, HORIZON_DAYS, cards,
                buildBreadcrumb(briefing.days(), nearby));
    }

    // ── collection ────────────────────────────────────────────────────────────

    private List<Candidate> collectNearby(List<BriefingDay> days,
            Map<Long, LocationEntity> byId, Map<String, LocationEntity> byName,
            double homeLat, double homeLon) {
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
                    collectSlots(out, region.slots(), region.regionName(), day.date(),
                            es.targetType(), byId, byName, homeLat, homeLon);
                }
                // Unregioned slots count too: a location without a region is still within reach.
                collectSlots(out, es.unregioned(), null, day.date(), es.targetType(),
                        byId, byName, homeLat, homeLon);
            }
        }
        return out;
    }

    private void collectSlots(List<Candidate> out, List<BriefingSlot> slots, String briefingRegion,
            LocalDate date, TargetType targetType, Map<Long, LocationEntity> byId,
            Map<String, LocationEntity> byName, double homeLat, double homeLon) {
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
                    date, targetType, slot.solarEventTime(), miles, slot.claudeRating(),
                    isPoor(slot), slot.standdownReason(), slot.claudeHeadline(),
                    slot.claudeSummary(), tideLabel(slot)));
        }
    }

    // ── ranking ───────────────────────────────────────────────────────────────

    /**
     * Best slot per location, then best overall, capped.
     *
     * <p>The tiebreak is on (date, targetType) — the EVENT — not the per-location solar time.
     * Keying on the solar time would make the rating tiebreak unreachable, because two locations
     * never share a sunrise to the minute.
     */
    private List<Candidate> rank(List<Candidate> candidates) {
        Map<String, Candidate> best = new HashMap<>();
        for (Candidate c : candidates) {
            if (!qualifies(c)) {
                continue;
            }
            Candidate prev = best.get(c.locationName());
            boolean better = prev == null
                    || c.rating() > prev.rating()
                    || (c.rating().equals(prev.rating())
                        && eventKey(c).compareTo(eventKey(prev)) < 0);
            if (better) {
                best.put(c.locationName(), c);
            }
        }
        return best.values().stream()
                .sorted(Comparator.comparing(Candidate::rating).reversed()
                        .thenComparing(CloseToHomeService::eventKey)
                        .thenComparing(Candidate::locationName))
                .limit(MAX_CARDS)
                .toList();
    }

    // ── breadcrumb ────────────────────────────────────────────────────────────

    private CloseToHomeResponse.Breadcrumb buildBreadcrumb(List<BriefingDay> days,
            List<Candidate> nearby) {
        NextEvent next = findNextEvent(days);
        if (next == null) {
            return CloseToHomeResponse.Breadcrumb.none();
        }
        List<Candidate> forNext = nearby.stream()
                .filter(c -> c.date().equals(next.date()) && c.targetType() == next.targetType())
                .toList();
        Candidate top = forNext.stream()
                .filter(this::qualifies)
                .max(Comparator.comparing(Candidate::rating))
                .orElse(null);
        if (top != null) {
            return new CloseToHomeResponse.Breadcrumb(true, next.date(), next.targetType(),
                    top.locationName(), top.rating(), top.headline(), top.summary(), null);
        }
        return new CloseToHomeResponse.Breadcrumb(false, next.date(), next.targetType(),
                null, null, null, null, dominantReason(forNext));
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
            Long locationId, String locationName, String regionName, LocalDate date,
            TargetType targetType, LocalDateTime eventTime, double distanceMiles, Integer rating,
            boolean poor, String standdownReason, String headline, String summary,
            String tideLabel) {
    }

    private record NextEvent(LocalDate date, TargetType targetType) {
    }
}
