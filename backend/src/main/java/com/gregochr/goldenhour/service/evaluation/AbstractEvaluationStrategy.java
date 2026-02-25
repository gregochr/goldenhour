package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AnthropicProperties;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Shared evaluation logic for all Claude-based strategies.
 *
 * <p>Handles the user message construction, API call, and delegates response
 * parsing to the subclass via {@link #parseEvaluation(String)}. Each subclass
 * provides its own system prompt and JSON parsing logic.
 */
public abstract class AbstractEvaluationStrategy implements EvaluationStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractEvaluationStrategy.class);

    /** Maximum tokens Claude may return per evaluation. */
    static final int MAX_TOKENS = 256;

    /**
     * Extracts the summary text from Claude's response using a greedy match.
     * The greedy {@code .*} captures everything up to the last {@code "} before the
     * closing {@code }} — correctly handling unescaped quote characters in the text.
     */
    static final Pattern SUMMARY_PATTERN =
            Pattern.compile("(?s)\"summary\"\\s*:\\s*\"(.*)\"\\s*[,}]");

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
     * Returns the system prompt for this strategy.
     *
     * @return the system prompt string sent to Claude
     */
    protected abstract String getSystemPrompt();

    /**
     * Returns the final instruction appended to the user message.
     *
     * @return the prompt suffix string
     */
    protected abstract String getPromptSuffix();

    /**
     * Parses Claude's JSON response text into a {@link SunsetEvaluation}.
     *
     * <p>Subclasses override this to handle their specific JSON schema.
     *
     * @param text          the raw text returned by Claude
     * @param objectMapper  Jackson mapper for JSON parsing
     * @return the parsed evaluation
     * @throws IllegalArgumentException if the response cannot be parsed
     */
    protected abstract SunsetEvaluation parseEvaluation(String text, ObjectMapper objectMapper);

    @Override
    public SunsetEvaluation evaluate(AtmosphericData data) {
        LOG.info("Anthropic ({}) ← {} {} {}", properties.getModel(),
                data.locationName(), data.targetType(), data.solarEventTime().toLocalDate());
        long startMs = System.currentTimeMillis();

        Message response = client.messages().create(
                MessageCreateParams.builder()
                        .model(properties.getModel())
                        .maxTokens(MAX_TOKENS)
                        .system(getSystemPrompt())
                        .addUserMessage(buildUserMessage(data))
                        .build());

        String text = response.content().stream()
                .filter(ContentBlock::isText)
                .map(ContentBlock::asText)
                .map(TextBlock::text)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Claude returned no text content"));

        SunsetEvaluation result = parseEvaluation(text, objectMapper);
        if (result.rating() != null) {
            LOG.info("Anthropic → {} {}: rating={}/5 ({}ms)",
                    data.locationName(), data.targetType(),
                    result.rating(), System.currentTimeMillis() - startMs);
        } else {
            LOG.info("Anthropic → {} {}: fiery={}/100 golden={}/100 ({}ms)",
                    data.locationName(), data.targetType(),
                    result.fierySkyPotential(), result.goldenHourPotential(),
                    System.currentTimeMillis() - startMs);
        }
        return result;
    }

    /**
     * Builds the user message from atmospheric data, ending with the strategy's prompt suffix.
     *
     * @param data the atmospheric forecast data
     * @return formatted user message string
     */
    String buildUserMessage(AtmosphericData data) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "Location: %s. %s: %s UTC.%n"
                + "Cloud: Low %d%%, Mid %d%%, High %d%%%n"
                + "Visibility: %,dm, Wind: %.2f m/s (%d\u00b0), Precip: %.2fmm%n"
                + "Humidity: %d%%, Weather code: %d%n"
                + "Boundary layer: %dm, Shortwave: %.0f W/m\u00b2%n"
                + "PM2.5: %s\u00b5g/m\u00b3, Dust: %s\u00b5g/m\u00b3, AOD: %s",
                data.locationName(), data.targetType(), data.solarEventTime(),
                data.lowCloudPercent(), data.midCloudPercent(), data.highCloudPercent(),
                data.visibilityMetres(), data.windSpeedMs(), data.windDirectionDegrees(),
                data.precipitationMm(),
                data.humidityPercent(), data.weatherCode(),
                data.boundaryLayerHeightMetres(), data.shortwaveRadiationWm2(),
                data.pm25(), data.dustUgm3(), data.aerosolOpticalDepth()));

        // Include tide data if available (coastal location)
        if (data.tideState() != null) {
            sb.append(String.format(
                    "%nTide: %s (next high: %.2fm at %s, next low: %.2fm at %s), Aligned: %s",
                    data.tideState(),
                    data.nextHighTideHeightMetres(),
                    data.nextHighTideTime(),
                    data.nextLowTideHeightMetres(),
                    data.nextLowTideTime(),
                    data.tideAligned()));
        }

        sb.append("\n").append(getPromptSuffix());
        return sb.toString();
    }
}
