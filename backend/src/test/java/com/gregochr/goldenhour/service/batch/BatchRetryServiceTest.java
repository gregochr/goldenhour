package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    private BatchRetryService service() {
        return new BatchRetryService(forecastBatchRepository, apiCallLogRepository, CAP);
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
