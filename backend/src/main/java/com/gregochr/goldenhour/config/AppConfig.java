package com.gregochr.goldenhour.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
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

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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

    /** Idle connections the Anthropic client's pool keeps. */
    static final int ANTHROPIC_MAX_IDLE_CONNECTIONS = 10;

    /** How long an idle Anthropic connection is kept before it is closed. */
    static final Duration ANTHROPIC_KEEP_ALIVE = Duration.ofMinutes(2);

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
     * Anthropic client for Claude API calls, built with the SDK's own builder.
     *
     * <p>The SDK's default transport protocols are in use: HTTP/2 with an HTTP/1.1 fallback.
     * ⚠️ Until 2026-10-06 this bean forced HTTP/1.1 through a hand-built OkHttp client, because
     * on Java 21 OkHttp's HTTP/2 frame writer pins a carrier thread under {@code synchronized},
     * and 200+ virtual threads multiplexed over one HTTP/2 connection deadlocked the
     * ForkJoinPool. That workaround held the SDK at 2.62.0 (2.63.0 removed the
     * {@code OkHttpClient(okhttp3.OkHttpClient, Backend)} constructor it needed, and the SDK
     * builder has no protocol option). The runtime is Java 25 now, where JEP 491 ends monitor
     * pinning of virtual threads, so the workaround is gone. Do not reintroduce protocol forcing.
     *
     * <p>Connection pool sized at 10 idle connections with a 2-minute keep-alive to support
     * parallel evaluation runs without excessive connection churn.
     *
     * <p>⚠️ <b>No timeout is set here, and none of the old client's was ever in force.</b> For every
     * request the SDK builds a fresh OkHttp client and overwrites its connect, read, write and call
     * timeouts from the request's {@code Timeout} (the caller's {@code RequestOptions}, else the
     * client-wide default, which {@code RequestOptions.from(ClientOptions)} fills in first). SDK
     * 2.62.0 and 2.68.0 do this identically ({@code OkHttpClient#newCall}), so the hand-built client's
     * 10 s connect/read/write and 90 s call timeouts were dead, and the effective timeouts did not
     * change with the SDK (call and read from the {@code X-Stainless-*} request headers, connect and
     * write from {@code Timeout}'s bytecode defaults, 2026-10-06):
     * <pre>
     *                          connect   read    write   call
     *   old client (2.62.0)      60 s    600 s   600 s   600 s   messages().create (any max_tokens),
     *   this client (2.68.0)     60 s    600 s   600 s   600 s   batches().retrieve / resultsStreaming
     * </pre>
     * A call that passes {@code RequestOptions.timeout(...)} (Ask's 20 s, {@code AnthropicBatchClient}'s
     * create and retrieve) gets that instead. The {@code max_tokens}-derived timeout the SDK also has
     * is never reached on {@code messages().create}, because the client default is already in place.
     * Do not add a client-wide {@code timeout(...)} shorter than a batch-results download: it would
     * apply to {@code resultsStreaming}, which passes no options and can run for minutes.
     *
     * <p>What that costs, unchanged by this bump: a connection that accepts and then goes silent holds
     * the calling thread for up to 600 s per SDK attempt, and the SDK makes up to 3 attempts
     * ({@code maxRetries} 2, which retries IO failures), roughly 30 minutes. The {@code anthropic}
     * Resilience4j retry does not add to it ({@code ClaudeRetryPredicate} retries only 500, 529 and the
     * content-filter 400, never an IO failure). Bounding it means per-call {@code RequestOptions}
     * timeouts sized to each caller, or a client-wide connect/read/write set alongside a
     * {@code request} timeout that keeps the batch download alive; neither is done here.
     * {@code AnthropicClientWireMockRoutingTest} pins the effective values above.
     *
     * @param properties Anthropic API configuration
     * @return a configured {@link AnthropicClient}
     */
    @Bean
    public AnthropicClient anthropicClient(AnthropicProperties properties) {
        return anthropicClientBuilder(properties.getApiKey()).build();
    }

    /**
     * The production client's builder, before the base URL is chosen.
     *
     * <p>Public and static so the integration tests' WireMock-routed client
     * ({@code WireMockAnthropicClientTestConfiguration}) is built from exactly the same
     * options and differs only in {@code baseUrl}.
     *
     * @param apiKey the Anthropic API key
     * @return a builder carrying the API key and the pool sizing
     */
    public static AnthropicOkHttpClient.Builder anthropicClientBuilder(String apiKey) {
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .maxIdleConnections(ANTHROPIC_MAX_IDLE_CONNECTIONS)
                .keepAliveDuration(ANTHROPIC_KEEP_ALIVE);
    }

    /**
     * Provides the application {@link Clock}: system UTC, wrapped in a {@link RewindAwareClock} so
     * an admin's {@code X-Rewind-To} request header ({@link RewindFilter}) can turn every
     * serve-time "now" back to an earlier moment for the span of that one GET. Off a request
     * thread, and on any request without that header, it is exactly {@code Clock.systemUTC()}.
     * Injected wherever a service needs a clock (the pipeline orchestrator and pipeline run
     * service among them) so tests can pin a fixed instant.
     *
     * @return a rewind-aware system UTC clock
     */
    @Bean
    public Clock clock() {
        return new RewindAwareClock(Clock.systemUTC());
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
