package com.gregochr.goldenhour.model;

/**
 * Claude's evaluation of colour potential for a sunrise or sunset.
 *
 * <p>Returned by {@code EvaluationService} after calling the Anthropic API.
 * All strategies (Haiku, Sonnet, Opus) use the same prompt and return all
 * three scores plus a summary. When directional cloud data is available,
 * Claude also returns "basic" scores representing what the evaluation would
 * be without directional information — these are served to LITE users.
 *
 * @param rating                   1-5 rating (overall potential, enhanced when directional data available)
 * @param fierySkyPotential        dramatic colour potential (0-100, enhanced)
 * @param goldenHourPotential      overall light quality (0-100, enhanced)
 * @param summary                  Claude's plain English explanation (enhanced)
 * @param basicFierySkyPotential   dramatic colour potential without directional data (0-100), or null
 * @param basicGoldenHourPotential overall light quality without directional data (0-100), or null
 * @param basicSummary             explanation without directional data, or null
 * @param inversionScore           cloud inversion score (0-10), or null if not applicable
 * @param inversionPotential       inversion classification (NONE, MODERATE, STRONG), or null
 * @param bluebellScore            bluebell conditions score (0-10) from Claude, or null
 * @param bluebellSummary          bluebell conditions summary from Claude, or null
 * @param headline                 4-9 word Claude-authored card header (Gate 2 redesign), or null
 *                                 when Claude omitted the field (legacy responses)
 */
public record SunsetEvaluation(
        Integer rating,
        Integer fierySkyPotential,
        Integer goldenHourPotential,
        String summary,
        Integer basicFierySkyPotential,
        Integer basicGoldenHourPotential,
        String basicSummary,
        Integer inversionScore,
        String inversionPotential,
        Integer bluebellScore,
        String bluebellSummary,
        String headline
) {

    /**
     * Constructs an evaluation without basic-tier, inversion, or bluebell fields.
     *
     * @param rating              1-5 rating
     * @param fierySkyPotential   dramatic colour potential (0-100)
     * @param goldenHourPotential overall light quality (0-100)
     * @param summary             Claude's explanation
     */
    public SunsetEvaluation(Integer rating, Integer fierySkyPotential,
            Integer goldenHourPotential, String summary) {
        this(rating, fierySkyPotential, goldenHourPotential, summary,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Constructs an evaluation with basic-tier fields but no inversion or bluebell data.
     *
     * @param rating                   1-5 rating
     * @param fierySkyPotential        dramatic colour potential (0-100)
     * @param goldenHourPotential      overall light quality (0-100)
     * @param summary                  Claude's explanation
     * @param basicFierySkyPotential   basic fiery sky score, or null
     * @param basicGoldenHourPotential basic golden hour score, or null
     * @param basicSummary             basic summary, or null
     */
    public SunsetEvaluation(Integer rating, Integer fierySkyPotential,
            Integer goldenHourPotential, String summary,
            Integer basicFierySkyPotential, Integer basicGoldenHourPotential,
            String basicSummary) {
        this(rating, fierySkyPotential, goldenHourPotential, summary,
                basicFierySkyPotential, basicGoldenHourPotential, basicSummary, null, null,
                null, null, null);
    }

    /**
     * Constructs an evaluation with basic-tier and inversion fields but no bluebell data.
     *
     * @param rating                   1-5 rating
     * @param fierySkyPotential        dramatic colour potential (0-100)
     * @param goldenHourPotential      overall light quality (0-100)
     * @param summary                  Claude's explanation
     * @param basicFierySkyPotential   basic fiery sky score, or null
     * @param basicGoldenHourPotential basic golden hour score, or null
     * @param basicSummary             basic summary, or null
     * @param inversionScore           inversion score (0-10), or null
     * @param inversionPotential       inversion classification string, or null
     */
    public SunsetEvaluation(Integer rating, Integer fierySkyPotential,
            Integer goldenHourPotential, String summary,
            Integer basicFierySkyPotential, Integer basicGoldenHourPotential,
            String basicSummary, Integer inversionScore, String inversionPotential) {
        this(rating, fierySkyPotential, goldenHourPotential, summary,
                basicFierySkyPotential, basicGoldenHourPotential, basicSummary,
                inversionScore, inversionPotential, null, null, null);
    }

    /**
     * Constructs an evaluation with basic-tier, inversion, and bluebell fields but no headline.
     *
     * @param rating                   1-5 rating
     * @param fierySkyPotential        dramatic colour potential (0-100)
     * @param goldenHourPotential      overall light quality (0-100)
     * @param summary                  Claude's explanation
     * @param basicFierySkyPotential   basic fiery sky score, or null
     * @param basicGoldenHourPotential basic golden hour score, or null
     * @param basicSummary             basic summary, or null
     * @param inversionScore           inversion score (0-10), or null
     * @param inversionPotential       inversion classification string, or null
     * @param bluebellScore            bluebell conditions score (0-10), or null
     * @param bluebellSummary          bluebell conditions summary, or null
     */
    public SunsetEvaluation(Integer rating, Integer fierySkyPotential,
            Integer goldenHourPotential, String summary,
            Integer basicFierySkyPotential, Integer basicGoldenHourPotential,
            String basicSummary, Integer inversionScore, String inversionPotential,
            Integer bluebellScore, String bluebellSummary) {
        this(rating, fierySkyPotential, goldenHourPotential, summary,
                basicFierySkyPotential, basicGoldenHourPotential, basicSummary,
                inversionScore, inversionPotential, bluebellScore, bluebellSummary, null);
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
                basicFierySkyPotential, basicGoldenHourPotential, basicSummary,
                inversionScore, inversionPotential, bluebellScore, bluebellSummary, headline);
    }
}
