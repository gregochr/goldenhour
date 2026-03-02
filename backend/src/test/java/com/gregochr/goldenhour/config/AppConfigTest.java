package com.gregochr.goldenhour.config;

import com.anthropic.client.AnthropicClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AppConfig} bean factory methods.
 *
 * <p>Verifies that each bean is constructed with the correct configuration,
 * killing PIT mutations on return values and void method calls.
 */
class AppConfigTest {

    private final AppConfig config = new AppConfig();

    @Test
    @DisplayName("webClient returns non-null WebClient")
    void webClient_returnsNonNull() {
        WebClient result = config.webClient(WebClient.builder());

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("forecastExecutor returns configured ThreadPoolTaskExecutor")
    void forecastExecutor_returnsConfiguredExecutor() {
        Executor executor = config.forecastExecutor(4);

        assertThat(executor).isNotNull().isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getCorePoolSize()).isEqualTo(4);
        assertThat(pool.getMaxPoolSize()).isEqualTo(4);
        assertThat(pool.getThreadNamePrefix()).isEqualTo("forecast-worker-");
        assertThat(pool.isDaemon()).isTrue();
        pool.shutdown();
    }

    @Test
    @DisplayName("forecastExecutor uses provided parallelism value")
    void forecastExecutor_respectsParallelism() {
        Executor executor = config.forecastExecutor(12);

        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getCorePoolSize()).isEqualTo(12);
        assertThat(pool.getMaxPoolSize()).isEqualTo(12);
        pool.shutdown();
    }

    @Test
    @DisplayName("anthropicClient returns non-null client")
    void anthropicClient_returnsNonNull() {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setApiKey("test-key");

        AnthropicClient client = config.anthropicClient(properties);

        assertThat(client).isNotNull();
    }
}
