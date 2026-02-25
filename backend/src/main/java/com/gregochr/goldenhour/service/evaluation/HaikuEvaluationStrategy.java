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
 * Evaluation strategy using Claude Haiku for lower cost and latency.
 *
 * <p>Produces a 1–5 rating and a concise 1–2 sentence explanation.
 * The resulting {@link SunsetEvaluation} has {@code rating} populated and
 * {@code fierySkyPotential} / {@code goldenHourPotential} set to {@code null}.
 */
public class HaikuEvaluationStrategy extends AbstractEvaluationStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(HaikuEvaluationStrategy.class);

    /** Extracts the integer rating from Claude's response. */
    static final Pattern RATING_PATTERN = Pattern.compile("\"rating\"\\s*:\\s*(\\d+)");

    /** System prompt instructing Haiku to return a 1–5 rating. */
    static final String SYSTEM_PROMPT =
            "You are an expert sunrise/sunset colour potential advisor for landscape photographers.\n"
            + "Rate the colour potential on a 1-5 scale and explain briefly.\n\n"
            + "Key criteria: clear horizon critical (high low cloud >70% = poor); "
            + "mid/high cloud above clear horizon = ideal canvas; "
            + "post-rain clearing often vivid; "
            + "moderate aerosol/dust (AOD 0.1-0.25) enhances red scattering; "
            + "high humidity (>80%) mutes colours; "
            + "low boundary layer traps aerosols near surface.\n\n"
            + "For coastal locations, tide data may be provided. Factor it briefly.\n\n"
            + "Rating scale: 1=poor, 2=below average, 3=average, 4=good, 5=exceptional.\n\n"
            + "Respond ONLY with raw JSON (no code fences):\n"
            + "{\"rating\": <1-5>, \"summary\": \"<1-2 sentences>\"}\n"
            + "Do not use double-quote characters within the summary text.";

    /** Prompt suffix requesting a concise 1-2 sentence explanation. */
    static final String PROMPT_SUFFIX = "Rate 1-5 and explain in 1-2 sentences.";

    /**
     * Constructs a {@code HaikuEvaluationStrategy}.
     *
     * @param client       configured Anthropic client
     * @param properties   Anthropic configuration (model identifier)
     * @param objectMapper Jackson mapper for parsing Claude's JSON response
     */
    public HaikuEvaluationStrategy(AnthropicClient client, AnthropicProperties properties,
            ObjectMapper objectMapper) {
        super(client, properties, objectMapper);
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
     * Parses Claude's 1–5 rating JSON response into a {@link SunsetEvaluation}.
     *
     * <p>First attempts strict JSON parsing; falls back to regex if the summary
     * contains unescaped quote characters.
     *
     * @param text         the raw text returned by Claude
     * @param objectMapper Jackson mapper for JSON parsing
     * @return evaluation with {@code rating} populated; score fields are null
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
            int rating = node.get("rating").asInt();
            String summary = node.get("summary").asText();
            return new SunsetEvaluation(rating, null, null, summary);
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
     * @throws IllegalArgumentException if the rating and summary cannot be extracted
     */
    private SunsetEvaluation parseWithRegexFallback(String text, Exception cause) {
        LOG.warn("Haiku response was not valid JSON — falling back to regex parser: {}",
                cause.getMessage());
        Matcher ratingMatcher = RATING_PATTERN.matcher(text);
        Matcher summaryMatcher = SUMMARY_PATTERN.matcher(text);

        if (ratingMatcher.find() && summaryMatcher.find()) {
            int rating = Integer.parseInt(ratingMatcher.group(1));
            String summary = summaryMatcher.group(1);
            return new SunsetEvaluation(rating, null, null, summary);
        }

        throw new IllegalArgumentException(
                "Failed to parse Haiku evaluation response: " + text, cause);
    }
}
