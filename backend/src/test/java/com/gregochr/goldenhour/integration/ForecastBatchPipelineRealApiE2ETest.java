package com.gregochr.goldenhour.integration;

import com.anthropic.models.messages.batches.BatchCreateParams;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.batch.BatchPollingService;
import com.gregochr.goldenhour.service.batch.BatchSubmissionService;
import com.gregochr.goldenhour.service.batch.BatchSubmitResult;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import com.gregochr.goldenhour.service.evaluation.BatchRequestFactory;
import com.gregochr.goldenhour.service.evaluation.CacheKeyFactory;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-API end-to-end test for the forecast batch pipeline.
 *
 * <p>Submits one Haiku request through the production
 * {@link BatchSubmissionService} → {@link BatchPollingService} →
 * {@code BatchResultProcessor} chain against the real Anthropic Batch API,
 * polls until completion, and asserts that {@code cached_evaluation},
 * {@code api_call_log}, and {@code forecast_batch} are all populated as a
 * production batch run would populate them.
 *
 * <p>This test complements (does not replace) the narrower
 * {@code BatchSchemaIntegrationTest} added in commit {@code e91376f}.
 * That test guards the schema-compile contract via the SDK directly;
 * this test guards the wiring contract — that every Pass 2 primitive,
 * every Pass V99 observability column, and every persistence write fires
 * correctly when the real Anthropic API responds.
 *
 * <p>Auto-skipped when {@code ANTHROPIC_API_KEY} is not set, so it costs
 * nothing in default CI or local dev. Runs only on a dedicated CI gate
 * (see {@code .github/workflows/ci.yml}).
 *
 * <p>Cost per run: one Haiku-on-batch inference (sub-pence). Wall clock:
 * up to ~4 minutes for the batch to reach ENDED at Anthropic.
 *
 * <p>Does not extend {@link IntegrationTestBase} because that base uses
 * the {@code WireMockAnthropicClientTestConfiguration} override; this test
 * needs the production {@link com.anthropic.client.AnthropicClient} bean
 * with a real API key. The Postgres testcontainer setup is duplicated
 * for that reason — small repetition, clear separation.
 */
@SpringBootTest
@ActiveProfiles({"test", "integration-test"})
@Testcontainers
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ForecastBatchPipelineRealApiE2ETest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("goldenhour_real_api_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");

        // Use the real ANTHROPIC_API_KEY for this test — production AnthropicClient
        // bean from AppConfig will be wired (no WireMock override).
        registry.add("anthropic.api-key", () -> System.getenv("ANTHROPIC_API_KEY"));
    }

    @Autowired
    private BatchSubmissionService batchSubmissionService;

    @Autowired
    private BatchPollingService batchPollingService;

    @Autowired
    private BatchRequestFactory batchRequestFactory;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private ForecastBatchRepository forecastBatchRepository;

    @Autowired
    private CachedEvaluationRepository cachedEvaluationRepository;

    @Autowired
    private ApiCallLogRepository apiCallLogRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void clearDataAfterTest() {
        apiCallLogRepository.deleteAll();
        cachedEvaluationRepository.deleteAll();
        forecastBatchRepository.deleteAll();
        locationRepository.deleteAll();
        regionRepository.deleteAll();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @DisplayName("Real-API E2E: full pipeline with one Haiku request through Anthropic")
    void fullPipeline_singleRequest_realAnthropic() {
        RegionEntity lakes = regionRepository.save(RegionEntity.builder()
                .name("Lake District")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
        LocationEntity castlerigg = locationRepository.save(LocationEntity.builder()
                .name("Castlerigg Stone Circle")
                .lat(54.6029)
                .lon(-3.0980)
                .region(lakes)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());

        LocalDate date = LocalDate.now().plusDays(1);
        TargetType target = TargetType.SUNRISE;
        String customId = CustomIdFactory.forForecast(castlerigg.getId(), date, target);

        BatchCreateParams.Request request = batchRequestFactory.buildForecastRequest(
                customId,
                EvaluationModel.HAIKU,
                TestAtmosphericData.defaults(),
                EvaluationModel.HAIKU.getMaxTokens());

        BatchSubmitResult result = batchSubmissionService.submit(
                List.of(request),
                BatchType.FORECAST,
                BatchTriggerSource.ADMIN,
                "Real-API E2E test");

        assertThat(result)
                .as("Submission against the real Anthropic API should return a result")
                .isNotNull();
        assertThat(result.batchId())
                .as("Real Anthropic batch IDs start with msgbatch_")
                .startsWith("msgbatch_");

        String batchId = result.batchId();

        // Poll until the batch reaches a terminal state. The poller updates
        // forecast_batch.status synchronously when results arrive.
        Awaitility.await()
                .atMost(Duration.ofMinutes(4))
                .pollInterval(Duration.ofSeconds(15))
                .pollDelay(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    batchPollingService.pollPendingBatches();
                    BatchStatus status = forecastBatchRepository.findByAnthropicBatchId(batchId)
                            .orElseThrow().getStatus();
                    assertThat(status)
                            .as("Batch %s should reach COMPLETED via the production "
                                    + "polling pipeline", batchId)
                            .isEqualTo(BatchStatus.COMPLETED);
                });

        // Forecast batch entity reflects a single succeeded request.
        assertThat(forecastBatchRepository.findByAnthropicBatchId(batchId))
                .hasValueSatisfying(b -> {
                    assertThat(b.getSucceededCount()).isEqualTo(1);
                    assertThat(b.getErroredCount()).isZero();
                });

        // cached_evaluation has the row with parseable, in-range scores.
        String cacheKey = CacheKeyFactory.build("Lake District", date, target);
        String resultsJson = cachedEvaluationRepository.findByCacheKey(cacheKey)
                .orElseThrow(() -> new AssertionError(
                        "cached_evaluation row missing for key " + cacheKey))
                .getResultsJson();

        List<BriefingEvaluationResult> results = parseResultsJson(resultsJson);
        assertThat(results).hasSize(1);
        BriefingEvaluationResult only = results.get(0);
        assertThat(only.locationName()).isEqualTo("Castlerigg Stone Circle");
        assertThat(only.fierySkyPotential()).isBetween(0, 100);
        assertThat(only.goldenHourPotential()).isBetween(0, 100);
        if (only.rating() != null) {
            assertThat(only.rating()).isBetween(1, 5);
        }
        assertThat(only.summary()).isNotBlank();

        // api_call_log has exactly one batch row with positive token counts.
        Long jobRunId = forecastBatchRepository.findByAnthropicBatchId(batchId)
                .orElseThrow().getJobRunId();
        List<ApiCallLogEntity> logs =
                apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(jobRunId);
        assertThat(logs).hasSize(1);
        ApiCallLogEntity log = logs.get(0);
        assertThat(log.getCustomId()).isEqualTo(customId);
        assertThat(log.getSucceeded()).isTrue();
        assertThat(log.getIsBatch()).isTrue();
        assertThat(log.getInputTokens()).isPositive();
        assertThat(log.getOutputTokens()).isPositive();
        assertThat(log.getTargetDate()).isEqualTo(date);
        assertThat(log.getTargetType()).isEqualTo(TargetType.SUNRISE);
    }

    private List<BriefingEvaluationResult> parseResultsJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new AssertionError("Failed to deserialise results_json: " + e.getMessage(), e);
        }
    }
}
