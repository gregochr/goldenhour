package com.gregochr.goldenhour.integration;

import com.anthropic.models.messages.batches.BatchCreateParams;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.CachedEvaluationEntity;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.RequestCounts;
import static com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.stubBatchCreate;
import static com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.stubBatchResults;
import static com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.stubBatchRetrieve;
import static com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.succeededWithEvaluation;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test for the forecast batch pipeline using WireMock-stubbed
 * Anthropic endpoints and a real Postgres testcontainer.
 *
 * <p>Exercises the production primitives:
 * <ol>
 *   <li>{@link BatchRequestFactory} (request construction)</li>
 *   <li>{@link CustomIdFactory#forForecast} (custom ID format)</li>
 *   <li>{@link BatchSubmissionService#submit} (submission + DB persistence)</li>
 *   <li>{@link BatchPollingService#pollPendingBatches} (status polling)</li>
 *   <li>{@code BatchResultProcessor} (result parsing + cache write)</li>
 *   <li>{@link CustomIdFactory#parse} (parse back to location/date/type)</li>
 *   <li>{@link CacheKeyFactory#build} (cache key construction)</li>
 *   <li>{@code RatingValidator}</li>
 *   <li>{@code cached_evaluation} write</li>
 *   <li>{@code api_call_log} write</li>
 *   <li>{@code forecast_batch} lifecycle (SUBMITTED → COMPLETED)</li>
 * </ol>
 *
 * <p>Tests start the pipeline from {@code BatchSubmissionService.submit} so they
 * do not need to seed weather/triage/briefing prerequisites. Those are extensively
 * unit-tested by {@code ScheduledBatchEvaluationServiceTest}; integration scope
 * here is "submit → poll → process → cache."
 */
class ForecastBatchPipelineIntegrationTest extends IntegrationTestBase {

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
    void clearDataBetweenTests() {
        // Order matters — child rows first, then parents.
        apiCallLogRepository.deleteAll();
        cachedEvaluationRepository.deleteAll();
        forecastBatchRepository.deleteAll();
        locationRepository.deleteAll();
        regionRepository.deleteAll();
    }

    @Test
    @DisplayName("Happy path: submit one inland forecast → poll → cache write + api_call_log")
    void inlandForecast_endToEnd_writesCacheAndApiCallLog() {
        // 1. Seed region + location.
        RegionEntity lakeDistrict = regionRepository.save(RegionEntity.builder()
                .name("Lake District")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
        LocationEntity castlerigg = locationRepository.save(LocationEntity.builder()
                .name("Castlerigg Stone Circle")
                .lat(54.6029)
                .lon(-3.0980)
                .region(lakeDistrict)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());

        LocalDate date = LocalDate.now().plusDays(1);
        TargetType target = TargetType.SUNRISE;
        String customId = CustomIdFactory.forForecast(castlerigg.getId(), date, target);
        assertThat(customId)
                .as("Production customId format must match fc-<id>-<yyyy-MM-dd>-<target>")
                .isEqualTo("fc-" + castlerigg.getId() + "-" + date + "-SUNRISE");

        // 2. Build the batch request through the production factory.
        BatchCreateParams.Request request = batchRequestFactory.buildForecastRequest(
                customId,
                EvaluationModel.HAIKU,
                TestAtmosphericData.defaults(),
                EvaluationModel.HAIKU.getMaxTokens());

        // 3. Stub Anthropic: create + initial in_progress retrieve.
        String batchId = "msgbatch_test_happy_path";
        WIRE_MOCK.stubFor(stubBatchCreate(batchId));
        WIRE_MOCK.stubFor(stubBatchRetrieve(batchId, "in_progress",
                new RequestCounts(1, 0, 0, 0, 0)));

        // 4. Submit and assert the SUBMITTED row.
        BatchSubmitResult result = batchSubmissionService.submit(
                List.of(request),
                BatchType.FORECAST,
                BatchTriggerSource.SCHEDULED,
                "Integration test happy path");
        assertThat(result)
                .as("Submission should return a non-null result")
                .isNotNull();
        assertThat(result.batchId())
                .as("Returned batchId must equal the WireMock stub's batchId")
                .isEqualTo(batchId);
        assertThat(forecastBatchRepository.findByAnthropicBatchId(batchId))
                .hasValueSatisfying(b -> {
                    assertThat(b.getStatus()).isEqualTo(BatchStatus.SUBMITTED);
                    assertThat(b.getBatchType()).isEqualTo(BatchType.FORECAST);
                    assertThat(b.getRequestCount()).isEqualTo(1);
                });

        // 5. First poll while batch is still in_progress — no result processing.
        batchPollingService.pollPendingBatches();
        assertThat(forecastBatchRepository.findByAnthropicBatchId(batchId))
                .as("In-progress batch should remain SUBMITTED after first poll")
                .hasValueSatisfying(b -> assertThat(b.getStatus()).isEqualTo(BatchStatus.SUBMITTED));

        // 6. Replace stubs: batch ends, results endpoint returns the success fixture.
        WIRE_MOCK.resetMappings();
        WIRE_MOCK.stubFor(stubBatchRetrieve(batchId, "ended",
                new RequestCounts(0, 1, 0, 0, 0)));
        WIRE_MOCK.stubFor(stubBatchResults(batchId, List.of(
                succeededWithEvaluation(customId, 4, 65, 70))));

        // 7. Second poll picks up the ended batch and processes results.
        batchPollingService.pollPendingBatches();

        // 8. Assert: ForecastBatchEntity transitioned to COMPLETED with correct counts.
        assertThat(forecastBatchRepository.findByAnthropicBatchId(batchId))
                .hasValueSatisfying(b -> {
                    assertThat(b.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                    assertThat(b.getSucceededCount()).isEqualTo(1);
                    assertThat(b.getErroredCount()).isZero();
                    assertThat(b.getEndedAt()).isNotNull();
                });

        // 9. Assert: cached_evaluation row written with the expected scores.
        String cacheKey = CacheKeyFactory.build("Lake District", date, target);
        Optional<CachedEvaluationEntity> cached =
                cachedEvaluationRepository.findByCacheKey(cacheKey);
        assertThat(cached)
                .as("Cached evaluation should exist for key %s", cacheKey)
                .isPresent();
        CachedEvaluationEntity row = cached.orElseThrow();
        assertThat(row.getSource()).isEqualTo("BATCH");
        assertThat(row.getRegionName()).isEqualTo("Lake District");
        assertThat(row.getEvaluationDate()).isEqualTo(date);

        List<BriefingEvaluationResult> results = parseResultsJson(row.getResultsJson());
        assertThat(results)
                .as("results_json should contain one BriefingEvaluationResult per location")
                .hasSize(1);
        BriefingEvaluationResult only = results.get(0);
        assertThat(only.locationName()).isEqualTo("Castlerigg Stone Circle");
        assertThat(only.rating()).isEqualTo(4);
        assertThat(only.fierySkyPotential()).isEqualTo(65);
        assertThat(only.goldenHourPotential()).isEqualTo(70);

        // 10. Assert: api_call_log has exactly one row for this batch's job run.
        Long jobRunId = forecastBatchRepository.findByAnthropicBatchId(batchId)
                .orElseThrow().getJobRunId();
        assertThat(jobRunId).isNotNull();
        List<ApiCallLogEntity> logs =
                apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(jobRunId);
        assertThat(logs).hasSize(1);
        ApiCallLogEntity log = logs.get(0);
        assertThat(log.getCustomId()).isEqualTo(customId);
        assertThat(log.getSucceeded()).isTrue();
        assertThat(log.getIsBatch()).isTrue();
        assertThat(log.getInputTokens()).isEqualTo(1500L);
        assertThat(log.getOutputTokens()).isEqualTo(50L);
        assertThat(log.getErrorType()).isNull();
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
