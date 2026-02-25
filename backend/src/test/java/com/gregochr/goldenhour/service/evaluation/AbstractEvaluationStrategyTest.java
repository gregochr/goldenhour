package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AnthropicProperties;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.JobRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AbstractEvaluationStrategy}.
 *
 * <p>Uses a concrete {@code TestEvaluationStrategy} inner class to test
 * the shared logic in the abstract base (user message construction and Claude API call).
 * Parse-specific tests live in {@link SonnetEvaluationStrategyTest} and
 * {@link HaikuEvaluationStrategyTest}.
 */
@ExtendWith(MockitoExtension.class)
class AbstractEvaluationStrategyTest {

    private static final String TEST_SUFFIX = "Score both dimensions and explain in 2-3 sentences.";

    @Mock
    private AnthropicClient anthropicClient;

    @Mock
    private MessageService messageService;

    @Mock
    private JobRunService jobRunService;

    private TestEvaluationStrategy strategy;

    @BeforeEach
    void setUp() {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setModel("claude-sonnet-4-5-20250929");
        strategy = new TestEvaluationStrategy(anthropicClient, properties, new ObjectMapper(), jobRunService);
    }

    @Test
    @DisplayName("evaluate() calls Claude and returns parsed evaluation")
    void evaluate_callsClaude_returnsParsedEvaluation() {
        AtmosphericData data = buildAtmosphericData();
        Message response = buildMessage(
                "{\"fiery_sky\": 70, \"golden_hour\": 75, \"summary\": \"Promising conditions.\"}");

        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.rating()).isNull();
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
                .stopReason(Message.StopReason.END_TURN)
                .stopSequence(Optional.empty())
                .usage(buildUsage())
                .build();

        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(response);

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
    }

    private Message buildMessage(String text) {
        TextBlock textBlock = TextBlock.builder()
                .text(text)
                .citations(List.of())
                .build();
        ContentBlock contentBlock = ContentBlock.ofText(textBlock);
        return Message.builder()
                .id("msg_test")
                .model(Model.of("claude-sonnet-4-5-20250929"))
                .content(List.of(contentBlock))
                .stopReason(Message.StopReason.END_TURN)
                .stopSequence(Optional.empty())
                .usage(buildUsage())
                .build();
    }

    private Usage buildUsage() {
        return Usage.builder()
                .inputTokens(10)
                .outputTokens(20)
                .cacheCreationInputTokens(0L)
                .cacheReadInputTokens(0L)
                .build();
    }

    @Test
    @DisplayName("evaluate() retries on Anthropic 529 error and succeeds on 2nd attempt")
    void evaluate_retries529_succeeds() {
        AtmosphericData data = buildAtmosphericData();
        Message successResponse = buildMessage(
                "{\"fiery_sky\": 70, \"golden_hour\": 75, \"summary\": \"Promising conditions.\"}");

        // Create a mock 529 exception (Anthropic overloaded)
        HttpServerErrorException overloadedException = mock(HttpServerErrorException.class);
        HttpStatus status529 = mock(HttpStatus.class);
        when(status529.value()).thenReturn(529);
        when(overloadedException.getStatusCode()).thenReturn(status529);

        when(anthropicClient.messages()).thenReturn(messageService);
        // First call throws 529, second call succeeds
        when(messageService.create(any(MessageCreateParams.class)))
                .thenThrow(overloadedException)
                .thenReturn(successResponse);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.fierySkyPotential()).isEqualTo(70);
        assertThat(result.goldenHourPotential()).isEqualTo(75);
    }

    @Test
    @DisplayName("evaluate() logs API call success with 200 status when jobRun is provided")
    void evaluate_logsApiCallSuccess() {
        AtmosphericData data = buildAtmosphericData();
        JobRunEntity jobRun = JobRunEntity.builder().id(1L).build();
        Message response = buildMessage(
                "{\"fiery_sky\": 70, \"golden_hour\": 75, \"summary\": \"Good conditions.\"}");

        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(response);

        strategy.evaluate(data, jobRun);

        ArgumentCaptor<Long> jobRunIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<ServiceName> serviceCaptor = ArgumentCaptor.forClass(ServiceName.class);
        ArgumentCaptor<String> methodCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> succeededCaptor = ArgumentCaptor.forClass(Boolean.class);

        verify(jobRunService).logApiCall(
                jobRunIdCaptor.capture(),
                serviceCaptor.capture(),
                methodCaptor.capture(),
                urlCaptor.capture(),
                any(),
                anyLong(),
                eq(200),
                any(),
                eq(true),
                any(),
                any());

        assertThat(jobRunIdCaptor.getValue()).isEqualTo(1L);
        assertThat(serviceCaptor.getValue()).isEqualTo(ServiceName.ANTHROPIC);
        assertThat(methodCaptor.getValue()).isEqualTo("POST");
        assertThat(urlCaptor.getValue()).contains("api.anthropic.com");
    }

    @Test
    @DisplayName("evaluate() logs API call failure with error message when jobRun is provided")
    void evaluate_logsApiCallFailure() {
        AtmosphericData data = buildAtmosphericData();
        JobRunEntity jobRun = JobRunEntity.builder().id(1L).build();
        String errorMsg = "API rate limit exceeded";

        // Create a mock 429 exception
        HttpServerErrorException rateLimitException = mock(HttpServerErrorException.class);
        HttpStatus status429 = mock(HttpStatus.class);
        when(status429.value()).thenReturn(429);
        when(rateLimitException.getStatusCode()).thenReturn(status429);
        when(rateLimitException.getMessage()).thenReturn(errorMsg);

        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenThrow(rateLimitException);

        try {
            strategy.evaluate(data, jobRun);
        } catch (Exception ignored) {
            // Expected to throw
        }

        ArgumentCaptor<Boolean> succeededCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(jobRunService).logApiCall(
                eq(1L),
                eq(ServiceName.ANTHROPIC),
                eq("POST"),
                contains("api.anthropic.com"),
                any(),
                anyLong(),
                eq(429),
                any(),
                succeededCaptor.capture(),
                any(),
                any());

        assertThat(succeededCaptor.getValue()).isFalse();
    }

    private AtmosphericData buildAtmosphericData() {
        return new AtmosphericData(
                "Durham UK", LocalDateTime.of(2026, 6, 21, 20, 47), TargetType.SUNSET,
                10, 50, 30, 25000,
                new BigDecimal("3.50"), 225, new BigDecimal("0.00"),
                62, 3, 1200, new BigDecimal("180.00"),
                new BigDecimal("8.50"), new BigDecimal("2.10"), new BigDecimal("0.120"),
                null, null, null,
                null, null, null, null, null, null);
    }

    /**
     * Concrete test implementation of the abstract strategy.
     * Uses Sonnet-style parsing so evaluate() tests can assert on score fields.
     */
    private static class TestEvaluationStrategy extends AbstractEvaluationStrategy {

        private static final String TEST_SYSTEM_PROMPT = "Test system prompt.";

        TestEvaluationStrategy(AnthropicClient client, AnthropicProperties properties,
                ObjectMapper objectMapper, JobRunService jobRunService) {
            super(client, properties, objectMapper, jobRunService);
        }

        @Override
        protected String getSystemPrompt() {
            return TEST_SYSTEM_PROMPT;
        }

        @Override
        protected String getPromptSuffix() {
            return TEST_SUFFIX;
        }

        @Override
        protected com.gregochr.goldenhour.entity.EvaluationModel getEvaluationModel() {
            return com.gregochr.goldenhour.entity.EvaluationModel.SONNET;
        }

        @Override
        protected SunsetEvaluation parseEvaluation(String text, ObjectMapper objectMapper) {
            // Delegate to Sonnet-style parsing for test purposes
            try {
                String cleaned = text.trim()
                        .replaceAll("(?s)^```(?:json)?\\s*", "")
                        .replaceAll("(?s)\\s*```$", "")
                        .trim();
                JsonNode node = objectMapper.readTree(cleaned);
                int fierySky = node.get("fiery_sky").asInt();
                int goldenHour = node.get("golden_hour").asInt();
                String summary = node.get("summary").asText();
                return new SunsetEvaluation(null, fierySky, goldenHour, summary);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to parse test response: " + text, e);
            }
        }
    }
}
