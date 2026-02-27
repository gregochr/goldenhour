package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AnthropicProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.service.JobRunService;

/**
 * Evaluation strategy using Claude Haiku for lower cost and latency.
 *
 * <p>Uses the shared system prompt and response format from
 * {@link AbstractEvaluationStrategy}. Only the underlying model differs.
 */
public class HaikuEvaluationStrategy extends AbstractEvaluationStrategy {

    // Override getSystemPrompt() and getPromptSuffix() to customise the Haiku prompt.
    // The simpler prompt below was used before all strategies were unified:
    //
    // private static final String HAIKU_SYSTEM_PROMPT =
    //         "You are an expert sunrise/sunset colour potential advisor for landscape "
    //         + "photographers.\n"
    //         + "Evaluate on three scales:\n"
    //         + "  1. Rating: 1-5 scale (overall potential)\n"
    //         + "  2. Fiery Sky Potential: 0-100 (dramatic colour, vivid reds/oranges)\n"
    //         + "  3. Golden Hour Potential: 0-100 (overall light quality, softness)\n\n"
    //         + "Key criteria: clear horizon critical (high low cloud >70% = poor); "
    //         + "mid/high cloud above clear horizon = ideal canvas; "
    //         + "post-rain clearing often vivid; "
    //         + "moderate aerosol/dust (AOD 0.1-0.25) enhances red scattering; "
    //         + "high humidity (>80%) mutes colours; "
    //         + "low boundary layer traps aerosols near surface.\n\n"
    //         + "For coastal locations, tide data may be provided. Factor it briefly.\n\n"
    //         + "Respond ONLY with raw JSON (no code fences):\n"
    //         + "{\"rating\": <1-5>, \"fiery_sky_potential\": <0-100>,\n"
    //         + "\"golden_hour_potential\": <0-100>, \"summary\": \"<1-2 sentences>\"}\n"
    //         + "Do not use double-quote characters within the summary text.";
    //
    // @Override
    // protected String getSystemPrompt() {
    //     return HAIKU_SYSTEM_PROMPT;
    // }

    /**
     * Constructs a {@code HaikuEvaluationStrategy}.
     *
     * @param client          configured Anthropic client
     * @param properties      Anthropic configuration (model identifier)
     * @param objectMapper    Jackson mapper for parsing Claude's JSON response
     * @param jobRunService   optional service for metrics tracking
     */
    public HaikuEvaluationStrategy(AnthropicClient client, AnthropicProperties properties,
            ObjectMapper objectMapper, JobRunService jobRunService) {
        super(client, properties, objectMapper, jobRunService);
    }

    @Override
    protected EvaluationModel getEvaluationModel() {
        return EvaluationModel.HAIKU;
    }

    @Override
    protected String getModelName() {
        return "claude-haiku-4-5";
    }
}
