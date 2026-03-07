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
 */
public record SunsetEvaluation(
        Integer rating,
        Integer fierySkyPotential,
        Integer goldenHourPotential,
        String summary,
        Integer basicFierySkyPotential,
        Integer basicGoldenHourPotential,
        String basicSummary
) {

    /**
     * Constructs an evaluation without basic-tier fields (no directional data was available).
     *
     * @param rating              1-5 rating
     * @param fierySkyPotential   dramatic colour potential (0-100)
     * @param goldenHourPotential overall light quality (0-100)
     * @param summary             Claude's explanation
     */
    public SunsetEvaluation(Integer rating, Integer fierySkyPotential,
            Integer goldenHourPotential, String summary) {
        this(rating, fierySkyPotential, goldenHourPotential, summary, null, null, null);
    }
}
