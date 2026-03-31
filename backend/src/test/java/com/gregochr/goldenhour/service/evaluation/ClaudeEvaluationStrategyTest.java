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
