package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AnthropicProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AbstractEvaluationStrategy}.
 *
 * <p>Uses a concrete {@code TestEvaluationStrategy} inner class to test
 * the shared logic in the abstract base.
 */
@ExtendWith(MockitoExtension.class)
class AbstractEvaluationStrategyTest {

    private static final String TEST_SUFFIX = "Rate 1-5 and explain in 2-3 sentences.";

    @Mock
    private AnthropicClient anthropicClient;

    @Mock
    private MessageService messageService;

    private TestEvaluationStrategy strategy;

    @BeforeEach
    void setUp() {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setModel("claude-sonnet-4-5-20250929");
        strategy = new TestEvaluationStrategy(anthropicClient, properties, new ObjectMapper());
    }

    @Test
    @DisplayName("parseEvaluation() extracts rating and summary from valid JSON")
    void parseEvaluation_validJson_returnsEvaluation() {
        SunsetEvaluation result = strategy.parseEvaluation(
                "{\"rating\": 4, \"summary\": \"Good mid-level cloud above a clear horizon.\"}");

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.summary()).isEqualTo("Good mid-level cloud above a clear horizon.");
    }

    @Test
    @DisplayName("parseEvaluation() throws on invalid JSON")
    void parseEvaluation_invalidJson_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> strategy.parseEvaluation("not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    @DisplayName("parseEvaluation() handles JSON with surrounding whitespace")
    void parseEvaluation_trailingWhitespace_returnsEvaluation() {
        SunsetEvaluation result = strategy.parseEvaluation(
                "  {\"rating\": 2, \"summary\": \"Mostly overcast.\"}  ");

        assertThat(result.rating()).isEqualTo(2);
        assertThat(result.summary()).isEqualTo("Mostly overcast.");
    }

    @Test
    @DisplayName("parseEvaluation() strips markdown code block wrapper")
    void parseEvaluation_codeBlockWrapped_returnsEvaluation() {
        SunsetEvaluation result = strategy.parseEvaluation(
                "```json\n{\"rating\": 3, \"summary\": \"Some cloud.\"}\n```");

        assertThat(result.rating()).isEqualTo(3);
        assertThat(result.summary()).isEqualTo("Some cloud.");
    }

    @Test
    @DisplayName("evaluate() calls Claude and returns parsed evaluation")
    void evaluate_callsClaude_returnsParsedEvaluation() {
        AtmosphericData data = buildAtmosphericData();
        Message response = buildMessage("{\"rating\": 4, \"summary\": \"Promising conditions.\"}");

        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.rating()).isEqualTo(4);
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

    private AtmosphericData buildAtmosphericData() {
        return new AtmosphericData(
                "Durham UK", LocalDateTime.of(2026, 6, 21, 20, 47), TargetType.SUNSET,
                10, 50, 30, 25000,
                new BigDecimal("3.50"), 225, new BigDecimal("0.00"),
                62, 3, 1200, new BigDecimal("180.00"),
                new BigDecimal("8.50"), new BigDecimal("2.10"), new BigDecimal("0.120"));
    }

    /**
     * Concrete test implementation of the abstract strategy.
     */
    private static class TestEvaluationStrategy extends AbstractEvaluationStrategy {

        TestEvaluationStrategy(AnthropicClient client, AnthropicProperties properties,
                ObjectMapper objectMapper) {
            super(client, properties, objectMapper);
        }

        @Override
        protected String getPromptSuffix() {
            return TEST_SUFFIX;
        }
    }
}
