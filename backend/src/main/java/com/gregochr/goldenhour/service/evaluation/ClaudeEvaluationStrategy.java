package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude-based evaluation strategy for sunrise/sunset colour potential.
 *
 * <p>Handles prompt construction (via {@link PromptBuilder}), API invocation
 * (via {@link AnthropicApiClient}), and response parsing. The model used is
 * determined by the {@link EvaluationModel} passed at construction time.
 *
 * <p>Cross-cutting concerns (timing, logging, metrics) are handled by
 * {@link MetricsLoggingDecorator}, not here. This class focuses purely on
 * the core evaluation pipeline: prompt → API → parse.
 */
public class ClaudeEvaluationStrategy implements EvaluationStrategy {

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

    /** Extracts the basic Fiery Sky Potential (0-100) from Claude's response. */
    static final Pattern BASIC_FIERY_SKY_PATTERN =
            Pattern.compile("\"basic_fiery_sky\"\\s*:\\s*(\\d+)");

    /** Extracts the basic Golden Hour Potential (0-100) from Claude's response. */
    static final Pattern BASIC_GOLDEN_HOUR_PATTERN =
            Pattern.compile("\"basic_golden_hour\"\\s*:\\s*(\\d+)");

    /** Extracts the basic summary from Claude's response. */
    static final Pattern BASIC_SUMMARY_PATTERN =
            Pattern.compile("(?s)\"basic_summary\"\\s*:\\s*\"(.*)\"\\s*[,}]");

    private final AnthropicApiClient anthropicApiClient;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final EvaluationModel evaluationModel;

    /**
     * Constructs a {@code ClaudeEvaluationStrategy}.
     *
     * @param anthropicApiClient resilient Anthropic API client with retry
     * @param promptBuilder      builds system prompt, user message, and output schema
     * @param objectMapper       Jackson mapper for parsing Claude's JSON response
     * @param evaluationModel    the Claude model to use (HAIKU, SONNET, or OPUS)
     */
    public ClaudeEvaluationStrategy(AnthropicApiClient anthropicApiClient,
            PromptBuilder promptBuilder, ObjectMapper objectMapper,
            EvaluationModel evaluationModel) {
        this.anthropicApiClient = anthropicApiClient;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
        this.evaluationModel = evaluationModel;
    }

    @Override
    public EvaluationModel getEvaluationModel() {
        return evaluationModel;
    }

    /**
     * Evaluates the colour potential by delegating to {@link #evaluateWithDetails}
     * and returning the parsed evaluation.
     *
     * @param data the atmospheric forecast data to evaluate
     * @return Claude's colour potential rating and plain-English explanation
     */
    @Override
    public SunsetEvaluation evaluate(AtmosphericData data) {
        return evaluateWithDetails(data).evaluation();
    }

    /**
     * Core evaluation pipeline: builds the prompt, invokes the Claude API,
     * parses the response, and returns the full detail.
     *
     * @param data the atmospheric forecast data to evaluate
     * @return full evaluation detail including prompt, raw response, duration, and token usage
     */
    @Override
    public EvaluationDetail evaluateWithDetails(AtmosphericData data) {
        long startMs = System.currentTimeMillis();
        String userMessage = promptBuilder.buildUserMessage(data);

        Message response = invokeClaude(userMessage);
        TokenUsage tokenUsage = extractTokenUsage(response);

        String text = response.content().stream()
                .filter(ContentBlock::isText)
                .map(ContentBlock::asText)
                .map(TextBlock::text)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Claude returned no text content"));

        SunsetEvaluation result = parseEvaluation(text, objectMapper);
        long durationMs = System.currentTimeMillis() - startMs;

        return new EvaluationDetail(result, userMessage, text, durationMs, tokenUsage);
    }

    /**
     * Parses Claude's JSON response into a {@link SunsetEvaluation}.
     *
     * <p>Expects JSON with {@code rating} (optional), {@code fiery_sky}, {@code golden_hour},
     * and {@code summary}. First attempts strict JSON parsing; falls back to regex if the
     * summary contains unescaped quote characters.
     *
     * @param text   the raw text returned by Claude
     * @param mapper Jackson mapper for JSON parsing
     * @return the parsed evaluation
     * @throws IllegalArgumentException if the response cannot be parsed
     */
    SunsetEvaluation parseEvaluation(String text, ObjectMapper mapper) {
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
            Integer basicFierySky = node.has("basic_fiery_sky")
                    ? node.get("basic_fiery_sky").asInt() : null;
            Integer basicGoldenHour = node.has("basic_golden_hour")
                    ? node.get("basic_golden_hour").asInt() : null;
            String basicSummary = node.has("basic_summary")
                    ? node.get("basic_summary").stringValue() : null;
            return new SunsetEvaluation(rating, fierySky, goldenHour, summary,
                    basicFierySky, basicGoldenHour, basicSummary);
        } catch (Exception jsonException) {
            return parseWithRegexFallback(text, jsonException);
        }
    }

    /**
     * Extracts token usage from Claude's response for cost calculation.
     *
     * @param response the Claude API response
     * @return token usage breakdown
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
     * @param userMessage the pre-built user message to send
     * @return Claude's response message
     */
    private Message invokeClaude(String userMessage) {
        return anthropicApiClient.createMessage(
                MessageCreateParams.builder()
                        .model(evaluationModel.getModelId())
                        .maxTokens(MAX_TOKENS)
                        .systemOfTextBlockParams(List.of(
                                TextBlockParam.builder()
                                        .text(promptBuilder.getSystemPrompt())
                                        .cacheControl(CacheControlEphemeral.builder().build())
                                        .build()))
                        .outputConfig(promptBuilder.buildOutputConfig())
                        .addUserMessage(userMessage)
                        .build());
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
        Matcher ratingMatcher = RATING_PATTERN.matcher(text);
        Matcher fierySkyMatcher = FIERY_SKY_PATTERN.matcher(text);
        Matcher goldenHourMatcher = GOLDEN_HOUR_PATTERN.matcher(text);
        Matcher summaryMatcher = SUMMARY_PATTERN.matcher(text);

        Integer rating = ratingMatcher.find() ? Integer.parseInt(ratingMatcher.group(1)) : null;
        if (fierySkyMatcher.find() && goldenHourMatcher.find() && summaryMatcher.find()) {
            int fierySky = Integer.parseInt(fierySkyMatcher.group(1));
            int goldenHour = Integer.parseInt(goldenHourMatcher.group(1));
            String summary = summaryMatcher.group(1);

            Matcher basicFierySkyMatcher = BASIC_FIERY_SKY_PATTERN.matcher(text);
            Matcher basicGoldenHourMatcher = BASIC_GOLDEN_HOUR_PATTERN.matcher(text);
            Matcher basicSummaryMatcher = BASIC_SUMMARY_PATTERN.matcher(text);
            Integer basicFierySky = basicFierySkyMatcher.find()
                    ? Integer.parseInt(basicFierySkyMatcher.group(1)) : null;
            Integer basicGoldenHour = basicGoldenHourMatcher.find()
                    ? Integer.parseInt(basicGoldenHourMatcher.group(1)) : null;
            String basicSummary = basicSummaryMatcher.find()
                    ? basicSummaryMatcher.group(1) : null;

            return new SunsetEvaluation(rating, fierySky, goldenHour, summary,
                    basicFierySky, basicGoldenHour, basicSummary);
        }

        throw new IllegalArgumentException(
                "Failed to parse evaluation response: " + text, cause);
    }
}
