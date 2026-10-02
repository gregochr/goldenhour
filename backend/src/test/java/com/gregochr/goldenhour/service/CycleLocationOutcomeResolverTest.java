package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BatchCallOutcome;
import com.gregochr.goldenhour.model.CycleDisposition;
import com.gregochr.goldenhour.model.CyclePlaceOutcome;
import com.gregochr.goldenhour.model.CyclePlaceOutcome.FailureKind;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CycleLocationOutcomeResolver}: a synthetic cycle's recorded dispositions and
 * batch results, reduced to one outcome per place.
 */
@ExtendWith(MockitoExtension.class)
class CycleLocationOutcomeResolverTest {

    private static final long RUN_ID = 300L;
    private static final long JOB_RUN_ID = 9001L;
    private static final String BATCH_ID = "msgbatch_first";
    private static final String RETRY_BATCH_ID = "msgbatch_retry";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 2);

    @Mock
    private ForecastBatchRepository forecastBatchRepository;

    @Mock
    private ForecastRunDispositionRepository dispositionRepository;

    @Mock
    private ApiCallLogRepository apiCallLogRepository;

    private CycleLocationOutcomeResolver resolver() {
        return new CycleLocationOutcomeResolver(
                forecastBatchRepository, dispositionRepository, apiCallLogRepository);
    }

    private static ForecastBatchEntity batch(String anthropicId, BatchType type, Long jobRunId) {
        ForecastBatchEntity batch = new ForecastBatchEntity(
                anthropicId, type, 10, Instant.parse("2026-10-03T01:00:00Z"));
        batch.setPipelineRunId(RUN_ID);
        batch.setJobRunId(jobRunId);
        return batch;
    }

    private void cycleWithBatches(ForecastBatchEntity... batches) {
        when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of(batches));
    }

    private static CycleDisposition disposition(long locationId, String category) {
        return new CycleDisposition(locationId, category);
    }

    private static BatchCallOutcome sky(long locationId, boolean succeeded) {
        return new BatchCallOutcome(
                CustomIdFactory.forForecast(locationId, DATE, TargetType.SUNSET), succeeded,
                succeeded ? null : "errored");
    }

    @Test
    @DisplayName("a scored result is GOT_THROUGH")
    void scoredResult_gotThrough() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID)))
                .thenReturn(List.of(disposition(1L, "EVALUATED")));
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID)))
                .thenReturn(List.of(sky(1L, true)));

        Map<Long, CyclePlaceOutcome> outcomes = resolver().resolve(RUN_ID);

        assertThat(outcomes).containsOnlyKeys(1L);
        assertThat(outcomes.get(1L)).isEqualTo(CyclePlaceOutcome.gotThrough());
    }

    @Test
    @DisplayName("a triaged place is GOT_THROUGH: the pipeline looked at it and answered")
    void triaged_gotThrough() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID)))
                .thenReturn(List.of(disposition(2L, "SKIPPED_TRIAGED")));
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID))).thenReturn(List.of());

        assertThat(resolver().resolve(RUN_ID).get(2L)).isEqualTo(CyclePlaceOutcome.gotThrough());
    }

    @Test
    @DisplayName("a collection error (SKIPPED_ERROR) is FAILED with the weather-data kind")
    void collectionError_failedWeatherData() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID)))
                .thenReturn(List.of(disposition(3L, "SKIPPED_ERROR")));
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID))).thenReturn(List.of());

        assertThat(resolver().resolve(RUN_ID).get(3L))
                .isEqualTo(CyclePlaceOutcome.failed(FailureKind.WEATHER_DATA));
    }

    @Test
    @DisplayName("an errored batch result with no success is FAILED with the evaluation kind")
    void erroredBatchResult_failedEvaluation() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID)))
                .thenReturn(List.of(disposition(4L, "EVALUATED")));
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID)))
                .thenReturn(List.of(sky(4L, false)));

        assertThat(resolver().resolve(RUN_ID).get(4L))
                .isEqualTo(CyclePlaceOutcome.failed(FailureKind.EVALUATION));
    }

    @Test
    @DisplayName("every category that means nothing was sent or judged is NOT_ATTEMPTED")
    void skippedCategories_notAttempted() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID))).thenReturn(List.of(
                disposition(10L, "SKIPPED_CACHED"),
                disposition(11L, "SKIPPED_PAST_DATE"),
                disposition(12L, "SKIPPED_TRAVEL_DAY"),
                disposition(13L, "SKIPPED_HARD_CONSTRAINT"),
                disposition(14L, "SKIPPED_STABILITY"),
                disposition(15L, "SKIPPED_NO_PROMPT"),
                disposition(16L, "SKIPPED_UNKNOWN_LOCATION"),
                disposition(17L, "SKIPPED_NO_REFRESH_NEEDED"),
                disposition(18L, "SUBMISSION_FAILED"),
                disposition(19L, "EVALUATED"),
                disposition(20L, "FORCE_EVALUATED")));
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID))).thenReturn(List.of());

        Map<Long, CyclePlaceOutcome> outcomes = resolver().resolve(RUN_ID);

        assertThat(outcomes).hasSize(11);
        assertThat(outcomes.values()).containsOnly(CyclePlaceOutcome.notAttempted());
    }

    @Test
    @DisplayName("a stability skip beside failing Claude requests is not a success: the place "
            + "stays FAILED, so its stability-skipped far slots cannot reset it every night")
    void stabilitySkipDoesNotMaskFailingRequests() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID))).thenReturn(List.of(
                disposition(5L, "SKIPPED_STABILITY"), disposition(5L, "EVALUATED")));
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID)))
                .thenReturn(List.of(sky(5L, false)));

        assertThat(resolver().resolve(RUN_ID).get(5L))
                .isEqualTo(CyclePlaceOutcome.failed(FailureKind.EVALUATION));
    }

    @Test
    @DisplayName("a woodland-only place is judged by its woodland-lane result (wl- custom id)")
    void woodlandOnlyPlace_judgedByWoodlandResult() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID)))
                .thenReturn(List.of(disposition(6L, "EVALUATED"), disposition(7L, "EVALUATED")));
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID))).thenReturn(List.of(
                new BatchCallOutcome(
                        CustomIdFactory.forWoodland(6L, DATE, TargetType.SUNRISE), true, null),
                new BatchCallOutcome(
                        CustomIdFactory.forWoodland(7L, DATE, TargetType.SUNRISE), false,
                        "truncation_error")));

        Map<Long, CyclePlaceOutcome> outcomes = resolver().resolve(RUN_ID);

        assertThat(outcomes.get(6L)).isEqualTo(CyclePlaceOutcome.gotThrough());
        assertThat(outcomes.get(7L)).isEqualTo(CyclePlaceOutcome.failed(FailureKind.EVALUATION));
    }

    @Test
    @DisplayName("an open-fell place whose bluebell request failed but whose sky request "
            + "succeeded got through")
    void failedBluebellButScoredSky_gotThrough() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID)))
                .thenReturn(List.of(disposition(8L, "EVALUATED")));
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID))).thenReturn(List.of(
                new BatchCallOutcome(
                        CustomIdFactory.forBluebell(8L, DATE, TargetType.SUNRISE), false,
                        "errored"),
                sky(8L, true)));

        assertThat(resolver().resolve(RUN_ID).get(8L)).isEqualTo(CyclePlaceOutcome.gotThrough());
    }

    @Test
    @DisplayName("a place with both a failure and a success in one cycle is GOT_THROUGH "
            + "whichever order the rows come in")
    void failureAndSuccessInOneCycle_gotThrough() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID))).thenReturn(List.of(
                disposition(9L, "SKIPPED_ERROR"), disposition(9L, "SKIPPED_TRIAGED")));
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID)))
                .thenReturn(List.of(sky(9L, false), sky(9L, true), sky(9L, false)));

        assertThat(resolver().resolve(RUN_ID).get(9L)).isEqualTo(CyclePlaceOutcome.gotThrough());
    }

    @Test
    @DisplayName("a request that failed in the first batch and was recovered by the retry batch "
            + "got through")
    void retryRecovery_gotThrough() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID),
                batch(RETRY_BATCH_ID, BatchType.FORECAST, 9002L));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID, 9002L)))
                .thenReturn(List.of(disposition(21L, "EVALUATED")));
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID, RETRY_BATCH_ID)))
                .thenReturn(List.of(sky(21L, false), sky(21L, true)));

        assertThat(resolver().resolve(RUN_ID).get(21L)).isEqualTo(CyclePlaceOutcome.gotThrough());
    }

    @Test
    @DisplayName("a place both a weather error and a Claude failure is reported as the earlier "
            + "stage's weather-data failure")
    void weatherErrorWinsOverEvaluationFailure() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID)))
                .thenReturn(List.of(disposition(22L, "SKIPPED_ERROR")));
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID)))
                .thenReturn(List.of(sky(22L, false)));

        assertThat(resolver().resolve(RUN_ID).get(22L))
                .isEqualTo(CyclePlaceOutcome.failed(FailureKind.WEATHER_DATA));
    }

    @Test
    @DisplayName("an unparseable or aurora custom id cannot be attributed to a place and is ignored")
    void unattributableCustomIds_ignored() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID))).thenReturn(List.of());
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID))).thenReturn(List.of(
                new BatchCallOutcome("garbage", false, "errored"),
                new BatchCallOutcome(
                        CustomIdFactory.forAurora(AlertLevel.MODERATE, DATE), false, "errored")));

        assertThat(resolver().resolve(RUN_ID)).isEmpty();
    }

    @Test
    @DisplayName("a result row with a null succeeded flag is treated as a failure, never a success")
    void nullSucceeded_isFailure() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID))).thenReturn(List.of());
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID))).thenReturn(List.of(
                new BatchCallOutcome(
                        CustomIdFactory.forForecast(23L, DATE, TargetType.SUNSET), null, null)));

        assertThat(resolver().resolve(RUN_ID).get(23L))
                .isEqualTo(CyclePlaceOutcome.failed(FailureKind.EVALUATION));
    }

    @Test
    @DisplayName("an unknown stored disposition string is no evidence either way")
    void unknownDisposition_notAttempted() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID)))
                .thenReturn(List.of(disposition(24L, "SOMETHING_FROM_A_NEWER_RELEASE")));
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID))).thenReturn(List.of());

        assertThat(resolver().resolve(RUN_ID).get(24L)).isEqualTo(CyclePlaceOutcome.notAttempted());
    }

    @Test
    @DisplayName("a batch with no job run is searched for results but not for dispositions, and "
            + "an AURORA batch is never searched for forecast results at all")
    void batchWithoutJobRunAndAuroraBatch() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, null),
                batch("msgbatch_aurora", BatchType.AURORA, 9003L));
        when(dispositionRepository.findCycleDispositions(List.of(9003L))).thenReturn(List.of());
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID)))
                .thenReturn(List.of(sky(30L, true)));

        assertThat(resolver().resolve(RUN_ID).get(30L)).isEqualTo(CyclePlaceOutcome.gotThrough());
    }

    @Test
    @DisplayName("a run id with nothing tagged to it, which is what a hand-started run has, "
            + "resolves to nothing and queries neither disposition nor result tables")
    void nothingTagged_resolvesToNothing() {
        when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of());

        assertThat(resolver().resolve(RUN_ID)).isEmpty();
        verifyNoInteractions(dispositionRepository);
        verifyNoInteractions(apiCallLogRepository);
    }
}
