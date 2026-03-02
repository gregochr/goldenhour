package com.gregochr.goldenhour.service.evaluation;

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
    private AnthropicApiClient anthropicApiClient;

    @Mock
    private JobRunService jobRunService;

    private OpusEvaluationStrategy strategy;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setModel("claude-opus-4-6");
        objectMapper = new ObjectMapper();
        strategy = new OpusEvaluationStrategy(anthropicApiClient, properties, objectMapper, jobRunService);
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
    @DisplayName("evaluate() end-to-end with mocked Claude returns all three scores")
    void evaluate_endToEnd_returnsAllThreeScores() {
        AtmosphericData data = buildAtmosphericData();
        Message response = buildMessage(
                "{\"rating\": 4, \"fiery_sky\": 82, \"golden_hour\": 76,"
                + " \"summary\": \"Outstanding cloud canvas with clear low horizon."
                + " Dust aerosols amplify warm red tones.\"}");

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.rating()).isEqualTo(4);
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
                .stopReason(StopReason.END_TURN)
                .stopSequence(Optional.empty())
                .usage(Usage.builder()
                        .inputTokens(10)
                        .outputTokens(20)
                        .cacheCreation(CacheCreation.builder()
                                .ephemeral5mInputTokens(0)
                                .ephemeral1hInputTokens(0)
                                .build())
                        .cacheCreationInputTokens(0L)
                        .cacheReadInputTokens(0L)
                        .inferenceGeo("us")
                        .serverToolUse(ServerToolUsage.builder()
                                .webSearchRequests(0)
                                .webFetchRequests(0)
                                .build())
                        .serviceTier(Usage.ServiceTier.of("standard"))
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
