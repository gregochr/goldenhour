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
        String inversionPotential
) {

    /**
     * Constructs an evaluation without basic-tier or inversion fields.
     *
     * @param rating              1-5 rating
     * @param fierySkyPotential   dramatic colour potential (0-100)
     * @param goldenHourPotential overall light quality (0-100)
     * @param summary             Claude's explanation
     */
    public SunsetEvaluation(Integer rating, Integer fierySkyPotential,
            Integer goldenHourPotential, String summary) {
        this(rating, fierySkyPotential, goldenHourPotential, summary,
                null, null, null, null, null);
    }

    /**
     * Constructs an evaluation with basic-tier fields but no inversion data.
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
                basicFierySkyPotential, basicGoldenHourPotential, basicSummary, null, null);
    }
}
