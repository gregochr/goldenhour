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
 * @param forced              true when this location's rating was produced by a task that carried
 *                            the {@code ForceEvalHeadlineSelector} force-evaluation marker —
 *                            decoded from the Anthropic batch custom id (or, on the sync path,
 *                            from the {@code EvaluationTask.Forecast} itself) and stamped ONCE, at
 *                            the single point in {@code ForecastResultHandler#buildResult} where a
 *                            result is built from a Claude response. Backs the verdict-minimum-
 *                            sample rule's force-evaluation exemption ({@code
 *                            BriefingRegion#forcedSample}): {@code BriefingRegionEvaluationRollup}
 *                            reads this off the winning result for each rated voting slot to decide
 *                            whether the region as a whole is exempt from the sample-size gate.
 *                            {@code false} whenever {@link #rating} is null — a field with no
 *                            rating behind it cannot be a forced <em>rating</em> — and for a result
 *                            built from a {@code forecast_evaluation} row rather than the cache
 *                            (that entity carries no forced marker of its own, so a winning source
 *                            that is a bare forecast row is never forced, unknown-is-safe).
 *                            {@code @JsonInclude(NON_DEFAULT)}, unlike {@link #retracted}: this
 *                            field IS persisted into {@code cached_evaluation.results_json} — it
 *                            has to survive the write/read round trip so a later serve can read
 *                            back exactly what the writing task decided, rather than re-inferring
 *                            it from timestamps (see the class-level history below). {@code false}
 *                            is omitted from the JSON (a non-forced result is byte-identical to one
 *                            written before this field existed), so a legacy row missing it
 *                            deserialises to {@code false} — "not forced", the correct default.
 *                            ⚠️ Any LATER write for the same slot — an ordinary batch evaluation, a
 *                            synchronous admin run, a force-submit, an intraday refresh — replaces
 *                            the stored result wholesale and none of those writers ever pass
 *                            {@code forced = true}, so the flag is cleared automatically the moment
 *                            anything but the forced task's own result speaks for the slot again.
 *                            ⚠️ <b>This field used to be computed at SERVE time, by comparing a
 *                            {@code forecast_run_disposition} row's {@code created_at} against the
 *                            winning result's own evaluation instant</b> (owner decision,
 *                            2026-09-29 — see {@code EvaluationViewService}'s former {@code
 *                            loadForceEvaluatedAt}/{@code isCurrentlyForced}). A second review found
 *                            that inference provably wrong: {@code FORCE_EVALUATED} dispositions are
 *                            written at submission, before any Claude result lands, and are anchored
 *                            to the CYCLE's first job run rather than the specific bucket that
 *                            actually force-evaluated a slot — so an unrelated, ordinary rating
 *                            (from a hand-started synchronous admin run, in the reported case) that
 *                            merely landed AFTER the disposition's timestamp satisfied the same
 *                            comparison and was wrongly granted the exemption. Provenance recorded
 *                            at write time, by the task that actually produced the rating, closes
 *                            that gap by construction rather than narrowing the inference further.
 *                            ⚠️ <b>Round 12: recording {@code forced} at write time was not enough
 *                            on its own, because WRITE ORDER is not EVALUATION order.</b> A batch
 *                            can outlive its pipeline's safety timeout (Anthropic allows up to
 *                            24h), so an older, forced batch can complete and merge AFTER a newer,
 *                            ordinary batch already wrote the same slot — copying the older batch's
 *                            {@code forced = true} onto a rating a newer, ordinary evaluation
 *                            should have ended the exemption for. {@link #submittedAt} (below) is
 *                            the fix: every cache write now compares submission instants before
 *                            writing or combining, so an out-of-order arrival cannot land at all,
 *                            and {@code forced} needs no write-order reasoning of its own any
 *                            more — see {@code BriefingEvaluationService}'s class javadoc for the
 *                            general rule.
 * @param submittedAt          round 12: the instant this result's EVALUATION was SUBMITTED — for a
 *                            batch result, the orchestrated cycle's own {@code
 *                            PipelineRunEntity.triggerTime} when the batch belongs to one (shared
 *                            identically by every batch the cycle submits, including a retry —
 *                            recoverable without a schema migration via {@code
 *                            ForecastBatchEntity#pipelineRunId}), or that batch's own {@code
 *                            submittedAt} for an ad-hoc submission outside any cycle; for a
 *                            synchronous/admin result, the instant the evaluation call started.
 *                            {@code null} for a result built before this field existed, for one
 *                            built through a context with no submission instant to report, and for
 *                            a result built purely for display ({@code
 *                            EvaluationViewService#toEnrichmentResult}, never a cache write).
 *                            <b>Deliberately NOT the same thing as {@link #evaluatedAt}</b> (which
 *                            is arrival/write time) — arrival order is exactly what cannot be
 *                            trusted to decide whether an incoming result should replace or combine
 *                            with a stored one, because {@code BatchPollingService} polls every
 *                            still-{@code SUBMITTED} batch independently and a slower, OLDER batch
 *                            can finish after a faster, NEWER one. {@code
 *                            BriefingEvaluationService}'s every cache-write method compares this
 *                            field, never {@code evaluatedAt}, before writing or combining: an
 *                            incoming result whose {@code submittedAt} is strictly BEFORE the
 *                            stored result's is stale and is neither written nor combined; a
 *                            {@code null} on either side (an unknown instant, including every
 *                            legacy row written before this field existed) is never stale — read
 *                            as "unrelated, no comparison possible," the same convention {@link
 *                            #forced} and {@link #skyRating} already use for their own unknowns.
 *                            {@code @JsonInclude(NON_NULL)}: it rides {@code
 *                            cached_evaluation.results_json} so a later serve or restart can still
 *                            compare against it, and a {@code null} value is omitted so a result
 *                            with no submission instant round-trips byte-identical to one written
 *                            before this field existed.
 *                            ⚠️ <b>Round 13: comparing against a STORED result is not the whole
 *                            story either — a result can be superseded by a DECISION with no
 *                            competing result to compare against at all.</b> A later cycle's Gate 4
 *                            stability skip or triage stand-down writes no {@code cached_evaluation}
 *                            entry — only a {@code forecast_run_disposition} row — so a batch delayed
 *                            past that decision had nothing stored to lose a staleness comparison
 *                            against. {@code BriefingEvaluationService.supersededByLaterRun} is the
 *                            second check every merge method now runs, before the staleness
 *                            comparison this field drives: it asks whether a pipeline run that
 *                            started AFTER this field's own value has already recorded a decision
 *                            about the same slot, via a join through {@code ForecastBatchEntity} to
 *                            the disposition's OWNING CYCLE'S trigger time — never the disposition's
 *                            own {@code created_at}, which a same-cycle disposition always postdates
 *                            and would otherwise make every cycle's own paperwork look like it
 *                            supersedes the very result it documents. See that method's own javadoc
 *                            for the two-phase query shape and {@code BriefingEvaluationService}'s
 *                            class javadoc for the full rule. Arrival order still decides nothing.
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
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean forced,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant submittedAt
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
                triageReason, triageMessage, headline, null, null, false, false, null);
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
                triageReason, triageMessage, headline, evaluatedAt, null, false, false, null);
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
                triageReason, triageMessage, headline, evaluatedAt, skyRating, false, false, null);
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
                null, null, null, null, null, true, false, null);
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
                newRating == null ? false : forced, submittedAt);
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
                skyRating, retracted, forced, submittedAt);
    }

    /**
     * Returns a copy of this result stamped as forced (or not), for the force-evaluation sample
     * exemption — see {@link #forced}.
     *
     * <p>Applied exactly once, by {@code ForecastResultHandler#buildResult}, from the task's own
     * {@code forced} field (decoded from the batch custom id, or read directly off the sync-path
     * {@code EvaluationTask.Forecast}) — never re-derived at serve time. A no-op when
     * {@link #rating} is null: an unrated result cannot carry a forced <em>rating</em>.
     *
     * @param newForced whether the task that produced this result carried the force-evaluation
     *                  marker
     * @return a copy carrying the flag, or this result unchanged when there is no rating to flag
     */
    public BriefingEvaluationResult withForced(boolean newForced) {
        if (rating == null) {
            return this;
        }
        return new BriefingEvaluationResult(locationName, rating, fierySkyPotential,
                goldenHourPotential, summary, triageReason, triageMessage, headline, evaluatedAt,
                skyRating, retracted, newForced, submittedAt);
    }

    /**
     * Returns a copy of this result stamped with the submission instant of the evaluation that
     * produced it — see {@link #submittedAt}. Applied once, by {@code ForecastResultHandler}'s
     * three {@code buildXxx} methods, from the {@code ResultContext} the batch/sync call carried —
     * never re-derived later. Unlike {@link #withForced}, this is NOT guarded on {@link #rating}
     * being non-null: a triage or sky-not-forecast result is still a real write this evaluation's
     * submission produced, and {@code BriefingEvaluationService}'s staleness comparison needs its
     * instant regardless of whether it carries a rating.
     *
     * @param instant the instant this result's evaluation was submitted, or {@code null} to mark
     *                it unknown
     * @return a copy of this result carrying that submission instant
     */
    public BriefingEvaluationResult withSubmittedAt(Instant instant) {
        return new BriefingEvaluationResult(locationName, rating, fierySkyPotential,
                goldenHourPotential, summary, triageReason, triageMessage, headline, evaluatedAt,
                skyRating, retracted, forced, instant);
    }

    /**
     * Returns a copy of this result carrying the forced mark a same-cycle REBUILD-OR-COMBINE site
     * must apply when it folds two evaluations together — the single combination rule referenced
     * by every such site (round 10, P1-A; corrected and simplified in round 12). Call it on the
     * freshly-built combined result, passing BOTH sources that were combined.
     *
     * <p><b>The rule: the combined result of a same-cycle pair is forced if EITHER half is
     * forced</b> — a genuine, commutative OR of {@code a.forced()} and {@code b.forced()}, in
     * either order. Round 10's first cut took a single "newly-arrived" argument and read only
     * that side's flag, reasoning that a same-cycle OPEN_FELL pair's two tasks always carry an
     * IDENTICAL {@code forced} flag by construction ({@code ForecastTaskCollector} submits both
     * from the SAME loop iteration reading the SAME local variable) — true in production, but not
     * a guarantee the method itself enforced; a synthetic same-cycle pair with the two flags
     * genuinely differing exposed exactly that gap (a round-12 test caught it). Round 12 also
     * removed the reason the old shape existed at all: {@code
     * BriefingEvaluationService.recombineBluebell} now only ever reaches this method for a pair
     * confirmed, by {@link #submittedAt}, to belong to the SAME cycle — a cross-cycle pair is
     * rejected as stale, or stands alone unmixed, before combination is even considered (see that
     * method's own javadoc) — so there is no arrival-order question left for this method to reason
     * about, and a plain two-argument OR is both simpler and strictly safer.
     *
     * @param a one source of the combination (e.g. the prior stored result)
     * @param b the other source of the combination (e.g. the newly-arrived result)
     * @return a copy of this result forced if either {@code a} or {@code b} is, or this result
     *         unchanged when it carries no rating (mirrors {@link #withForced})
     */
    public BriefingEvaluationResult withForcedFromCombination(BriefingEvaluationResult a,
            BriefingEvaluationResult b) {
        boolean either = (a != null && a.forced()) || (b != null && b.forced());
        return withForced(either);
    }

    /**
     * Returns a copy of this result carrying the submission instant a same-cycle REBUILD-OR-COMBINE
     * site must apply, mirroring {@link #withForcedFromCombination} exactly (round 12). Within the
     * one case that ever reaches this — a confirmed same-cycle pair — {@code a} and {@code b}'s own
     * {@link #submittedAt} are equal whenever both are known, so which one is read makes no
     * difference; {@code b} (conventionally the newly-arrived side) is preferred, falling back to
     * {@code a} so a combination with one leg's instant unknown still stamps the other's, rather
     * than silently reverting to {@code null}.
     *
     * @param a one source of the combination (e.g. the prior stored result)
     * @param b the other source of the combination (e.g. the newly-arrived result), preferred when
     *          both carry a known instant
     * @return a copy of this result carrying the resolved submission instant
     */
    public BriefingEvaluationResult withSubmittedAtFromCombination(BriefingEvaluationResult a,
            BriefingEvaluationResult b) {
        Instant chosen = b != null && b.submittedAt() != null
                ? b.submittedAt() : (a != null ? a.submittedAt() : null);
        return withSubmittedAt(chosen);
    }
}
