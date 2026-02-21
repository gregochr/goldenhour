package com.gregochr.goldenhour.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Core Spring application configuration.
 *
 * <p>Provides shared infrastructure beans and enables the caching layer.
 * Caffeine is the in-memory cache provider (declared as a dependency in {@code pom.xml}
 * and auto-configured by Spring Boot when on the classpath).
 */
@Configuration
@EnableCaching
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
     * Anthropic client for Claude API calls.
     *
     * <p>Configured from {@link AnthropicProperties}. Used exclusively by
     * {@code EvaluationService} — no other class accesses the Anthropic SDK directly.
     *
     * @param properties Anthropic API configuration
     * @return a configured {@link AnthropicClient}
     */
    @Bean
    public AnthropicClient anthropicClient(AnthropicProperties properties) {
        return AnthropicOkHttpClient.builder()
                .apiKey(properties.getApiKey())
                .build();
    }
}
