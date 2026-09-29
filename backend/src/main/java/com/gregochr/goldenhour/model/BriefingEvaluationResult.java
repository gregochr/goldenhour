package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Per-location Claude evaluation result from a briefing drill-down forecast run.
 *
 * <p>For triaged locations, {@code rating}, {@code fierySkyPotential}, {@code goldenHourPotential}
 * and {@code summary} are all {@code null} and {@code triageReason} + {@code triageMessage} are
 * populated instead. For Claude-scored locations the triage fields are {@code null}.
 *
 * <p><b>On {@code evaluatedAt}.</b> It is the per-location counterpart to
 * {@code CachedEvaluation.evaluatedAt}, which is per <em>cache key</em> (region|date|event) and is
 * reset by any write to that region. A region's slots routinely span several batches — inland and
 * coastal are split per-location, and bluebell and woodland are their own buckets — so the
 * region-level stamp records when the region was last touched, not when this location was last
 * scored. Anything reasoning about how stale a single location's rating is, notably
 * {@code evaluation_delta_log.age_hours}, needs this field rather than that one. It is null for
 * results cached before the field existed, and for results built for display rather than for the
 * cache; {@code evaluation_delta_log.age_basis} records which of the two was available per row.
 *
 * @param locationName        the location that was evaluated
 * @param rating              1-5 star rating, or null for triage / failure
 * @param fierySkyPotential   fiery sky score 0-100, or null
 * @param goldenHourPotential golden hour score 0-100, or null
 * @param summary             Claude's plain-English explanation, or null
 * @param triageReason        categorised stand-down reason, or null if Claude-scored
 * @param triageMessage       formatted stand-down explanation text, or null
 * @param headline            4-9 word Claude-authored card header (Gate 2 redesign), or null
 *                            for legacy results that pre-date the field
 * @param evaluatedAt         when this location's result was written, or null — see above
 * @param skyRating           the sky visitor's own 1-5 component score, with no tide (or other
 *                            foreground) contribution averaged in — see {@code
 *                            BriefingSlot#skyRating} for why. Null for triage/failure, for a
 *                            woodland or WOODLAND-exposure bluebell result (no sky component
 *                            exists to carry — the combiner's rating peers were BLUEBELL alone),
 *                            and for rows persisted before this field existed. Added with the
 *                            tide gate lift (2026-09-18, {@code
 *                            docs/engineering/tide-window-plan.md} §6 Q1)
 * @param retracted           true when a nightly Gate 4 stability skip has superseded evidence
 *                            that would otherwise have spoken for this location — see {@code
 *                            EvaluationViewService#isRetractedByStabilitySkip}. Never {@code true}
 *                            on anything actually persisted to {@code cached_evaluation}:
 *                            retraction is computed at serve time, inside {@code
 *                            EvaluationViewService}'s enrichment path, over a result already read
 *                            back out of the cache — so this field exists purely to let {@link
 *                            com.gregochr.goldenhour.service.BriefingRegionEvaluationRollup
 *                            #enrichSlot} tell "retracted" apart from "the map has no entry for
 *                            this location at all", which must NOT clear a slot's embedded rating
 *                            (see that method's own javadoc). {@code @JsonIgnore}d so it never
 *                            round-trips through {@code results_json} and a legacy row missing it
 *                            always deserialises to {@code false} — the one value it may ever hold
 *                            on a real evaluation result
 * @param forced              true when this location's CURRENT rating was written by a force
 *                            evaluation rather than an ordinary one, AND that rating's own
 *                            evaluation instant is at or after the force-evaluation disposition's
 *                            {@code created_at} — see {@code
 *                            EvaluationViewService#loadForceEvaluatedAt} and {@code
 *                            ForceEvalHeadlineSelector}. Backs the verdict-minimum-sample rule's
 *                            force-evaluation exemption ({@code BriefingRegion#forcedSample}):
 *                            {@code BriefingRegionEvaluationRollup} reads this off the winning
 *                            result for each rated voting slot to decide whether the region as a
 *                            whole is exempt from the sample-size gate. {@code false} whenever
 *                            {@link #rating} is null — a field with no rating behind it cannot be a
 *                            forced <em>rating</em> — whenever the disposition lookup found nothing
 *                            or failed, and whenever the disposition exists but the winning result
 *                            demonstrably PREDATES it (a Codex review of #943, P1-B: dispositions
 *                            are persisted at submission, before any Claude result lands, so
 *                            {@code FORCE_EVALUATED} alone only ever meant "a forced run was
 *                            requested", never "this rating came from it" — an older cached rating
 *                            must not be stamped forced merely because a force-evaluation was later
 *                            requested for the same slot). Unknown never grants the exemption, the
 *                            same safe-direction rule {@code loadForceEvaluatedAt} itself documents.
 *                            {@code @JsonIgnore}d for the same reason {@link #retracted} is: it is
 *                            a serve-time annotation stamped onto a result already read back out of
 *                            the cache, never something {@code cached_evaluation} itself persists,
 *                            and a legacy row missing it deserialises to {@code false} — "not
 *                            known to be forced", the correct default
 * @param triagedByBatch      true for the synthetic marker {@link #triagedByBatch(String)} builds
 *                            for a voting slot the batch's own latest disposition (ignoring a
 *                            region-level {@code SKIPPED_CACHED} reuse) recorded as
 *                            {@code SKIPPED_TRIAGED} — never set on a real rated or cached result.
 *                            Backs the verdict-minimum-sample rule's "examined" evidence ({@code
 *                            VerdictSampleGate#examinedCount}, a Codex review of #943, P1-A): the
 *                            briefing's own weather-triage {@code Verdict} is computed
 *                            independently across the whole horizon and can disagree with, or
 *                            simply never have been asked about, what the batch actually looked at
 *                            for THIS cycle — so "examined" has to come from the batch's own
 *                            disposition, not from re-reading a verdict the batch never wrote.
 *                            {@code @JsonIgnore}d for the same reason {@link #forced} is: a
 *                            serve-time annotation over a synthetic marker, never itself persisted.
 */
public record BriefingEvaluationResult(
        String locationName,
        Integer rating,
        Integer fierySkyPotential,
        Integer goldenHourPotential,
        String summary,
        @JsonInclude(JsonInclude.Include.NON_NULL) TriageReason triageReason,
        @JsonInclude(JsonInclude.Include.NON_NULL) String triageMessage,
        @JsonInclude(JsonInclude.Include.NON_NULL) String headline,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant evaluatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer skyRating,
        @JsonIgnore boolean retracted,
        @JsonIgnore boolean forced,
        @JsonIgnore boolean triagedByBatch
) {

    /**
     * Convenience constructor for the pre-{@code evaluatedAt} shape.
     *
     * <p>Deliberately retained at the old arity so the ~120 existing construction sites, and any
     * caller that has no meaningful write time to supply, keep compiling unchanged. A result built
     * this way carries a null {@code evaluatedAt} and is indistinguishable from a legacy cached
     * row — which is correct, because in both cases the write time genuinely is not known.
     *
     * @param locationName        the location that was evaluated
     * @param rating              1-5 star rating, or null
     * @param fierySkyPotential   fiery sky score 0-100, or null
     * @param goldenHourPotential golden hour score 0-100, or null
     * @param summary             Claude's explanation, or null
     * @param triageReason        categorised stand-down reason, or null
     * @param triageMessage       formatted stand-down explanation, or null
     * @param headline            Claude-authored card header, or null
     */
    public BriefingEvaluationResult(String locationName, Integer rating,
            Integer fierySkyPotential, Integer goldenHourPotential, String summary,
            TriageReason triageReason, String triageMessage, String headline) {
        this(locationName, rating, fierySkyPotential, goldenHourPotential, summary,
                triageReason, triageMessage, headline, null, null, false, false, false);
    }

    /**
     * Convenience constructor for the pre-{@code skyRating} shape, evaluated-at included.
     *
     * <p>Retained so every construction site that predates the tide gate lift (2026-09-18) and
     * that DOES supply a write time — production and test alike — keeps compiling unchanged. A
     * result built this way carries a null {@code skyRating}, the same "unknown, not zero"
     * convention the arity above uses for {@code evaluatedAt} on legacy rows.
     *
     * @param locationName        the location that was evaluated
     * @param rating              1-5 star rating, or null
     * @param fierySkyPotential   fiery sky score 0-100, or null
     * @param goldenHourPotential golden hour score 0-100, or null
     * @param summary             Claude's explanation, or null
     * @param triageReason        categorised stand-down reason, or null
     * @param triageMessage       formatted stand-down explanation, or null
     * @param headline            Claude-authored card header, or null
     * @param evaluatedAt         when this location's result was written, or null
     */
    public BriefingEvaluationResult(String locationName, Integer rating,
            Integer fierySkyPotential, Integer goldenHourPotential, String summary,
            TriageReason triageReason, String triageMessage, String headline, Instant evaluatedAt) {
        this(locationName, rating, fierySkyPotential, goldenHourPotential, summary,
                triageReason, triageMessage, headline, evaluatedAt, null, false, false, false);
    }

    /**
     * Convenience constructor for the pre-{@code retracted} shape, every field up to and
     * including {@code skyRating} included.
     *
     * <p>Retained so every construction site that predates the stale-rating stability-skip
     * retraction rule keeps compiling unchanged. A result built this way is never itself a
     * retraction marker — use {@link #retracted(String)} for that — so defaulting {@code false}
     * here is correct for every one of those ~120 existing sites.
     *
     * @param locationName        the location that was evaluated
     * @param rating              1-5 star rating, or null
     * @param fierySkyPotential   fiery sky score 0-100, or null
     * @param goldenHourPotential golden hour score 0-100, or null
     * @param summary             Claude's explanation, or null
     * @param triageReason        categorised stand-down reason, or null
     * @param triageMessage       formatted stand-down explanation, or null
     * @param headline            Claude-authored card header, or null
     * @param evaluatedAt         when this location's result was written, or null
     * @param skyRating           the sky visitor's own component score, or null
     */
    public BriefingEvaluationResult(String locationName, Integer rating,
            Integer fierySkyPotential, Integer goldenHourPotential, String summary,
            TriageReason triageReason, String triageMessage, String headline, Instant evaluatedAt,
            Integer skyRating) {
        this(locationName, rating, fierySkyPotential, goldenHourPotential, summary,
                triageReason, triageMessage, headline, evaluatedAt, skyRating, false, false, false);
    }

    /**
     * Builds the marker a resolver returns when a nightly stability skip has superseded whatever
     * evidence used to speak for this location, and nothing newer has replaced it.
     *
     * <p>Carries no rating, no triage fields and no prose — {@link
     * com.gregochr.goldenhour.service.BriefingRegionEvaluationRollup#enrichSlot} reads only {@link
     * #retracted()} off it and clears a slot's Claude fields directly, never by inspecting
     * {@code rating} or {@code triageReason} here (which would make this indistinguishable from a
     * genuine empty or triaged result to any other reader that has not been updated to check the
     * flag first).
     *
     * @param locationName the location the marker is about
     * @return a retraction marker for that location
     */
    public static BriefingEvaluationResult retracted(String locationName) {
        return new BriefingEvaluationResult(locationName, null, null, null, null,
                null, null, null, null, null, true, false, false);
    }

    /**
     * Builds the marker a resolver returns for a voting slot nobody rated or cached anything for
     * this cycle, but whose batch's own latest non-{@code SKIPPED_CACHED} disposition was
     * {@code SKIPPED_TRIAGED} — see {@link #triagedByBatch} and {@code
     * EvaluationViewService#loadTriagedByBatch}.
     *
     * <p>Carries no rating and no triage fields, so {@link
     * com.gregochr.goldenhour.service.BriefingRegionEvaluationRollup#enrichSlot} treats it exactly
     * like an absent entry — every one of its branches is a no-op on a marker with a null rating,
     * a false {@link #retracted}, and a null {@link #triageReason}: this marker exists purely to
     * carry the "examined" evidence one step further, to {@code VerdictSampleGate.examinedCount},
     * never to touch a slot's Claude fields.
     *
     * @param locationName the location the marker is about
     * @return a batch-triaged marker for that location
     */
    public static BriefingEvaluationResult triagedByBatch(String locationName) {
        return new BriefingEvaluationResult(locationName, null, null, null, null,
                null, null, null, null, null, false, false, true);
    }

    /**
     * Convenience constructor for Claude-scored results (no triage fields, no headline).
     *
     * @param locationName        the location
     * @param rating              1-5 rating
     * @param fierySkyPotential   fiery sky score 0-100
     * @param goldenHourPotential golden hour score 0-100
     * @param summary             Claude's explanation
     */
    public BriefingEvaluationResult(String locationName, Integer rating,
            Integer fierySkyPotential, Integer goldenHourPotential, String summary) {
        this(locationName, rating, fierySkyPotential, goldenHourPotential, summary,
                null, null, null);
    }

    /**
     * Convenience constructor for triaged results (no headline).
     *
     * @param locationName        the location
     * @param rating              1-5 rating (typically null for triaged)
     * @param fierySkyPotential   fiery sky score (typically null for triaged)
     * @param goldenHourPotential golden hour score (typically null for triaged)
     * @param summary             Claude's explanation (typically null for triaged)
     * @param triageReason        categorised stand-down reason
     * @param triageMessage       formatted stand-down explanation
     */
    public BriefingEvaluationResult(String locationName, Integer rating,
            Integer fierySkyPotential, Integer goldenHourPotential, String summary,
            TriageReason triageReason, String triageMessage) {
        this(locationName, rating, fierySkyPotential, goldenHourPotential, summary,
                triageReason, triageMessage, null);
    }

    /**
     * Returns a copy of this result with the rating replaced. All other fields are preserved,
     * except {@link #skyRating}: the sky component must never be present without the combined
     * rating it is a component OF ({@code BriefingSlot#skyRating}'s documented invariant), so
     * clearing {@code newRating} to null clears {@code skyRating} too.
     *
     * @param newRating the rating to apply (may be {@code null} to mark as unscored)
     * @return a new {@code BriefingEvaluationResult} with the updated rating
     */
    public BriefingEvaluationResult withRating(Integer newRating) {
        return new BriefingEvaluationResult(locationName, newRating, fierySkyPotential,
                goldenHourPotential, summary, triageReason, triageMessage, headline, evaluatedAt,
                newRating == null ? null : skyRating, retracted,
                newRating == null ? false : forced, triagedByBatch);
    }

    /**
     * Returns a copy of this result stamped with the instant it was written to the cache.
     *
     * <p>Applied by {@code BriefingEvaluationService} on every write path, so the stamp is set
     * once, at the single point where "this location's result was persisted" is actually true.
     * Callers that build a result for display rather than for the cache should not call this.
     *
     * @param writtenAt the instant this result was written; must not be null
     * @return a new {@code BriefingEvaluationResult} carrying that write time
     */
    public BriefingEvaluationResult withEvaluatedAt(Instant writtenAt) {
        return new BriefingEvaluationResult(locationName, rating, fierySkyPotential,
                goldenHourPotential, summary, triageReason, triageMessage, headline, writtenAt,
                skyRating, retracted, forced, triagedByBatch);
    }

    /**
     * Returns a copy of this result stamped as forced (or not), for the force-evaluation sample
     * exemption — see {@link #forced}.
     *
     * <p>Applied by {@code EvaluationViewService} once it has decided which source (cache or
     * {@code forecast_evaluation}) speaks for this slot AND confirmed that source's own evaluation
     * instant is at or after the slot's latest {@code FORCE_EVALUATED} disposition ({@code
     * loadForceEvaluatedAt}). A no-op when {@link #rating} is null: an unrated result cannot carry
     * a forced <em>rating</em>.
     *
     * @param newForced whether the winning result demonstrably came from a force evaluation
     * @return a copy carrying the flag, or this result unchanged when there is no rating to flag
     */
    public BriefingEvaluationResult withForced(boolean newForced) {
        if (rating == null) {
            return this;
        }
        return new BriefingEvaluationResult(locationName, rating, fierySkyPotential,
                goldenHourPotential, summary, triageReason, triageMessage, headline, evaluatedAt,
                skyRating, retracted, newForced, triagedByBatch);
    }
}
