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
 * Unit tests for {@link SonnetEvaluationStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class SonnetEvaluationStrategyTest {

    @Mock
    private AnthropicApiClient anthropicApiClient;

    @Mock
    private JobRunService jobRunService;

    private SonnetEvaluationStrategy strategy;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setModel("claude-sonnet-4-5-20250929");
        objectMapper = new ObjectMapper();
        strategy = new SonnetEvaluationStrategy(anthropicApiClient, properties, objectMapper, jobRunService);
    }

    @Test
    @DisplayName("getEvaluationModel() returns SONNET")
    void getEvaluationModel_returnsSonnet() {
        assertThat(strategy.getEvaluationModel()).isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("getModelName() returns claude-sonnet-4-5-20250929")
    void getModelName_returnsSonnetModelId() {
        assertThat(strategy.getModelName()).isEqualTo("claude-sonnet-4-5-20250929");
    }

    @Test
    @DisplayName("getPromptSuffix() returns shared prompt suffix")
    void getPromptSuffix_returnsCorrectString() {
        assertThat(strategy.getPromptSuffix())
                .contains("Rate 1-5")
                .contains("Fiery Sky Potential")
                .contains("Golden Hour Potential");
    }

    @Test
    @DisplayName("getSystemPrompt() contains rating and dual-score JSON format instruction")
    void getSystemPrompt_containsRatingAndDualScoreInstruction() {
        assertThat(strategy.getSystemPrompt()).contains("rating");
        assertThat(strategy.getSystemPrompt()).contains("fiery_sky");
        assertThat(strategy.getSystemPrompt()).contains("golden_hour");
    }

    @Test
    @DisplayName("evaluate() end-to-end with mocked Claude returns all three scores")
    void evaluate_endToEnd_returnsAllThreeScores() {
        AtmosphericData data = buildAtmosphericData();
        Message response = buildMessage(
                "{\"rating\": 4, \"fiery_sky\": 78, \"golden_hour\": 72,"
                + " \"summary\": \"Excellent high cloud canvas with clear horizon."
                + " Moderate AOD enhances warm tones.\"}");

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(78);
        assertThat(result.goldenHourPotential()).isEqualTo(72);
        assertThat(result.summary()).contains("Excellent high cloud");
    }

    @Test
    @DisplayName("parseEvaluation() extracts rating, scores, and summary from valid JSON")
    void parseEvaluation_validJson_returnsEvaluation() {
        SunsetEvaluation result = strategy.parseEvaluation(
                "{\"rating\": 4, \"fiery_sky\": 75, \"golden_hour\": 80, "
                + "\"summary\": \"Good mid-level cloud above a clear horizon.\"}",
                objectMapper);

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(75);
        assertThat(result.goldenHourPotential()).isEqualTo(80);
        assertThat(result.summary()).isEqualTo("Good mid-level cloud above a clear horizon.");
    }

    @Test
    @DisplayName("parseEvaluation() throws on invalid JSON")
    void parseEvaluation_invalidJson_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> strategy.parseEvaluation("not json", objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    @DisplayName("parseEvaluation() handles JSON with surrounding whitespace")
    void parseEvaluation_trailingWhitespace_returnsEvaluation() {
        SunsetEvaluation result = strategy.parseEvaluation(
                "  {\"rating\": 2, \"fiery_sky\": 20, \"golden_hour\": 35,"
                + " \"summary\": \"Mostly overcast.\"}  ",
                objectMapper);

        assertThat(result.rating()).isEqualTo(2);
        assertThat(result.fierySkyPotential()).isEqualTo(20);
        assertThat(result.goldenHourPotential()).isEqualTo(35);
        assertThat(result.summary()).isEqualTo("Mostly overcast.");
    }

    @Test
    @DisplayName("parseEvaluation() strips markdown code block wrapper")
    void parseEvaluation_codeBlockWrapped_returnsEvaluation() {
        SunsetEvaluation result = strategy.parseEvaluation(
                "```json\n{\"rating\": 3, \"fiery_sky\": 55, \"golden_hour\": 65,"
                + " \"summary\": \"Some cloud.\"}\n```",
                objectMapper);

        assertThat(result.rating()).isEqualTo(3);
        assertThat(result.fierySkyPotential()).isEqualTo(55);
        assertThat(result.goldenHourPotential()).isEqualTo(65);
        assertThat(result.summary()).isEqualTo("Some cloud.");
    }

    @Test
    @DisplayName("parseEvaluation() falls back to regex when summary contains unescaped quotes")
    void parseEvaluation_unescapedQuotesInSummary_returnsEvaluation() {
        SunsetEvaluation result = strategy.parseEvaluation(
                "```json\n{\"rating\": 2, \"fiery_sky\": 25, \"golden_hour\": 40, "
                + "\"summary\": \"A \"blank canvas\" scenario with pale tones.\"}\n```",
                objectMapper);

        assertThat(result.rating()).isEqualTo(2);
        assertThat(result.fierySkyPotential()).isEqualTo(25);
        assertThat(result.goldenHourPotential()).isEqualTo(40);
        assertThat(result.summary()).isEqualTo("A \"blank canvas\" scenario with pale tones.");
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
                null,
                null, null, null, null, null, null);
    }
}
