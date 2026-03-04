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
import com.gregochr.goldenhour.exception.WeatherDataFetchException;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
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
            + "high humidity (>80%) mutes colours.\n\n"
            + "AEROSOL & DUST GUIDANCE:\n"
            + "AOD thresholds: 0.05-0.15 clean (baseline), 0.15-0.30 slight enhancement, "
            + "0.30-0.60 notable warm-tone boost, 0.60-1.0 vivid reds/oranges possible, "
            + ">1.2 diminishing returns (too thick, light blocked).\n"
            + "AOD + PM2.5 differentiation: high AOD with low PM2.5 (<15 µg/m³) = mineral dust "
            + "(Saharan/desert origin, enhances warm reds and oranges); high AOD with high PM2.5 "
            + "(>25 µg/m³) = smoke or urban pollution (grey/brown haze, negative for colour).\n"
            + "Boundary layer height (BLH): <500m concentrates aerosols near surface (stronger "
            + "near-horizon effect); >1500m disperses them (weaker effect for same AOD).\n"
            + "At sunrise/sunset the solar elevation is near 0°, maximising atmospheric path "
            + "length — dust scattering impact is at its peak compared to midday.\n\n"
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
            TokenUsage tokenUsage = extractTokenUsage(response);

            String text = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(ContentBlock::asText)
                    .map(TextBlock::text)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Claude returned no text content"));

            SunsetEvaluation result = parseEvaluation(text, objectMapper);
            long durationMs = System.currentTimeMillis() - startMs;
            statusCode = 200;

            LOG.info("Anthropic -> {} {}: rating={}/5 fiery={}/100 golden={}/100 ({}ms, {}tok)",
                    data.locationName(), data.targetType(),
                    result.rating(), result.fierySkyPotential(),
                    result.goldenHourPotential(), durationMs, tokenUsage.totalTokens());

            // Log API call to metrics if jobRun is available
            if (jobRun != null && jobRunService != null) {
                jobRunService.logAnthropicApiCall(jobRun.getId(),
                        durationMs, statusCode, null, true, null, getEvaluationModel(),
                        tokenUsage, false,
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
                jobRunService.logAnthropicApiCall(jobRun.getId(),
                        durationMs, statusCode, errorMessage, false, errorMessage, getEvaluationModel(),
                        TokenUsage.EMPTY, false,
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
            TokenUsage tokenUsage = extractTokenUsage(response);

            String text = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(ContentBlock::asText)
                    .map(TextBlock::text)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Claude returned no text content"));

            SunsetEvaluation result = parseEvaluation(text, objectMapper);
            long durationMs = System.currentTimeMillis() - startMs;

            LOG.info("Anthropic -> {} {}: rating={}/5 fiery={}/100 golden={}/100 ({}ms, {}tok)",
                    data.locationName(), data.targetType(),
                    result.rating(), result.fierySkyPotential(),
                    result.goldenHourPotential(), durationMs, tokenUsage.totalTokens());

            if (jobRun != null && jobRunService != null) {
                jobRunService.logAnthropicApiCall(jobRun.getId(),
                        durationMs, 200, null, true, null, getEvaluationModel(),
                        tokenUsage, false,
                        data.solarEventTime().toLocalDate(), data.targetType());
            }

            return new EvaluationDetail(result, userMessage, text, durationMs, tokenUsage);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            int statusCode = 500;
            if (e instanceof AnthropicServiceException serviceEx) {
                statusCode = serviceEx.statusCode();
            }

            LOG.error("Anthropic evaluation failed: {}", e.getMessage(), e);
            if (jobRun != null && jobRunService != null) {
                jobRunService.logAnthropicApiCall(jobRun.getId(),
                        durationMs, statusCode, e.getMessage(), false, e.getMessage(),
                        getEvaluationModel(), TokenUsage.EMPTY, false,
                        data.solarEventTime().toLocalDate(), data.targetType());
            }

            throw e;
        }
    }

    /**
     * Extracts token usage from Claude's response for cost calculation.
     *
     * @param response the Claude API response
     * @return token usage breakdown, or {@link TokenUsage#EMPTY} if usage is unavailable
     */
    TokenUsage extractTokenUsage(Message response) {
        var u = response.usage();
        return new TokenUsage(
                u.inputTokens(),
                u.outputTokens(),
                u.cacheCreationInputTokens().orElse(0L),
                u.cacheReadInputTokens().orElse(0L));
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
                + "Humidity: %d%%, Precip probability: %s%%%n"
                + "Weather code: %d%n"
                + "Boundary layer: %dm, Shortwave: %.0f W/m\u00b2%n"
                + "PM2.5: %s\u00b5g/m\u00b3, Dust: %s\u00b5g/m\u00b3, AOD: %s",
                data.locationName(), data.targetType(), data.solarEventTime(),
                data.lowCloudPercent(), data.midCloudPercent(), data.highCloudPercent(),
                data.visibilityMetres(), data.windSpeedMs(), data.windDirectionDegrees(),
                data.precipitationMm(),
                data.humidityPercent(),
                data.precipitationProbability() != null ? data.precipitationProbability() : "N/A",
                data.weatherCode(),
                data.boundaryLayerHeightMetres(), data.shortwaveRadiationWm2(),
                data.pm25(), data.dustUgm3(), data.aerosolOpticalDepth()));

        // Conditional dust enrichment block — only when aerosol levels are elevated
        if (isDustElevated(data)) {
            sb.append(String.format(
                    "%nSAHARAN DUST CONTEXT:%n"
                    + "AOD: %s (elevated), Surface dust: %s \u00b5g/m\u00b3%n"
                    + "Wind: %s (%d\u00b0) at %s m/s%n"
                    + "Boundary layer: %dm%n"
                    + "Elevated AOD with low solar elevation at %s maximises warm scattering potential.",
                    data.aerosolOpticalDepth(), data.dustUgm3(),
                    toCardinal(data.windDirectionDegrees()), data.windDirectionDegrees(),
                    data.windSpeedMs(),
                    data.boundaryLayerHeightMetres(),
                    data.targetType()));
        }

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

    /** 16-point compass directions, indexed by (degrees / 22.5) rounded. */
    private static final String[] CARDINAL_DIRECTIONS = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };

    /** AOD threshold above which the dust context block is included. */
    private static final double DUST_AOD_THRESHOLD = 0.3;

    /** Surface dust threshold (µg/m³) above which the dust context block is included. */
    private static final double DUST_UGM3_THRESHOLD = 50.0;

    /**
     * Converts a wind direction in degrees (0–360) to a 16-point compass cardinal.
     *
     * @param degrees wind direction in degrees (meteorological convention)
     * @return compass cardinal (e.g. "N", "SW", "ENE")
     */
    static String toCardinal(int degrees) {
        int normalised = ((degrees % 360) + 360) % 360;
        int index = (int) Math.round(normalised / 22.5) % 16;
        return CARDINAL_DIRECTIONS[index];
    }

    /**
     * Returns {@code true} if aerosol levels are elevated enough to warrant the dust context block.
     *
     * @param data the atmospheric data
     * @return true when AOD exceeds 0.3 or surface dust exceeds 50 µg/m³
     */
    private static boolean isDustElevated(AtmosphericData data) {
        return (data.aerosolOpticalDepth() != null
                        && data.aerosolOpticalDepth().doubleValue() > DUST_AOD_THRESHOLD)
                || (data.dustUgm3() != null
                        && data.dustUgm3().doubleValue() > DUST_UGM3_THRESHOLD);
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
                                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
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
