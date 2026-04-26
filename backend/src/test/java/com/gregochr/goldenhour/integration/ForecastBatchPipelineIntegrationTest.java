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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.RequestCounts;
import static com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.erroredOverloaded;
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

    @Test
    @DisplayName("Errored-only batch: api_call_log captures error_type but no cache write")
    void erroredOnlyBatch_persistsErrorButSkipsCache() {
        RegionEntity ne = regionRepository.save(RegionEntity.builder()
                .name("North East")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
        LocationEntity bamburgh = locationRepository.save(LocationEntity.builder()
                .name("Bamburgh Castle")
                .lat(55.6090)
                .lon(-1.7099)
                .region(ne)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());

        LocalDate date = LocalDate.now().plusDays(1);
        TargetType target = TargetType.SUNRISE;
        String customId = CustomIdFactory.forForecast(bamburgh.getId(), date, target);
        BatchCreateParams.Request request = batchRequestFactory.buildForecastRequest(
                customId, EvaluationModel.HAIKU,
                TestAtmosphericData.defaults(),
                EvaluationModel.HAIKU.getMaxTokens());

        String batchId = "msgbatch_test_errored";
        WIRE_MOCK.stubFor(stubBatchCreate(batchId));

        BatchSubmitResult result = batchSubmissionService.submit(
                List.of(request),
                BatchType.FORECAST,
                BatchTriggerSource.SCHEDULED,
                "Integration test errored path");
        assertThat(result).isNotNull();

        WIRE_MOCK.resetMappings();
        WIRE_MOCK.stubFor(stubBatchRetrieve(batchId, "ended",
                new RequestCounts(0, 0, 1, 0, 0)));
        WIRE_MOCK.stubFor(stubBatchResults(batchId, List.of(
                erroredOverloaded(customId))));

        batchPollingService.pollPendingBatches();

        // No succeeded results means no cache_evaluation row was written.
        String cacheKey = CacheKeyFactory.build("North East", date, target);
        assertThat(cachedEvaluationRepository.findByCacheKey(cacheKey))
                .as("Errored result must not produce a cached_evaluation row")
                .isEmpty();

        // Forecast batch row reflects the error counts and ended_at.
        assertThat(forecastBatchRepository.findByAnthropicBatchId(batchId))
                .hasValueSatisfying(b -> {
                    assertThat(b.getErroredCount()).isEqualTo(1);
                    assertThat(b.getSucceededCount()).isZero();
                    assertThat(b.getEndedAt()).isNotNull();
                });

        // api_call_log row captures the error type from Anthropic's payload.
        Long jobRunId = forecastBatchRepository.findByAnthropicBatchId(batchId)
                .orElseThrow().getJobRunId();
        List<ApiCallLogEntity> logs =
                apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(jobRunId);
        assertThat(logs).hasSize(1);
        ApiCallLogEntity log = logs.get(0);
        assertThat(log.getCustomId()).isEqualTo(customId);
        assertThat(log.getSucceeded()).isFalse();
        assertThat(log.getErrorType()).isEqualTo("overloaded_error");
        assertThat(log.getInputTokens()).isNull();
        assertThat(log.getOutputTokens()).isNull();
    }

    @Test
    @DisplayName("Submission failure: 500 from Anthropic create returns null, no DB writes")
    void submissionFailure_returnsNullAndPersistsNothing() {
        RegionEntity nw = regionRepository.save(RegionEntity.builder()
                .name("North West")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
        LocationEntity buttermere = locationRepository.save(LocationEntity.builder()
                .name("Buttermere")
                .lat(54.5410)
                .lon(-3.2762)
                .region(nw)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());

        String customId = CustomIdFactory.forForecast(
                buttermere.getId(), LocalDate.now().plusDays(1), TargetType.SUNRISE);
        BatchCreateParams.Request request = batchRequestFactory.buildForecastRequest(
                customId, EvaluationModel.HAIKU,
                TestAtmosphericData.defaults(),
                EvaluationModel.HAIKU.getMaxTokens());

        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/messages/batches"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("{\"error\":\"internal_server_error\"}")));

        BatchSubmitResult result = batchSubmissionService.submit(
                List.of(request),
                BatchType.FORECAST,
                BatchTriggerSource.SCHEDULED,
                "Integration test submission failure");

        assertThat(result)
                .as("BatchSubmissionService.submit must return null on Anthropic-side failure"
                        + " (its catch-all swallows and logs)")
                .isNull();
        assertThat(forecastBatchRepository.findAll())
                .as("No forecast_batch row should be persisted on submission failure")
                .isEmpty();
    }

    @Test
    @DisplayName("Multi-location batch: each region's cache key is written independently")
    void multiLocationBatch_writesPerRegionCacheKeys() {
        RegionEntity lakes = regionRepository.save(RegionEntity.builder()
                .name("Lake District")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
        RegionEntity ne = regionRepository.save(RegionEntity.builder()
                .name("North East")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
        LocationEntity castlerigg = locationRepository.save(LocationEntity.builder()
                .name("Castlerigg Stone Circle")
                .lat(54.6029).lon(-3.0980)
                .region(lakes)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
        LocationEntity bamburgh = locationRepository.save(LocationEntity.builder()
                .name("Bamburgh Castle")
                .lat(55.6090).lon(-1.7099)
                .region(ne)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());

        LocalDate date = LocalDate.now().plusDays(1);
        TargetType target = TargetType.SUNRISE;
        String customIdLakes = CustomIdFactory.forForecast(castlerigg.getId(), date, target);
        String customIdNe = CustomIdFactory.forForecast(bamburgh.getId(), date, target);

        BatchCreateParams.Request reqLakes = batchRequestFactory.buildForecastRequest(
                customIdLakes, EvaluationModel.HAIKU,
                TestAtmosphericData.defaults(),
                EvaluationModel.HAIKU.getMaxTokens());
        BatchCreateParams.Request reqNe = batchRequestFactory.buildForecastRequest(
                customIdNe, EvaluationModel.HAIKU,
                TestAtmosphericData.defaults(),
                EvaluationModel.HAIKU.getMaxTokens());

        String batchId = "msgbatch_test_multi";
        WIRE_MOCK.stubFor(stubBatchCreate(batchId));

        BatchSubmitResult result = batchSubmissionService.submit(
                List.of(reqLakes, reqNe),
                BatchType.FORECAST,
                BatchTriggerSource.SCHEDULED,
                "Integration test multi-location");
        assertThat(result.requestCount()).isEqualTo(2);

        WIRE_MOCK.resetMappings();
        WIRE_MOCK.stubFor(stubBatchRetrieve(batchId, "ended",
                new RequestCounts(0, 2, 0, 0, 0)));
        WIRE_MOCK.stubFor(stubBatchResults(batchId, List.of(
                succeededWithEvaluation(customIdLakes, 5, 80, 75),
                succeededWithEvaluation(customIdNe, 3, 40, 50))));

        batchPollingService.pollPendingBatches();

        // Each region writes to a distinct cache key with its own location's scores.
        String keyLakes = CacheKeyFactory.build("Lake District", date, target);
        String keyNe = CacheKeyFactory.build("North East", date, target);

        BriefingEvaluationResult lakesResult = parseResultsJson(
                cachedEvaluationRepository.findByCacheKey(keyLakes).orElseThrow().getResultsJson())
                .get(0);
        assertThat(lakesResult.locationName()).isEqualTo("Castlerigg Stone Circle");
        assertThat(lakesResult.rating()).isEqualTo(5);
        assertThat(lakesResult.fierySkyPotential()).isEqualTo(80);

        BriefingEvaluationResult neResult = parseResultsJson(
                cachedEvaluationRepository.findByCacheKey(keyNe).orElseThrow().getResultsJson())
                .get(0);
        assertThat(neResult.locationName()).isEqualTo("Bamburgh Castle");
        assertThat(neResult.rating()).isEqualTo(3);
        assertThat(neResult.fierySkyPotential()).isEqualTo(40);

        // ForecastBatchEntity reflects 2 succeeded, 0 errored.
        assertThat(forecastBatchRepository.findByAnthropicBatchId(batchId))
                .hasValueSatisfying(b -> {
                    assertThat(b.getSucceededCount()).isEqualTo(2);
                    assertThat(b.getErroredCount()).isZero();
                    assertThat(b.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                });

        // api_call_log has both rows, each with its own custom_id.
        Long jobRunId = forecastBatchRepository.findByAnthropicBatchId(batchId)
                .orElseThrow().getJobRunId();
        List<ApiCallLogEntity> logs =
                apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(jobRunId);
        assertThat(logs).hasSize(2);
        assertThat(logs).extracting(ApiCallLogEntity::getCustomId)
                .containsExactlyInAnyOrder(customIdLakes, customIdNe);
        assertThat(logs).allMatch(ApiCallLogEntity::getSucceeded);
    }

    @Test
    @DisplayName("Mixed batch: succeeded location writes cache, errored does not")
    void mixedBatch_partialCacheWrite() {
        RegionEntity lakes = regionRepository.save(RegionEntity.builder()
                .name("Lake District")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
        RegionEntity ne = regionRepository.save(RegionEntity.builder()
                .name("North East")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
        LocationEntity castlerigg = locationRepository.save(LocationEntity.builder()
                .name("Castlerigg Stone Circle")
                .lat(54.6029).lon(-3.0980)
                .region(lakes).enabled(true).createdAt(LocalDateTime.now()).build());
        LocationEntity bamburgh = locationRepository.save(LocationEntity.builder()
                .name("Bamburgh Castle")
                .lat(55.6090).lon(-1.7099)
                .region(ne).enabled(true).createdAt(LocalDateTime.now()).build());

        LocalDate date = LocalDate.now().plusDays(1);
        TargetType target = TargetType.SUNRISE;
        String customIdLakes = CustomIdFactory.forForecast(castlerigg.getId(), date, target);
        String customIdNe = CustomIdFactory.forForecast(bamburgh.getId(), date, target);

        BatchCreateParams.Request reqLakes = batchRequestFactory.buildForecastRequest(
                customIdLakes, EvaluationModel.HAIKU,
                TestAtmosphericData.defaults(),
                EvaluationModel.HAIKU.getMaxTokens());
        BatchCreateParams.Request reqNe = batchRequestFactory.buildForecastRequest(
                customIdNe, EvaluationModel.HAIKU,
                TestAtmosphericData.defaults(),
                EvaluationModel.HAIKU.getMaxTokens());

        String batchId = "msgbatch_test_mixed";
        WIRE_MOCK.stubFor(stubBatchCreate(batchId));

        batchSubmissionService.submit(
                List.of(reqLakes, reqNe),
                BatchType.FORECAST,
                BatchTriggerSource.SCHEDULED,
                "Integration test mixed batch");

        WIRE_MOCK.resetMappings();
        WIRE_MOCK.stubFor(stubBatchRetrieve(batchId, "ended",
                new RequestCounts(0, 1, 1, 0, 0)));
        WIRE_MOCK.stubFor(stubBatchResults(batchId, List.of(
                succeededWithEvaluation(customIdLakes, 4, 60, 65),
                erroredOverloaded(customIdNe))));

        batchPollingService.pollPendingBatches();

        // Lakes succeeded → cache_evaluation row exists.
        assertThat(cachedEvaluationRepository.findByCacheKey(
                CacheKeyFactory.build("Lake District", date, target)))
                .as("Successful Lake District result should produce a cache row")
                .isPresent();

        // North East errored → no cache_evaluation row.
        assertThat(cachedEvaluationRepository.findByCacheKey(
                CacheKeyFactory.build("North East", date, target)))
                .as("Errored North East result must not produce a cache row")
                .isEmpty();

        // Forecast batch entity reports 1 succeeded, 1 errored.
        assertThat(forecastBatchRepository.findByAnthropicBatchId(batchId))
                .hasValueSatisfying(b -> {
                    assertThat(b.getSucceededCount()).isEqualTo(1);
                    assertThat(b.getErroredCount()).isEqualTo(1);
                    assertThat(b.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                });
    }

    private List<BriefingEvaluationResult> parseResultsJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new AssertionError("Failed to deserialise results_json: " + e.getMessage(), e);
        }
    }
}
