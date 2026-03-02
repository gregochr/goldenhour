package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AnthropicProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.exception.WeatherDataFetchException;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.JobRunService;

import com.anthropic.errors.AnthropicServiceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared evaluation logic for all Claude-based strategies.
 *
 * <p>Handles the user message construction, API call, response parsing, and
 * metric logging. Retry logic is handled declaratively by {@link AnthropicApiClient}'s
 * {@code @Retryable} annotation. Each subclass provides its own model identifier and
 * evaluation model enum; the system prompt and prompt suffix are shared by default but
 * can be overridden by subclasses to customise the prompt per model.
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

    /** Extracts the integer rating from Claude's response. */
    static final Pattern RATING_PATTERN = Pattern.compile("\"rating\"\\s*:\\s*(\\d+)");

    /** Extracts the Fiery Sky Potential (0-100) from Claude's response. */
    static final Pattern FIERY_SKY_PATTERN = Pattern.compile("\"fiery_sky\"\\s*:\\s*(\\d+)");

    /** Extracts the Golden Hour Potential (0-100) from Claude's response. */
    static final Pattern GOLDEN_HOUR_PATTERN = Pattern.compile("\"golden_hour\"\\s*:\\s*(\\d+)");

    /** System prompt shared by all strategies: rating (1-5), dual scores (0-100), and summary. */
    protected static final String SYSTEM_PROMPT =
            "You are an expert sunrise/sunset colour potential advisor for landscape photographers.\n"
            + "Evaluate on three scales:\n"
            + "  1. Rating: 1\u20135 scale (overall potential)\n"
            + "  2. Fiery Sky Potential: 0\u2013100 (dramatic colour, vivid reds/oranges)\n"
            + "  3. Golden Hour Potential: 0\u2013100 (overall light quality, softness)\n\n"
            + "Key criteria: clear horizon critical (high low cloud >70% = poor for fiery sky); "
            + "mid/high cloud above clear horizon = ideal canvas for fiery sky; "
            + "post-rain clearing often vivid; "
            + "moderate aerosol/dust (AOD 0.1-0.25) enhances red scattering; "
            + "high humidity (>80%) mutes colours; "
            + "low boundary layer traps aerosols near surface.\n\n"
            + "Solar/antisolar horizon model: at sunset the sun is west \u2014 the solar horizon "
            + "(west) must be clear for light penetration, while mid/high cloud on the antisolar "
            + "side (east) at 20-60% catches and reflects colour. Sunrise is the reverse. "
            + "Since data is non-directional, use altitude as proxy: low cloud (0-3km) sits near "
            + "the horizon and blocks light; mid (3-8km) and high (8+km) cloud sits above and "
            + "catches it. Ideal: low cloud <30% with mid/high 20-60%.\n\n"
            + "For coastal locations, tide data may be provided. When available:\n"
            + "- High tide can expose dramatic rock formations and alter water colour\n"
            + "- Low tide may reveal sand patterns and new horizon details\n"
            + "- If the tide aligns with the photographer's preference, factor this favourably\n"
            + "- If not aligned, briefly mention the tide limitation but don't heavily penalise"
            + " unless extreme\n\n"
            + "Output your evaluation as JSON with these fields: "
            + "rating (1-5), fiery_sky (0-100), golden_hour (0-100), summary (2 sentences).\n\n"
            + "fiery_sky: dramatic colour potential. Requires clouds (mid/high) to catch light. "
            + "Clear sky = 20-40. Ideal cloud canvas with clear horizon = 70-90. Total overcast = 5-15.\n"
            + "golden_hour: overall light quality. Clear sky with good visibility scores well. "
            + "Clear + low humidity + moderate aerosol = 65-85. Overcast = 10-30. Haze = varies.\n"
            + "Do not use double-quote characters within the summary text.";

    /** Prompt suffix shared by all strategies: requests all three metrics and a summary. */
    protected static final String PROMPT_SUFFIX =
            "Rate 1-5, estimate Fiery Sky Potential (0-100) and Golden Hour Potential (0-100), "
            + "then explain in 1-2 sentences.";

    private final AnthropicApiClient anthropicApiClient;
    private final AnthropicProperties properties;
    private final ObjectMapper objectMapper;
    private final JobRunService jobRunService;

    /**
     * Constructs an {@code AbstractEvaluationStrategy}.
     *
     * @param anthropicApiClient resilient Anthropic API client with retry
     * @param properties         Anthropic configuration (model identifier)
     * @param objectMapper       Jackson mapper for parsing Claude's JSON response
     * @param jobRunService      optional service for metrics tracking
     */
    protected AbstractEvaluationStrategy(AnthropicApiClient anthropicApiClient,
            AnthropicProperties properties,
            ObjectMapper objectMapper, JobRunService jobRunService) {
        this.anthropicApiClient = anthropicApiClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jobRunService = jobRunService;
    }

    /**
     * Returns the system prompt for this strategy.
     *
     * <p>Default implementation returns the shared {@link #SYSTEM_PROMPT}.
     * Subclasses may override to customise the prompt per model.
     *
     * @return the system prompt string sent to Claude
     */
    protected String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * Returns the final instruction appended to the user message.
     *
     * <p>Default implementation returns the shared {@link #PROMPT_SUFFIX}.
     * Subclasses may override to customise the suffix per model.
     *
     * @return the prompt suffix string
     */
    protected String getPromptSuffix() {
        return PROMPT_SUFFIX;
    }

    /**
     * Returns the evaluation model used by this strategy.
     *
     * @return HAIKU, SONNET, or OPUS
     */
    protected abstract EvaluationModel getEvaluationModel();

    /**
     * Returns the Claude model identifier for this strategy.
     *
     * @return the model name (e.g., "claude-haiku-4-5" or "claude-sonnet-4-5-20250929")
     */
    protected abstract String getModelName();

    /**
     * Parses Claude's JSON response into a {@link SunsetEvaluation}.
     *
     * <p>Expects JSON with {@code rating} (optional), {@code fiery_sky}, {@code golden_hour},
     * and {@code summary}. First attempts strict JSON parsing; falls back to regex if the
     * summary contains unescaped quote characters.
     *
     * <p>Subclasses may override to handle a different JSON schema.
     *
     * @param text          the raw text returned by Claude
     * @param mapper        Jackson mapper for JSON parsing
     * @return the parsed evaluation
     * @throws IllegalArgumentException if the response cannot be parsed
     */
    protected SunsetEvaluation parseEvaluation(String text, ObjectMapper mapper) {
        String cleaned = text.trim()
                .replaceAll("(?s)^```(?:json)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .trim();
        try {
            JsonNode node = mapper.readTree(cleaned);
            Integer rating = node.has("rating") ? node.get("rating").asInt() : null;
            int fierySky = node.get("fiery_sky").asInt();
            int goldenHour = node.get("golden_hour").asInt();
            String summary = node.get("summary").stringValue();
            return new SunsetEvaluation(rating, fierySky, goldenHour, summary);
        } catch (Exception jsonException) {
            return parseWithRegexFallback(text, jsonException);
        }
    }

    @Override
    public SunsetEvaluation evaluate(AtmosphericData data) {
        return evaluate(data, null);
    }

    @Override
    public SunsetEvaluation evaluate(AtmosphericData data, JobRunEntity jobRun) {
        LOG.info("Anthropic ({}) <- {} {} {}", getModelName(),
                data.locationName(), data.targetType(), data.solarEventTime().toLocalDate());
        long startMs = System.currentTimeMillis();
        int statusCode = 500;
        String errorMessage = null;

        try {
            Message response = invokeClaude(data);

            String text = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(ContentBlock::asText)
                    .map(TextBlock::text)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Claude returned no text content"));

            SunsetEvaluation result = parseEvaluation(text, objectMapper);
            long durationMs = System.currentTimeMillis() - startMs;
            statusCode = 200;

            LOG.info("Anthropic -> {} {}: rating={}/5 fiery={}/100 golden={}/100 ({}ms)",
                    data.locationName(), data.targetType(),
                    result.rating(), result.fierySkyPotential(),
                    result.goldenHourPotential(), durationMs);

            // Log API call to metrics if jobRun is available
            if (jobRun != null && jobRunService != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.ANTHROPIC,
                        "POST", "https://api.anthropic.com/v1/messages", null,
                        durationMs, statusCode, null, true, null, getEvaluationModel(),
                        data.solarEventTime().toLocalDate(), data.targetType());
            }

            return result;
        } catch (WeatherDataFetchException e) {
            // Weather data fetch failed -- Anthropic evaluation was blocked
            LOG.error("Skipping Anthropic evaluation -- weather data unavailable: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            errorMessage = e.getMessage();
            if (e instanceof AnthropicServiceException serviceEx) {
                statusCode = serviceEx.statusCode();
                // Log content filter final-failure diagnostic details
                if (statusCode == 400 && errorMessage != null
                        && errorMessage.contains("content filtering")) {
                    LOG.warn("Anthropic content filter — final failure. "
                            + "Location: {}, Target: {}, Date: {}, Model: {}. "
                            + "User message:\n{}",
                            data.locationName(), data.targetType(),
                            data.solarEventTime().toLocalDate(), getModelName(),
                            buildUserMessage(data));
                }
            }

            // Log failed Anthropic API call to metrics if jobRun is available
            LOG.error("Anthropic evaluation failed: {}", e.getMessage(), e);
            if (jobRun != null && jobRunService != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.ANTHROPIC,
                        "POST", "https://api.anthropic.com/v1/messages", null,
                        durationMs, statusCode, errorMessage, false, errorMessage, getEvaluationModel(),
                        data.solarEventTime().toLocalDate(), data.targetType());
            }

            throw e;
        }
    }

    /**
     * Evaluates the colour potential and returns the full detail including the prompt
     * sent and raw response text, for model comparison tests.
     *
     * @param data   the atmospheric forecast data to evaluate
     * @param jobRun the parent job run for metrics tracking, or {@code null}
     * @return full evaluation detail including prompt and raw response
     */
    public EvaluationDetail evaluateWithDetails(AtmosphericData data, JobRunEntity jobRun) {
        LOG.info("Anthropic ({}) [detailed] <- {} {} {}", getModelName(),
                data.locationName(), data.targetType(), data.solarEventTime().toLocalDate());
        long startMs = System.currentTimeMillis();
        String userMessage = buildUserMessage(data);

        try {
            Message response = invokeClaude(data);

            String text = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(ContentBlock::asText)
                    .map(TextBlock::text)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Claude returned no text content"));

            SunsetEvaluation result = parseEvaluation(text, objectMapper);
            long durationMs = System.currentTimeMillis() - startMs;

            LOG.info("Anthropic -> {} {}: rating={}/5 fiery={}/100 golden={}/100 ({}ms)",
                    data.locationName(), data.targetType(),
                    result.rating(), result.fierySkyPotential(),
                    result.goldenHourPotential(), durationMs);

            if (jobRun != null && jobRunService != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.ANTHROPIC,
                        "POST", "https://api.anthropic.com/v1/messages", null,
                        durationMs, 200, null, true, null, getEvaluationModel(),
                        data.solarEventTime().toLocalDate(), data.targetType());
            }

            return new EvaluationDetail(result, userMessage, text, durationMs);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            int statusCode = 500;
            if (e instanceof AnthropicServiceException serviceEx) {
                statusCode = serviceEx.statusCode();
            }

            LOG.error("Anthropic evaluation failed: {}", e.getMessage(), e);
            if (jobRun != null && jobRunService != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.ANTHROPIC,
                        "POST", "https://api.anthropic.com/v1/messages", null,
                        durationMs, statusCode, e.getMessage(), false, e.getMessage(),
                        getEvaluationModel(), data.solarEventTime().toLocalDate(), data.targetType());
            }

            throw e;
        }
    }

    /**
     * Invokes the Anthropic API via the resilient {@link AnthropicApiClient}.
     *
     * <p>Retry logic for transient failures (529 overloaded, 400 content filtering)
     * is handled declaratively by the {@code @Retryable} annotation on
     * {@link AnthropicApiClient#createMessage}.
     *
     * @param data atmospheric data for the prompt
     * @return Claude's response message
     */
    private Message invokeClaude(AtmosphericData data) {
        String userMessage = buildUserMessage(data);
        return anthropicApiClient.createMessage(
                MessageCreateParams.builder()
                        .model(getModelName())
                        .maxTokens(MAX_TOKENS)
                        .systemOfTextBlockParams(List.of(
                                TextBlockParam.builder()
                                        .text(getSystemPrompt())
                                        .cacheControl(CacheControlEphemeral.builder().build())
                                        .build()))
                        .outputConfig(buildOutputConfig())
                        .addUserMessage(userMessage)
                        .build());
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

    /**
     * Builds the structured output configuration constraining Claude's response to our JSON schema.
     *
     * <p>The schema requires four fields: {@code rating} (integer 1-5), {@code fiery_sky}
     * (integer 0-100), {@code golden_hour} (integer 0-100), and {@code summary} (string).
     * Claude's output is guaranteed to conform to this schema, making parse failures
     * extremely unlikely.
     *
     * @return the output configuration with JSON schema constraint
     */
    private OutputConfig buildOutputConfig() {
        return OutputConfig.builder()
                .format(JsonOutputFormat.builder()
                        .schema(JsonOutputFormat.Schema.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "rating", Map.of("type", "integer"),
                                        "fiery_sky", Map.of("type", "integer"),
                                        "golden_hour", Map.of("type", "integer"),
                                        "summary", Map.of("type", "string"))))
                                .putAdditionalProperty("required", JsonValue.from(
                                        List.of("rating", "fiery_sky", "golden_hour", "summary")))
                                .build())
                        .build())
                .build();
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
        LOG.warn("Claude response was not valid JSON -- falling back to regex parser: {}",
                cause.getMessage());
        Matcher ratingMatcher = RATING_PATTERN.matcher(text);
        Matcher fierySkyMatcher = FIERY_SKY_PATTERN.matcher(text);
        Matcher goldenHourMatcher = GOLDEN_HOUR_PATTERN.matcher(text);
        Matcher summaryMatcher = SUMMARY_PATTERN.matcher(text);

        Integer rating = ratingMatcher.find() ? Integer.parseInt(ratingMatcher.group(1)) : null;
        if (fierySkyMatcher.find() && goldenHourMatcher.find() && summaryMatcher.find()) {
            int fierySky = Integer.parseInt(fierySkyMatcher.group(1));
            int goldenHour = Integer.parseInt(goldenHourMatcher.group(1));
            String summary = summaryMatcher.group(1);
            return new SunsetEvaluation(rating, fierySky, goldenHour, summary);
        }

        throw new IllegalArgumentException(
                "Failed to parse evaluation response: " + text, cause);
    }
}
