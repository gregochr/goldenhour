package com.gregochr.goldenhour.config;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.HaikuEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.SonnetEvaluationStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link EvaluationConfig}.
 *
 * <p>Verifies that each profile wires the correct {@link EvaluationStrategy} implementation.
 * The {@code @Profile} annotation is a Spring concern and has no effect on direct bean-method
 * invocation, so these tests exercise the factory logic without loading a Spring context.
 */
class EvaluationConfigTest {

    private final EvaluationConfig config = new EvaluationConfig();
    private final AnthropicClient client = mock(AnthropicClient.class);
    private final AnthropicProperties properties = new AnthropicProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("liteStrategy() returns a HaikuEvaluationStrategy")
    void liteStrategy_returnsHaikuEvaluationStrategy() {
        EvaluationStrategy strategy = config.liteStrategy(client, properties, objectMapper);

        assertThat(strategy).isInstanceOf(HaikuEvaluationStrategy.class);
    }

    @Test
    @DisplayName("proStrategy() returns a SonnetEvaluationStrategy")
    void proStrategy_returnsSonnetEvaluationStrategy() {
        EvaluationStrategy strategy = config.proStrategy(client, properties, objectMapper);

        assertThat(strategy).isInstanceOf(SonnetEvaluationStrategy.class);
    }
}
