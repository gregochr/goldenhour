package com.gregochr.goldenhour.config;

import com.anthropic.backends.AnthropicBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientImpl;
import com.anthropic.core.ClientOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.client.OpenMeteoAirQualityApi;
import com.gregochr.goldenhour.client.OpenMeteoArchiveApi;
import com.gregochr.goldenhour.client.OpenMeteoForecastApi;
import com.gregochr.goldenhour.client.OpenMeteoMarineApi;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.MoonriseMoonsetCalculator;
import com.gregochr.solarutils.SolarCalculator;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import okhttp3.ConnectionPool;
import okhttp3.Protocol;

import java.time.Clock;
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

    /** Connect timeout applied to every outbound REST client built here. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Read timeout applied to every outbound REST client built here. */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

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
     * <p>Used by WorldTides, NOAA SWPC, Pushover, postcodes.io, OpenRouteService, Turnstile,
     * the exchange-rate and light-pollution lookups, the NLC scraper, and the three external
     * health indicators. Open-Meteo calls use dedicated {@code @HttpExchange} proxies instead.
     *
     * <p>⚠️ <b>This client must always carry timeouts.</b> It was created with
     * {@code RestClient.create()} — no request factory, and therefore no read timeout at all —
     * while the Open-Meteo proxies beside it were given 10s/30s for exactly the hang documented
     * on {@link #timeoutRequestFactory()}. The fix had been applied to one caller of the failure
     * mode rather than to the shared default. A peer that accepts a connection and then stops
     * sending bytes pins the calling thread indefinitely: on the Turnstile path that stalls a
     * login, on a health indicator it stalls the single-threaded status-SSE scheduler for every
     * connected client, and on the tide refresh it consumes one of the five dynamic-scheduler
     * threads permanently. Give a specific API its own longer-lived client rather than removing
     * the timeouts here.
     *
     * @return a RestClient instance with connect and read timeouts applied
     */
    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .requestFactory(timeoutRequestFactory())
                .build();
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
                // Since SDK 2.58.0 the transport no longer falls back to the backend's base URL;
                // ClientOptions owns it and defaults to production. Mirror what
                // AnthropicOkHttpClient.builder() does so the two never disagree.
                .baseUrl(backend.baseUrl())
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
     * Provides a system-UTC {@link Clock} for services that need an injectable clock
     * (used by the pipeline orchestrator and pipeline run service so tests can run
     * deterministically against a fixed instant).
     *
     * @return a system UTC clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
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
     * Stateless moonrise/moonset calculator from solar-utils, used for the supermoon hot topic's
     * moonrise time + azimuth.
     *
     * @return a shared {@link MoonriseMoonsetCalculator}
     */
    @Bean
    public MoonriseMoonsetCalculator moonriseMoonsetCalculator() {
        return new MoonriseMoonsetCalculator();
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
                .requestFactory(timeoutRequestFactory())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
                .build().createClient(OpenMeteoForecastApi.class);
    }

    /**
     * Proxy for the Open-Meteo Historical Weather (archive) API backed by {@link RestClient}.
     *
     * <p>Serves ERA5 reanalysis — a reconstruction of past weather that assimilates observations
     * unavailable when the original forecast was issued. Independent enough to score a forecast
     * against, but still a model field rather than a measurement.
     *
     * @return a typed proxy implementing {@link OpenMeteoArchiveApi}
     */
    @Bean
    OpenMeteoArchiveApi openMeteoArchiveApi() {
        RestClient client = RestClient.builder()
                .baseUrl("https://archive-api.open-meteo.com")
                .requestFactory(timeoutRequestFactory())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
                .build().createClient(OpenMeteoArchiveApi.class);
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
                .requestFactory(timeoutRequestFactory())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
                .build().createClient(OpenMeteoAirQualityApi.class);
    }

    /**
     * Proxy for the Open-Meteo Marine Weather API backed by {@link RestClient}.
     *
     * @return a typed proxy implementing {@link OpenMeteoMarineApi}
     */
    @Bean
    OpenMeteoMarineApi openMeteoMarineApi() {
        RestClient client = RestClient.builder()
                .baseUrl("https://marine-api.open-meteo.com")
                .requestFactory(timeoutRequestFactory())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
                .build().createClient(OpenMeteoMarineApi.class);
    }

    /**
     * HTTP request factory carrying the default outbound timeouts, used by every REST client
     * this class builds — the Open-Meteo proxies and the shared {@link #restClient()} alike.
     *
     * <p>Without explicit timeouts the default factory has no read timeout, causing individual
     * location calls to hang for minutes when Open-Meteo is slow (as seen in 181-second hang).
     * A 30-second read timeout allows Resilience4j retry/circuit-breaker to respond promptly.
     *
     * <p>A fresh instance per client: the factory is cheap, and sharing one across clients would
     * make a future per-client tuning change silently global.
     *
     * <p>Package-visible so {@code AppConfigTest} can assert the durations directly. Asserting
     * them through the built {@link RestClient} is not possible — it exposes no accessor for its
     * request factory — and a "returns non-null" test passes just as happily against the
     * untimed {@code RestClient.create()} this replaced.
     *
     * @return a request factory with a 10-second connect and 30-second read timeout
     */
    static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
