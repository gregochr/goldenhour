package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.BriefingWindowTide;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.HotTopic;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projects each solar event in a served briefing into the {@link BriefingWindow} the window-first
 * Plan tab renders: verdict, best rating, confidence, Best Bet, Also good, and the hot topics that
 * land on it.
 *
 * <p><b>This must run last, and that is the whole design.</b> It is applied as the outermost step
 * of {@code BriefingService.getCachedBriefingForApi}, after {@code BriefingHonestyFilter}. Anywhere
 * upstream and the projection describes regions the served payload no longer contains: the honesty
 * filter blanks a zero-coverage region to STAND_DOWN with an empty slot list and a null gloss
 * headline, so a window projected before it would name a Best Bet, a verdict and a rating for a
 * region the same response simultaneously reports as unevaluable. That failure renders perfectly
 * and is visible only by opening the drill-down, which is why the ordering is pinned by a test
 * rather than by this paragraph.
 *
 * <p><b>Serve-time, and no carrier.</b> Every input is already on the response, or is handed in as a
 * map derived alongside it. Nothing here is persisted, nothing is written back to the cache, and the
 * build path does not call it. The {@code AtomicReference} pattern used by {@code NlcClarityService}
 * and {@code SurgeCurveService} exists because hot topics are recomputed live and would overwrite a
 * persisted field; {@code days} is passed straight through, so a field hung off a window needs no
 * such protection.
 *
 * <p><b>Dependency-free, and kept that way.</b> Anything needing a repository is derived by a
 * component and passed in — {@code WindowTideRollupBuilder} supplies the tide rollups exactly as the
 * badges arrive on the response. Injecting a repository here would turn every future window field
 * into a licence for another one, and would put a DB read inside a pure projection.
 *
 * <p><b>Two rules the design did not state, decided here and recorded so they can be argued with.</b>
 * <ul>
 *   <li><b>The window verdict is the best rating's own verdict</b>, falling back to the top region's
 *       when nothing is rated. Every fixture in the design of record satisfies it, and it makes the
 *       window badge equal the badge on its own best spot card by construction — which is what
 *       "verdict colours consistent in every location" requires. The rejected alternative, the top
 *       region's {@code displayVerdict} (an average), puts a MAYBE badge above a strip whose lead
 *       card reads Worth it.</li>
 *   <li><b>A NIGHT topic lands on two windows</b> — that evening's sunset and the following
 *       morning's sunrise — because a night runs across the date boundary and the topic carries an
 *       evening and a morning half. The design's own mock does not settle this; the twilight-window
 *       calculator does, computing its sunrise from {@code eveningDate.plusDays(1)}.</li>
 * </ul>
 */
public final class PlanWindowProjector {

    /** Ranks a region above another: better average, then wider scored coverage, then name. */
    private static final Comparator<RankedRegion> BY_RANK =
            Comparator.comparingDouble((RankedRegion r) -> r.stats().averageRating()).reversed()
                    .thenComparing(Comparator.comparingInt(
                            (RankedRegion r) -> r.stats().count()).reversed())
                    // Content before name: with nothing rated, a blanked region (no slots at all)
                    // and a real one both compute to 0.0/0, and the tie fell through to the region's
                    // display string — so renaming a region flipped the window's verdict.
                    .thenComparing((RankedRegion r) -> r.region().slots().isEmpty())
                    .thenComparing(r -> r.region().regionName(),
                            Comparator.nullsLast(Comparator.naturalOrder()));

    /** Ranks a slot above another for the Pick's named location: better rating, then name. */
    private static final Comparator<BriefingSlot> BY_SLOT_RATING =
            Comparator.comparingInt((BriefingSlot s) -> s.claudeRating()).reversed()
                    .thenComparing(BriefingSlot::locationName,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * How many <em>dates</em> of windows the picks may be chosen from.
     *
     * <p>Mirrors the rail's own window (`STRIP_MAX_DAYS` in `DailyBriefing.jsx`). Counted over the
     * dates that still have a live window, never over list positions: the briefing's day list is
     * built from the <em>build</em> day, so on a next-day serve `days[0]` is yesterday. Indexing
     * would then admit an elapsed day and exclude a rendered one at the far end.
     */
    private static final int RENDERED_DAY_COUNT = 4;

    /**
     * How long a window stays pickable after its solar event.
     *
     * <p>Same value and same reason as {@code CloseToHomeService} — the colour does not stop at the
     * instant of sunset, and a pick that vanishes while you are still standing in it is worse than
     * one that lingers half an hour.
     */
    private static final long AFTERGLOW_MINUTES = 30;

    /**
     * Ranks one window's candidate above another's: better region average, then sooner.
     *
     * <p>Chronology is the tie-break rather than a second quality term because the picks exist to
     * be acted on — between two equally good windows the nearer one is the one you can still make.
     */
    private static final Comparator<Draft> BY_PICK_RANK =
            Comparator.comparingDouble(Draft::averageRating).reversed()
                    .thenComparing(Draft::eventTime,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(d -> d.key().date())
                    .thenComparing(d -> d.key().targetType());

    /** Claude's rating scale, inclusive — anything outside it is a bad row, not a score. */
    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;

    private static final String EVENT_SUNRISE = "SUNRISE";
    private static final String EVENT_SUNSET = "SUNSET";
    private static final String EVENT_NIGHT = "NIGHT";

    private PlanWindowProjector() {
    }

    /**
     * Returns the response with a window attached to every event summary.
     *
     * @param response the fully filtered served briefing, or null when none has been built
     * @param now      the request instant, used to refuse a pick on an elapsed window
     * @param tides    the tide rollup per window; empty when none could be derived, and missing
     *                 individual keys for any window whose date has no drawable tide
     * @return the same response with windows projected, or null when given null
     */
    public static DailyBriefingResponse apply(DailyBriefingResponse response, LocalDateTime now,
            Map<WindowKey, BriefingWindowTide> tides) {
        if (response == null) {
            return null;
        }
        Map<WindowKey, List<BriefingWindow.Badge>> badges = bucketTopics(response.hotTopics());

        // Pass 1: draft every window with its candidate — the best region it could offer.
        List<Draft> drafts = new ArrayList<>();
        for (BriefingDay day : response.days()) {
            for (BriefingEventSummary summary : day.eventSummaries()) {
                drafts.add(draft(day.date(), summary, badges));
            }
        }

        // Pass 2: one fold across the whole forecast chooses the two picks, then each window is
        // emitted carrying its own or nothing.
        Map<WindowKey, BriefingWindow.Pick> picks = selectPicks(drafts, now);
        int cursor = 0;
        List<BriefingDay> projected = new ArrayList<>(response.days().size());
        for (BriefingDay day : response.days()) {
            List<BriefingEventSummary> summaries = new ArrayList<>(day.eventSummaries().size());
            for (BriefingEventSummary summary : day.eventSummaries()) {
                Draft dr = drafts.get(cursor++);
                summaries.add(summary.withWindow(
                        dr.toWindow(picks.get(dr.key()), tides.get(dr.key()))));
            }
            projected.add(new BriefingDay(day.date(), summaries));
        }
        return response.withDays(projected);
    }

    private static Draft draft(LocalDate date, BriefingEventSummary summary,
            Map<WindowKey, List<BriefingWindow.Badge>> badgesByWindow) {
        // Decided once for the whole window, because the header star and the location it names
        // must read the same slots. Deciding it twice is how they came to disagree.
        boolean canopyCounts = canopyCounts(summary);
        List<RankedRegion> ranked = rank(date, summary, canopyCounts);
        RankedRegion top = ranked.isEmpty() ? null : ranked.get(0);
        Integer bestRating = bestRating(summary, canopyCounts);
        List<BriefingWindow.Badge> badges =
                badgesByWindow.getOrDefault(new WindowKey(date, summary.targetType()), List.of());

        return new Draft(
                new WindowKey(date, summary.targetType()),
                earliestEventTime(summary),
                verdict(bestRating, top),
                bestRating,
                top == null ? null : top.region().confidence(),
                candidate(top, canopyCounts),
                top == null ? 0.0 : top.stats().averageRating(),
                badges,
                rarestRank(badges));
    }

    /**
     * Chooses the forecast's two picks and binds each to the window it falls on.
     *
     * <p><b>Ranked across windows, not within one.</b> The picks answer "which window in this
     * forecast is worth planning for", so a Best Bet on Tuesday's sunrise and an Also good on
     * Thursday's sunset is the normal shape, not an anomaly.
     *
     * <p><b>Scoped to the windows the rail actually renders.</b> The briefing carries more days than
     * the rail shows, and a pick bound to a window with no tile is a recommendation the reader
     * cannot reach — the one failure this scoping exists to prevent.
     *
     * <p>The runner-up must still clear {@link AlsoGoodFloor}, exactly as it did when the two picks
     * were regions within one window: the comparands changed, the rule did not. An honest silence is
     * better than a padded second recommendation.
     */
    private static Map<WindowKey, BriefingWindow.Pick> selectPicks(List<Draft> drafts,
            LocalDateTime now) {
        // Order matters here. The horizon is a fact about DATES, so it is resolved chronologically
        // BEFORE anything is ranked — resolving it after the rank sort let the highest-rated window
        // claim a date slot whatever its date, which is the opposite of a horizon.
        List<Draft> live = drafts.stream()
                .filter(d -> d.candidate() != null)
                .filter(d -> !isPast(d, now))
                .toList();
        Set<LocalDate> rendered = live.stream()
                .map(d -> d.key().date())
                .distinct()
                .sorted()
                .limit(RENDERED_DAY_COUNT)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Draft> eligible = live.stream()
                .filter(d -> rendered.contains(d.key().date()))
                .sorted(BY_PICK_RANK)
                .toList();
        if (eligible.isEmpty()) {
            return Map.of();
        }
        Draft best = eligible.get(0);
        Map<WindowKey, BriefingWindow.Pick> picks = new HashMap<>();
        picks.put(best.key(), best.candidate().withKind(BriefingWindow.PickKind.BEST));
        if (eligible.size() > 1) {
            Draft also = eligible.get(1);
            if (AlsoGoodFloor.qualifies(best.averageRating(), also.averageRating())) {
                picks.put(also.key(), also.candidate().withKind(BriefingWindow.PickKind.ALSO));
            }
        }
        return picks;
    }

    /**
     * The window's header time — the earliest slot time across every region and the unregioned
     * slots.
     *
     * <p>Earliest rather than first-in-list: list order traces to whichever grid cell Open-Meteo
     * answered first, which is not a fact about the event. The same argument already decides
     * "is this event past" in {@code CloseToHomeService} — an event belongs to all of its
     * locations.
     */
    private static LocalDateTime earliestEventTime(BriefingEventSummary summary) {
        LocalDateTime earliest = null;
        for (BriefingSlot slot : allSlots(summary)) {
            LocalDateTime time = slot.solarEventTime();
            if (time != null && (earliest == null || time.isBefore(earliest))) {
                earliest = time;
            }
        }
        return earliest;
    }

    /**
     * The highest rating in the window, over non-canopy slots.
     *
     * <p>Canopy slots are excluded for the reason the region vote already excludes them: a woodland
     * GO means heavy cloud and mist, the opposite of what it means for a sky window, and averaging
     * the two averages two opposite meanings. An all-canopy window falls back to its own slots
     * rather than reporting nothing, mirroring the {@code votingSlots} fallback.
     *
     * <p>Unregioned slots are excluded because they are never enriched with Claude scores, so their
     * rating is always null — including them is a no-op today and a lie the day that changes.
     */
    private static Integer bestRating(BriefingEventSummary summary, boolean canopyCounts) {
        List<BriefingSlot> slots = regionSlots(summary);
        return maxRating(canopyCounts ? slots : slots.stream().filter(s -> !s.canopy()).toList());
    }

    /**
     * Whether canopy slots count for this window — true only when the window has no sky slot at
     * all.
     *
     * <p>Keyed on the <em>presence</em> of a sky slot, never on whether one is rated. The two are
     * not the same population and the difference is an ordinary misty sunrise: the woodland lane is
     * deliberately exempt from the sky triage, so the fog that leaves every sky slot unrated is the
     * same fog that scores the wood well. Rated-keyed, that morning hands the window to the wood.
     */
    private static boolean canopyCounts(BriefingEventSummary summary) {
        List<BriefingSlot> slots = regionSlots(summary);
        return !slots.isEmpty() && slots.stream().allMatch(BriefingSlot::canopy);
    }

    /**
     * The highest valid rating in the given slots, or null.
     *
     * <p>Out-of-range ratings are discarded rather than reported. The ranking already drops them
     * through {@code RatingValidator}, and a header that printed a value the ranking refused would
     * let one bad row be both rejected and displayed. Silently here: the ranking logs it once per
     * region on this same request, and a second warning would only duplicate that.
     */
    private static Integer maxRating(List<BriefingSlot> slots) {
        Integer best = null;
        for (BriefingSlot slot : slots) {
            Integer rating = slot.claudeRating();
            if (usableRating(slot) && (best == null || rating > best)) {
                best = rating;
            }
        }
        return best;
    }

    /**
     * Whether a slot carries a rating this projection will read.
     *
     * <p>One predicate for every reader, because the header star and the location it names must
     * agree about which rows exist. Bounding only one of them is how a row rejected by the star,
     * by the region average and by the ranking could still become the Best Bet's destination.
     */
    private static boolean usableRating(BriefingSlot slot) {
        Integer rating = slot.claudeRating();
        return rating != null && rating >= MIN_RATING && rating <= MAX_RATING;
    }

    /**
     * The window's verdict: the best rating's own verdict, or the top region's when nothing is
     * rated, or AWAITING when there is nothing at all.
     */
    private static DisplayVerdict verdict(Integer bestRating, RankedRegion top) {
        if (bestRating != null) {
            return DisplayVerdict.resolve(bestRating, null);
        }
        if (top != null && top.region().displayVerdict() != null) {
            return top.region().displayVerdict();
        }
        return DisplayVerdict.AWAITING;
    }

    /**
     * Ranks the window's regions by average rating, then scored coverage, then name.
     *
     * <p>Nothing upstream orders regions — the hierarchy builder appends them in grid-fetch order —
     * so this ranking is authored here rather than inherited. The coverage tie-break is
     * load-bearing rather than decorative: averages are rounded to one decimal place before any
     * comparison and there are only a handful of enabled regions, so ties are routine.
     *
     * <p>Stats are recomputed from the region's own slots — the same entry set the serve-time
     * enrichment used — so this ranking cannot disagree with the region's own displayed verdict.
     */
    private static List<RankedRegion> rank(LocalDate date, BriefingEventSummary summary,
            boolean canopyCounts) {
        List<BriefingRegion> eligible = canopyCounts ? summary.regions()
                : summary.regions().stream()
                        .filter(r -> r.slots().isEmpty() || r.slots().stream()
                                .anyMatch(s -> !s.canopy()))
                        .toList();
        List<RankedRegion> ranked = new ArrayList<>(eligible.size());
        for (BriefingRegion region : eligible) {
            List<BriefingRatingStats.Entry> entries = region.slots().stream()
                    .map(s -> new BriefingRatingStats.Entry(s.locationName(), s.claudeRating()))
                    .toList();
            ranked.add(new RankedRegion(region, BriefingRatingStats.compute(
                    entries, region.regionName(), date, summary.targetType())));
        }
        ranked.sort(BY_RANK);
        return ranked;
    }

    /**
     * This window's candidate for the forecast-wide pick — its top region's narrative — or null when
     * that region has no usable gloss headline.
     *
     * <p>A candidate is not a recommendation. Most windows have one and publish nothing.
     */
    /**
     * Whether this window's moment has gone.
     *
     * <p>A window with no time at all counts as <b>current</b>, never as past — the same choice
     * {@code CloseToHomeService} makes. Reading a missing time as elapsed would silently publish no
     * picks at all for a briefing whose slots happen to carry no solar time.
     */
    private static boolean isPast(Draft draft, LocalDateTime now) {
        LocalDateTime time = draft.eventTime();
        return time != null && time.plusMinutes(AFTERGLOW_MINUTES).isBefore(now);
    }

    private static BriefingWindow.Pick candidate(RankedRegion ranked, boolean canopyCounts) {
        if (ranked == null || !usable(ranked.region().glossHeadline())) {
            return null;
        }
        BriefingRegion region = ranked.region();
        BriefingSlot destination = topSlot(region, canopyCounts);
        return new BriefingWindow.Pick(
                null,
                region.regionName(),
                region.glossHeadline(),
                region.glossDetail(),
                ranked.stats().averageRating(),
                destination == null ? null : destination.locationName(),
                destination == null ? null : destination.locationId());
    }

    /**
     * Whether a gloss headline can be shown.
     *
     * <p>Keyed on the headline and never the detail: the honesty filter nulls the headline while
     * setting the detail to canned "too unsettled to evaluate" copy, so a detail-first rule would
     * promote that failure text into a recommendation.
     *
     * <p>The {@code "null"} case is not defensive padding. The gloss parser accepts a JSON node
     * whose value is an explicit null, and reading that as text yields the four-character string.
     */
    private static boolean usable(String headline) {
        return headline != null && !headline.isBlank() && !"null".equalsIgnoreCase(headline.trim());
    }

    /**
     * The region's highest-rated slot, read from the same population the header star was taken
     * from — returned whole so the Pick's id and name come from one value rather than two lookups.
     *
     * <p>An all-canopy region inside a mixed window resolves to nothing rather than to its wood:
     * the header refused that slot, and a card whose star and destination disagree sends someone
     * to a place chosen by the opposite measure of a good morning.
     */
    private static BriefingSlot topSlot(BriefingRegion region, boolean canopyCounts) {
        return region.slots().stream()
                .filter(s -> (canopyCounts || !s.canopy()) && usableRating(s))
                .min(BY_SLOT_RATING)
                .orElse(null);
    }

    /**
     * Buckets every hot topic onto the window(s) it belongs to.
     *
     * <p>A topic with no date or no solar event is bucketed nowhere — storm surge and clearance
     * carry no event anchor at all, and a tide topic resolves one only when its metrics say which
     * event it aligns with. A NIGHT topic lands on two windows; see the class Javadoc.
     */
    private static Map<WindowKey, List<BriefingWindow.Badge>> bucketTopics(List<HotTopic> topics) {
        Map<WindowKey, List<BriefingWindow.Badge>> byWindow = new HashMap<>();
        for (HotTopic topic : topics) {
            if (topic.date() == null || topic.eventType() == null) {
                continue;
            }
            BriefingWindow.Badge badge = new BriefingWindow.Badge(
                    topic.type(), topic.label(), topic.detail(), topic.facts(), topic.eventTime(),
                    TopicRarity.rankOf(topic.type()), topic.safetyNote());
            for (WindowKey key : keysFor(topic)) {
                byWindow.computeIfAbsent(key, k -> new ArrayList<>()).add(badge);
            }
        }
        return byWindow;
    }

    private static List<WindowKey> keysFor(HotTopic topic) {
        LocalDate date = topic.date();
        return switch (topic.eventType()) {
            case EVENT_SUNRISE -> List.of(new WindowKey(date, TargetType.SUNRISE));
            case EVENT_SUNSET -> List.of(new WindowKey(date, TargetType.SUNSET));
            case EVENT_NIGHT -> List.of(new WindowKey(date, TargetType.SUNSET),
                    new WindowKey(date.plusDays(1), TargetType.SUNRISE));
            default -> List.of();
        };
    }

    private static Integer rarestRank(List<BriefingWindow.Badge> badges) {
        return badges.stream()
                .mapToInt(BriefingWindow.Badge::rarityRank)
                .min()
                .stream().boxed().findFirst().orElse(null);
    }

    private static List<BriefingSlot> regionSlots(BriefingEventSummary summary) {
        return summary.regions().stream().flatMap(r -> r.slots().stream()).toList();
    }

    private static List<BriefingSlot> allSlots(BriefingEventSummary summary) {
        List<BriefingSlot> slots = new ArrayList<>(regionSlots(summary));
        slots.addAll(summary.unregioned());
        return slots;
    }

    /**
     * One window, fully derived except for whether it won a pick.
     *
     * <p>The projection is two-pass because a forecast-wide choice cannot be made while walking the
     * days one at a time. Everything here is final at the end of pass one; only the pick is
     * outstanding.
     */
    private record Draft(
            WindowKey key,
            LocalDateTime eventTime,
            DisplayVerdict verdict,
            Integer bestRating,
            Confidence confidence,
            BriefingWindow.Pick candidate,
            double averageRating,
            List<BriefingWindow.Badge> badges,
            Integer topRarityRank) {

        BriefingWindow toWindow(BriefingWindow.Pick awarded, BriefingWindowTide tide) {
            return new BriefingWindow(eventTime, verdict, bestRating, confidence,
                    awarded, badges, topRarityRank, tide);
        }
    }

    /** A region paired with the rating statistics it was ranked on. */
    private record RankedRegion(BriefingRegion region, BriefingRatingStats.Stats stats) {
    }

    /**
     * Identifies one window — the date and the solar event.
     *
     * <p>Public because it is the key of the tide-rollup map handed in from
     * {@code WindowTideRollupBuilder}. Window identity is defined here, in the class that defines
     * the window, so a second producer of per-window data cannot key its map on a different notion
     * of which window it belongs to.
     *
     * @param date       the window's local date
     * @param targetType the solar event
     */
    public record WindowKey(LocalDate date, TargetType targetType) {
    }
}
