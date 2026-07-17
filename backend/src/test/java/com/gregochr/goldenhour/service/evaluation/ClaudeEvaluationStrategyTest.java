package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputTokensDetails;
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
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.TideSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
                anthropicApiClient, new PromptBuilder(), new CoastalPromptBuilder(),
                new ObjectMapper(), EvaluationModel.SONNET, new SunsetEvaluationParser());
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
                .stopDetails(Optional.empty())
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

        AnthropicServiceException badRequestException = mock(AnthropicServiceException.class);

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








    // ── Bug B: regex-fallback over-capture fix (bounded summary capture) ──────

    // SYNTHETIC fixture — reconstructs the over-capture trigger from the real
    // cached_evaluation artifact ("…horizon.','headline\"}", Tyne and Wear /
    // Angel of the North, observed 2026-06). Not a captured production rawText
    // (over-capture is a silent success and was never persisted pre-instrumentation).
    // Purpose: demonstrate the bounded-regex fix handles the over-capture CLASS.
    // A single-quoted headline value breaks strict JSON (sending it to the fallback) and,
    // with the OLD greedy pattern, the summary swallowed up to the last quote
    // (…horizon.","headline). The headline is single-quoted so it is unextractable — that is
    // the model's malformation; the FIX's job is to stop the summary swallowing it.











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


    // ── Code-fenced JSON ──────────────────────────────────────────────────────



    // ── Kitchen-sink response (all fields simultaneously) ────────────────────


    // ── Score boundary values ────────────────────────────────────────────────



    // ── Regex fallback for extended fields ────────────────────────────────────



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

    // ── Builder Selection (coastal vs inland) ───────────────────────────────

    @Test
    @DisplayName("evaluateWithDetails() sends coastal (surge) system prompt when data has tide")
    void evaluateWithDetails_withTide_sendsCoastalSystemPrompt() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null,
                        LunarTideType.SPRING_TIDE, "Full Moon", false, null))
                .build();
        String rawJson = "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75,"
                + " \"summary\": \"Coastal conditions.\"}";
        Message response = buildMessage(rawJson);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        strategy.evaluateWithDetails(data);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());

        MessageCreateParams params = captor.getValue();
        String systemPrompt = params.system().get().asTextBlockParams().get(0).text();

        // v2.13.2: coastal guidance is now surge-only; the tide-boost guidance is gone.
        assertThat(systemPrompt)
                .contains("COASTAL CONDITIONS GUIDANCE:")
                .doesNotContain("COASTAL TIDE GUIDANCE");
    }

    @Test
    @DisplayName("evaluateWithDetails() sends base system prompt (no coastal guidance) when data has no tide")
    void evaluateWithDetails_withoutTide_sendsBaseSystemPrompt() {
        AtmosphericData data = TestAtmosphericData.defaults();
        String rawJson = "{\"rating\": 3, \"fiery_sky\": 50, \"golden_hour\": 55,"
                + " \"summary\": \"Inland conditions.\"}";
        Message response = buildMessage(rawJson);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        strategy.evaluateWithDetails(data);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());

        MessageCreateParams params = captor.getValue();
        String systemPrompt = params.system().get().asTextBlockParams().get(0).text();

        assertThat(systemPrompt).doesNotContain("COASTAL TIDE GUIDANCE:");
    }

    @Test
    @DisplayName("SSE path uses default cache TTL (not 1-hour batch TTL)")
    void evaluateWithDetails_cacheControlUsesDefaultTtl() {
        AtmosphericData data = TestAtmosphericData.defaults();
        String rawJson = "{\"rating\": 3, \"fiery_sky\": 50, \"golden_hour\": 55,"
                + " \"summary\": \"Test.\"}";
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(buildMessage(rawJson));

        strategy.evaluateWithDetails(data);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());

        // SSE path should have cache control but NO explicit TTL — the API default
        // (5 min) is appropriate for single-request real-time calls. Only batch
        // paths should use TTL_1H.
        var systemBlock = captor.getValue().system().get().asTextBlockParams().get(0);
        assertThat(systemBlock.cacheControl()).isPresent();
        assertThat(systemBlock.cacheControl().get().ttl())
                .isNotEqualTo(java.util.Optional.of(CacheControlEphemeral.Ttl.TTL_1H));
    }

    @Test
    @DisplayName("evaluateWithDetails() user message omits the tide block even for coastal data "
            + "(v2.13.2: tide is scored separately by TideVisitor, not in the prompt)")
    void evaluateWithDetails_withTide_userMessageOmitsTideBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null,
                        LunarTideType.KING_TIDE, "New Moon", true, null))
                .build();
        String rawJson = "{\"rating\": 5, \"fiery_sky\": 85, \"golden_hour\": 90,"
                + " \"summary\": \"King tide conditions.\"}";
        Message response = buildMessage(rawJson);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        assertThat(detail.promptSent())
                .doesNotContain("Tide:")
                .doesNotContain("KING TIDE");
    }

    @Test
    @DisplayName("evaluateWithDetails() user message omits tide block for inland data")
    void evaluateWithDetails_withoutTide_userMessageOmitsTideBlock() {
        AtmosphericData data = TestAtmosphericData.defaults();
        String rawJson = "{\"rating\": 3, \"fiery_sky\": 50, \"golden_hour\": 55,"
                + " \"summary\": \"Standard.\"}";
        Message response = buildMessage(rawJson);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        assertThat(detail.promptSent()).doesNotContain("Tide:");
    }

    @Test
    @DisplayName("evaluateWithDetails() coastal data with surge includes the surge block (no tide line)")
    void evaluateWithDetails_coastalWithSurge_includesSurgeNotTide() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Moderate surge");
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null,
                        LunarTideType.SPRING_TIDE, "Full Moon", false, null))
                .build()
                .withSurge(surge, 4.85, 4.50);
        String rawJson = "{\"rating\": 4, \"fiery_sky\": 72, \"golden_hour\": 68,"
                + " \"summary\": \"Surge + tide.\"}";
        Message response = buildMessage(rawJson);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        // v2.13.2: surge block kept (foreground/safety), tide line removed (scored separately).
        assertThat(detail.promptSent())
                .doesNotContain("Tide:")
                .contains("STORM SURGE FORECAST:")
                .contains("Risk level: MODERATE");

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        String systemPrompt = captor.getValue().system().get().asTextBlockParams().get(0).text();
        assertThat(systemPrompt).contains("COASTAL CONDITIONS GUIDANCE:");
    }

    @Test
    @DisplayName("evaluateWithDetails() user prompt contains fiery_sky, golden_hour, and location name")
    void evaluateWithDetails_userMessage_containsPromptKeywords() {
        AtmosphericData data = buildAtmosphericData();
        String rawJson = "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75,"
                + " \"summary\": \"Standard.\"}";
        Message response = buildMessage(rawJson);
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        String systemPrompt = captor.getValue().system().get()
                .asTextBlockParams().get(0).text();
        assertThat(systemPrompt).isNotBlank();

        assertThat(detail.promptSent()).contains("Fiery Sky Potential");
        assertThat(detail.promptSent()).contains("Golden Hour Potential");
        assertThat(detail.promptSent()).contains("Durham UK");
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
                .stopDetails(Optional.empty())
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
                .outputTokensDetails(OutputTokensDetails.builder()
                        .thinkingTokens(0)
                        .build())
                .build();
    }

    private AtmosphericData buildAtmosphericData() {
        return TestAtmosphericData.defaults();
    }
}
