package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.BriefingGatingPolicy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

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
        LocalDate today = LocalDate.now(clock.withZone(LONDON));
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
