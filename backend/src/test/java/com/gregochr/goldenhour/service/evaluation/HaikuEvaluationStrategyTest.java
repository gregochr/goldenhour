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

    @BeforeEach
    void setUp() {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setModel("claude-haiku-4-5-20251001");
        strategy = new HaikuEvaluationStrategy(anthropicClient, properties, new ObjectMapper());
    }

    @Test
    @DisplayName("getPromptSuffix() returns concise 1-2 sentence instruction")
    void getPromptSuffix_returnsCorrectString() {
        assertThat(strategy.getPromptSuffix())
                .isEqualTo("Rate 1-5 and explain in 1-2 sentences.");
    }

    @Test
    @DisplayName("evaluate() end-to-end with mocked Claude returns parsed evaluation")
    void evaluate_endToEnd_returnsParsedEvaluation() {
        AtmosphericData data = buildAtmosphericData();
        Message response = buildMessage(
                "{\"rating\": 3, \"summary\": \"Some cloud cover limits potential.\"}");

        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.rating()).isEqualTo(3);
        assertThat(result.summary()).isEqualTo("Some cloud cover limits potential.");
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
