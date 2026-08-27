package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;

import java.util.List;

/**
 * Claude-based evaluation strategy for sunrise/sunset colour potential.
 *
 * <p>Handles prompt construction (via {@link PromptBuilder}) and API invocation
 * (via {@link AnthropicApiClient}); response parsing is delegated to
 * {@link SunsetEvaluationParser}. The model used is determined by the
 * {@link EvaluationModel} passed at construction time.
 *
 * <p>Cross-cutting concerns (timing, logging, metrics) are handled by
 * {@link MetricsLoggingDecorator}, not here. This class focuses purely on
 * the core evaluation pipeline: prompt → API → parse.
 */
public class ClaudeEvaluationStrategy implements EvaluationStrategy {

    private final AnthropicApiClient anthropicApiClient;
    private final PromptBuilder promptBuilder;
    private final CoastalPromptBuilder coastalPromptBuilder;
    private final ObjectMapper objectMapper;
    private final EvaluationModel evaluationModel;
    private final SunsetEvaluationParser parser;

    /**
     * Constructs a {@code ClaudeEvaluationStrategy}.
     *
     * @param anthropicApiClient   resilient Anthropic API client with retry
     * @param promptBuilder        builds system prompt and user message for inland locations
     * @param coastalPromptBuilder builds system prompt and user message for coastal locations
     * @param objectMapper         Jackson mapper for parsing Claude's JSON response
     * @param evaluationModel      the Claude model to use (HAIKU, SONNET, or OPUS)
     * @param parser               parses Claude's JSON response into an evaluation
     */
    public ClaudeEvaluationStrategy(AnthropicApiClient anthropicApiClient,
            PromptBuilder promptBuilder, CoastalPromptBuilder coastalPromptBuilder,
            ObjectMapper objectMapper, EvaluationModel evaluationModel,
            SunsetEvaluationParser parser) {
        this.anthropicApiClient = anthropicApiClient;
        this.promptBuilder = promptBuilder;
        this.coastalPromptBuilder = coastalPromptBuilder;
        this.objectMapper = objectMapper;
        this.evaluationModel = evaluationModel;
        this.parser = parser;
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
        PromptBuilder builder = data.tide() != null
                ? coastalPromptBuilder : promptBuilder;

        String userMessage = data.surge() != null
                ? builder.buildUserMessage(data, data.surge(),
                        data.adjustedRangeMetres(), data.astronomicalRangeMetres())
                : builder.buildUserMessage(data);

        Message response = invokeClaude(userMessage, builder);
        checkStopReason(response);
        TokenUsage tokenUsage = extractTokenUsage(response);

        String text = response.content().stream()
                .filter(ContentBlock::isText)
                .map(ContentBlock::asText)
                .map(TextBlock::text)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Claude returned no text content"));

        SunsetEvaluation result = parser.parseEvaluation(text, objectMapper);
        long durationMs = System.currentTimeMillis() - startMs;

        return new EvaluationDetail(result, userMessage, text, durationMs, tokenUsage);
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
     * Fails fast with a clear diagnostic when Claude did not produce a complete evaluation to
     * parse, rather than letting a refusal, context-window overflow, or token-limit truncation
     * surface downstream as an opaque {@code SunsetEvaluationParser} JSON-parse failure — or
     * worse, as a silent success: the parser's regex-salvage fallback can reconstruct a
     * valid-looking {@link SunsetEvaluation} from a truncated prefix (rating, potentials, and a
     * closed summary) when the cut lands after those fields but before optional ones, so without
     * this check a {@code max_tokens} truncation is persisted as a complete forecast.
     *
     * <p>Package-private (not {@code private}) so {@link EvaluationServiceImpl#evaluateNowForecast}
     * — the other live engine that calls Claude for a forecast, independently of this strategy
     * class — shares this exact check rather than growing its own copy that could drift out of
     * sync with it.
     *
     * @param response the Claude API response
     */
    static void checkStopReason(Message response) {
        StopReason stopReason = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stopReason)) {
            throw new IllegalStateException(
                    "Claude refused to evaluate this forecast (stop_reason=refusal)");
        }
        if (StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED.equals(stopReason)) {
            throw new IllegalStateException(
                    "Claude's response was cut short by the context window limit "
                            + "(stop_reason=model_context_window_exceeded)");
        }
        if (StopReason.MAX_TOKENS.equals(stopReason)) {
            throw new IllegalStateException(
                    "Claude's response was truncated at the max_tokens limit "
                            + "(stop_reason=max_tokens)");
        }
    }

    /**
     * Invokes the Anthropic API via the resilient {@link AnthropicApiClient}.
     *
     * <p>Retry logic for transient failures (529 overloaded, 400 content filtering)
     * is handled declaratively by the Resilience4j {@code @Retry} annotation on
     * {@link AnthropicApiClient#createMessage}.
     *
     * @param userMessage the pre-built user message to send
     * @param builder     the prompt builder used for system prompt and output config
     * @return Claude's response message
     */
    private Message invokeClaude(String userMessage, PromptBuilder builder) {
        return anthropicApiClient.createMessage(
                MessageCreateParams.builder()
                        .model(evaluationModel.getModelId())
                        .maxTokens(evaluationModel.getMaxTokens())
                        .systemOfTextBlockParams(List.of(
                                TextBlockParam.builder()
                                        .text(builder.getSystemPrompt())
                                        .cacheControl(CacheControlEphemeral.builder().build())
                                        .build()))
                        .outputConfig(builder.buildOutputConfig())
                        .addUserMessage(userMessage)
                        .build());
    }

}
