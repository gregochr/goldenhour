package com.gregochr.goldenhour.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Core Spring application configuration.
 *
 * <p>Provides shared infrastructure beans, enables the caching layer, and enables
 * asynchronous method execution (for {@code @Async} methods such as email sending).
 * Caffeine is the in-memory cache provider (declared as a dependency in {@code pom.xml}
 * and auto-configured by Spring Boot when on the classpath).
 */
@Configuration
@EnableCaching
@EnableAsync
public class AppConfig {

    /**
     * Shared {@link WebClient} instance for outbound HTTP calls.
     *
     * <p>Used by {@code OpenMeteoService} (Open-Meteo APIs) and
     * {@code PushoverNotificationService} (Pushover REST API).
     *
     * @param builder Spring Boot's auto-configured builder
     * @return a WebClient built from the default builder
     */
    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder.build();
    }

    /**
     * Executor used to run forecast evaluations in parallel.
     *
     * <p>Pool size is configurable via {@code forecast.parallelism} (default: 8).
     * Spring manages lifecycle — the pool is shut down cleanly on application stop.
     *
     * @param parallelism maximum number of concurrent forecast calls
     * @return a configured thread pool executor
     */
    @Bean
    public Executor forecastExecutor(@Value("${forecast.parallelism:8}") int parallelism) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(parallelism);
        executor.setMaxPoolSize(parallelism);
        executor.setThreadNamePrefix("forecast-worker-");
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }

    /**
     * Anthropic client for Claude API calls with connection pooling.
     *
     * <p>Configured from {@link AnthropicProperties}. Used exclusively by
     * {@code EvaluationService} — no other class accesses the Anthropic SDK directly.
     * Connection pool sized to support parallel evaluation runs (5 idle connections,
     * 2-minute keep-alive).
     *
     * @param properties Anthropic API configuration
     * @return a configured {@link AnthropicClient}
     */
    @Bean
    public AnthropicClient anthropicClient(AnthropicProperties properties) {
        return AnthropicOkHttpClient.builder()
                .apiKey(properties.getApiKey())
                .maxIdleConnections(5)
                .keepAliveDuration(Duration.ofMinutes(2))
                .build();
    }
}
