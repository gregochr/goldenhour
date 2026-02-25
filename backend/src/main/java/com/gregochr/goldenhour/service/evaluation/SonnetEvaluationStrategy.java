package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AnthropicProperties;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluation strategy using Claude Sonnet for higher accuracy.
 *
 * <p>Produces dual 0–100 scores ({@code fierySkyPotential} and
 * {@code goldenHourPotential}) and a 2–3 sentence explanation.
 * The resulting {@link SunsetEvaluation} has both score fields populated and
 * {@code rating} set to {@code null}.
 */
public class SonnetEvaluationStrategy extends AbstractEvaluationStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(SonnetEvaluationStrategy.class);

    /** Extracts the fiery sky potential score from Claude's response. */
    static final Pattern FIERY_SKY_PATTERN =
            Pattern.compile("\"fiery_sky\"\\s*:\\s*(\\d+)");

    /** Extracts the golden hour potential score from Claude's response. */
    static final Pattern GOLDEN_HOUR_PATTERN =
            Pattern.compile("\"golden_hour\"\\s*:\\s*(\\d+)");

    /** System prompt instructing Sonnet to return two 0–100 scores. */
    static final String SYSTEM_PROMPT =
            "You are an expert sunrise/sunset colour potential advisor for landscape photographers.\n"
            + "Score two dimensions and explain briefly.\n\n"
            + "Key criteria: clear horizon critical (high low cloud >70% = poor for fiery sky); "
            + "mid/high cloud above clear horizon = ideal canvas for fiery sky; "
            + "post-rain clearing often vivid; "
            + "moderate aerosol/dust (AOD 0.1-0.25) enhances red scattering; "
            + "high humidity (>80%) mutes colours; "
            + "low boundary layer traps aerosols near surface.\n\n"
            + "Solar/antisolar horizon model: at sunset the sun is west — the solar horizon "
            + "(west) must be clear for light penetration, while mid/high cloud on the antisolar "
            + "side (east) at 20-60% catches and reflects colour. Sunrise is the reverse. "
            + "Since data is non-directional, use altitude as proxy: low cloud (0-3km) sits near "
            + "the horizon and blocks light; mid (3-8km) and high (8+km) cloud sits above and "
            + "catches it. Ideal: low cloud <30% with mid/high 20-60%.\n\n"
            + "For coastal locations, tide data may be provided. When available:\n"
            + "- High tide can expose dramatic rock formations and alter water colour\n"
            + "- Low tide may reveal sand patterns and new horizon details\n"
            + "- If the tide aligns with the photographer's preference, factor this favourably\n"
            + "- If not aligned, briefly mention the tide limitation but don't heavily penalise unless extreme\n\n"
            + "Respond ONLY with raw JSON (no code fences):\n"
            + "{\"fiery_sky\": <0-100>, \"golden_hour\": <0-100>, \"summary\": \"<2 sentences>\"}\n\n"
            + "fiery_sky: dramatic colour potential. Requires clouds (mid/high) to catch light. "
            + "Clear sky = 20-40. Ideal cloud canvas with clear horizon = 70-90. Total overcast = 5-15.\n"
            + "golden_hour: overall light quality. Clear sky with good visibility scores well. "
            + "Clear + low humidity + moderate aerosol = 65-85. Overcast = 10-30. Haze = varies.\n"
            + "Do not use double-quote characters within the summary text.";

    /** Prompt suffix requesting a detailed 2-3 sentence explanation. */
    static final String PROMPT_SUFFIX = "Score both dimensions and explain in 2-3 sentences.";

    /**
     * Constructs a {@code SonnetEvaluationStrategy}.
     *
     * @param client          configured Anthropic client
     * @param properties      Anthropic configuration (model identifier)
     * @param objectMapper    Jackson mapper for parsing Claude's JSON response
     * @param jobRunService   optional service for metrics tracking
     */
    public SonnetEvaluationStrategy(AnthropicClient client, AnthropicProperties properties,
            ObjectMapper objectMapper, com.gregochr.goldenhour.service.JobRunService jobRunService) {
        super(client, properties, objectMapper, jobRunService);
    }

    @Override
    protected String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    protected String getPromptSuffix() {
        return PROMPT_SUFFIX;
    }

    /**
     * Parses Claude's dual-score JSON response into a {@link SunsetEvaluation}.
     *
     * <p>First attempts strict JSON parsing; falls back to regex if the summary
     * contains unescaped quote characters.
     *
     * @param text         the raw text returned by Claude
     * @param objectMapper Jackson mapper for JSON parsing
     * @return evaluation with {@code fierySkyPotential} and {@code goldenHourPotential}
     *         populated; {@code rating} is null
     * @throws IllegalArgumentException if the response cannot be parsed
     */
    @Override
    protected SunsetEvaluation parseEvaluation(String text, ObjectMapper objectMapper) {
        String cleaned = text.trim()
                .replaceAll("(?s)^```(?:json)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .trim();
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            int fierySky = node.get("fiery_sky").asInt();
            int goldenHour = node.get("golden_hour").asInt();
            String summary = node.get("summary").asText();
            return new SunsetEvaluation(null, fierySky, goldenHour, summary);
        } catch (Exception jsonException) {
            return parseWithRegexFallback(text, jsonException);
        }
    }

    /**
     * Fallback parser when the JSON is structurally invalid (e.g. unescaped quotes in summary).
     *
     * @param text  the raw response text
     * @param cause the original JSON parse exception, re-thrown if regex also fails
     * @return the parsed evaluation
     * @throws IllegalArgumentException if scores and summary cannot be extracted
     */
    private SunsetEvaluation parseWithRegexFallback(String text, Exception cause) {
        LOG.warn("Sonnet response was not valid JSON — falling back to regex parser: {}",
                cause.getMessage());
        Matcher fierySkyMatcher = FIERY_SKY_PATTERN.matcher(text);
        Matcher goldenHourMatcher = GOLDEN_HOUR_PATTERN.matcher(text);
        Matcher summaryMatcher = SUMMARY_PATTERN.matcher(text);

        if (fierySkyMatcher.find() && goldenHourMatcher.find() && summaryMatcher.find()) {
            int fierySky = Integer.parseInt(fierySkyMatcher.group(1));
            int goldenHour = Integer.parseInt(goldenHourMatcher.group(1));
            String summary = summaryMatcher.group(1);
            return new SunsetEvaluation(null, fierySky, goldenHour, summary);
        }

        throw new IllegalArgumentException(
                "Failed to parse Sonnet evaluation response: " + text, cause);
    }
}
