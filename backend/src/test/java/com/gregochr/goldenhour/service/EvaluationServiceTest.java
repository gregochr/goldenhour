package com.gregochr.goldenhour.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EvaluationService}.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationServiceTest {

    @Mock
    private AnthropicClient anthropicClient;

    @Mock
    private MessageService messageService;

    @Mock
    private Message message;

    @Mock
    private ContentBlock contentBlock;

    @Mock
    private TextBlock textBlock;

    private EvaluationService evaluationService;

    @BeforeEach
    void setUp() {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setModel("claude-haiku-4-5");
        evaluationService = new EvaluationService(anthropicClient, properties, new ObjectMapper());
    }

    @Test
    @DisplayName("parseEvaluation() extracts rating and summary from valid JSON")
    void parseEvaluation_validJson_returnsEvaluation() {
        SunsetEvaluation result = evaluationService.parseEvaluation(
                "{\"rating\": 4, \"summary\": \"Good mid-level cloud above a clear horizon.\"}");

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.summary()).isEqualTo("Good mid-level cloud above a clear horizon.");
    }

    @Test
    @DisplayName("parseEvaluation() throws on invalid JSON")
    void parseEvaluation_invalidJson_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> evaluationService.parseEvaluation("not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    @DisplayName("parseEvaluation() handles JSON with surrounding whitespace")
    void parseEvaluation_trailingWhitespace_returnsEvaluation() {
        SunsetEvaluation result = evaluationService.parseEvaluation(
                "  {\"rating\": 2, \"summary\": \"Mostly overcast.\"}  ");

        assertThat(result.rating()).isEqualTo(2);
        assertThat(result.summary()).isEqualTo("Mostly overcast.");
    }

    @Test
    @DisplayName("evaluate() calls Claude and returns parsed evaluation")
    void evaluate_callsClaude_returnsParsedEvaluation() {
        AtmosphericData data = buildAtmosphericData(LocalDateTime.of(2026, 6, 21, 20, 47),
                TargetType.SUNSET);

        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(message);
        when(message.content()).thenReturn(List.of(contentBlock));
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(textBlock.text()).thenReturn("{\"rating\": 4, \"summary\": \"Promising conditions.\"}");

        SunsetEvaluation result = evaluationService.evaluate(data);

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.summary()).isEqualTo("Promising conditions.");
    }

    @Test
    @DisplayName("evaluate() throws when Claude returns no text content")
    void evaluate_noTextContent_throwsIllegalStateException() {
        AtmosphericData data = buildAtmosphericData(LocalDateTime.of(2026, 6, 21, 20, 47),
                TargetType.SUNSET);

        ContentBlock nonTextBlock = org.mockito.Mockito.mock(ContentBlock.class);
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(message);
        when(message.content()).thenReturn(List.of(nonTextBlock));
        when(nonTextBlock.isText()).thenReturn(false);

        assertThatThrownBy(() -> evaluationService.evaluate(data))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no text content");
    }

    private AtmosphericData buildAtmosphericData(LocalDateTime eventTime, TargetType targetType) {
        return new AtmosphericData(
                "Durham UK", eventTime, targetType,
                10, 50, 30, 25000,
                new BigDecimal("3.50"), 225, new BigDecimal("0.00"),
                62, 3, 1200, new BigDecimal("180.00"),
                new BigDecimal("8.50"), new BigDecimal("2.10"), new BigDecimal("0.120"));
    }
}
