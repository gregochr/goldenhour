package com.gregochr.goldenhour.model;

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
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant evaluatedAt
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
                triageReason, triageMessage, headline, null);
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
     * Returns a copy of this result with the rating replaced. All other fields are preserved.
     *
     * @param newRating the rating to apply (may be {@code null} to mark as unscored)
     * @return a new {@code BriefingEvaluationResult} with the updated rating
     */
    public BriefingEvaluationResult withRating(Integer newRating) {
        return new BriefingEvaluationResult(locationName, newRating, fierySkyPotential,
                goldenHourPotential, summary, triageReason, triageMessage, headline, evaluatedAt);
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
                goldenHourPotential, summary, triageReason, triageMessage, headline, writtenAt);
    }
}
