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
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.JobRunService;
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
 * Unit tests for {@link OpusEvaluationStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class OpusEvaluationStrategyTest {

    @Mock
    private AnthropicClient anthropicClient;

    @Mock
    private MessageService messageService;

    @Mock
    private JobRunService jobRunService;

    private OpusEvaluationStrategy strategy;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setModel("claude-opus-4-6");
        objectMapper = new ObjectMapper();
        strategy = new OpusEvaluationStrategy(anthropicClient, properties, objectMapper, jobRunService);
    }

    @Test
    @DisplayName("getEvaluationModel() returns OPUS")
    void getEvaluationModel_returnsOpus() {
        assertThat(strategy.getEvaluationModel()).isEqualTo(EvaluationModel.OPUS);
    }

    @Test
    @DisplayName("getModelName() returns claude-opus-4-6")
    void getModelName_returnsOpusModelId() {
        assertThat(strategy.getModelName()).isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("evaluate() end-to-end with mocked Claude returns dual-score evaluation")
    void evaluate_endToEnd_returnsDualScoreEvaluation() {
        AtmosphericData data = buildAtmosphericData();
        Message response = buildMessage(
                "{\"fiery_sky\": 82, \"golden_hour\": 76, \"summary\": \"Outstanding cloud canvas "
                + "with clear low horizon. Dust aerosols amplify warm red tones.\"}");

        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.rating()).isNull();
        assertThat(result.fierySkyPotential()).isEqualTo(82);
        assertThat(result.goldenHourPotential()).isEqualTo(76);
        assertThat(result.summary()).contains("Outstanding cloud canvas");
    }

    private Message buildMessage(String text) {
        TextBlock textBlock = TextBlock.builder()
                .text(text)
                .citations(List.of())
                .build();
        ContentBlock contentBlock = ContentBlock.ofText(textBlock);
        return Message.builder()
                .id("msg_test_opus")
                .model(Model.of("claude-opus-4-6"))
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
                null, null, null,
                null, null, null, null, null, null);
    }
}
