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
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean forced
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
                triageReason, triageMessage, headline, null, null, false, false);
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
                triageReason, triageMessage, headline, evaluatedAt, null, false, false);
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
                triageReason, triageMessage, headline, evaluatedAt, skyRating, false, false);
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
                null, null, null, null, null, true, false);
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
                newRating == null ? false : forced);
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
                skyRating, retracted, forced);
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
                skyRating, retracted, newForced);
    }

    /**
     * Returns a copy of this result carrying the forced mark a REBUILD-OR-COMBINE site must apply
     * when it folds a newly-arrived evaluation into (or in place of) an earlier one — the single
     * combination rule referenced by every such site (round 10, P1-B). Call it on the freshly-built
     * combined/rebuilt result, passing the side that just arrived.
     *
     * <p><b>The rule: the combined result carries the newly-arrived side's own {@link #forced}
     * mark, full stop</b> — not an OR of the two sides' flags. That single formula is provably
     * correct for both cases a combination can face:
     * <ul>
     *   <li><b>Within one cycle's pair</b> (e.g. an OPEN_FELL candidate's sky task and its paired
     *       bluebell task) — either forced makes the combination forced. {@code
     *       ForecastTaskCollector} submits both tasks from the SAME loop iteration reading the SAME
     *       {@code forced} local variable, so a same-cycle pair's two sides always carry an
     *       IDENTICAL flag by construction. Reading either side's flag therefore already equals
     *       "OR of both" — there is nothing for an explicit OR to add.</li>
     *   <li><b>Across cycles</b> (a forced sky rating from cycle N recombined with an ordinary
     *       bluebell rating that arrives in cycle N+1, or the reverse) — the newer write's mark
     *       must win, because a later ORDINARY evaluation ending the exemption is the whole point:
     *       an exemption is for a candidate Gate 4 would otherwise have starved of evidence THIS
     *       cycle, not a standing grant that survives every future rating. The side passed to this
     *       method is, by construction of every call site, the side that was just produced — so
     *       reading its flag alone IS "the newer write's mark wins".</li>
     * </ul>
     *
     * <p><b>The code cannot tell a same-cycle pair from a cross-cycle recombination apart, and does
     * not need to</b> — neither {@code BriefingEvaluationResult} nor {@code
     * BriefingEvaluationService#recombineBluebell} carries a cycle identifier to compare. The single
     * formula above is deliberately the same formula for both cases; it does not branch on which
     * case it is in because the two cases were shown above to always agree.
     *
     * <p><b>Safety direction if the shared-flag invariant is ever violated</b> (e.g. a future change
     * lets a same-cycle pair's two tasks disagree on {@code forced}): this method still reads only
     * the newly-arrived side, so a same-cycle sky task wrongly marked forced while its paired
     * bluebell task is not would lose the exemption on combination — under-exempt, the same safe
     * default {@link #forced}'s own field javadoc documents for every other unknown case.
     *
     * @param newlyArrived the side of the combination that was just produced — the bluebell result
     *                      in {@code recombineBluebell}, or the equivalent "just arrived" side at
     *                      any future rebuild-or-combine site
     * @return a copy of this result carrying {@code newlyArrived}'s forced mark, or this result
     *         unchanged when it carries no rating (mirrors {@link #withForced})
     */
    public BriefingEvaluationResult withForcedFromCombination(BriefingEvaluationResult newlyArrived) {
        return withForced(newlyArrived != null && newlyArrived.forced());
    }
}
