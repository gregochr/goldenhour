package com.gregochr.goldenhour.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.gregochr.goldenhour.client.OpenMeteoAirQualityApi;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.SolarCalculator;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Core Spring application configuration.
 *
 * <p>Provides shared infrastructure beans, enables the caching layer, enables
 * asynchronous method execution (for {@code @Async} methods such as email sending),
 * and enables resilient method processing ({@code @Retryable}, {@code @ConcurrencyLimit}).
 */
@Configuration
@EnableCaching
@EnableAsync
@EnableResilientMethods
public class AppConfig {

    /**
     * Shared {@link RestClient} instance for outbound HTTP calls.
     *
     * <p>Used by {@code TideService} (WorldTides API) and
     * {@code PushoverNotificationService} (Pushover REST API).
     * Open-Meteo calls use dedicated {@code @HttpExchange} proxies instead.
     *
     * @return a RestClient instance
     */
    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }

    /**
     * Executor used to run forecast evaluations in parallel.
     *
     * <p>Uses virtual threads — each forecast task gets its own lightweight thread
     * (~1 KB each vs ~1 MB for platform threads). No pool sizing needed;
     * concurrency is controlled by {@code @ConcurrencyLimit} on the service methods.
     *
     * @return a virtual-thread-per-task executor
     */
    @Bean
    public Executor forecastExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Anthropic client for Claude API calls with connection pooling.
     *
     * <p>Configured from {@link AnthropicProperties}. Used exclusively by
     * {@code AnthropicApiClient} — no other class accesses the Anthropic SDK directly.
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

    /**
     * Provides a {@link SolarCalculator} for solar altitude and twilight calculations.
     *
     * @return a stateless {@link SolarCalculator} instance
     */
    @Bean
    public SolarCalculator solarCalculator() {
        return new SolarCalculator();
    }

    /**
     * Provides a {@link LunarCalculator} for aurora moon-penalty calculations.
     *
     * @return a stateless {@link LunarCalculator} instance
     */
    @Bean
    public LunarCalculator lunarCalculator() {
        return new LunarCalculator();
    }

    /**
     * Proxy for the Open-Meteo Forecast API backed by {@link RestClient}.
     *
     * @return a typed proxy implementing {@link OpenMeteoForecastApi}
     */
    @Bean
    OpenMeteoForecastApi openMeteoForecastApi() {
        RestClient client = RestClient.builder()
                .baseUrl("https://api.open-meteo.com")
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
                .build().createClient(OpenMeteoForecastApi.class);
    }

    /**
     * Proxy for the Open-Meteo Air Quality API backed by {@link RestClient}.
     *
     * @return a typed proxy implementing {@link OpenMeteoAirQualityApi}
     */
    @Bean
    OpenMeteoAirQualityApi openMeteoAirQualityApi() {
        RestClient client = RestClient.builder()
                .baseUrl("https://air-quality-api.open-meteo.com")
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
                .build().createClient(OpenMeteoAirQualityApi.class);
    }
}
