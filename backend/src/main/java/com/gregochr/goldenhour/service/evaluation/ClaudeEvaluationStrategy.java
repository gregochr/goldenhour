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
import com.gregochr.goldenhour.model.BluebellEvaluation;
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

    /**
     * Outcome of {@link #parseEvaluationWithMetadata}: the parsed evaluation plus whether the
     * greedy regex fallback was used (i.e. strict JSON parsing failed). The fallback can
     * over-capture adjacent fields and then SUCCEED silently, so callers persist the raw
     * response for diagnosis when this flag is set (the Bug B capture).
     *
     * @param evaluation        the parsed evaluation
     * @param usedRegexFallback {@code true} when strict {@code readTree} failed and the regex
     *                          fallback produced the result
     */
    public record ParseResult(SunsetEvaluation evaluation, boolean usedRegexFallback) {
    }

    /**
     * Extracts the summary text — bounded so it CANNOT over-capture into a following field
     * (Bug B). {@code (?:[^"\\]|\\.)*} matches any run of non-quote / escaped characters and
     * stops at the first genuine (unescaped) closing quote, so it physically cannot swallow
     * {@code ","headline":"..."}. If the value itself contains unescaped quotes this finds no
     * match; {@link #SUMMARY_PATTERN_SALVAGE} then salvages the field so the fallback never
     * loses the rating.
     */
    static final Pattern SUMMARY_PATTERN =
            Pattern.compile("\"summary\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*[,}]");

    /**
     * Legacy greedy summary extractor, used ONLY when {@link #SUMMARY_PATTERN} (bounded) finds no
     * match — i.e. the summary value itself contains unescaped quotes. It can over-capture, but it
     * keeps a (mangled) summary and the correct rating rather than failing the whole fallback.
     */
    static final Pattern SUMMARY_PATTERN_SALVAGE =
            Pattern.compile("(?s)\"summary\"\\s*:\\s*\"(.*)\"\\s*[,}]");

    /** Extracts the integer rating from Claude's response. */
    static final Pattern RATING_PATTERN = Pattern.compile("\"rating\"\\s*:\\s*(\\d{1,2})");

    /** Extracts the Fiery Sky Potential (0-100) from Claude's response. */
    static final Pattern FIERY_SKY_PATTERN = Pattern.compile("\"fiery_sky\"\\s*:\\s*(\\d{1,3})");

    /** Extracts the Golden Hour Potential (0-100) from Claude's response. */
    static final Pattern GOLDEN_HOUR_PATTERN = Pattern.compile("\"golden_hour\"\\s*:\\s*(\\d{1,3})");

    /** Extracts the basic Fiery Sky Potential (0-100) from Claude's response. */
    static final Pattern BASIC_FIERY_SKY_PATTERN =
            Pattern.compile("\"basic_fiery_sky\"\\s*:\\s*(\\d{1,3})");

    /** Extracts the basic Golden Hour Potential (0-100) from Claude's response. */
    static final Pattern BASIC_GOLDEN_HOUR_PATTERN =
            Pattern.compile("\"basic_golden_hour\"\\s*:\\s*(\\d{1,3})");

    /** Extracts the basic summary — bounded against over-capture, same as {@link #SUMMARY_PATTERN}. */
    static final Pattern BASIC_SUMMARY_PATTERN =
            Pattern.compile("\"basic_summary\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*[,}]");

    /** Greedy salvage for basic summary, used only when {@link #BASIC_SUMMARY_PATTERN} finds no match. */
    static final Pattern BASIC_SUMMARY_PATTERN_SALVAGE =
            Pattern.compile("(?s)\"basic_summary\"\\s*:\\s*\"(.*)\"\\s*[,}]");

    /** Extracts the cloud inversion score (0-10) from Claude's response. */
    static final Pattern INVERSION_SCORE_PATTERN =
            Pattern.compile("\"inversion_score\"\\s*:\\s*(\\d{1,2})");

    /** Extracts the cloud inversion potential classification from Claude's response. */
    static final Pattern INVERSION_POTENTIAL_PATTERN =
            Pattern.compile("\"inversion_potential\"\\s*:\\s*\"(\\w+)\"");

    /** Extracts the Claude-authored card headline (Gate 2 redesign). */
    static final Pattern HEADLINE_PATTERN =
            Pattern.compile("\"headline\"\\s*:\\s*\"([^\"]+)\"");

    private final AnthropicApiClient anthropicApiClient;
    private final PromptBuilder promptBuilder;
    private final CoastalPromptBuilder coastalPromptBuilder;
    private final ObjectMapper objectMapper;
    private final EvaluationModel evaluationModel;

    /**
     * Constructs a {@code ClaudeEvaluationStrategy}.
     *
     * @param anthropicApiClient   resilient Anthropic API client with retry
     * @param promptBuilder        builds system prompt and user message for inland locations
     * @param coastalPromptBuilder builds system prompt and user message for coastal locations
     * @param objectMapper         Jackson mapper for parsing Claude's JSON response
     * @param evaluationModel      the Claude model to use (HAIKU, SONNET, or OPUS)
     */
    public ClaudeEvaluationStrategy(AnthropicApiClient anthropicApiClient,
            PromptBuilder promptBuilder, CoastalPromptBuilder coastalPromptBuilder,
            ObjectMapper objectMapper, EvaluationModel evaluationModel) {
        this.anthropicApiClient = anthropicApiClient;
        this.promptBuilder = promptBuilder;
        this.coastalPromptBuilder = coastalPromptBuilder;
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
        PromptBuilder builder = data.tide() != null
                ? coastalPromptBuilder : promptBuilder;

        String userMessage = data.surge() != null
                ? builder.buildUserMessage(data, data.surge(),
                        data.adjustedRangeMetres(), data.astronomicalRangeMetres())
                : builder.buildUserMessage(data);

        Message response = invokeClaude(userMessage, builder);
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
    public SunsetEvaluation parseEvaluation(String text, ObjectMapper mapper) {
        return parseEvaluationWithMetadata(text, mapper).evaluation();
    }

    /**
     * Parses the dedicated bluebell prompt's response into a {@link BluebellEvaluation}.
     *
     * <p>The bluebell contract is small: a {@code rating} (1-5), a {@code summary}, and an
     * optional {@code headline} — no sky sub-scores. Attempts strict JSON first; on failure
     * (e.g. an unescaped quote in the summary) falls back to the same bounded/salvage regex
     * extraction the sky parser uses, so a malformed-but-recoverable response still yields a
     * rating and summary rather than dropping the slot. Introduced in Pass 3.
     *
     * @param text   the raw text returned by Claude for a bluebell evaluation
     * @param mapper Jackson mapper for JSON parsing
     * @return the parsed bluebell evaluation
     * @throws IllegalArgumentException if neither strict parsing nor the regex fallback recovers
     *                                  a rating and summary
     */
    public BluebellEvaluation parseBluebellEvaluation(String text, ObjectMapper mapper) {
        String cleaned = text.trim()
                .replaceAll("(?s)^```(?:json)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .trim();
        try {
            JsonNode node = mapper.readTree(cleaned);
            Integer rating = node.has("rating") ? node.get("rating").asInt() : null;
            String summary = node.get("summary").stringValue();
            String headline = node.has("headline") ? node.get("headline").stringValue() : null;
            return new BluebellEvaluation(rating, summary, headline);
        } catch (Exception jsonException) {
            Matcher ratingMatcher = RATING_PATTERN.matcher(text);
            String summary = extractField(SUMMARY_PATTERN, SUMMARY_PATTERN_SALVAGE, text);
            if (summary == null) {
                throw new IllegalArgumentException(
                        "Failed to parse bluebell evaluation response: " + text, jsonException);
            }
            Integer rating = ratingMatcher.find() ? Integer.parseInt(ratingMatcher.group(1)) : null;
            Matcher headlineMatcher = HEADLINE_PATTERN.matcher(text);
            String headline = headlineMatcher.find() ? headlineMatcher.group(1) : null;
            return new BluebellEvaluation(rating, summary, headline);
        }
    }

    /**
     * Parses Claude's JSON response, reporting whether the regex fallback was used.
     *
     * <p>Attempts strict JSON parsing first; on failure, falls back to regex extraction and
     * sets {@link ParseResult#usedRegexFallback()}. The fallback can over-capture adjacent
     * fields (e.g. swallow {@code headline} into {@code summary}) and still SUCCEED, so the flag
     * lets the batch result handler persist the raw response for diagnosis (the Bug B capture)
     * without changing any parsing behaviour. Parsing itself is identical to
     * {@link #parseEvaluation}.
     *
     * @param text   the raw text returned by Claude
     * @param mapper Jackson mapper for JSON parsing
     * @return the parsed evaluation and whether the regex fallback was used
     * @throws IllegalArgumentException if neither strict parsing nor the regex fallback succeeds
     */
    public ParseResult parseEvaluationWithMetadata(String text, ObjectMapper mapper) {
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
            Integer inversionScore = node.has("inversion_score")
                    ? node.get("inversion_score").asInt() : null;
            String inversionPotential = node.has("inversion_potential")
                    ? sanitiseInversionPotential(node.get("inversion_potential").stringValue())
                    : null;
            String headline = node.has("headline")
                    ? node.get("headline").stringValue() : null;
            // Bluebell left the standard prompt in Pass 3 — it now has its own prompt/parser
            // (parseBluebellEvaluation). The standard response no longer carries bluebell fields,
            // so they are always null here until the legacy columns drop (Pass 3 commit 5).
            return new ParseResult(new SunsetEvaluation(rating, fierySky, goldenHour, summary,
                    basicFierySky, basicGoldenHour, basicSummary,
                    inversionScore, inversionPotential, null, null,
                    headline), false);
        } catch (Exception jsonException) {
            // Strict JSON parse failed — fall through to the greedy regex fallback and flag it so
            // the caller can persist the raw response for diagnosis (Bug B). Diagnostic only:
            // parsing behaviour is unchanged.
            return new ParseResult(parseWithRegexFallback(text, jsonException), true);
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
        // Bounded first (no over-capture), greedy salvage only if the value has unescaped quotes.
        String summary = extractField(SUMMARY_PATTERN, SUMMARY_PATTERN_SALVAGE, text);

        Integer rating = ratingMatcher.find() ? Integer.parseInt(ratingMatcher.group(1)) : null;
        if (fierySkyMatcher.find() && goldenHourMatcher.find() && summary != null) {
            int fierySky = Integer.parseInt(fierySkyMatcher.group(1));
            int goldenHour = Integer.parseInt(goldenHourMatcher.group(1));

            Matcher basicFierySkyMatcher = BASIC_FIERY_SKY_PATTERN.matcher(text);
            Matcher basicGoldenHourMatcher = BASIC_GOLDEN_HOUR_PATTERN.matcher(text);
            Integer basicFierySky = basicFierySkyMatcher.find()
                    ? Integer.parseInt(basicFierySkyMatcher.group(1)) : null;
            Integer basicGoldenHour = basicGoldenHourMatcher.find()
                    ? Integer.parseInt(basicGoldenHourMatcher.group(1)) : null;
            String basicSummary = extractField(
                    BASIC_SUMMARY_PATTERN, BASIC_SUMMARY_PATTERN_SALVAGE, text);

            Matcher inversionScoreMatcher = INVERSION_SCORE_PATTERN.matcher(text);
            Matcher inversionPotentialMatcher = INVERSION_POTENTIAL_PATTERN.matcher(text);
            Integer inversionScore = inversionScoreMatcher.find()
                    ? Integer.parseInt(inversionScoreMatcher.group(1)) : null;
            String inversionPotential = inversionPotentialMatcher.find()
                    ? sanitiseInversionPotential(inversionPotentialMatcher.group(1)) : null;

            Matcher headlineMatcher = HEADLINE_PATTERN.matcher(text);
            String headline = headlineMatcher.find() ? headlineMatcher.group(1) : null;

            // Bluebell fields left the standard prompt in Pass 3 (own prompt/parser) — null here.
            return new SunsetEvaluation(rating, fierySky, goldenHour, summary,
                    basicFierySky, basicGoldenHour, basicSummary,
                    inversionScore, inversionPotential, null, null,
                    headline);
        }

        throw new IllegalArgumentException(
                "Failed to parse evaluation response: " + text, cause);
    }

    /**
     * Extracts a string field from malformed JSON, preferring a bounded pattern that cannot
     * over-capture into the following field (the Bug B fix), and only falling back to the legacy
     * greedy pattern when the bounded one finds no match — i.e. the value itself contains unescaped
     * quotes. The greedy salvage keeps the fallback resilient: it never loses the rating just
     * because a summary is internally mangled. Returns {@code null} if neither pattern matches.
     *
     * @param bounded the bounded (non-over-capturing) pattern, group 1 = value
     * @param salvage the greedy fallback pattern, group 1 = value
     * @param text    the raw response text
     * @return the extracted field value, or null if neither pattern matches
     */
    private static String extractField(Pattern bounded, Pattern salvage, String text) {
        Matcher boundedMatcher = bounded.matcher(text);
        if (boundedMatcher.find()) {
            return boundedMatcher.group(1);
        }
        Matcher salvageMatcher = salvage.matcher(text);
        return salvageMatcher.find() ? salvageMatcher.group(1) : null;
    }

    /**
     * Normalises the inversion potential value from Claude's response to a known enum name.
     * Maps any value containing "moderate" to MODERATE, "strong" to STRONG, otherwise NONE.
     * Prevents VARCHAR(10) overflow in the database.
     *
     * @param raw the raw string from Claude's JSON response
     * @return NONE, MODERATE, or STRONG
     */
    static String sanitiseInversionPotential(String raw) {
        if (raw == null) {
            return null;
        }
        String upper = raw.toUpperCase();
        if (upper.contains("STRONG")) {
            return "STRONG";
        }
        if (upper.contains("MODERATE")) {
            return "MODERATE";
        }
        return "NONE";
    }
}
