package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import com.gregochr.goldenhour.service.evaluation.EvaluationHandle;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BatchRetryService} failure selection and the cap-as-tripwire
 * policy (commit 1 — selection only, no submission).
 *
 * <p>The cost-safety guarantee under test: the retry set is derived purely from
 * {@code api_call_log} failure rows. A deliberate skip ({@code SKIPPED_*}) is never
 * sent to the model and therefore has no failure row, so it cannot be selected —
 * proven here by seeding only genuine failures and asserting the selection contains
 * exactly those custom ids and nothing else.
 */
@ExtendWith(MockitoExtension.class)
class BatchRetryServiceTest {

    private static final Long RUN_ID = 77L;
    private static final int CAP = 5;
    private static final LocalDate DATE = LocalDate.of(2026, 4, 16);

    @Mock
    private ForecastBatchRepository forecastBatchRepository;
    @Mock
    private ApiCallLogRepository apiCallLogRepository;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private ForecastService forecastService;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private EvaluationService evaluationService;

    private BatchRetryService service() {
        return new BatchRetryService(forecastBatchRepository, apiCallLogRepository,
                locationRepository, forecastService, modelSelectionService, evaluationService, CAP);
    }

    private static ForecastBatchEntity precursor(String anthropicBatchId) {
        ForecastBatchEntity b = new ForecastBatchEntity(
                anthropicBatchId, BatchType.FORECAST, 10, Instant.now());
        b.setRetry(false);
        return b;
    }

    private static ApiCallLogEntity failedCall(String customId, String batchId, String errorType) {
        return ApiCallLogEntity.builder()
                .jobRunId(1L)
                .service(com.gregochr.goldenhour.entity.ServiceName.ANTHROPIC)
                .calledAt(java.time.LocalDateTime.now())
                .succeeded(false)
                .isBatch(true)
                .customId(customId)
                .batchId(batchId)
                .errorType(errorType)
                .build();
    }

    @Test
    @DisplayName("selects only attempted-and-failed forecast requests; queries precursor batches only")
    void selectsOnlyAttemptedFailures() {
        when(forecastBatchRepository.findByPipelineRunIdAndRetryFalse(RUN_ID))
                .thenReturn(List.of(precursor("msgbatch_A")));
        String failed = CustomIdFactory.forForecast(42L, DATE, TargetType.SUNRISE);
        when(apiCallLogRepository.findFailedBatchCalls(List.of("msgbatch_A")))
                .thenReturn(List.of(failedCall(failed, "msgbatch_A", "parse_error")));

        RetrySelection selection = service().selectFailures(RUN_ID);

        assertThat(selection.decision()).isEqualTo(RetrySelection.Decision.RETRY);
        assertThat(selection.failures())
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.customId()).isEqualTo(failed);
                    assertThat(f.locationId()).isEqualTo(42L);
                    assertThat(f.date()).isEqualTo(DATE);
                    assertThat(f.targetType()).isEqualTo(TargetType.SUNRISE);
                });
        // Cost-safety: the precursor (non-retry) lookup is the seam, not findByPipelineRunId.
        verify(forecastBatchRepository).findByPipelineRunIdAndRetryFalse(RUN_ID);
    }

    @Test
    @DisplayName("no precursor batches → NONE (no api_call_log query)")
    void noPrecursorBatchesIsNoOp() {
        when(forecastBatchRepository.findByPipelineRunIdAndRetryFalse(RUN_ID))
                .thenReturn(List.of());

        RetrySelection selection = service().selectFailures(RUN_ID);

        assertThat(selection.decision()).isEqualTo(RetrySelection.Decision.NONE);
        assertThat(selection.failures()).isEmpty();
        verifyNoInteractions(apiCallLogRepository);
    }

    @Test
    @DisplayName("no failures (clean cycle) → NONE")
    void cleanCycleIsNoOp() {
        when(forecastBatchRepository.findByPipelineRunIdAndRetryFalse(RUN_ID))
                .thenReturn(List.of(precursor("msgbatch_A")));
        when(apiCallLogRepository.findFailedBatchCalls(List.of("msgbatch_A")))
                .thenReturn(List.of());

        RetrySelection selection = service().selectFailures(RUN_ID);

        assertThat(selection.decision()).isEqualTo(RetrySelection.Decision.NONE);
        assertThat(selection.failureCount()).isZero();
    }

    @Test
    @DisplayName("failures exactly at cap → RETRY")
    void atCapRetries() {
        when(forecastBatchRepository.findByPipelineRunIdAndRetryFalse(RUN_ID))
                .thenReturn(List.of(precursor("msgbatch_A")));
        when(apiCallLogRepository.findFailedBatchCalls(List.of("msgbatch_A")))
                .thenReturn(failuresFor(CAP));

        RetrySelection selection = service().selectFailures(RUN_ID);

        assertThat(selection.decision()).isEqualTo(RetrySelection.Decision.RETRY);
        assertThat(selection.failures()).hasSize(CAP);
        assertThat(selection.failureCount()).isEqualTo(CAP);
    }

    @Test
    @DisplayName("failures one over cap → SYSTEMATIC, not retried")
    void overCapIsSystematic() {
        when(forecastBatchRepository.findByPipelineRunIdAndRetryFalse(RUN_ID))
                .thenReturn(List.of(precursor("msgbatch_A")));
        when(apiCallLogRepository.findFailedBatchCalls(List.of("msgbatch_A")))
                .thenReturn(failuresFor(CAP + 1));

        RetrySelection selection = service().selectFailures(RUN_ID);

        assertThat(selection.decision()).isEqualTo(RetrySelection.Decision.SYSTEMATIC);
        assertThat(selection.failures()).isEmpty();
        assertThat(selection.failureCount()).isEqualTo(CAP + 1);
    }

    @Test
    @DisplayName("malformed and non-forecast custom ids are dropped, not retried")
    void dropsUnreconstructableCustomIds() {
        when(forecastBatchRepository.findByPipelineRunIdAndRetryFalse(RUN_ID))
                .thenReturn(List.of(precursor("msgbatch_A")));
        String good = CustomIdFactory.forForecast(7L, DATE, TargetType.SUNSET);
        when(apiCallLogRepository.findFailedBatchCalls(List.of("msgbatch_A")))
                .thenReturn(List.of(
                        failedCall(good, "msgbatch_A", "parse_error"),
                        failedCall("garbage-not-a-custom-id", "msgbatch_A", "parse_error"),
                        failedCall("au-MODERATE-2026-04-16", "msgbatch_A", "parse_error")));

        RetrySelection selection = service().selectFailures(RUN_ID);

        assertThat(selection.decision()).isEqualTo(RetrySelection.Decision.RETRY);
        assertThat(selection.failures())
                .singleElement()
                .satisfies(f -> assertThat(f.customId()).isEqualTo(good));
    }

    @Test
    @DisplayName("duplicate failure rows for one custom id are de-duplicated")
    void dedupesByCustomId() {
        when(forecastBatchRepository.findByPipelineRunIdAndRetryFalse(RUN_ID))
                .thenReturn(List.of(precursor("msgbatch_A")));
        String dup = CustomIdFactory.forForecast(9L, DATE, TargetType.SUNRISE);
        when(apiCallLogRepository.findFailedBatchCalls(List.of("msgbatch_A")))
                .thenReturn(List.of(
                        failedCall(dup, "msgbatch_A", "parse_error"),
                        failedCall(dup, "msgbatch_A", "overloaded_error")));

        RetrySelection selection = service().selectFailures(RUN_ID);

        assertThat(selection.failureCount()).isEqualTo(1);
        assertThat(selection.failures()).hasSize(1);
    }

    // ── submitRetry ────────────────────────────────────────────────────────────

    private static LocationEntity location(Long id, String name) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName(name);
        loc.setTideType(Set.<TideType>of());
        return loc;
    }

    private ForecastPreEvalResult preEval(LocationEntity loc, LocalDate date,
            TargetType targetType, boolean triaged) {
        return new ForecastPreEvalResult(
                triaged, triaged ? "triaged" : null,
                triaged ? null : TestAtmosphericData.defaults(),
                loc, date, targetType, null, 100, 0, EvaluationModel.HAIKU, Set.of(),
                loc.getName() + "|" + date + "|" + targetType, null);
    }

    @Test
    @DisplayName("submitRetry reconstructs the failed request and submits ONE retry batch "
            + "(isRetry=true) — only the failed location, nothing else")
    void submitRetryReconstructsAndSubmits() {
        LocationEntity loc = location(42L, "Bamburgh");
        when(locationRepository.findById(42L)).thenReturn(Optional.of(loc));
        when(modelSelectionService.getActiveModel(any())).thenReturn(EvaluationModel.HAIKU);
        when(forecastService.fetchWeatherAndTriage(eq(loc), eq(DATE), eq(TargetType.SUNRISE),
                any(), any(), eq(false), isNull()))
                .thenReturn(preEval(loc, DATE, TargetType.SUNRISE, false));
        when(forecastBatchRepository.findByPipelineRunIdAndRetryTrue(RUN_ID))
                .thenReturn(List.of());
        when(evaluationService.submit(anyList(), eq(BatchTriggerSource.RETRY), eq(RUN_ID), eq(true)))
                .thenReturn(new EvaluationHandle(5L, "msgbatch_retry", 1));

        String customId = CustomIdFactory.forForecast(42L, DATE, TargetType.SUNRISE);
        RetrySelection selection = RetrySelection.retry(List.of(
                new RetrySelection.RetryFailure(customId, 42L, DATE, TargetType.SUNRISE)), CAP);

        int submitted = service().submitRetry(RUN_ID, selection);

        assertThat(submitted).isEqualTo(1);
        ArgumentCaptor<List<EvaluationTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(evaluationService).submit(captor.capture(),
                eq(BatchTriggerSource.RETRY), eq(RUN_ID), eq(true));
        assertThat(captor.getValue()).singleElement()
                .isInstanceOfSatisfying(EvaluationTask.Forecast.class, t -> {
                    assertThat(t.location()).isEqualTo(loc);
                    assertThat(t.date()).isEqualTo(DATE);
                    assertThat(t.targetType()).isEqualTo(TargetType.SUNRISE);
                });
    }

    @Test
    @DisplayName("submitRetry is idempotent — a retry batch already existing → no second submit")
    void submitRetryIsIdempotent() {
        ForecastBatchEntity existingRetry = precursor("msgbatch_retry_existing");
        existingRetry.setRetry(true);
        when(forecastBatchRepository.findByPipelineRunIdAndRetryTrue(RUN_ID))
                .thenReturn(List.of(existingRetry));

        String customId = CustomIdFactory.forForecast(42L, DATE, TargetType.SUNRISE);
        RetrySelection selection = RetrySelection.retry(List.of(
                new RetrySelection.RetryFailure(customId, 42L, DATE, TargetType.SUNRISE)), CAP);

        int submitted = service().submitRetry(RUN_ID, selection);

        assertThat(submitted).isZero();
        verifyNoInteractions(evaluationService);
        verifyNoInteractions(forecastService);
    }

    @Test
    @DisplayName("submitRetry does nothing for a non-RETRY selection")
    void submitRetryIgnoresNonRetrySelection() {
        int submitted = service().submitRetry(RUN_ID, RetrySelection.systematic(99, CAP));

        assertThat(submitted).isZero();
        verifyNoInteractions(evaluationService);
        verifyNoInteractions(forecastService);
        verifyNoInteractions(locationRepository);
    }

    @Test
    @DisplayName("submitRetry: a request that cannot be reconstructed (location gone) "
            + "is left failed and no batch is submitted")
    void submitRetrySkipsUnreconstructable() {
        when(forecastBatchRepository.findByPipelineRunIdAndRetryTrue(RUN_ID))
                .thenReturn(List.of());
        when(locationRepository.findById(42L)).thenReturn(Optional.empty());

        String customId = CustomIdFactory.forForecast(42L, DATE, TargetType.SUNRISE);
        RetrySelection selection = RetrySelection.retry(List.of(
                new RetrySelection.RetryFailure(customId, 42L, DATE, TargetType.SUNRISE)), CAP);

        int submitted = service().submitRetry(RUN_ID, selection);

        assertThat(submitted).isZero();
        verify(evaluationService, never()).submit(anyList(), any(), any(), eq(true));
    }

    @Test
    @DisplayName("summariseRecovery reads the retry batch counts into the phase detail")
    void summariseRecoveryFormatsDetail() {
        ForecastBatchEntity retryBatch = precursor("msgbatch_retry");
        retryBatch.setRetry(true);
        // 3 reconstructed, 2 recovered, 1 still-failed.
        retryBatch.setSucceededCount(2);
        retryBatch.setErroredCount(1);
        ForecastBatchEntity threeReq = new ForecastBatchEntity(
                "msgbatch_retry", BatchType.FORECAST, 3, Instant.now());
        threeReq.setRetry(true);
        threeReq.setSucceededCount(2);
        threeReq.setErroredCount(1);
        when(forecastBatchRepository.findByPipelineRunIdAndRetryTrue(RUN_ID))
                .thenReturn(List.of(threeReq));

        String detail = service().summariseRecovery(RUN_ID, 4);

        assertThat(detail).isEqualTo("4 failed, 3 retried, 2 recovered, 1 still-failed");
    }

    private static List<ApiCallLogEntity> failuresFor(int count) {
        List<ApiCallLogEntity> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String customId = CustomIdFactory.forForecast(
                    (long) (100 + i), DATE, TargetType.SUNRISE);
            rows.add(failedCall(customId, "msgbatch_A", "parse_error"));
        }
        return rows;
    }
}
