package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AnthropicProperties;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared evaluation logic for all Claude-based strategies.
 *
 * <p>Handles the system prompt, user message construction, API call, and response
 * parsing. Subclasses control only the prompt suffix (sentence count instruction).
 */
public abstract class AbstractEvaluationStrategy implements EvaluationStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractEvaluationStrategy.class);

    /** Maximum tokens Claude may return per evaluation. */
    private static final int MAX_TOKENS = 256;

    /** Extracts the integer rating from Claude's response. */
    private static final Pattern RATING_PATTERN = Pattern.compile("\"rating\"\\s*:\\s*(\\d+)");

    /**
     * Extracts the summary text from Claude's response using a greedy match.
     * The greedy {@code .*} captures everything up to the last {@code "} before the
     * closing {@code }} — correctly handling unescaped quote characters in the text.
     */
    private static final Pattern SUMMARY_PATTERN =
            Pattern.compile("(?s)\"summary\"\\s*:\\s*\"(.*)\"\\s*[,}]");

    static final String SYSTEM_PROMPT =
            "You are an expert sunrise/sunset colour potential advisor for landscape photographers.\n"
            + "Rate colour potential 1-5 and explain briefly.\n\n"
            + "Key criteria: clear horizon critical (high low cloud >70% = poor); "
            + "mid/high cloud above clear horizon = ideal canvas; "
            + "post-rain clearing often vivid; "
            + "moderate aerosol/dust (AOD 0.1-0.25) enhances red scattering; "
            + "high humidity (>80%) mutes colours; "
            + "low boundary layer traps aerosols near surface; "
            + "fully clear sky = 2-3; total overcast = 1.\n\n"
            + "Solar/antisolar horizon model: at sunset the sun is west — the solar horizon "
            + "(west) must be clear for light penetration, while mid/high cloud on the antisolar "
            + "side (east) at 20-60% catches and reflects colour. Sunrise is the reverse. "
            + "Since data is non-directional, use altitude as proxy: low cloud (0-3km) sits near "
            + "the horizon and blocks light; mid (3-8km) and high (8+km) cloud sits above and "
            + "catches it. Ideal: low cloud <30% with mid/high 20-60%.\n\n"
            + "Respond ONLY with raw JSON (no code fences): "
            + "{\"rating\": <1-5>, \"summary\": \"<2 sentences>\"}. "
            + "Do not use double-quote characters within the summary text.";

    private final AnthropicClient client;
    private final AnthropicProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Constructs an {@code AbstractEvaluationStrategy}.
     *
     * @param client       configured Anthropic client
     * @param properties   Anthropic configuration (model identifier)
     * @param objectMapper Jackson mapper for parsing Claude's JSON response
     */
    protected AbstractEvaluationStrategy(AnthropicClient client, AnthropicProperties properties,
            ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the final instruction appended to the user message.
     *
     * <p>For example: {@code "Rate 1-5 and explain in 2-3 sentences."}
     *
     * @return the prompt suffix string
     */
    protected abstract String getPromptSuffix();

    @Override
    public SunsetEvaluation evaluate(AtmosphericData data) {
        LOG.debug("Calling Claude ({}) for {} {}", properties.getModel(),
                data.locationName(), data.targetType());
        long startMs = System.currentTimeMillis();

        Message response = client.messages().create(
                MessageCreateParams.builder()
                        .model(properties.getModel())
                        .maxTokens(MAX_TOKENS)
                        .system(SYSTEM_PROMPT)
                        .addUserMessage(buildUserMessage(data))
                        .build());

        String text = response.content().stream()
                .filter(ContentBlock::isText)
                .map(ContentBlock::asText)
                .map(TextBlock::text)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Claude returned no text content"));

        SunsetEvaluation result = parseEvaluation(text);
        LOG.debug("Claude responded in {}ms", System.currentTimeMillis() - startMs);
        return result;
    }

    /**
     * Builds the user message from atmospheric data, ending with the strategy's prompt suffix.
     *
     * @param data the atmospheric forecast data
     * @return formatted user message string
     */
    String buildUserMessage(AtmosphericData data) {
        return String.format(
                "Location: %s. %s: %s UTC.%n"
                + "Cloud: Low %d%%, Mid %d%%, High %d%%%n"
                + "Visibility: %,dm, Wind: %.2f m/s (%d\u00b0), Precip: %.2fmm%n"
                + "Humidity: %d%%, Weather code: %d%n"
                + "Boundary layer: %dm, Shortwave: %.0f W/m\u00b2%n"
                + "PM2.5: %s\u00b5g/m\u00b3, Dust: %s\u00b5g/m\u00b3, AOD: %s%n"
                + "%s",
                data.locationName(), data.targetType(), data.solarEventTime(),
                data.lowCloudPercent(), data.midCloudPercent(), data.highCloudPercent(),
                data.visibilityMetres(), data.windSpeedMs(), data.windDirectionDegrees(),
                data.precipitationMm(),
                data.humidityPercent(), data.weatherCode(),
                data.boundaryLayerHeightMetres(), data.shortwaveRadiationWm2(),
                data.pm25(), data.dustUgm3(), data.aerosolOpticalDepth(),
                getPromptSuffix());
    }

    /**
     * Parses Claude's JSON response text into a {@link SunsetEvaluation}.
     *
     * <p>First attempts strict JSON parsing after stripping any markdown code fences.
     * If that fails (e.g. because the summary contains unescaped quote characters),
     * falls back to regex extraction of the rating integer and summary string.
     *
     * <p>Package-private so it can be unit-tested independently of the HTTP call.
     *
     * @param text the raw text returned by Claude
     * @return the parsed evaluation
     * @throws IllegalArgumentException if the response cannot be parsed by either method
     */
    SunsetEvaluation parseEvaluation(String text) {
        String cleaned = text.trim()
                .replaceAll("(?s)^```(?:json)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .trim();
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            int rating = node.get("rating").asInt();
            String summary = node.get("summary").asText();
            return new SunsetEvaluation(rating, summary);
        } catch (Exception jsonException) {
            return parseWithRegexFallback(text, jsonException);
        }
    }

    /**
     * Fallback parser used when the JSON is structurally invalid (e.g. unescaped quotes
     * inside the summary value). Uses a greedy regex to extract rating and summary.
     *
     * @param text      the raw response text
     * @param cause     the original JSON parse exception, re-thrown if regex also fails
     * @return the parsed evaluation
     * @throws IllegalArgumentException if the rating and summary cannot be extracted
     */
    private SunsetEvaluation parseWithRegexFallback(String text, Exception cause) {
        LOG.warn("Claude response was not valid JSON — falling back to regex parser: {}",
                cause.getMessage());
        Matcher ratingMatcher = RATING_PATTERN.matcher(text);
        Matcher summaryMatcher = SUMMARY_PATTERN.matcher(text);

        if (ratingMatcher.find() && summaryMatcher.find()) {
            int rating = Integer.parseInt(ratingMatcher.group(1));
            String summary = summaryMatcher.group(1);
            return new SunsetEvaluation(rating, summary);
        }

        throw new IllegalArgumentException(
                "Failed to parse Claude evaluation response: " + text, cause);
    }
}
