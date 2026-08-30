package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingDayPeak;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.BriefingWindowTide;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.PlanRenderedEvent;
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
 *   <li><b>The window verdict is the top region's own {@code displayVerdict}</b> — the region-average
 *       rule ({@link BriefingRatingStats}: mean ≥ 3.5 WORTH_IT, ≥ 2.5 MAYBE, else STAND_DOWN),
 *       already carrying that region's triage fallback for the case where nothing is rated.
 *       <b>Decided 2026-08-17</b>, replacing the best-rating rule this class shipped with: one 4★
 *       location anywhere in the roster promoted a whole window's badge while every cell beneath it
 *       read Poor, and nothing on screen said the row and the grid were answering different
 *       questions. The superseded rationale — that the average "puts a MAYBE badge above a strip
 *       whose lead card reads Worth it" — is answered rather than ignored: the single-best-spot
 *       signal is still published, on {@code bestRating}, and the card renders it as an explicitly
 *       labelled {@code best spot N★} chip that never borrows verdict vocabulary. A
 *       {@code Maybe · best spot 4★} header above a 4★ lead card is then two labelled facts, not a
 *       contradiction. See {@code docs/engineering/plan-verdict-consolidation-plan.md} §2.</li>
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
        Map<WindowKey, List<BriefingWindow.Badge>> badges = bucketTopics(response.hotTopics(), tides);

        // Pass 1: draft every window with its candidate — the best region it could offer.
        List<Draft> drafts = new ArrayList<>();
        for (BriefingDay day : response.days()) {
            for (BriefingEventSummary summary : day.eventSummaries()) {
                drafts.add(draft(day.date(), summary, badges));
            }
        }

        // The render horizon, resolved ONCE and then used for three things that must not disagree:
        // the events the client draws, the windows a pick may land on, and the events each day's
        // peak rolls up. Two of those used to be derived independently — the client kept its own
        // copy of the cap and its own pastness rule — which is how a BEST pick came to name a window
        // with no card. See docs/engineering/plan-verdict-consolidation-plan.md §1 D3 and §4 Phase 3.
        Set<WindowKey> rendered = renderHorizon(drafts, now);

        // Pass 2: one fold across the whole forecast chooses the two picks, then each window is
        // emitted carrying its own or nothing.
        Map<WindowKey, BriefingWindow.Pick> picks = selectPicks(drafts, rendered);
        int cursor = 0;
        List<BriefingDay> projected = new ArrayList<>(response.days().size());
        for (BriefingDay day : response.days()) {
            List<BriefingEventSummary> summaries = new ArrayList<>(day.eventSummaries().size());
            for (BriefingEventSummary summary : day.eventSummaries()) {
                Draft dr = drafts.get(cursor++);
                summaries.add(summary.withWindow(
                        dr.toWindow(picks.get(dr.key()), tides.get(dr.key()))));
            }
            projected.add(new BriefingDay(day.date(), summaries)
                    .withPeak(dayPeak(day.date(), summaries, rendered)));
        }
        return response.withPlan(projected, rendered.stream()
                .map(k -> new PlanRenderedEvent(k.date(), k.targetType()))
                .toList());
    }

    /**
     * The events the Plan tab draws: the leading {@link PlanRenderLimits#MAX_VISIBLE_EVENTS}
     * non-past drafts, in payload order.
     *
     * <p>Order matters. The horizon is a fact about the <b>rendered event set</b>, so it is resolved
     * chronologically <em>before</em> anything is ranked — resolving it after a rank sort let the
     * highest-rated window claim a slot whatever its position, which is the opposite of a horizon.
     * {@code drafts} is already in chronological (date, then payload) order, built by walking
     * {@code response.days()} then each day's {@code eventSummaries()}, so taking the leading N
     * non-past entries is exactly "the first N events" with no re-sort.
     *
     * <p>Scoped over every non-past draft, not only candidate-bearing ones: a window with no usable
     * gloss still occupies a slot in the rendered list, and excluding it here would let the horizon
     * stretch further than what is actually shown.
     */
    private static Set<WindowKey> renderHorizon(List<Draft> drafts, LocalDateTime now) {
        return drafts.stream()
                .filter(d -> !isPast(d, now))
                .limit(PlanRenderLimits.MAX_VISIBLE_EVENTS)
                .map(Draft::key)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * One day's peak band across the events of that day the client renders — the day card's answer.
     *
     * <p><b>Scoped to the rendered events, never to the day.</b> A day whose sunrise has passed is
     * drawn with its sunset alone, and a tile that rolled up the elapsed sunrise would describe a
     * window with no card. That scoping is why this is computed here rather than anywhere earlier:
     * the horizon is not known until the drafts are.
     *
     * <p>A region reaching WORTH_IT on one of the day's events and MAYBE on the other appears once,
     * at its better band — the tile names a region's best showing that day, not one per event. The
     * region order is the payload's own, which is what the peak-tier chips render in.
     *
     * @return the roll-up, or null when the day has no rendered event at all
     */
    private static BriefingDayPeak dayPeak(LocalDate date, List<BriefingEventSummary> summaries,
            Set<WindowKey> rendered) {
        Map<String, BriefingDayPeak.PeakRegion> best = new java.util.LinkedHashMap<>();
        boolean anyRendered = false;
        for (BriefingEventSummary summary : summaries) {
            if (!rendered.contains(new WindowKey(date, summary.targetType()))) {
                continue;
            }
            anyRendered = true;
            for (BriefingRegion region : summary.regions()) {
                // The region's OWN verdict and nothing else — the same field, read the same way,
                // that decides the window badge and the grid cell. A null one is skipped rather
                // than mapped through the region's triage: the window calls that AWAITING, and a
                // day peak mapping it to a band would put two answers for one region on one screen.
                // Unreachable on the serve path (enrichment always derives a verdict, and the
                // honesty filter writes STAND_DOWN), which is why matching the window matters more
                // than choosing between them.
                DisplayVerdict dv = region.displayVerdict();
                if (dv != DisplayVerdict.WORTH_IT && dv != DisplayVerdict.MAYBE) {
                    continue;
                }
                BriefingDayPeak.PeakRegion held = best.get(region.regionName());
                if (held == null || bandRank(dv) < bandRank(held.displayVerdict())) {
                    best.put(region.regionName(), new BriefingDayPeak.PeakRegion(
                            region.regionName(), summary.targetType(), dv));
                }
            }
        }
        if (!anyRendered) {
            return null;
        }
        DisplayVerdict peak = best.values().stream()
                .anyMatch(r -> r.displayVerdict() == DisplayVerdict.WORTH_IT)
                ? DisplayVerdict.WORTH_IT
                : best.isEmpty() ? DisplayVerdict.STAND_DOWN : DisplayVerdict.MAYBE;
        List<BriefingDayPeak.PeakRegion> atPeak = best.values().stream()
                .filter(r -> r.displayVerdict() == peak)
                .toList();
        List<TargetType> events = atPeak.stream()
                .map(BriefingDayPeak.PeakRegion::targetType)
                .distinct()
                .toList();
        return new BriefingDayPeak(peak, events, atPeak);
    }

    /** WORTH_IT outranks MAYBE; nothing else reaches this comparison. */
    private static int bandRank(DisplayVerdict verdict) {
        return verdict == DisplayVerdict.WORTH_IT ? 0 : 1;
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
                verdict(top),
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
     * <p><b>Scoped to the windows the rail actually renders</b> — the same
     * {@link PlanRenderLimits#MAX_VISIBLE_EVENTS} events the window-first cards show, not a
     * date-counted approximation of them. The briefing carries more days than the rail shows, and a
     * pick bound to a window with no tile is a recommendation the reader cannot reach — the one
     * failure this scoping exists to prevent.
     *
     * <p>The runner-up must still clear {@link AlsoGoodFloor}, exactly as it did when the two picks
     * were regions within one window: the comparands changed, the rule did not. An honest silence is
     * better than a padded second recommendation.
     */
    private static Map<WindowKey, BriefingWindow.Pick> selectPicks(List<Draft> drafts,
            Set<WindowKey> rendered) {
        List<Draft> eligible = drafts.stream()
                .filter(d -> d.candidate() != null)
                .filter(d -> rendered.contains(d.key()))
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
     * slots, falling back to the summary's own {@code solarEventTime} when no slot carries one.
     *
     * <p>Earliest rather than first-in-list: list order traces to whichever grid cell Open-Meteo
     * answered first, which is not a fact about the event. The same argument already decides
     * "is this event past" in {@code CloseToHomeService} — an event belongs to all of its
     * locations.
     *
     * <p><b>The fallback is load-bearing since this instant began deciding what is RENDERED.</b>
     * The honesty filter empties a zero-coverage region's slot list while leaving the summary's own
     * time intact, so a slot-only scan reads that window as timeless — and a timeless window counts
     * as current, spending one of the six rendered slots on an event that may be hours past. The
     * client's {@code getEventTime} has always had this fallback; before Phase 3 the two could
     * disagree without either being authoritative.
     */
    private static LocalDateTime earliestEventTime(BriefingEventSummary summary) {
        LocalDateTime earliest = null;
        for (BriefingSlot slot : allSlots(summary)) {
            LocalDateTime time = slot.solarEventTime();
            if (time != null && (earliest == null || time.isBefore(earliest))) {
                earliest = time;
            }
        }
        // The summary's own time is the fallback, and it is load-bearing now that this instant
        // decides which events are RENDERED and not merely which may hold a pick. The honesty
        // filter empties a zero-coverage region's slot list while leaving the summary's
        // solarEventTime intact, so a scan of slots alone reads that window as timeless — and a
        // timeless window counts as current, spending one of the six rendered slots on an event
        // that may be hours past. The client's getEventTime has always had this fallback; before
        // Phase 3 the two could disagree without either being authoritative.
        return earliest != null ? earliest : summary.solarEventTime();
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
     * The window's verdict: its top region's own {@code displayVerdict}, or AWAITING when the
     * window has no region at all — or one whose verdict component is missing, which only a payload
     * cached before that field existed can produce.
     *
     * <p><b>Region-led, never rating-led.</b> The region's verdict already carries both the
     * mean-rating bands and the triage fallback for a region nothing has scored, so reading it here
     * is what makes the plan's invariant — "any two Plan surfaces showing a verdict for the same
     * (region, window) show the same verdict" — hold by construction rather than by two derivations
     * happening to agree. {@code bestRating} is deliberately not consulted; see the class Javadoc
     * for what replaced it and why.
     */
    private static DisplayVerdict verdict(RankedRegion top) {
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
     * <p>Stats are recomputed from the region's own <b>voting</b> slots — the same entry set the
     * serve-time enrichment derives {@code displayVerdict} and {@code meanRating} from — so this
     * ranking cannot disagree with the region's own displayed verdict, and the {@code Pick}'s
     * published {@code averageRating} is the number the grid cell prints. Both halves of that
     * matter: the entry set has to be the voting one, or a rated wood ranks a region above its
     * own displayed band and the pick names an average no surface shows.
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
            List<BriefingRatingStats.Entry> entries =
                    BriefingSlot.votingSlots(region.slots()).stream()
                            .map(s -> new BriefingRatingStats.Entry(
                                    s.locationName(), s.claudeRating()))
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
     * Buckets every hot topic onto the window(s) it belongs to, with the badge each window earns.
     *
     * <p>A topic with no date is bucketed nowhere, and so is one with neither a solar anchor nor a
     * day-level claim — storm surge and clearance carry no anchor at all. A NIGHT topic lands on two
     * windows; see the class Javadoc. A tide topic lands on both of its day's windows; see
     * {@link #keysFor}.
     *
     * <p><b>The badge is built per key, not per topic.</b> It used to be one instance pushed to
     * every window, which was correct while a topic's every window said the same thing. A tide
     * topic's two windows do not: one is the window the water aligns with and the other is simply
     * another window on a day the sea is worth planning around, and handing the second the first's
     * sentence would print {@code "tide aligned with sunrise at 47 of 61 coastal locations"} on an
     * evening card. This is the design bundle's own {@code WTOPICS {[windowId]: [{t, d}]}} shape,
     * whose per-window {@code d} the first port collapsed away.
     */
    private static Map<WindowKey, List<BriefingWindow.Badge>> bucketTopics(List<HotTopic> topics,
            Map<WindowKey, BriefingWindowTide> tides) {
        Map<WindowKey, List<BriefingWindow.Badge>> byWindow = new HashMap<>();
        for (HotTopic topic : topics) {
            if (topic.date() == null) {
                continue;
            }
            for (WindowKey key : keysFor(topic)) {
                byWindow.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(badgeFor(topic, key, tides));
            }
        }
        return byWindow;
    }

    /**
     * Whether this topic's claim is about a <em>day</em> rather than about one window of it.
     *
     * <p>True for the two tide kinds and nothing else. A spring or king tide is a fact about the
     * moon and the water, neither of which cares which end of the day you stand at — so the pill
     * reading "Spring tide" is true of that day's sunrise and its sunset alike. Every other topic
     * type names a window: an inversion burns off, an aurora needs the dark.
     *
     * @param type the served topic type
     * @return true when both of the date's windows should carry the badge
     */
    private static boolean isDayScoped(String type) {
        return "KING_TIDE".equals(type) || "SPRING_TIDE".equals(type);
    }

    /**
     * The windows a topic's badge lands on.
     *
     * <p>⚠️ <b>A day-scoped topic is bucketed onto both windows regardless of {@code eventType},
     * including when it has none.</b> That null is not "no anchor" for a tide — it is
     * {@code HotTopicEventEnricher} reporting that the alignment has already passed, or that no
     * water reached the light at all. Reading it as "bucket nowhere" is what left a spring tide day
     * with no tide indication anywhere on the matrix once its morning had gone, and what leaves a
     * run's last day blank when its alignment falls past the end of the forecast.
     *
     * <p>⚠️ Expired windows need no guard here, but <b>not</b> because this class protects itself:
     * {@link #draft} reads the badge map for every summary, past ones included, and
     * {@link #renderHorizon} does not run until afterwards — so a badge on this morning's elapsed
     * sunrise IS serialised. What stops it being drawn is the client, which renders only
     * {@code renderedEvents} and re-applies its own pastness test on top. Stated precisely because
     * the first cut of this comment claimed an ordering guarantee this class does not have, which
     * would have misled anyone adding a consumer that walks the payload directly.
     */
    private static List<WindowKey> keysFor(HotTopic topic) {
        LocalDate date = topic.date();
        if (isDayScoped(topic.type())) {
            return List.of(new WindowKey(date, TargetType.SUNRISE),
                    new WindowKey(date, TargetType.SUNSET));
        }
        if (topic.eventType() == null) {
            return List.of();
        }
        return switch (topic.eventType()) {
            case EVENT_SUNRISE -> List.of(new WindowKey(date, TargetType.SUNRISE));
            case EVENT_SUNSET -> List.of(new WindowKey(date, TargetType.SUNSET));
            case EVENT_NIGHT -> List.of(new WindowKey(date, TargetType.SUNSET),
                    new WindowKey(date.plusDays(1), TargetType.SUNRISE));
            default -> List.of();
        };
    }

    /**
     * The badge one window carries for one topic.
     *
     * <p>Identical to the topic's own line everywhere except a day-scoped topic's <b>non-aligned</b>
     * window, which states that window's own water instead — {@code "HW 19:28 · 1h43 before
     * sunset"}. The alignment sentence is a window-level claim and stays on the window it is about;
     * this one is asked of the window being drawn.
     *
     * <p>⚠️ <b>The water is READ from {@link BriefingWindowTide}, never re-derived.</b> The first
     * cut of this computed it in {@code TideRunBuilder} as two new {@code TideRunDay} components,
     * which was wrong twice over. That builder clips its extremes to the Europe/London civil day, so
     * a late-June sunset whose nearest water is the following morning's high was told about the
     * previous evening's low — wrong extremum, wrong side, wrong state word. And the run row's
     * geometry belongs to the <em>run's</em> representative location, which is selected over a
     * different date list from the plan's, so the sentence was an unattributed high-water time
     * sitting beside an attributed one — the exact claim {@link BriefingWindowTide}'s own contract
     * forbids. This map answers the same question ("the nearest extreme of either kind, and its
     * offset from this window's own solar event") against the full extreme series, for the window
     * being drawn, from the location the row beneath it names.
     *
     * <p>{@code eventTime} is dropped rather than moved. The topic's own value names the aligned
     * event, which this window is not, and the alternatives all name a third clock anchor — the
     * split {@code BriefingWindowTide} warns about. No claim is the honest one; nothing on the Plan
     * surface reads the field.
     *
     * <p>Falls back to the topic's own detail when this window has no tide rollup at all — an
     * inland window, or a coastal one with no stored extremes. The badge is then the day-level
     * claim with the day-level sentence under it, which over-states nothing.
     */
    private static BriefingWindow.Badge badgeFor(HotTopic topic, WindowKey key,
            Map<WindowKey, BriefingWindowTide> tides) {
        String detail = topic.detail();
        String eventTime = topic.eventTime();
        if (isDayScoped(topic.type()) && !isAlignedWindow(topic, key)) {
            String water = nearestWaterPhrase(tides == null ? null : tides.get(key));
            if (water != null) {
                detail = water;
                eventTime = null;
            }
        }
        return new BriefingWindow.Badge(topic.type(), topic.label(), detail, topic.facts(),
                eventTime, TopicRarity.rankOf(topic.type()), topic.note(), topic.rarityNote(),
                topic.safetyNote());
    }

    /**
     * This window's nearest water, in the shape {@code TideRunDay.alignmentPhrase} already uses —
     * {@code "HW 19:28 · 1h43 before sunset"} — or null when the window carries no tide.
     *
     * <p>Deliberately the same idiom as the aligned window's clause, so a reader moving between the
     * two cards of one day is reading one sentence form with one meaning, and the only difference is
     * which window it is about.
     *
     * <p>All three parts are required. They are written together by {@code WindowTideRollupBuilder}
     * and a partial set means the rollup could not name a nearest extreme, in which case there is
     * nothing to say and the caller keeps the topic's own line.
     */
    private static String nearestWaterPhrase(BriefingWindowTide tide) {
        if (tide == null || tide.nearestType() == null
                || tide.nearestTime() == null || tide.nearestOffset() == null) {
            return null;
        }
        return tide.nearestType() + " " + tide.nearestTime() + " · " + tide.nearestOffset();
    }

    /**
     * Whether this window is the one the topic's own {@code eventType} names.
     *
     * <p>A topic with no {@code eventType} has no aligned window, so every window it lands on takes
     * the neutral form — which is the whole point on a day whose alignment has already passed.
     */
    private static boolean isAlignedWindow(HotTopic topic, WindowKey key) {
        if (topic.eventType() == null) {
            return false;
        }
        return key.targetType() == TargetType.SUNRISE
                ? EVENT_SUNRISE.equals(topic.eventType())
                : EVENT_SUNSET.equals(topic.eventType());
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
