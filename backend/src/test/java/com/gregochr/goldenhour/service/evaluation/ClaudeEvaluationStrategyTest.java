package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClaudeEvaluationStrategy}.
 *
 * <p>Tests the shared logic: Claude API call, response parsing, token extraction,
 * and dual-tier scoring. Prompt construction tests live in {@link PromptBuilderTest};
 * metrics logging tests live in {@code MetricsLoggingDecoratorTest}.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeEvaluationStrategyTest {

    @Mock
    private AnthropicApiClient anthropicApiClient;

    private ClaudeEvaluationStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ClaudeEvaluationStrategy(
                anthropicApiClient, new PromptBuilder(), new ObjectMapper(), EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("evaluate() calls Claude and returns parsed evaluation with all scores")
    void evaluate_callsClaude_returnsParsedEvaluation() {
        AtmosphericData data = buildAtmosphericData();
        Message response = buildMessage(
                "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75,"
                + " \"summary\": \"Promising conditions.\"}");

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(70);
        assertThat(result.goldenHourPotential()).isEqualTo(75);
        assertThat(result.summary()).isEqualTo("Promising conditions.");
    }

    @Test
    @DisplayName("evaluate() throws when Claude returns no text content")
    void evaluate_noTextContent_throwsIllegalStateException() {
        AtmosphericData data = buildAtmosphericData();
        Message response = Message.builder()
                .id("msg_test")
                .model(Model.of("claude-sonnet-4-5-20250929"))
                .content(List.of())
                .stopReason(StopReason.END_TURN)
                .stopSequence(Optional.empty())
                .usage(buildUsage(10, 20, 0, 0))
                .build();

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        assertThatThrownBy(() -> strategy.evaluate(data))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no text content");
    }

    @Test
    @DisplayName("evaluate() propagates non-retryable Anthropic errors")
    void evaluate_nonRetryableError_propagates() {
        AtmosphericData data = buildAtmosphericData();

        AnthropicServiceException badRequestException = buildServiceException(
                400, "invalid_request_error: max_tokens must be positive");

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenThrow(badRequestException);

        assertThatThrownBy(() -> strategy.evaluate(data))
                .isInstanceOf(AnthropicServiceException.class);
    }

    @Test
    @DisplayName("evaluateWithDetails() returns prompt, raw response, and token usage")
    void evaluateWithDetails_returnsFull() {
        AtmosphericData data = buildAtmosphericData();
        String rawJson = "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75,"
                + " \"summary\": \"Promising conditions.\"}";
        Message response = buildMessage(rawJson, 400, 60, 0, 150);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        assertThat(detail.evaluation().rating()).isEqualTo(4);
        assertThat(detail.evaluation().fierySkyPotential()).isEqualTo(70);
        assertThat(detail.evaluation().goldenHourPotential()).isEqualTo(75);
        assertThat(detail.promptSent()).contains("Durham UK");
        assertThat(detail.promptSent()).endsWith(PromptBuilder.PROMPT_SUFFIX);
        assertThat(detail.rawResponse()).isEqualTo(rawJson);
        assertThat(detail.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(detail.tokenUsage()).isNotNull();
        assertThat(detail.tokenUsage().inputTokens()).isEqualTo(400);
        assertThat(detail.tokenUsage().outputTokens()).isEqualTo(60);
        assertThat(detail.tokenUsage().cacheReadInputTokens()).isEqualTo(150);
    }

    @Test
    @DisplayName("extractTokenUsage() extracts all four token categories from SDK response")
    void extractTokenUsage_extractsAllCategories() {
        Message response = buildMessage("{\"rating\": 3, \"fiery_sky\": 50, \"golden_hour\": 60,"
                + " \"summary\": \"Test.\"}", 500, 80, 200, 100);

        TokenUsage usage = strategy.extractTokenUsage(response);

        assertThat(usage.inputTokens()).isEqualTo(500);
        assertThat(usage.outputTokens()).isEqualTo(80);
        assertThat(usage.cacheCreationInputTokens()).isEqualTo(200);
        assertThat(usage.cacheReadInputTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("parseEvaluation() handles missing rating field gracefully")
    void parseEvaluation_missingRating_returnsNullRating() {
        SunsetEvaluation result = strategy.parseEvaluation(
                "{\"fiery_sky\": 50, \"golden_hour\": 60, \"summary\": \"Moderate conditions.\"}",
                new ObjectMapper());

        assertThat(result.rating()).isNull();
        assertThat(result.fierySkyPotential()).isEqualTo(50);
        assertThat(result.goldenHourPotential()).isEqualTo(60);
    }

    @Test
    @DisplayName("evaluate() parses dual-tier response with basic fields")
    void evaluate_dualTierResponse_parsesBasicFields() {
        AtmosphericData data = buildAtmosphericData();
        String json = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":78,"
                + "\"summary\":\"Enhanced evaluation with directional data.\","
                + "\"basic_fiery_sky\":55,\"basic_golden_hour\":60,"
                + "\"basic_summary\":\"Moderate potential with some low cloud.\"}";
        Message response = buildMessage(json);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(72);
        assertThat(result.goldenHourPotential()).isEqualTo(78);
        assertThat(result.summary()).isEqualTo("Enhanced evaluation with directional data.");
        assertThat(result.basicFierySkyPotential()).isEqualTo(55);
        assertThat(result.basicGoldenHourPotential()).isEqualTo(60);
        assertThat(result.basicSummary()).isEqualTo("Moderate potential with some low cloud.");
    }

    @Test
    @DisplayName("evaluate() returns null basic fields when not in response")
    void evaluate_noBasicFields_returnsNullBasicFields() {
        AtmosphericData data = buildAtmosphericData();
        String json = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":75,"
                + "\"summary\":\"Standard evaluation.\"}";
        Message response = buildMessage(json);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.fierySkyPotential()).isEqualTo(70);
        assertThat(result.basicFierySkyPotential()).isNull();
        assertThat(result.basicGoldenHourPotential()).isNull();
        assertThat(result.basicSummary()).isNull();
    }

    @Test
    @DisplayName("evaluateWithDetails() uses surge-aware prompt when surge is non-null")
    void evaluateWithDetails_withSurge_usesSurgeAwarePrompt() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Moderate surge conditions");
        AtmosphericData data = TestAtmosphericData.defaults()
                .withSurge(surge, 5.4, 5.0);
        String rawJson = "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75,"
                + " \"summary\": \"Surge conditions.\"}";
        Message response = buildMessage(rawJson);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        assertThat(detail.promptSent()).contains("STORM SURGE");
        assertThat(detail.evaluation().fierySkyPotential()).isEqualTo(70);
    }

    @Test
    @DisplayName("evaluateWithDetails() uses standard prompt when surge is null")
    void evaluateWithDetails_withoutSurge_usesStandardPrompt() {
        AtmosphericData data = TestAtmosphericData.defaults();
        String rawJson = "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75,"
                + " \"summary\": \"Standard.\"}";
        Message response = buildMessage(rawJson);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        assertThat(detail.promptSent()).doesNotContain("STORM SURGE");
    }

    @Test
    @DisplayName("parseEvaluation() extracts inversion fields from JSON response")
    void parseEvaluation_withInversionFields_extractsInversion() {
        String json = "{\"rating\":5,\"fiery_sky\":85,\"golden_hour\":90,"
                + "\"summary\":\"Dramatic inversion.\","
                + "\"inversion_score\":9,\"inversion_potential\":\"STRONG\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.inversionScore()).isEqualTo(9);
        assertThat(result.inversionPotential()).isEqualTo("STRONG");
    }

    @Test
    @DisplayName("parseEvaluation() returns null inversion fields when not present")
    void parseEvaluation_noInversionFields_returnsNullInversion() {
        String json = "{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":60,"
                + "\"summary\":\"Normal conditions.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.inversionScore()).isNull();
        assertThat(result.inversionPotential()).isNull();
    }

    @Test
    @DisplayName("evaluate() parses inversion fields from full Claude response")
    void evaluate_withInversionResponse_parsesInversionFields() {
        AtmosphericData data = buildAtmosphericData();
        String json = "{\"rating\":5,\"fiery_sky\":88,\"golden_hour\":90,"
                + "\"summary\":\"Spectacular sea of clouds.\","
                + "\"inversion_score\":10,\"inversion_potential\":\"STRONG\"}";
        Message response = buildMessage(json);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.inversionScore()).isEqualTo(10);
        assertThat(result.inversionPotential()).isEqualTo("STRONG");
    }

    @Test
    @DisplayName("parseEvaluation() extracts moderate inversion fields")
    void parseEvaluation_moderateInversion_extractsCorrectly() {
        String json = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":75,"
                + "\"summary\":\"Cloud blanket visible.\","
                + "\"inversion_score\":7,\"inversion_potential\":\"MODERATE\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.inversionScore()).isEqualTo(7);
        assertThat(result.inversionPotential()).isEqualTo("MODERATE");
    }

    @Test
    @DisplayName("sanitiseInversionPotential() normalises verbose Claude responses to enum names")
    void sanitiseInversionPotential_normalisesVerboseValues() {
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("STRONG")).isEqualTo("STRONG");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("MODERATE")).isEqualTo("MODERATE");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("NONE")).isEqualTo("NONE");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential(null)).isNull();
    }

    @Test
    @DisplayName("sanitiseInversionPotential() handles verbose labels from Claude")
    void sanitiseInversionPotential_handlesVerboseLabels() {
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential(
                "Moderate Cloud Inversion Potential")).isEqualTo("MODERATE");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential(
                "Strong Cloud Inversion Potential")).isEqualTo("STRONG");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential(
                "No inversion potential")).isEqualTo("NONE");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential(
                "not_applicable")).isEqualTo("NONE");
    }

    @Test
    @DisplayName("parseEvaluation() sanitises verbose inversion_potential from Claude JSON")
    void parseEvaluation_verboseInversionPotential_sanitisedToEnum() {
        String json = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":75,"
                + "\"summary\":\"Cloud blanket below.\","
                + "\"inversion_score\":8,"
                + "\"inversion_potential\":\"Moderate Cloud Inversion Potential\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.inversionPotential()).isEqualTo("MODERATE");
    }

    @Test
    @DisplayName("parseEvaluation() extracts basic-tier fields when present")
    void parseEvaluation_withBasicTierFields_extractsBasicScores() {
        String json = "{\"rating\":4,\"fiery_sky\":75,\"golden_hour\":70,"
                + "\"summary\":\"Good conditions.\","
                + "\"basic_fiery_sky\":60,\"basic_golden_hour\":55,"
                + "\"basic_summary\":\"Decent conditions.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.basicFierySkyPotential()).isEqualTo(60);
        assertThat(result.basicGoldenHourPotential()).isEqualTo(55);
        assertThat(result.basicSummary()).isEqualTo("Decent conditions.");
    }

    @Test
    @DisplayName("parseEvaluation() returns null basic-tier fields when not present")
    void parseEvaluation_noBasicTierFields_returnsNull() {
        String json = "{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":60,"
                + "\"summary\":\"Average.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.basicFierySkyPotential()).isNull();
        assertThat(result.basicGoldenHourPotential()).isNull();
        assertThat(result.basicSummary()).isNull();
    }

    @Test
    @DisplayName("parseEvaluation() falls back to regex when JSON has unescaped quotes in summary")
    void parseEvaluation_regexFallback_extractsScores() {
        // Invalid JSON due to unescaped quotes — triggers regex fallback
        String text = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":65,"
                + "\"summary\":\"Beautiful \"orange\" sky at sunset.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(text, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(72);
        assertThat(result.goldenHourPotential()).isEqualTo(65);
        assertThat(result.summary()).contains("orange");
    }

    @Test
    @DisplayName("parseEvaluation() throws when regex fallback also fails")
    void parseEvaluation_totalGarbage_throws() {
        String text = "This is not JSON and has no matching patterns.";

        assertThatThrownBy(() -> strategy.parseEvaluation(text, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    @DisplayName("parseEvaluation() handles rating-only response (no basic or inversion fields)")
    void parseEvaluation_ratingOnly_minimalResponse() {
        String json = "{\"rating\":2,\"fiery_sky\":25,\"golden_hour\":30,"
                + "\"summary\":\"Cloudy with little colour.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(2);
        assertThat(result.fierySkyPotential()).isEqualTo(25);
        assertThat(result.goldenHourPotential()).isEqualTo(30);
        assertThat(result.summary()).isEqualTo("Cloudy with little colour.");
        assertThat(result.basicFierySkyPotential()).isNull();
        assertThat(result.basicGoldenHourPotential()).isNull();
        assertThat(result.basicSummary()).isNull();
        assertThat(result.inversionScore()).isNull();
        assertThat(result.inversionPotential()).isNull();
    }

    @Test
    @DisplayName("parseEvaluation() without rating field returns null rating")
    void parseEvaluation_noRatingField_returnsNullRating() {
        String json = "{\"fiery_sky\":80,\"golden_hour\":75,"
                + "\"summary\":\"Great sky.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isNull();
        assertThat(result.fierySkyPotential()).isEqualTo(80);
        assertThat(result.goldenHourPotential()).isEqualTo(75);
    }

    @Test
    @DisplayName("extractTokenUsage() extracts all four token counts")
    void extractTokenUsage_extractsAllCounts() {
        Message response = buildMessage("test", 500, 200, 1000, 300);

        TokenUsage usage = strategy.extractTokenUsage(response);

        assertThat(usage.inputTokens()).isEqualTo(500);
        assertThat(usage.outputTokens()).isEqualTo(200);
        assertThat(usage.cacheCreationInputTokens()).isEqualTo(1000);
        assertThat(usage.cacheReadInputTokens()).isEqualTo(300);
    }

    @Test
    @DisplayName("sanitiseInversionPotential() handles case variations")
    void sanitiseInversionPotential_caseInsensitive() {
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("strong")).isEqualTo("STRONG");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("Strong")).isEqualTo("STRONG");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("moderate")).isEqualTo("MODERATE");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("Moderate")).isEqualTo("MODERATE");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("none")).isEqualTo("NONE");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("")).isEqualTo("NONE");
    }

    // ── Code-fenced JSON ──────────────────────────────────────────────────────

    @Test
    @DisplayName("parseEvaluation() strips ```json fences around response")
    void parseEvaluation_codeFencedJson_strippedAndParsed() {
        String fenced = "```json\n"
                + "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,"
                + "\"summary\":\"Fenced response.\"}\n"
                + "```";

        SunsetEvaluation result = strategy.parseEvaluation(fenced, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(70);
        assertThat(result.goldenHourPotential()).isEqualTo(65);
        assertThat(result.summary()).isEqualTo("Fenced response.");
    }

    @Test
    @DisplayName("parseEvaluation() strips bare ``` fences (no language tag)")
    void parseEvaluation_bareFences_strippedAndParsed() {
        String fenced = "```\n"
                + "{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":55,"
                + "\"summary\":\"Bare fence.\"}\n"
                + "```";

        SunsetEvaluation result = strategy.parseEvaluation(fenced, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(3);
        assertThat(result.summary()).isEqualTo("Bare fence.");
    }

    // ── Kitchen-sink response (all fields simultaneously) ────────────────────

    @Test
    @DisplayName("parseEvaluation() extracts all fields when response contains everything")
    void parseEvaluation_allFieldsPresent_extractsEveryField() {
        String json = "{\"rating\":5,\"fiery_sky\":92,\"golden_hour\":88,"
                + "\"summary\":\"Spectacular conditions.\","
                + "\"basic_fiery_sky\":65,\"basic_golden_hour\":60,"
                + "\"basic_summary\":\"Decent conditions.\","
                + "\"inversion_score\":9,\"inversion_potential\":\"STRONG\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.fierySkyPotential()).isEqualTo(92);
        assertThat(result.goldenHourPotential()).isEqualTo(88);
        assertThat(result.summary()).isEqualTo("Spectacular conditions.");
        assertThat(result.basicFierySkyPotential()).isEqualTo(65);
        assertThat(result.basicGoldenHourPotential()).isEqualTo(60);
        assertThat(result.basicSummary()).isEqualTo("Decent conditions.");
        assertThat(result.inversionScore()).isEqualTo(9);
        assertThat(result.inversionPotential()).isEqualTo("STRONG");
    }

    // ── Score boundary values ────────────────────────────────────────────────

    @Test
    @DisplayName("parseEvaluation() handles minimum score boundaries (rating=1, fiery=0, golden=0)")
    void parseEvaluation_minimumScores_parsedCorrectly() {
        String json = "{\"rating\":1,\"fiery_sky\":0,\"golden_hour\":0,"
                + "\"summary\":\"Total overcast.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(1);
        assertThat(result.fierySkyPotential()).isZero();
        assertThat(result.goldenHourPotential()).isZero();
    }

    @Test
    @DisplayName("parseEvaluation() handles maximum score boundaries (rating=5, fiery=100, golden=100)")
    void parseEvaluation_maximumScores_parsedCorrectly() {
        String json = "{\"rating\":5,\"fiery_sky\":100,\"golden_hour\":100,"
                + "\"summary\":\"Perfect conditions.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.fierySkyPotential()).isEqualTo(100);
        assertThat(result.goldenHourPotential()).isEqualTo(100);
    }

    // ── Regex fallback for extended fields ────────────────────────────────────

    @Test
    @DisplayName("regex fallback extracts basic-tier fields from malformed JSON")
    void parseEvaluation_regexFallback_extractsBasicFields() {
        // Invalid JSON (unescaped quote) but regex can extract all fields
        String text = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":65,"
                + "\"summary\":\"Beautiful \"glow\" at sunset.\","
                + "\"basic_fiery_sky\":55,\"basic_golden_hour\":50,"
                + "\"basic_summary\":\"Moderate conditions.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(text, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(72);
        assertThat(result.basicFierySkyPotential()).isEqualTo(55);
        assertThat(result.basicGoldenHourPotential()).isEqualTo(50);
    }

    @Test
    @DisplayName("regex fallback extracts inversion fields from malformed JSON")
    void parseEvaluation_regexFallback_extractsInversionFields() {
        String text = "{\"rating\":5,\"fiery_sky\":88,\"golden_hour\":90,"
                + "\"summary\":\"Sea of \"clouds\" below.\","
                + "\"inversion_score\":10,\"inversion_potential\":\"STRONG\"}";

        SunsetEvaluation result = strategy.parseEvaluation(text, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.inversionScore()).isEqualTo(10);
        assertThat(result.inversionPotential()).isEqualTo("STRONG");
    }

    // ── Evaluate with cache-hit tokens ─────────────────────────────────────

    @Test
    @DisplayName("extractTokenUsage() handles zero cache tokens without error")
    void extractTokenUsage_zeroCacheTokens_parsesCorrectly() {
        Message response = buildMessage("{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":55,"
                + "\"summary\":\"Test.\"}", 300, 50, 0, 0);

        TokenUsage usage = strategy.extractTokenUsage(response);

        assertThat(usage.cacheCreationInputTokens()).isZero();
        assertThat(usage.cacheReadInputTokens()).isZero();
        assertThat(usage.inputTokens()).isEqualTo(300);
    }

    // --- Helper methods ---

    private Message buildMessage(String text) {
        return buildMessage(text, 10, 20, 0, 0);
    }

    private Message buildMessage(String text, long inputTokens, long outputTokens,
            long cacheCreationTokens, long cacheReadTokens) {
        TextBlock textBlock = TextBlock.builder()
                .text(text)
                .citations(List.of())
                .build();
        ContentBlock contentBlock = ContentBlock.ofText(textBlock);
        return Message.builder()
                .id("msg_test")
                .model(Model.of("claude-sonnet-4-5-20250929"))
                .content(List.of(contentBlock))
                .stopReason(StopReason.END_TURN)
                .stopSequence(Optional.empty())
                .usage(buildUsage(inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens))
                .build();
    }

    private Usage buildUsage(long inputTokens, long outputTokens,
            long cacheCreationTokens, long cacheReadTokens) {
        return Usage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cacheCreation(CacheCreation.builder()
                        .ephemeral5mInputTokens(0)
                        .ephemeral1hInputTokens(0)
                        .build())
                .cacheCreationInputTokens(cacheCreationTokens)
                .cacheReadInputTokens(cacheReadTokens)
                .inferenceGeo("us")
                .serverToolUse(ServerToolUsage.builder()
                        .webSearchRequests(0)
                        .webFetchRequests(0)
                        .build())
                .serviceTier(Usage.ServiceTier.of("standard"))
                .build();
    }

    private AnthropicServiceException buildServiceException(int statusCode, String message) {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        org.mockito.Mockito.lenient().when(ex.statusCode()).thenReturn(statusCode);
        org.mockito.Mockito.lenient().when(ex.getMessage()).thenReturn(message);
        return ex;
    }

    private AtmosphericData buildAtmosphericData() {
        return TestAtmosphericData.defaults();
    }
}
