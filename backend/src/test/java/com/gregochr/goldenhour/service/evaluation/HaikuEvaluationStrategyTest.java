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
 * Unit tests for {@link HaikuEvaluationStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class HaikuEvaluationStrategyTest {

    @Mock
    private AnthropicClient anthropicClient;

    @Mock
    private MessageService messageService;

    private HaikuEvaluationStrategy strategy;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setModel("claude-haiku-4-5-20251001");
        objectMapper = new ObjectMapper();
        strategy = new HaikuEvaluationStrategy(anthropicClient, properties, objectMapper);
    }

    @Test
    @DisplayName("getPromptSuffix() returns 1-5 rate instruction")
    void getPromptSuffix_returnsCorrectString() {
        assertThat(strategy.getPromptSuffix())
                .isEqualTo("Rate 1-5 and explain in 1-2 sentences.");
    }

    @Test
    @DisplayName("getSystemPrompt() contains rating scale instruction")
    void getSystemPrompt_containsRatingScaleInstruction() {
        assertThat(strategy.getSystemPrompt()).contains("1-5");
        assertThat(strategy.getSystemPrompt()).contains("rating");
    }

    @Test
    @DisplayName("evaluate() end-to-end with mocked Claude returns Haiku rating evaluation")
    void evaluate_endToEnd_returnsHaikuRatingEvaluation() {
        AtmosphericData data = buildAtmosphericData();
        Message response = buildMessage(
                "{\"rating\": 3, \"summary\": \"Some cloud cover limits potential.\"}");

        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.rating()).isEqualTo(3);
        assertThat(result.fierySkyPotential()).isNull();
        assertThat(result.goldenHourPotential()).isNull();
        assertThat(result.summary()).isEqualTo("Some cloud cover limits potential.");
    }

    @Test
    @DisplayName("parseEvaluation() extracts rating and summary from valid JSON")
    void parseEvaluation_validJson_returnsEvaluation() {
        SunsetEvaluation result = strategy.parseEvaluation(
                "{\"rating\": 4, \"summary\": \"Good mid-level cloud above a clear horizon.\"}",
                objectMapper);

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isNull();
        assertThat(result.goldenHourPotential()).isNull();
        assertThat(result.summary()).isEqualTo("Good mid-level cloud above a clear horizon.");
    }

    @Test
    @DisplayName("parseEvaluation() throws on invalid JSON with no rating field")
    void parseEvaluation_invalidJson_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> strategy.parseEvaluation("not json", objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    @DisplayName("parseEvaluation() strips markdown code block wrapper")
    void parseEvaluation_codeBlockWrapped_returnsEvaluation() {
        SunsetEvaluation result = strategy.parseEvaluation(
                "```json\n{\"rating\": 5, \"summary\": \"Exceptional conditions.\"}\n```",
                objectMapper);

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.summary()).isEqualTo("Exceptional conditions.");
    }

    @Test
    @DisplayName("parseEvaluation() falls back to regex when summary contains unescaped quotes")
    void parseEvaluation_unescapedQuotesInSummary_returnsEvaluation() {
        SunsetEvaluation result = strategy.parseEvaluation(
                "{\"rating\": 2, \"summary\": \"A \"dull\" outlook with heavy overcast.\"}",
                objectMapper);

        assertThat(result.rating()).isEqualTo(2);
        assertThat(result.summary()).isEqualTo("A \"dull\" outlook with heavy overcast.");
    }

    private Message buildMessage(String text) {
        TextBlock textBlock = TextBlock.builder()
                .text(text)
                .citations(List.of())
                .build();
        ContentBlock contentBlock = ContentBlock.ofText(textBlock);
        return Message.builder()
                .id("msg_test")
                .model(Model.of("claude-haiku-4-5-20251001"))
                .content(List.of(contentBlock))
                .stopReason(Message.StopReason.END_TURN)
                .stopSequence(Optional.empty())
                .usage(Usage.builder()
                        .inputTokens(10)
                        .outputTokens(20)
                        .cacheCreationInputTokens(0L)
                        .cacheReadInputTokens(0L)
                        .build())
                .build();
    }

    private AtmosphericData buildAtmosphericData() {
        return new AtmosphericData(
                "Durham UK", LocalDateTime.of(2026, 6, 21, 20, 47), TargetType.SUNSET,
                10, 50, 30, 25000,
                new BigDecimal("3.50"), 225, new BigDecimal("0.00"),
                62, 3, 1200, new BigDecimal("180.00"),
                new BigDecimal("8.50"), new BigDecimal("2.10"), new BigDecimal("0.120"),
                null, null, null, null, null, null);
    }
}
