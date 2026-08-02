package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.HotTopic;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p><b>Serve-time, and no carrier.</b> Every input is already on the response. Nothing here is
 * persisted, nothing is written back to the cache, and the build path does not call it. The
 * {@code AtomicReference} pattern used by {@code NlcClarityService} and {@code SurgeCurveService}
 * exists because hot topics are recomputed live and would overwrite a persisted field; {@code days}
 * is passed straight through, so a field hung off a window needs no such protection.
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
                    .thenComparing(r -> r.region().regionName(),
                            Comparator.nullsLast(Comparator.naturalOrder()));

    /** Ranks a slot above another for the Pick's named location: better rating, then name. */
    private static final Comparator<BriefingSlot> BY_SLOT_RATING =
            Comparator.comparingInt((BriefingSlot s) -> s.claudeRating()).reversed()
                    .thenComparing(BriefingSlot::locationName,
                            Comparator.nullsLast(Comparator.naturalOrder()));

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
     * @return the same response with windows projected, or null when given null
     */
    public static DailyBriefingResponse apply(DailyBriefingResponse response) {
        if (response == null) {
            return null;
        }
        Map<WindowKey, List<BriefingWindow.Badge>> badges = bucketTopics(response.hotTopics());
        List<BriefingDay> projected = new ArrayList<>(response.days().size());
        for (BriefingDay day : response.days()) {
            List<BriefingEventSummary> summaries = new ArrayList<>(day.eventSummaries().size());
            for (BriefingEventSummary summary : day.eventSummaries()) {
                summaries.add(summary.withWindow(project(day.date(), summary, badges)));
            }
            projected.add(new BriefingDay(day.date(), summaries));
        }
        return response.withDays(projected);
    }

    private static BriefingWindow project(LocalDate date, BriefingEventSummary summary,
            Map<WindowKey, List<BriefingWindow.Badge>> badgesByWindow) {
        // Decided once for the whole window, because the header star and the location it names
        // must read the same slots. Deciding it twice is how they came to disagree.
        boolean canopyCounts = canopyCounts(summary);
        List<RankedRegion> ranked = rank(date, summary, canopyCounts);
        RankedRegion top = ranked.isEmpty() ? null : ranked.get(0);
        Integer bestRating = bestRating(summary, canopyCounts);
        List<BriefingWindow.Badge> badges =
                badgesByWindow.getOrDefault(new WindowKey(date, summary.targetType()), List.of());

        BriefingWindow.Pick bestBet = pick(top, canopyCounts);
        return new BriefingWindow(
                earliestEventTime(summary),
                verdict(bestRating, top),
                bestRating,
                top == null ? null : top.region().confidence(),
                bestBet,
                bestBet == null ? null : alsoGood(ranked, top, canopyCounts),
                badges,
                rarestRank(badges));
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
            if (rating != null && rating >= MIN_RATING && rating <= MAX_RATING
                    && (best == null || rating > best)) {
                best = rating;
            }
        }
        return best;
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

    /** The Best Bet, or null when the top region has no usable gloss headline. */
    private static BriefingWindow.Pick pick(RankedRegion ranked, boolean canopyCounts) {
        if (ranked == null || !usable(ranked.region().glossHeadline())) {
            return null;
        }
        BriefingRegion region = ranked.region();
        return new BriefingWindow.Pick(
                region.regionName(),
                region.glossHeadline(),
                region.glossDetail(),
                ranked.stats().averageRating(),
                topLocationName(region, canopyCounts));
    }

    /**
     * The second region worth the drive for this same window, or null.
     *
     * <p>Never widened to a third region and never crossed to another window: a cross-day
     * alternative is the Best Bet of the window it belongs to, not an "also good" here.
     */
    private static BriefingWindow.Pick alsoGood(List<RankedRegion> ranked, RankedRegion top,
            boolean canopyCounts) {
        if (ranked.size() < 2) {
            return null;
        }
        RankedRegion candidate = ranked.get(1);
        if (!AlsoGoodFloor.qualifies(top.stats().averageRating(),
                candidate.stats().averageRating())) {
            return null;
        }
        return pick(candidate, canopyCounts);
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
     * The region's highest-rated location, read from the same slots the header star was taken
     * from.
     *
     * <p>An all-canopy region inside a mixed window names nothing rather than naming its wood: the
     * header refused that slot, and a card whose star and destination disagree sends someone to a
     * place chosen by the opposite measure of a good morning.
     */
    private static String topLocationName(BriefingRegion region, boolean canopyCounts) {
        return region.slots().stream()
                .filter(s -> (canopyCounts || !s.canopy()) && s.claudeRating() != null)
                .min(BY_SLOT_RATING)
                .map(BriefingSlot::locationName)
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
                    topic.type(), topic.label(), topic.detail(), topic.eventTime(),
                    TopicRarity.rankOf(topic.type()));
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

    /** A region paired with the rating statistics it was ranked on. */
    private record RankedRegion(BriefingRegion region, BriefingRatingStats.Stats stats) {
    }

    /** Identifies one window — the date and the solar event. */
    private record WindowKey(LocalDate date, TargetType targetType) {
    }
}
