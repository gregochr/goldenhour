package com.gregochr.goldenhour.model;

/**
 * Claude's evaluation of colour potential for a sunrise or sunset.
 *
 * <p>Returned by {@code EvaluationService} after calling the Anthropic API.
 * All strategies (Haiku, Sonnet, Opus) use the same prompt and return all
 * three scores plus a summary, scored with directional cloud only. The older
 * "basic" (observer-point) tier is no longer requested
 * ({@code docs/engineering/basic-tier-removal-plan.md}).
 *
 * <p>Bluebell is no longer a field here: Pass 3 extracted it into its own prompt
 * ({@code BluebellEvaluation} / {@code BluebellVisitor}), so the colour evaluation
 * carries no bluebell score.
 *
 * @param rating              1-5 rating (overall potential)
 * @param fierySkyPotential   dramatic colour potential (0-100)
 * @param goldenHourPotential overall light quality (0-100)
 * @param summary             Claude's plain English explanation
 * @param inversionScore      cloud inversion score (0-10), or null if not applicable
 * @param inversionPotential  inversion classification (NONE, MODERATE, STRONG), or null
 * @param headline            4-9 word Claude-authored card header (Gate 2 redesign), or null
 *                            when Claude omitted the field (legacy responses)
 * @param mistPotential       Claude's own reading of valley mist at a {@code mist_prone} site
 *                            (a {@link MistPotential} name), or null when absent or unrecognised
 *                            (valley-mist-plan M2)
 */
public record SunsetEvaluation(
        Integer rating,
        Integer fierySkyPotential,
        Integer goldenHourPotential,
        String summary,
        Integer inversionScore,
        String inversionPotential,
        String headline,
        String mistPotential
) {

    /**
     * Constructs an evaluation without inversion, headline or mist fields.
     *
     * @param rating              1-5 rating
     * @param fierySkyPotential   dramatic colour potential (0-100)
     * @param goldenHourPotential overall light quality (0-100)
     * @param summary             Claude's explanation
     */
    public SunsetEvaluation(Integer rating, Integer fierySkyPotential,
            Integer goldenHourPotential, String summary) {
        this(rating, fierySkyPotential, goldenHourPotential, summary,
                null, null, null, null);
    }

    /**
     * Returns a copy of this evaluation with the rating replaced.
     *
     * <p>Used by the v2.13 visitor layer ({@code RatingCombiner}) so the persisted star
     * rating flows through the combiner rather than being read directly off the raw Claude
     * response. In v2.13.1 the combiner returns the same value (a single applied visitor),
     * so the copy is field-equal to the original; the indirection is what later passes
     * (tide and beyond) build on.
     *
     * @param newRating the rating to set (may be null)
     * @return a new evaluation identical to this one but with {@code rating == newRating}
     */
    public SunsetEvaluation withRating(Integer newRating) {
        return new SunsetEvaluation(newRating, fierySkyPotential, goldenHourPotential, summary,
                inversionScore, inversionPotential, headline, mistPotential);
    }
}
