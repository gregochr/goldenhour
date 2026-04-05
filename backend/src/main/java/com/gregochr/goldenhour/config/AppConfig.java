package com.gregochr.goldenhour.config;

import com.anthropic.backends.AnthropicBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientImpl;
import com.anthropic.core.ClientOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.client.OpenMeteoAirQualityApi;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.SolarCalculator;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import okhttp3.ConnectionPool;
import okhttp3.Protocol;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Core Spring application configuration.
 *
 * <p>Provides shared infrastructure beans, enables the caching layer, enables
 * asynchronous method execution (for {@code @Async} methods such as email sending),
 * and enables resilient method processing via Resilience4j annotations.
 */
@Configuration
@EnableCaching
@EnableAsync
public class AppConfig {

    /**
     * Shared {@link ObjectMapper} for JSON serialisation/deserialisation.
     *
     * <p>Registered with {@link JavaTimeModule} so that Java 8 date/time types
     * (e.g. {@link java.time.LocalDateTime}) serialise correctly.
     *
     * @return a configured {@link ObjectMapper}
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

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
     * concurrency is controlled by {@code @Bulkhead} on the service methods.
     *
     * @return a virtual-thread-per-task executor
     */
    @Bean
    public Executor forecastExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Anthropic client for Claude API calls with HTTP/1.1 to avoid virtual-thread pinning.
     *
     * <p>OkHttp's HTTP/2 implementation uses {@code synchronized} blocks for frame
     * writing/reading. When 200+ virtual threads multiplex over a shared HTTP/2
     * connection, they pin carrier threads in the ForkJoinPool and deadlock. Forcing
     * HTTP/1.1 gives each request its own connection, avoiding monitor contention.
     *
     * <p>Connection pool sized at 10 idle connections with 2-minute keep-alive to
     * support parallel evaluation runs without excessive connection churn.
     *
     * @param properties Anthropic API configuration
     * @return a configured {@link AnthropicClient}
     */
    @Bean
    public AnthropicClient anthropicClient(AnthropicProperties properties) {
        okhttp3.OkHttpClient okHttp = createOkHttpClient();

        AnthropicBackend backend = AnthropicBackend.builder()
                .apiKey(properties.getApiKey())
                .build();

        com.anthropic.client.okhttp.OkHttpClient httpClient =
                new com.anthropic.client.okhttp.OkHttpClient(okHttp, backend);

        ClientOptions clientOptions = ClientOptions.builder()
                .httpClient(httpClient)
                .build();

        return new AnthropicClientImpl(clientOptions);
    }

    /**
     * Creates the OkHttp client with HTTP/1.1 protocol only.
     *
     * <p>Package-visible for testing. HTTP/1.1 avoids virtual-thread pinning
     * caused by OkHttp's {@code synchronized} HTTP/2 frame writers.
     *
     * @return configured OkHttp client
     */
    okhttp3.OkHttpClient createOkHttpClient() {
        return new okhttp3.OkHttpClient.Builder()
                .protocols(List.of(Protocol.HTTP_1_1))
                .connectionPool(new ConnectionPool(10, 2, TimeUnit.MINUTES))
                .callTimeout(Duration.ofSeconds(90))
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
