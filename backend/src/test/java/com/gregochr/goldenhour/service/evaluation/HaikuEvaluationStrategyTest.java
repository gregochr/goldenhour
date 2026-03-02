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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.gregochr.goldenhour.service.JobRunService;
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
    private AnthropicApiClient anthropicApiClient;

    @Mock
    private JobRunService jobRunService;

    private HaikuEvaluationStrategy strategy;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setModel("claude-haiku-4-5-20251001");
        objectMapper = new ObjectMapper();
        strategy = new HaikuEvaluationStrategy(anthropicApiClient, properties, objectMapper, jobRunService);
    }

    @Test
    @DisplayName("getEvaluationModel() returns HAIKU")
    void getEvaluationModel_returnsHaiku() {
        assertThat(strategy.getEvaluationModel()).isEqualTo(EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("getModelName() returns claude-haiku-4-5")
    void getModelName_returnsHaikuModelId() {
        assertThat(strategy.getModelName()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    @DisplayName("getPromptSuffix() returns shared prompt suffix with rating instruction")
    void getPromptSuffix_returnsCorrectString() {
        assertThat(strategy.getPromptSuffix())
                .contains("Rate 1-5")
                .contains("Fiery Sky Potential")
                .contains("Golden Hour Potential");
    }

    @Test
    @DisplayName("getSystemPrompt() contains rating scale instruction")
    void getSystemPrompt_containsRatingScaleInstruction() {
        assertThat(strategy.getSystemPrompt()).contains("1\u20135");
        assertThat(strategy.getSystemPrompt()).contains("rating");
    }

    @Test
    @DisplayName("evaluate() end-to-end with mocked Claude returns all three Haiku scores")
    void evaluate_endToEnd_returnsHaikuRatingEvaluation() {
        AtmosphericData data = buildAtmosphericData();
        Message response = buildMessage(
                "{\"rating\": 3, \"fiery_sky\": 45, \"golden_hour\": 60,"
                + " \"summary\": \"Some cloud cover limits potential.\"}");

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.rating()).isEqualTo(3);
        assertThat(result.fierySkyPotential()).isEqualTo(45);
        assertThat(result.goldenHourPotential()).isEqualTo(60);
        assertThat(result.summary()).isEqualTo("Some cloud cover limits potential.");
    }

    @Test
    @DisplayName("parseEvaluation() extracts rating, dual scores, and summary from valid JSON")
    void parseEvaluation_validJson_returnsEvaluation() {
        SunsetEvaluation result = strategy.parseEvaluation(
                "{\"rating\": 4, \"fiery_sky\": 65, \"golden_hour\": 75,"
                + " \"summary\": \"Good mid-level cloud above a clear horizon.\"}",
                objectMapper);

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(65);
        assertThat(result.goldenHourPotential()).isEqualTo(75);
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
                "```json\n{\"rating\": 5, \"fiery_sky\": 90, \"golden_hour\": 85,"
                + " \"summary\": \"Exceptional conditions.\"}\n```",
                objectMapper);

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.fierySkyPotential()).isEqualTo(90);
        assertThat(result.goldenHourPotential()).isEqualTo(85);
        assertThat(result.summary()).isEqualTo("Exceptional conditions.");
    }

    @Test
    @DisplayName("parseEvaluation() falls back to regex when summary contains unescaped quotes")
    void parseEvaluation_unescapedQuotesInSummary_returnsEvaluation() {
        SunsetEvaluation result = strategy.parseEvaluation(
                "{\"rating\": 2, \"fiery_sky\": 20, \"golden_hour\": 25,"
                + " \"summary\": \"A \"dull\" outlook with heavy overcast.\"}",
                objectMapper);

        assertThat(result.rating()).isEqualTo(2);
        assertThat(result.fierySkyPotential()).isEqualTo(20);
        assertThat(result.goldenHourPotential()).isEqualTo(25);
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
