package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.BriefingVerdictEvaluator.StanddownReason;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for whether a briefing slot is eligible for Claude evaluation.
 *
 * <p>Replaces the duplicated {@code verdict == GO || verdict == MARGINAL} predicate
 * that previously lived in three places:
 * <ul>
 *   <li>{@code ForecastTaskCollector} (batch candidate filter)</li>
 *   <li>{@code BriefingEvaluationService} (SSE "Run full forecast" drill-down)</li>
 *   <li>{@code BriefingGlossService} (Claude-authored region gloss generation)</li>
 * </ul>
 *
 * <p><b>Gate 2 redesign (Option B):</b> weather-condition STANDDOWN verdicts (cloud,
 * precip, mist, visibility, clear-sky, sun-blocked-horizon, building cloud, fallback)
 * NO LONGER gate Claude evaluation. The verdict still gets computed by
 * {@link BriefingVerdictEvaluator} and stored on the slot for display, but it stops
 * being a filter. Claude evaluates these slots and surfaces nuance the threshold
 * pipeline cannot.
 *
 * <p>Only <b>hard-constraint</b> reasons continue to gate. Currently that is
 * {@link StanddownReason#TIDE_MISMATCH} — a deterministic geometric constraint
 * (a low-tide-only beach is unphotographable at high tide regardless of weather)
 * that Claude has no model of from prompt context alone.
 *
 * <p>The slot's {@code standdownReason} is currently stored as a human-readable
 * label String, not the enum. This class decodes the label back to the enum via
 * a static lookup, with a round-trip test ({@code BriefingGatingPolicyTest}) that
 * asserts every enum value's label is present in the map. Treating an unknown
 * label as "not a hard constraint" is the safe default — the slot reaches Claude
 * and Claude rates it down rather than being silently dropped.
 */
public final class BriefingGatingPolicy {

    /**
     * Reverse lookup from human-readable label to the {@link StanddownReason} enum.
     * Built once at class load. {@link BriefingGatingPolicy} consumers compare
     * against this map; a labelled assertion in the unit test guards against
     * label drift.
     */
    private static final Map<String, StanddownReason> REASON_BY_LABEL =
            Arrays.stream(StanddownReason.values())
                    .collect(Collectors.toUnmodifiableMap(
                            StanddownReason::label, r -> r));

    /**
     * Standdown reasons that continue to gate Claude evaluation after the
     * Gate 2 redesign. Hard physical constraints only — these are not
     * probabilistic weather signals Claude could revise.
     */
    private static final Set<StanddownReason> HARD_CONSTRAINT_REASONS =
            EnumSet.of(StanddownReason.TIDE_MISMATCH);

    private BriefingGatingPolicy() {
    }

    /**
     * Returns {@code true} when the slot should be evaluated by Claude.
     *
     * <p>GO and MARGINAL slots are always eligible. STANDDOWN slots are eligible
     * iff their reason is NOT a hard constraint (see {@link #HARD_CONSTRAINT_REASONS}).
     * Slots with a STANDDOWN verdict but an unrecognised or null reason label are
     * treated as eligible (safe default — Claude will rate them down rather than
     * being silently skipped).
     *
     * @param slot the briefing slot to evaluate (must not be null)
     * @return true if Claude should evaluate this slot
     */
    public static boolean isEligibleForEvaluation(BriefingSlot slot) {
        if (slot.verdict() == Verdict.GO || slot.verdict() == Verdict.MARGINAL) {
            return true;
        }
        String label = slot.standdownReason();
        if (label == null) {
            return true;
        }
        StanddownReason reason = REASON_BY_LABEL.get(label);
        if (reason == null) {
            return true;
        }
        return !HARD_CONSTRAINT_REASONS.contains(reason);
    }

    /**
     * Returns {@code true} when at least one slot in the region is eligible for
     * Claude evaluation. Used by region-level callers (notably
     * {@code BriefingGlossService}) that previously gated on the region's
     * rolled-up verdict.
     *
     * @param region the briefing region (must not be null)
     * @return true if any slot in the region is eligible
     */
    public static boolean hasAnyEligibleSlot(BriefingRegion region) {
        return region.slots().stream().anyMatch(BriefingGatingPolicy::isEligibleForEvaluation);
    }
}
