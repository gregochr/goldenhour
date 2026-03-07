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
import com.gregochr.goldenhour.config.AnthropicProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.JobRunService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AbstractEvaluationStrategy}.
 *
 * <p>Uses a concrete {@code TestEvaluationStrategy} inner class to test
 * the shared logic in the abstract base (user message construction, Claude API
 * call, and response parsing).
 */
@ExtendWith(MockitoExtension.class)
class AbstractEvaluationStrategyTest {

    private static final String TEST_SUFFIX = "Test suffix.";

    @Mock
    private AnthropicApiClient anthropicApiClient;

    @Mock
    private JobRunService jobRunService;

    private TestEvaluationStrategy strategy;

    @BeforeEach
    void setUp() {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setModel("claude-sonnet-4-5-20250929");
        strategy = new TestEvaluationStrategy(anthropicApiClient, properties, new ObjectMapper(), jobRunService);
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
    @DisplayName("buildUserMessage() contains the strategy's prompt suffix")
    void buildUserMessage_containsSuffix() {
        AtmosphericData data = buildAtmosphericData();
        String message = strategy.buildUserMessage(data);

        assertThat(message).endsWith(TEST_SUFFIX);
    }

    @Test
    @DisplayName("buildUserMessage() contains location and weather data")
    void buildUserMessage_containsLocationData() {
        AtmosphericData data = buildAtmosphericData();
        String message = strategy.buildUserMessage(data);

        assertThat(message).contains("Durham UK");
        assertThat(message).contains("SUNSET");
        assertThat(message).contains("Low 10%");
        assertThat(message).contains("Mid 50%");
        assertThat(message).contains("High 30%");
        assertThat(message).contains("Precip probability:");
    }

    @Test
    @DisplayName("buildUserMessage() includes dust context when AOD exceeds threshold")
    void buildUserMessage_highAod_includesDustContext() {
        AtmosphericData data = buildAtmosphericDataWithDust(
                new BigDecimal("0.50"), new BigDecimal("12.00"));
        String message = strategy.buildUserMessage(data);

        assertThat(message).contains("SAHARAN DUST CONTEXT:");
        assertThat(message).contains("AOD: 0.50 (elevated)");
        assertThat(message).contains("Surface dust: 12.00");
        assertThat(message).contains("SW");
        assertThat(message).contains("maximises warm scattering potential");
    }

    @Test
    @DisplayName("buildUserMessage() includes dust context when surface dust exceeds threshold")
    void buildUserMessage_highDust_includesDustContext() {
        AtmosphericData data = buildAtmosphericDataWithDust(
                new BigDecimal("0.10"), new BigDecimal("65.00"));
        String message = strategy.buildUserMessage(data);

        assertThat(message).contains("SAHARAN DUST CONTEXT:");
        assertThat(message).contains("Surface dust: 65.00");
    }

    @Test
    @DisplayName("buildUserMessage() omits dust context when both AOD and dust are below threshold")
    void buildUserMessage_lowAerosols_noDustContext() {
        AtmosphericData data = buildAtmosphericData();
        String message = strategy.buildUserMessage(data);

        assertThat(message).doesNotContain("SAHARAN DUST CONTEXT:");
    }

    @Test
    @DisplayName("toCardinal() converts degrees to 16-point compass directions")
    void toCardinal_convertsCorrectly() {
        assertThat(AbstractEvaluationStrategy.toCardinal(0)).isEqualTo("N");
        assertThat(AbstractEvaluationStrategy.toCardinal(45)).isEqualTo("NE");
        assertThat(AbstractEvaluationStrategy.toCardinal(90)).isEqualTo("E");
        assertThat(AbstractEvaluationStrategy.toCardinal(135)).isEqualTo("SE");
        assertThat(AbstractEvaluationStrategy.toCardinal(180)).isEqualTo("S");
        assertThat(AbstractEvaluationStrategy.toCardinal(225)).isEqualTo("SW");
        assertThat(AbstractEvaluationStrategy.toCardinal(270)).isEqualTo("W");
        assertThat(AbstractEvaluationStrategy.toCardinal(315)).isEqualTo("NW");
        assertThat(AbstractEvaluationStrategy.toCardinal(360)).isEqualTo("N");
        assertThat(AbstractEvaluationStrategy.toCardinal(22)).isEqualTo("NNE");
        assertThat(AbstractEvaluationStrategy.toCardinal(202)).isEqualTo("SSW");
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
    @DisplayName("evaluate() logs Anthropic API call with token usage when jobRun is provided")
    void evaluate_logsAnthropicApiCallWithTokenUsage() {
        AtmosphericData data = buildAtmosphericData();
        JobRunEntity jobRun = JobRunEntity.builder().id(1L).build();
        Message response = buildMessage(
                "{\"rating\": 3, \"fiery_sky\": 70, \"golden_hour\": 75,"
                + " \"summary\": \"Good conditions.\"}",
                500, 80, 200, 100);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        strategy.evaluate(data, jobRun);

        ArgumentCaptor<TokenUsage> tokenCaptor = ArgumentCaptor.forClass(TokenUsage.class);
        verify(jobRunService).logAnthropicApiCall(
                eq(1L), anyLong(), eq(200), any(), eq(true), any(),
                eq(EvaluationModel.SONNET), tokenCaptor.capture(), eq(false), any(), any());

        TokenUsage captured = tokenCaptor.getValue();
        assertThat(captured.inputTokens()).isEqualTo(500);
        assertThat(captured.outputTokens()).isEqualTo(80);
        assertThat(captured.cacheCreationInputTokens()).isEqualTo(200);
        assertThat(captured.cacheReadInputTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("evaluate() logs failure with EMPTY token usage when jobRun is provided")
    void evaluate_logsFailureWithEmptyTokenUsage() {
        AtmosphericData data = buildAtmosphericData();
        JobRunEntity jobRun = JobRunEntity.builder().id(1L).build();

        AnthropicServiceException rateLimitException = buildServiceException(
                429, "API rate limit exceeded");

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenThrow(rateLimitException);

        try {
            strategy.evaluate(data, jobRun);
        } catch (Exception ignored) {
            // Expected to throw
        }

        ArgumentCaptor<TokenUsage> tokenCaptor = ArgumentCaptor.forClass(TokenUsage.class);
        verify(jobRunService).logAnthropicApiCall(
                eq(1L), anyLong(), eq(429), any(), eq(false), any(),
                eq(EvaluationModel.SONNET), tokenCaptor.capture(), eq(false), any(), any());

        assertThat(tokenCaptor.getValue()).isEqualTo(TokenUsage.EMPTY);
    }

    @Test
    @DisplayName("evaluateWithDetails() returns prompt, raw response, and token usage")
    void evaluateWithDetails_returnsFull() {
        AtmosphericData data = buildAtmosphericData();
        String rawJson = "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75,"
                + " \"summary\": \"Promising conditions.\"}";
        Message response = buildMessage(rawJson, 400, 60, 0, 150);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data, null);

        assertThat(detail.evaluation().rating()).isEqualTo(4);
        assertThat(detail.evaluation().fierySkyPotential()).isEqualTo(70);
        assertThat(detail.evaluation().goldenHourPotential()).isEqualTo(75);
        assertThat(detail.promptSent()).contains("Durham UK");
        assertThat(detail.promptSent()).endsWith(TEST_SUFFIX);
        assertThat(detail.rawResponse()).isEqualTo(rawJson);
        assertThat(detail.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(detail.tokenUsage()).isNotNull();
        assertThat(detail.tokenUsage().inputTokens()).isEqualTo(400);
        assertThat(detail.tokenUsage().outputTokens()).isEqualTo(60);
        assertThat(detail.tokenUsage().cacheReadInputTokens()).isEqualTo(150);
    }

    @Test
    @DisplayName("evaluateWithDetails() logs Anthropic API call when jobRun provided")
    void evaluateWithDetails_logsAnthropicApiCall() {
        AtmosphericData data = buildAtmosphericData();
        JobRunEntity jobRun = JobRunEntity.builder().id(1L).build();
        Message response = buildMessage(
                "{\"rating\": 3, \"fiery_sky\": 50, \"golden_hour\": 60,"
                + " \"summary\": \"Moderate conditions.\"}");

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        strategy.evaluateWithDetails(data, jobRun);

        verify(jobRunService).logAnthropicApiCall(
                eq(1L), anyLong(), eq(200), any(), eq(true), any(),
                eq(EvaluationModel.SONNET), any(TokenUsage.class), eq(false), any(), any());
    }

    @Test
    @DisplayName("evaluateWithDetails() throws and logs failure when Claude fails")
    void evaluateWithDetails_logsFailure() {
        AtmosphericData data = buildAtmosphericData();
        JobRunEntity jobRun = JobRunEntity.builder().id(1L).build();

        AnthropicServiceException exception = buildServiceException(500, "Internal error");

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenThrow(exception);

        assertThatThrownBy(() -> strategy.evaluateWithDetails(data, jobRun))
                .isInstanceOf(AnthropicServiceException.class);

        ArgumentCaptor<Boolean> succeededCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(jobRunService).logAnthropicApiCall(
                eq(1L), anyLong(), eq(500), any(), succeededCaptor.capture(), any(),
                eq(EvaluationModel.SONNET), eq(TokenUsage.EMPTY), eq(false), any(), any());

        assertThat(succeededCaptor.getValue()).isFalse();
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

    // --- Directional cloud and dual-tier tests ---

    @Test
    @DisplayName("buildUserMessage() includes directional cloud data when available")
    void buildUserMessage_withDirectionalCloud_includesDirectionalBlock() {
        AtmosphericData data = buildAtmosphericDataWithDirectionalCloud();
        String message = strategy.buildUserMessage(data);

        assertThat(message).contains("DIRECTIONAL CLOUD (50km sample):");
        assertThat(message).contains("Solar horizon (toward sun): Low 65%, Mid 20%, High 10%");
        assertThat(message).contains("Antisolar horizon (away from sun): Low 5%, Mid 45%, High 30%");
    }

    @Test
    @DisplayName("buildUserMessage() omits directional cloud block when not available")
    void buildUserMessage_withoutDirectionalCloud_omitsDirectionalBlock() {
        AtmosphericData data = buildAtmosphericData();
        String message = strategy.buildUserMessage(data);

        assertThat(message).doesNotContain("DIRECTIONAL CLOUD");
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
        return new AtmosphericData(
                "Durham UK", LocalDateTime.of(2026, 6, 21, 20, 47), TargetType.SUNSET,
                10, 50, 30, 25000,
                new BigDecimal("3.50"), 225, new BigDecimal("0.00"),
                62, 3, 1200, new BigDecimal("180.00"),
                new BigDecimal("8.50"), new BigDecimal("2.10"), new BigDecimal("0.120"),
                null, null, null,
                null,
                null, null, null, null, null, null);
    }

    private AtmosphericData buildAtmosphericDataWithDirectionalCloud() {
        return new AtmosphericData(
                "Durham UK", LocalDateTime.of(2026, 6, 21, 20, 47), TargetType.SUNSET,
                10, 50, 30, 25000,
                new BigDecimal("3.50"), 225, new BigDecimal("0.00"),
                62, 3, 1200, new BigDecimal("180.00"),
                new BigDecimal("8.50"), new BigDecimal("2.10"), new BigDecimal("0.120"),
                null, null, null,
                new DirectionalCloudData(65, 20, 10, 5, 45, 30),
                null, null, null, null, null, null);
    }

    private AtmosphericData buildAtmosphericDataWithDust(BigDecimal aod, BigDecimal dust) {
        return new AtmosphericData(
                "Durham UK", LocalDateTime.of(2026, 6, 21, 20, 47), TargetType.SUNSET,
                10, 50, 30, 25000,
                new BigDecimal("3.50"), 225, new BigDecimal("0.00"),
                62, 3, 1200, new BigDecimal("180.00"),
                new BigDecimal("8.50"), dust, aod,
                null, null, null,
                null,
                null, null, null, null, null, null);
    }

    /**
     * Concrete test implementation of the abstract strategy.
     */
    private static class TestEvaluationStrategy extends AbstractEvaluationStrategy {

        TestEvaluationStrategy(AnthropicApiClient anthropicApiClient, AnthropicProperties properties,
                ObjectMapper objectMapper, JobRunService jobRunService) {
            super(anthropicApiClient, properties, objectMapper, jobRunService);
        }

        @Override
        protected String getPromptSuffix() {
            return TEST_SUFFIX;
        }

        @Override
        protected EvaluationModel getEvaluationModel() {
            return EvaluationModel.SONNET;
        }

        @Override
        protected String getModelName() {
            return "claude-sonnet-4-5-20250929";
        }
    }
}
