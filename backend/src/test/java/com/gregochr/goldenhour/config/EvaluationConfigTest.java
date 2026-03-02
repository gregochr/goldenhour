package com.gregochr.goldenhour.config;

import com.anthropic.client.AnthropicClient;
import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.HaikuEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.NoOpEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.OpusEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.SonnetEvaluationStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link EvaluationConfig}.
 *
 * <p>Verifies that each factory method returns the correct {@link EvaluationStrategy}
 * implementation without loading a Spring context.
 */
class EvaluationConfigTest {

    private final EvaluationConfig config = new EvaluationConfig();
    private final AnthropicClient client = mock(AnthropicClient.class);
    private final AnthropicProperties properties = new AnthropicProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JobRunService jobRunService = mock(JobRunService.class);

    @Test
    @DisplayName("haikuEvaluationStrategy() returns a HaikuEvaluationStrategy")
    void haikuEvaluationStrategy_returnsHaikuEvaluationStrategy() {
        EvaluationStrategy strategy = config.haikuEvaluationStrategy(client, properties, objectMapper, jobRunService);

        assertThat(strategy).isInstanceOf(HaikuEvaluationStrategy.class);
    }

    @Test
    @DisplayName("sonnetEvaluationStrategy() returns a SonnetEvaluationStrategy")
    void sonnetEvaluationStrategy_returnsSonnetEvaluationStrategy() {
        EvaluationStrategy strategy = config.sonnetEvaluationStrategy(client, properties, objectMapper, jobRunService);

        assertThat(strategy).isInstanceOf(SonnetEvaluationStrategy.class);
    }

    @Test
    @DisplayName("opusEvaluationStrategy() returns an OpusEvaluationStrategy")
    void opusEvaluationStrategy_returnsOpusEvaluationStrategy() {
        EvaluationStrategy strategy = config.opusEvaluationStrategy(client, properties, objectMapper, jobRunService);

        assertThat(strategy).isInstanceOf(OpusEvaluationStrategy.class);
    }

    @Test
    @DisplayName("noOpEvaluationStrategy() returns a NoOpEvaluationStrategy")
    void noOpEvaluationStrategy_returnsNoOpEvaluationStrategy() {
        EvaluationStrategy strategy = config.noOpEvaluationStrategy();

        assertThat(strategy).isInstanceOf(NoOpEvaluationStrategy.class);
    }
}
