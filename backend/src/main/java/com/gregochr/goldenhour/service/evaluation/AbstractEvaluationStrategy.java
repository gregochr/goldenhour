package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AnthropicProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.JobRunService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpServerErrorException;

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
    private final JobRunService jobRunService;

    /**
     * Constructs an {@code AbstractEvaluationStrategy}.
     *
     * @param client       configured Anthropic client
     * @param properties   Anthropic configuration (model identifier)
     * @param objectMapper Jackson mapper for parsing Claude's JSON response
     * @param jobRunService optional service for metrics tracking
     */
    protected AbstractEvaluationStrategy(AnthropicClient client, AnthropicProperties properties,
            ObjectMapper objectMapper, JobRunService jobRunService) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jobRunService = jobRunService;
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
     * Returns the evaluation model used by this strategy.
     *
     * @return HAIKU or SONNET
     */
    protected abstract EvaluationModel getEvaluationModel();

    /**
     * Returns the Claude model identifier for this strategy.
     *
     * @return the model name (e.g., "claude-haiku-4-5" or "claude-sonnet-4-5-20250929")
     */
    protected abstract String getModelName();

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
        return evaluate(data, null);
    }

    @Override
    public SunsetEvaluation evaluate(AtmosphericData data, JobRunEntity jobRun) {
        LOG.info("Anthropic ({}) ← {} {} {}", getModelName(),
                data.locationName(), data.targetType(), data.solarEventTime().toLocalDate());
        long startMs = System.currentTimeMillis();
        int statusCode = 500;
        String errorMessage = null;
        boolean succeeded = false;

        try {
            Message response = invokeClaudeWithRetry(data);

            String text = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(ContentBlock::asText)
                    .map(TextBlock::text)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Claude returned no text content"));

            SunsetEvaluation result = parseEvaluation(text, objectMapper);
            long durationMs = System.currentTimeMillis() - startMs;
            statusCode = 200;
            succeeded = true;

            if (result.rating() != null) {
                LOG.info("Anthropic → {} {}: rating={}/5 ({}ms)",
                        data.locationName(), data.targetType(),
                        result.rating(), durationMs);
            } else {
                LOG.info("Anthropic → {} {}: fiery={}/100 golden={}/100 ({}ms)",
                        data.locationName(), data.targetType(),
                        result.fierySkyPotential(), result.goldenHourPotential(),
                        durationMs);
            }

            // Log API call to metrics if jobRun is available
            if (jobRun != null && jobRunService != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.ANTHROPIC,
                        "POST", "https://api.anthropic.com/v1/messages", null,
                        durationMs, statusCode, null, true, null, getEvaluationModel());
            }

            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            errorMessage = e.getMessage();
            if (e instanceof HttpServerErrorException httpEx) {
                statusCode = httpEx.getStatusCode().value();
            }

            // Log failed API call to metrics if jobRun is available
            if (jobRun != null && jobRunService != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.ANTHROPIC,
                        "POST", "https://api.anthropic.com/v1/messages", null,
                        durationMs, statusCode, errorMessage, false, errorMessage, getEvaluationModel());
            }

            throw e;
        }
    }

    /**
     * Invokes the Anthropic API with retry logic for transient failures (529).
     *
     * @param data atmospheric data for the prompt
     * @return Claude's response message
     */
    private Message invokeClaudeWithRetry(AtmosphericData data) {
        int maxRetries = 3;
        int retryCount = 0;
        long backoffMs = 1000;

        while (true) {
            try {
                return client.messages().create(
                        MessageCreateParams.builder()
                                .model(getModelName())
                                .maxTokens(MAX_TOKENS)
                                .system(getSystemPrompt())
                                .addUserMessage(buildUserMessage(data))
                                .build());
            } catch (HttpServerErrorException httpEx) {
                if (httpEx.getStatusCode().value() == 529 && retryCount < maxRetries) {
                    retryCount++;
                    LOG.warn("Anthropic overloaded (529), retrying in {}ms... (attempt {}/{})",
                            backoffMs, retryCount, maxRetries);
                    try {
                        Thread.sleep(backoffMs);
                        backoffMs = Math.min(backoffMs * 2, 30000); // exponential backoff, max 30s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry sleep interrupted", ie);
                    }
                } else {
                    throw httpEx;
                }
            }
        }
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
