package com.gregochr.goldenhour.config;

import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import com.gregochr.goldenhour.service.evaluation.ClaudeEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.CoastalPromptBuilder;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.NoOpEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link EvaluationConfig}.
 *
 * <p>Verifies that the {@code evaluationStrategies()} bean returns the correct
 * {@link EvaluationStrategy} implementations without loading a Spring context.
 */
class EvaluationConfigTest {

    private final EvaluationConfig config = new EvaluationConfig();
    private final AnthropicApiClient anthropicApiClient = mock(AnthropicApiClient.class);
    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final CoastalPromptBuilder coastalPromptBuilder = new CoastalPromptBuilder();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("promptBuilder() returns a PromptBuilder")
    void promptBuilder_returnsPromptBuilder() {
        assertThat(config.promptBuilder()).isInstanceOf(PromptBuilder.class);
    }

    @Test
    @DisplayName("coastalPromptBuilder() returns a CoastalPromptBuilder")
    void coastalPromptBuilder_returnsCoastalPromptBuilder() {
        assertThat(config.coastalPromptBuilder()).isInstanceOf(CoastalPromptBuilder.class);
    }

    @Test
    @DisplayName("evaluationStrategies() returns map with all four EvaluationModel keys")
    void evaluationStrategies_containsAllFourKeys() {
        Map<EvaluationModel, EvaluationStrategy> strategies =
                config.evaluationStrategies(anthropicApiClient, promptBuilder, coastalPromptBuilder, objectMapper);

        assertThat(strategies).containsOnlyKeys(
                EvaluationModel.HAIKU, EvaluationModel.SONNET,
                EvaluationModel.OPUS, EvaluationModel.WILDLIFE);
    }

    @Test
    @DisplayName("evaluationStrategies() maps HAIKU to ClaudeEvaluationStrategy")
    void evaluationStrategies_haikuIsClaudeStrategy() {
        Map<EvaluationModel, EvaluationStrategy> strategies =
                config.evaluationStrategies(anthropicApiClient, promptBuilder, coastalPromptBuilder, objectMapper);

        assertThat(strategies.get(EvaluationModel.HAIKU)).isInstanceOf(ClaudeEvaluationStrategy.class);
    }

    @Test
    @DisplayName("evaluationStrategies() maps SONNET to ClaudeEvaluationStrategy")
    void evaluationStrategies_sonnetIsClaudeStrategy() {
        Map<EvaluationModel, EvaluationStrategy> strategies =
                config.evaluationStrategies(anthropicApiClient, promptBuilder, coastalPromptBuilder, objectMapper);

        assertThat(strategies.get(EvaluationModel.SONNET)).isInstanceOf(ClaudeEvaluationStrategy.class);
    }

    @Test
    @DisplayName("evaluationStrategies() maps OPUS to ClaudeEvaluationStrategy")
    void evaluationStrategies_opusIsClaudeStrategy() {
        Map<EvaluationModel, EvaluationStrategy> strategies =
                config.evaluationStrategies(anthropicApiClient, promptBuilder, coastalPromptBuilder, objectMapper);

        assertThat(strategies.get(EvaluationModel.OPUS)).isInstanceOf(ClaudeEvaluationStrategy.class);
    }

    @Test
    @DisplayName("evaluationStrategies() maps WILDLIFE to NoOpEvaluationStrategy")
    void evaluationStrategies_wildlifeIsNoOpStrategy() {
        Map<EvaluationModel, EvaluationStrategy> strategies =
                config.evaluationStrategies(anthropicApiClient, promptBuilder, coastalPromptBuilder, objectMapper);

        assertThat(strategies.get(EvaluationModel.WILDLIFE)).isInstanceOf(NoOpEvaluationStrategy.class);
    }
}
