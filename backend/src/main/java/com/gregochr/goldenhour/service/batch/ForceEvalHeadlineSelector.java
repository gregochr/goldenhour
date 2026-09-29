package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.BriefingGatingPolicy;
import com.gregochr.goldenhour.util.ForecastHorizon;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Selects the capped set of far-out best-bet headline contenders to force-evaluate
 * this cycle, so a clear far-out day can be crowned with real Claude evidence even
 * when the Gate 4 stability gate would otherwise drop it.
 *
 * <p>Instance-scoped seam extracted from {@code ForecastTaskCollector}. The
 * {@code forceEvalCap} bounds the per-cycle force-eval cost; a zero cap disables
 * the feature entirely.
 *
 * <p>⚠️ <b>Interaction with the verdict-minimum-sample rule
 * ({@code docs/engineering/plan-verdict-consolidation-plan.md}, {@code VerdictSampleGate}) —
 * owner decision, 2026-09-29.</b> That rule withholds a region's own verdict, pick eligibility and
 * ranking-by-average until at least {@code VerdictSampleGate.MIN_RATED} of its voting slots are
 * rated and roughly half the voting roster has been examined. A handful of forced ratings can never
 * reach that bar on their own — this selector's {@code forceEvalCap} is a "targeted, not blanket"
 * cost bound, by design far short of a sample the gate would trust — so WITHOUT an exemption every
 * forced call this class buys would spend real Claude cost for stars nobody's verdict could use.
 * The exemption: a region with at least one CURRENTLY rated voting slot whose stored result carries
 * {@code BriefingEvaluationResult#forced} bypasses the sample gate outright — its verdict, pick
 * eligibility and ranking follow the rated average exactly as they did before the minimum-sample
 * rule existed, and its confidence takes no extra floor from it. That exemption is why this class
 * keeps its original purpose rather than being retired or repurposed by the sample rule. The
 * interaction with the PARENT stability-skip retraction (see CLAUDE.md's "Where a rating lives"
 * table) is also deliberate: a forced rating is withdrawn the next time a nightly cycle
 * stability-skips that slot again, so the exemption lasts at most until the next nightly run
 * unless the slot is forced or evaluated again that night.
 *
 * <p>⚠️ <b>The marker travels with the TASK, not with a disposition timestamp — a second Codex
 * review of #943 found the timestamp comparison this paragraph used to describe provably wrong.</b>
 * This selector's own cap writes its {@code FORCE_EVALUATED} disposition at SUBMISSION time, before
 * Claude's answer lands, AND {@code ScheduledBatchEvaluationService.persistCycleDispositions}
 * anchors every disposition in a cycle to the cycle's FIRST submitted bucket's job run — not the
 * specific far-term bucket that actually carries this selector's own force-evaluated candidates.
 * The first fix (comparing the disposition's {@code created_at} against the winning result's own
 * evaluation instant) still let an OLDER, unrelated rating — a hand-started synchronous admin run
 * for the same slot, in the reported case — satisfy the comparison merely by landing after the
 * disposition's timestamp, exempting a region on the strength of a rating that never came from this
 * selector's own spend at all. The actual fix carries {@code forced} on {@code
 * EvaluationTask.Forecast} itself, embeds it in the Anthropic batch custom id (alongside the
 * pending-row id, {@code CustomIdFactory#forForecast}) so it survives the async round trip, and
 * {@code ForecastResultHandler#buildResult} stamps {@code BriefingEvaluationResult#forced} from
 * that embedded fact — the ONE place the flag is ever set, never re-derived at serve time. See
 * {@code BriefingEvaluationResult#forced}'s own javadoc for the full history.
 *
 * <p>⚠️ <b>This selector's own force-evaluation is a DIFFERENT thing from the sample gate's
 * "examined" count.</b> {@code VerdictSampleGate#examinedCount} (P1-A, the same review) counts a
 * voting slot as examined only when the BATCH's own latest non-{@code SKIPPED_CACHED} disposition
 * was {@code SKIPPED_TRIAGED} — never a slot this class force-evaluated (that counts via the
 * ordinary rated path instead, once the instant check above confirms it), and never the briefing's
 * own weather-triage {@code Verdict}, which this selector does read ({@code
 * ForceEvalHeadlineSelector}'s own candidate scoring) but which is a different question from "did
 * the batch examine this slot this cycle".
 */
public final class ForceEvalHeadlineSelector {

    /**
     * Lowest horizon at which force-evaluation applies. T+0/T+1 are always
     * eligible under Gate 4 so never need forcing; force-eval only rescues
     * stability-gated far-out cells.
     */
    static final int FORCE_EVAL_MIN_DAYS_AHEAD = 2;

    /**
     * Highest horizon at which force-evaluation applies. Beyond T+3 there is no
     * batch model tier and the forecast is too volatile to back a headline.
     */
    static final int FORCE_EVAL_MAX_DAYS_AHEAD = 3;

    /** UK civil-date zone for "today" derivation. */

    /**
     * Hard cap on the number of stability-gated far-out candidates force-evaluated
     * per cycle because they are best-bet headline contenders. Keeps the extra
     * Claude spend tiny (a handful of far-term batch evals against ~£2.50/night)
     * and prevents "targeted" silently becoming "blanket". Zero disables the
     * feature.
     */
    private final int forceEvalCap;

    private final Clock clock;

    /**
     * Constructs a {@code ForceEvalHeadlineSelector}.
     *
     * @param forceEvalCap max force-evaluated headline candidates per cycle (0 disables)
     * @param clock        UTC clock supplying "today" (via London)
     */
    public ForceEvalHeadlineSelector(int forceEvalCap, Clock clock) {
        this.forceEvalCap = forceEvalCap;
        this.clock = clock;
    }

    /**
     * Selects the capped set of far-out headline contenders to force-evaluate
     * this cycle, identified by {@code locationName|date|targetType} key.
     *
     * <p>Scans the briefing for far-out ({@value #FORCE_EVAL_MIN_DAYS_AHEAD}..{@value
     * #FORCE_EVAL_MAX_DAYS_AHEAD}) region/event cells with at least one
     * evaluation-eligible GO slot, ranks them by GO count then tide-aligned count
     * then horizon (sooner first), and accumulates the eligible GO slots of the
     * top cells until {@link #forceEvalCap} keys are gathered. These are the cells
     * most likely to win the best-bet headline; forcing them guarantees the
     * crowned region has real Claude coverage rather than cheap-threshold survivors.
     *
     * <p>Returns an empty set when the cap is zero (feature disabled) or no far-out
     * GO cells exist. The cap bounds the set size, so the per-cycle force-eval cost
     * is bounded regardless of how many GO candidates the briefing contains.
     *
     * @param briefing the cached briefing being collected
     * @return capped set of force-eval keys (possibly empty)
     */
    public Set<String> selectForceEvalKeys(DailyBriefingResponse briefing) {
        if (forceEvalCap <= 0 || briefing.days() == null) {
            return Set.of();
        }
        LocalDate today = ForecastHorizon.today(clock);
        List<ForceEvalCell> cells = new ArrayList<>();
        for (BriefingDay day : briefing.days()) {
            int daysAhead = (int) ChronoUnit.DAYS.between(today, day.date());
            if (daysAhead < FORCE_EVAL_MIN_DAYS_AHEAD || daysAhead > FORCE_EVAL_MAX_DAYS_AHEAD) {
                continue;
            }
            for (BriefingEventSummary es : day.eventSummaries()) {
                for (BriefingRegion region : es.regions()) {
                    if (region.slots() == null) {
                        continue;
                    }
                    List<String> goNames = new ArrayList<>();
                    long tideAligned = 0;
                    for (BriefingSlot slot : region.slots()) {
                        if (slot.verdict() == Verdict.GO
                                && BriefingGatingPolicy.isEligibleForEvaluation(slot)) {
                            goNames.add(slot.locationName());
                            if (slot.tide() != null && slot.tide().tideAligned()) {
                                tideAligned++;
                            }
                        }
                    }
                    if (!goNames.isEmpty()) {
                        cells.add(new ForceEvalCell(day.date(), es.targetType(),
                                goNames.size(), tideAligned, daysAhead, goNames));
                    }
                }
            }
        }
        cells.sort(Comparator
                .comparingInt(ForceEvalCell::goCount).reversed()
                .thenComparing(Comparator.comparingLong(ForceEvalCell::tideAligned).reversed())
                .thenComparingInt(ForceEvalCell::daysAhead)
                .thenComparing(ForceEvalCell::date));
        Set<String> keys = new LinkedHashSet<>();
        for (ForceEvalCell cell : cells) {
            for (String name : cell.goLocationNames()) {
                if (keys.size() >= forceEvalCap) {
                    return keys;
                }
                keys.add(forceEvalKey(name, cell.date(), cell.targetType()));
            }
        }
        return keys;
    }

    /**
     * Builds the {@code locationName|date|targetType} key used for force-eval matching.
     *
     * @param locationName the location name
     * @param date         the event date
     * @param targetType   SUNRISE or SUNSET
     * @return the composite force-eval key
     */
    public static String forceEvalKey(String locationName, LocalDate date,
            TargetType targetType) {
        return locationName + "|" + date + "|" + targetType.name();
    }

    /**
     * A far-out briefing region/event cell ranked as a best-bet headline contender.
     *
     * @param date            the event date
     * @param targetType      SUNRISE or SUNSET
     * @param goCount         number of evaluation-eligible GO slots
     * @param tideAligned     number of tide-aligned slots (headline differentiator)
     * @param daysAhead       forecast horizon
     * @param goLocationNames eligible GO slot location names, in briefing order
     */
    private record ForceEvalCell(LocalDate date, TargetType targetType, int goCount,
            long tideAligned, int daysAhead, List<String> goLocationNames) {
    }
}
