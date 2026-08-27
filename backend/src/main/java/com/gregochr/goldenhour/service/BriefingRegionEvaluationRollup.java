package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a weather/triage region hierarchy into a <em>scored</em> one: it walks the
 * day/event/region tree, populates each slot's Claude fields from a {@link RegionScoreResolver},
 * and rebuilds every region with the rating statistics, display verdict and confidence those
 * scores imply.
 *
 * <p><b>One owner, two timings.</b> This ran inside {@code BriefingService} until 2026-08-27,
 * reached from the build path directly and from the serve path through the
 * {@link BriefingScoreEnricher} socket. Both still call it — the difference is only which
 * resolver they hand in — but the policy now lives in one place instead of inside the class that
 * also owns caching, refresh orchestration and Open-Meteo acquisition.
 *
 * <p><b>What it owns, and what it deliberately does not.</b> It owns per-slot enrichment, roster
 * derivation, the two rating rollups, gloss invalidation and confidence derivation. It does
 * <em>not</em> own score retrieval — that stays behind {@link RegionScoreResolver}, so this class
 * cannot tell a per-region lookup from a bulk index — and it does not build the initial
 * weather-based hierarchy, which remains {@code BriefingHierarchyBuilder}'s job.
 *
 * <p>⚠️ The commentary inside these methods is load-bearing. It records why there are
 * <b>two</b> rollups over one slot list, why the confidence floor is explicit rather than null,
 * and why the coverage denominator is deliberately pessimistic for a wood-bearing region. Each
 * of those was a shipped bug before it was a comment. Move the code, keep the reasons.
 */
@Component
public class BriefingRegionEvaluationRollup implements BriefingScoreEnricher {

    private static final Logger LOG =
            LoggerFactory.getLogger(BriefingRegionEvaluationRollup.class);

    /**
     * The UK civil calendar, used only to derive the confidence horizon. A target date names a
     * solar event at a UK location, so "today" is Europe/London — see {@code ForecastHorizon}.
     */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final Clock clock;

    /**
     * Constructs the rollup.
     *
     * @param clock injectable clock; read at call time so a briefing built yesterday and served
     *              today derives its confidence horizon from the request day, not the build day
     */
    public BriefingRegionEvaluationRollup(Clock clock) {
        this.clock = clock;
    }

    /**
     * Enriches the hierarchy using the supplied {@link RegionScoreResolver}. The build path passes
     * the per-region {@code getScoresForEnrichment} lookup; the serve path passes a resolver backed
     * by a single bulk load so it does not fan out into a query per region/date/target.
     *
     * <p>Package-private, not private: this is the {@link BriefingScoreEnricher} implementation
     * bound into {@link ServedBriefingAssembler} from this class's own constructor, so a serve-path
     * change can swap the implementation later without the assembler needing to know how the
     * enrichment is actually computed. See {@code docs/engineering/served-briefing-assembler-plan.md}.
     */
    @Override
    public List<BriefingDay> enrich(List<BriefingDay> days, RegionScoreResolver resolver) {
        // Request-time "today" so the confidence horizon stays fresh when a briefing built
        // yesterday is served today (this method runs on both the build and the serve paths).
        LocalDate today = LocalDate.now(clock.withZone(LONDON));
        List<BriefingDay> enrichedDays = new ArrayList<>(days.size());
        for (BriefingDay day : days) {
            List<BriefingEventSummary> enrichedEvents = new ArrayList<>();
            for (BriefingEventSummary es : day.eventSummaries()) {
                List<BriefingRegion> enrichedRegions = new ArrayList<>();
                for (BriefingRegion region : es.regions()) {
                    Map<String, BriefingEvaluationResult> cached =
                            resolver.resolve(region.regionName(), day.date(), es.targetType());
                    List<BriefingSlot> enrichedSlots = region.slots().stream()
                            .map(slot -> enrichSlot(slot, cached))
                            .toList();
                    // TWO rollups over one slot list, because two different questions are asked
                    // of it and answering both with one number is what let a wood set a sky
                    // region's band.
                    //
                    //   coverageStats — every slot, because "how many locations here were
                    //   evaluated" counts the wood: it IS evaluated, by the bluebell or woodland
                    //   prompt, and both arms render "N of M evaluated" against the full slot list.
                    //
                    //   votingStats — the voting slots only (BriefingSlot.votingSlots), because the
                    //   VERDICT and the star are a claim about the sky. A canopy GO means heavy
                    //   cloud and mist, the opposite of what it means for a sky window, so a rated
                    //   wood used to lift the region's band — and with it the grid cell, the day
                    //   card and (since the verdict became region-led) the window badge — above
                    //   every rating those surfaces could show. Every other consumer of a region
                    //   verdict already excluded canopy; this was the one place that did not.
                    List<BriefingRatingStats.Entry> coverageEntries = enrichedSlots.stream()
                            .map(s -> new BriefingRatingStats.Entry(
                                    s.locationName(), s.claudeRating()))
                            .toList();
                    BriefingRatingStats.Stats coverageStats = BriefingRatingStats.compute(
                            coverageEntries, region.regionName(), day.date(), es.targetType());
                    List<BriefingRatingStats.Entry> votingEntries =
                            BriefingSlot.votingSlots(enrichedSlots).stream()
                                    .map(s -> new BriefingRatingStats.Entry(
                                            s.locationName(), s.claudeRating()))
                                    .toList();
                    BriefingRatingStats.Stats votingStats = BriefingRatingStats.compute(
                            votingEntries, region.regionName(), day.date(), es.targetType());
                    // Only warn about zero coverage where coverage was actually expected. An
                    // all-canopy region has none by construction and the honesty filter now
                    // passes it through untouched, so logging it here would fire per region per
                    // event on EVERY serve — drowning the line an operator greps for a genuine
                    // overnight batch failure, and asserting a rewrite that no longer happens.
                    boolean anyScoreable = enrichedSlots.stream()
                            .anyMatch(slot -> !(slot.canopy() && slot.claudeRating() == null));
                    if (coverageStats.isEmpty() && anyScoreable) {
                        LOG.info("[ZERO COVERAGE] region={} date={} target={} "
                                        + "briefingVerdict={} scoredCount=0 "
                                        + "(honesty filter will rewrite at API read time — "
                                        + "post-Gate-2 this should only fire on batch "
                                        + "failures or all-hard-constrained regions)",
                                region.regionName(), day.date(), es.targetType(),
                                region.verdict());
                    }
                    DisplayVerdict freshVerdict = BriefingRatingStats
                            .resolveRegionDisplayVerdict(votingStats, region.verdict());
                    // A gloss is Claude prose written against the verdict that held when the
                    // briefing was built. When read-time re-enrichment moves the verdict —
                    // a batch re-scored the region after build — that prose can now contradict
                    // the fresh rating (a glowing gloss on a downgraded cell). Drop it rather
                    // than show copy that disagrees with the score; regenerating would cost a
                    // Claude call. At build time the gloss is still null (generated afterwards),
                    // so this is a no-op on the write path.
                    boolean verdictChanged = region.displayVerdict() != freshVerdict;
                    String glossHeadline = verdictChanged ? null : region.glossHeadline();
                    String glossDetail = verdictChanged ? null : region.glossDetail();
                    // Derive the quiet confidence channel from horizon + rating spread/coverage.
                    // A region with NOTHING scored yields null, the documented unknown case.
                    int daysAhead = (int) (day.date().toEpochDay() - today.toEpochDay());
                    // Coverage denominator counts only slots that COULD carry a rating. A
                    // canopy slot is excluded from the sky batch, so counting it would report a
                    // permanent, unfixable shortfall and downgrade a wood-bearing region's
                    // confidence a band every day — including for the sky locations in it.
                    // Keyed on what the slot actually carries, NOT on "is it canopy": an in-season
                    // bluebell site IS scored, by the bluebell prompt. See rosterOf for why it is
                    // now a denominator alone.
                    // The VOTING stats, so the channel qualifies the verdict it is attached to:
                    // the spread term stops counting a wood as sky locations disagreeing, which it
                    // is not. The coverage term's denominator stays `scoreable` (canopy-inclusive
                    // where rated), so for a wood-bearing region the ratio is marginally
                    // pessimistic — it can only make the channel more provisional, the safe
                    // direction.
                    //
                    // The FLOOR is not decoration. Handing `derive` empty voting stats returns
                    // null, and null does NOT read as "no confidence" on the client:
                    // `confidenceUtils.resolveConfidence` is fail-soft and falls back to a
                    // horizon-only tier capped at medium. So a region whose only rated slot is a
                    // wood — verdict from the triage fallback, no sky score behind it at all —
                    // would render MEDIUM where the canopy-inclusive stats could have yielded LOW.
                    // That is the channel reading LESS provisional at the moment it knows least,
                    // the exact inversion it exists to prevent. Something here was scored and none
                    // of it votes: LOW, explicitly.
                    //
                    // Null is still right where NOTHING is scored — the documented zero-coverage
                    // case — and `coverageStats` is what tells the two apart.
                    Confidence confidence = votingStats.isEmpty() && !coverageStats.isEmpty()
                            ? Confidence.LOW
                            : ConfidenceDeriver.derive(
                                    daysAhead, votingStats, rosterOf(enrichedSlots));
                    enrichedRegions.add(new BriefingRegion(
                            region.regionName(), region.verdict(), region.summary(),
                            region.tideHighlights(), enrichedSlots,
                            region.regionTemperatureCelsius(),
                            region.regionApparentTemperatureCelsius(),
                            region.regionWindSpeedMs(), region.regionWeatherCode(),
                            glossHeadline, glossDetail,
                            // Coverage, NOT the voting count: the honesty filter tests this
                            // against its own canopy-inclusive scoreable count, and both arms
                            // render "N of M evaluated" with M as the whole slot list. A voting
                            // count here would under-report a scored wood in the drill-down and
                            // could blank a region whose only evaluated location is one.
                            freshVerdict, coverageStats.count())
                            .withConfidence(confidence)
                            // The grid cell's star, from the SAME statistics as freshVerdict above,
                            // so the word and the number in one cell can no longer come from two
                            // computations. The grid used to derive this in the browser off a second
                            // endpoint joined on a region-name prefix — see plan §1 D2. Null rather
                            // than 0.0 when nothing is scored: "not rated" and "rated badly" are
                            // different statements and the cell renders them differently.
                            .withMeanRating(
                                    votingStats.isEmpty() ? null : votingStats.averageRating())
                            // The region rail's `best N★`, from the SAME statistics again — so the
                            // best, the mean and the verdict word describe one population and the
                            // best can never sit below the mean it is printed beside. The VOTING
                            // stats for the reason the mean uses them: a woodland GO means heavy
                            // cloud and mist, so a rated wood must not supply a sky region's best
                            // spot any more than it may set its band. Null rather than 0 when
                            // nothing that votes is scored — "not rated" and "rated badly" are
                            // different statements, and BriefingRatingStats.Stats.empty() reports
                            // maxRating 0 for the same reason its averageRating is 0.0.
                            .withBestRating(
                                    votingStats.isEmpty() ? null : votingStats.maxRating()));
                }
                enrichedEvents.add(es.withRegions(enrichedRegions));
            }
            enrichedDays.add(new BriefingDay(day.date(), enrichedEvents));
        }
        return enrichedDays;
    }
    /**
     * Derives the two roster sizes {@link ConfidenceDeriver} needs from a region's slots. They are
     * different filters over the same list, and getting either wrong is silent — hence one named,
     * tested method rather than two inline stream counts at the call site.
     *
     * <p><b>scoreable</b> — slots that COULD carry a rating, the coverage DENOMINATOR. A canopy
     * slot is excluded while it holds no rating: it is out of the sky batch, so counting it
     * unconditionally would report a permanent, unfixable shortfall and downgrade a wood-bearing
     * region every day, including for the sky locations in it. Keyed on what the slot actually
     * carries rather than on the season, so no clock is needed — an in-season bluebell wood IS
     * scored, by the bluebell prompt.
     *
     * <p>Since the canopy fix it is a denominator alone: the numerator handed to
     * {@link ConfidenceDeriver} is now over VOTING slots, so a rated wood is counted here and not
     * there. The ratio is therefore slightly pessimistic for a wood-bearing region, which can only
     * downgrade — the safe direction, and preferred to measuring coverage of a population the
     * verdict does not use.
     *
     * <p><b>voting</b> — slots that vote on the region VERDICT. Mirrors
     * {@code BriefingHierarchyBuilder.buildRegion} exactly, non-canopy slots with a fallback to
     * the full list for an all-canopy region, because a floor guarding a roster the verdict does
     * not use guards the wrong number.
     *
     * <p>For a wood-bearing region in bluebell season the two genuinely differ: the scored wood
     * counts toward coverage and still never votes.
     *
     * @param slots the region's slots for one date and event
     * @return the two denominators, never null
     */
    static ConfidenceDeriver.RegionRoster rosterOf(List<BriefingSlot> slots) {
        if (slots == null || slots.isEmpty()) {
            return new ConfidenceDeriver.RegionRoster(0, 0);
        }
        long scoreable = slots.stream()
                .filter(slot -> !(slot.canopy() && slot.claudeRating() == null))
                .count();
        return new ConfidenceDeriver.RegionRoster(
                (int) scoreable, BriefingSlot.votingSlots(slots).size());
    }
    /**
     * Enriches a single slot from the resolved evaluation for its location.
     *
     * <p><b>A resolved triage clears the rating.</b> The resolver returns the winner of a merge
     * across both evaluation stores, so a triaged entry is a positive statement — "the freshest
     * thing known about this slot is that it stood down before Claude was ever called" — and not
     * merely an absence. This used to overwrite only on a non-null rating, which meant it could
     * raise a rating but never retract one: a slot scored 4★ by an overnight batch kept that 4★ on
     * the payload after a later run triaged it, while the map and the region drill-down (which
     * merge through {@code EvaluationViewService.mergeToView}) already showed the stand-down. That
     * is the same rating reading two ways on one screen, and it is the half of the divergence that
     * gating the resolver alone cannot reach.
     *
     * <p>The prose goes with the rating. A summary written to explain a 4★ is worse than no
     * summary at all once the 4★ is gone — the same reasoning that drops a region gloss when
     * re-enrichment moves its verdict.
     *
     * <p>An absent entry still leaves the slot untouched: not being in the map is an absence, and
     * only a resolved triage is evidence.
     */
    private BriefingSlot enrichSlot(BriefingSlot slot,
            Map<String, BriefingEvaluationResult> cached) {
        BriefingEvaluationResult eval = cached.get(slot.locationName());
        if (eval == null) {
            return slot;
        }
        if (eval.rating() != null) {
            return slot.withClaudeScores(eval.rating(), eval.fierySkyPotential(),
                    eval.goldenHourPotential(), eval.summary(), eval.headline());
        }
        if (eval.triageReason() != null && slot.claudeRating() != null) {
            return slot.withClaudeScores(null, null, null, null, null);
        }
        return slot;
    }
}
