package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.BriefingVerdictEvaluator.StanddownReason;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
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
 * <p><b>No hard constraint remains today.</b> {@link StanddownReason#TIDE_MISMATCH} was the
 * last one — a coastal slot whose tide missed the light was withheld from Claude entirely and
 * served unrated. The owner lifted that gate (2026-09-18, {@code docs/engineering/tide-window-plan.md}
 * §6 Q1): the tide is no longer a precondition for evaluation, it is a
 * {@code service.evaluation.visitor.TideVisitor} component averaged into the star by
 * {@code RatingCombiner} — "the star is the shot". {@link #HARD_CONSTRAINT_REASONS} is therefore
 * empty, and {@link #isEligibleForEvaluation}/{@link #hardConstraintReason} always answer
 * "eligible" / empty for every verdict. The mechanism itself — the set, the decode-by-label
 * lookup, the {@code evaluationGate} wording it drives in {@code BriefingSlotBuilder} — is kept
 * rather than deleted: it is the one seam a genuinely new hard physical constraint (one Claude
 * truly has no model of from prompt context alone) would re-use, and ripping it out would mean
 * re-inventing the same label round-trip and disposition-trail wiring the next time one is
 * needed. Until then it is inert by construction, not by omission.
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
     * Decodes a standdown reason label back to its enum value — the same lookup
     * {@link #isEligibleForEvaluation} and {@link #hardConstraintReason} use internally, exposed
     * so the label round-trip can be tested directly.
     *
     * <p>Needed because, with {@link #HARD_CONSTRAINT_REASONS} empty (the tide gate lift,
     * 2026-09-18), neither of those methods can any longer distinguish "the label decoded, but
     * is not a hard constraint" from "the label failed to decode" — both answer the same way.
     * Testing the decode through either method would therefore silently stop catching label
     * drift the moment the set went empty; this method keeps that guard live regardless of what
     * the set currently holds.
     *
     * @param label a {@link StanddownReason#label()} value, or any other string
     * @return the decoded reason, or empty when the label is unrecognised
     */
    static Optional<StanddownReason> decodeLabel(String label) {
        return Optional.ofNullable(REASON_BY_LABEL.get(label));
    }

    /**
     * Standdown reasons that continue to gate Claude evaluation after the
     * Gate 2 redesign. Hard physical constraints only — these are not
     * probabilistic weather signals Claude could revise.
     *
     * <p><b>Empty since the tide gate lift (2026-09-18).</b> {@code TIDE_MISMATCH} was the only
     * member; see the class javadoc for why it is gone and why the set stays rather than the
     * mechanism being deleted.
     */
    private static final Set<StanddownReason> HARD_CONSTRAINT_REASONS = EnumSet.noneOf(
            StanddownReason.class);

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

    /**
     * Returns {@code true} when the slot is being gated by a hard-constraint
     * standdown reason. {@link #HARD_CONSTRAINT_REASONS} is empty today (the
     * tide gate lift, 2026-09-18), so this answers {@code false} for every slot until a new
     * hard-constraint member is added.
     *
     * <p>Callers that emit skip diagnostics should prefer this method over
     * inspecting the slot's reason label so the diagnostic stays in lockstep
     * with {@link #HARD_CONSTRAINT_REASONS} as that set evolves.
     *
     * @param slot the briefing slot
     * @return true iff the slot is STANDDOWN and its reason is a hard constraint
     */
    public static boolean isHardConstraintSkip(BriefingSlot slot) {
        return hardConstraintReason(slot).isPresent();
    }

    /**
     * The hard constraint gating this slot, decoded — or empty when the slot reaches Claude.
     *
     * <p>For callers that must say <em>which</em> constraint fired rather than merely that one
     * did: {@code BriefingSlotBuilder} words the served {@code evaluationGate} per reason, and a
     * boolean would have every future member of {@link #HARD_CONSTRAINT_REASONS} served in the
     * tide's words.
     *
     * @param slot the briefing slot
     * @return the gating reason, or empty for GO/MARGINAL, a weather stand-down, a null label or an
     *         unrecognised one (the same safe default as {@link #isEligibleForEvaluation})
     */
    public static Optional<StanddownReason> hardConstraintReason(BriefingSlot slot) {
        if (slot.verdict() != Verdict.STANDDOWN) {
            return Optional.empty();
        }
        String label = slot.standdownReason();
        if (label == null) {
            return Optional.empty();
        }
        StanddownReason reason = REASON_BY_LABEL.get(label);
        return reason != null && HARD_CONSTRAINT_REASONS.contains(reason)
                ? Optional.of(reason)
                : Optional.empty();
    }
}
