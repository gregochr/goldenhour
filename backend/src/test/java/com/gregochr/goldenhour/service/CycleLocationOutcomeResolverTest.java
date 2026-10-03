package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BatchCallOutcome;
import com.gregochr.goldenhour.model.CycleDisposition;
import com.gregochr.goldenhour.model.CyclePlaceEvidence;
import com.gregochr.goldenhour.model.CyclePlaceEvidence.Lane;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CycleLocationOutcomeResolver}: a synthetic cycle's recorded dispositions and
 * batch results, reduced to per-lane evidence for each place.
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

    private void firstBatchWith(List<CycleDisposition> dispositions,
            List<BatchCallOutcome> results) {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID)))
                .thenReturn(dispositions);
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID))).thenReturn(results);
    }

    private static CycleDisposition disposition(long locationId, String category) {
        return new CycleDisposition(locationId, category);
    }

    private static BatchCallOutcome sky(long locationId, boolean succeeded) {
        return new BatchCallOutcome(
                CustomIdFactory.forForecast(locationId, DATE, TargetType.SUNSET), succeeded,
                succeeded ? null : "errored");
    }

    private static BatchCallOutcome woodland(long locationId, boolean succeeded) {
        return new BatchCallOutcome(
                CustomIdFactory.forWoodland(locationId, DATE, TargetType.SUNRISE), succeeded,
                succeeded ? null : "errored");
    }

    private static BatchCallOutcome bluebell(long locationId, boolean succeeded) {
        return new BatchCallOutcome(
                CustomIdFactory.forBluebell(locationId, DATE, TargetType.SUNRISE), succeeded,
                succeeded ? null : "errored");
    }

    @Test
    @DisplayName("a scored sky result puts the place in the sky success lane and it got through")
    void scoredSkyResult_succeededInSky() {
        firstBatchWith(List.of(disposition(1L, "EVALUATED")), List.of(sky(1L, true)));

        Map<Long, CyclePlaceEvidence> evidence = resolver().resolve(RUN_ID);

        assertThat(evidence).containsOnlyKeys(1L);
        assertThat(evidence.get(1L)).isEqualTo(CyclePlaceEvidence.scoredIn(Lane.SKY));
        assertThat(evidence.get(1L).gotThrough()).isTrue();
    }

    @Test
    @DisplayName("a triaged place got through, as triage-only evidence with no Claude result lane")
    void triaged_triageOnly() {
        firstBatchWith(List.of(disposition(2L, "SKIPPED_TRIAGED")), List.of());

        CyclePlaceEvidence evidence = resolver().resolve(RUN_ID).get(2L);

        assertThat(evidence).isEqualTo(CyclePlaceEvidence.triagedOnly());
        assertThat(evidence.gotThrough()).isTrue();
        assertThat(evidence.hasResultIn(Lane.SKY)).isFalse();
    }

    @Test
    @DisplayName("a collection error (SKIPPED_ERROR) is a collection failure")
    void collectionError_collectionFailure() {
        firstBatchWith(List.of(disposition(3L, "SKIPPED_ERROR")), List.of());

        CyclePlaceEvidence evidence = resolver().resolve(RUN_ID).get(3L);

        assertThat(evidence).isEqualTo(CyclePlaceEvidence.collectionError());
        assertThat(evidence.failed()).isTrue();
    }

    @Test
    @DisplayName("an errored sky result with no success is a failure in the sky lane")
    void erroredSkyResult_failedInSky() {
        firstBatchWith(List.of(disposition(4L, "EVALUATED")), List.of(sky(4L, false)));

        CyclePlaceEvidence evidence = resolver().resolve(RUN_ID).get(4L);

        assertThat(evidence).isEqualTo(CyclePlaceEvidence.failedIn(Lane.SKY));
        assertThat(evidence.failed()).isTrue();
    }

    @Test
    @DisplayName("every category that means nothing was sent or judged leaves no evidence either way")
    void skippedCategories_nothing() {
        firstBatchWith(List.of(
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
                disposition(20L, "FORCE_EVALUATED")), List.of());

        Map<Long, CyclePlaceEvidence> evidence = resolver().resolve(RUN_ID);

        assertThat(evidence).hasSize(11);
        assertThat(evidence.values()).containsOnly(CyclePlaceEvidence.nothing());
        assertThat(evidence.values()).noneMatch(CyclePlaceEvidence::attempted);
    }

    @Test
    @DisplayName("a stability skip beside failing Claude requests is not a success: the place "
            + "stays failed, so its stability-skipped far slots cannot reset it every night")
    void stabilitySkipDoesNotMaskFailingRequests() {
        firstBatchWith(
                List.of(disposition(5L, "SKIPPED_STABILITY"), disposition(5L, "EVALUATED")),
                List.of(sky(5L, false)));

        assertThat(resolver().resolve(RUN_ID).get(5L)).isEqualTo(CyclePlaceEvidence.failedIn(Lane.SKY));
    }

    @Test
    @DisplayName("a woodland-only place is judged by its woodland-lane result (wd- custom id)")
    void woodlandOnlyPlace_judgedByWoodlandLane() {
        firstBatchWith(
                List.of(disposition(6L, "EVALUATED"), disposition(7L, "EVALUATED")),
                List.of(woodland(6L, true), woodland(7L, false)));

        Map<Long, CyclePlaceEvidence> evidence = resolver().resolve(RUN_ID);

        assertThat(evidence.get(6L)).isEqualTo(CyclePlaceEvidence.scoredIn(Lane.WOODLAND));
        assertThat(evidence.get(7L)).isEqualTo(CyclePlaceEvidence.failedIn(Lane.WOODLAND));
    }

    @Test
    @DisplayName("an open-fell place whose bluebell request failed but whose sky request "
            + "succeeded keeps both facts per lane, and got through")
    void failedBluebellButScoredSky_keepsPerLaneFacts() {
        firstBatchWith(List.of(disposition(8L, "EVALUATED")),
                List.of(bluebell(8L, false), sky(8L, true)));

        CyclePlaceEvidence evidence = resolver().resolve(RUN_ID).get(8L);

        assertThat(evidence.succeededLanes()).isEqualTo(Set.of(Lane.SKY));
        assertThat(evidence.failedLanes()).isEqualTo(Set.of(Lane.BLUEBELL));
        assertThat(evidence.gotThrough()).isTrue();
        assertThat(evidence.failed()).isFalse();
    }

    @Test
    @DisplayName("a place with both a failure and a success in one cycle got through, whichever "
            + "order the rows come in")
    void failureAndSuccessInOneCycle_gotThrough() {
        firstBatchWith(
                List.of(disposition(9L, "SKIPPED_ERROR"), disposition(9L, "SKIPPED_TRIAGED")),
                List.of(sky(9L, false), sky(9L, true), sky(9L, false)));

        CyclePlaceEvidence evidence = resolver().resolve(RUN_ID).get(9L);

        assertThat(evidence.gotThrough()).isTrue();
        assertThat(evidence.failed()).isFalse();
    }

    @Test
    @DisplayName("a request that failed in the first batch and was recovered by the retry batch "
            + "has a success in the sky lane")
    void retryRecovery_gotThrough() {
        cycleWithBatches(batch(BATCH_ID, BatchType.FORECAST, JOB_RUN_ID),
                batch(RETRY_BATCH_ID, BatchType.FORECAST, 9002L));
        when(dispositionRepository.findCycleDispositions(List.of(JOB_RUN_ID, 9002L)))
                .thenReturn(List.of(disposition(21L, "EVALUATED")));
        when(apiCallLogRepository.findBatchCallOutcomes(List.of(BATCH_ID, RETRY_BATCH_ID)))
                .thenReturn(List.of(sky(21L, false), sky(21L, true)));

        assertThat(resolver().resolve(RUN_ID).get(21L).gotThrough()).isTrue();
    }

    @Test
    @DisplayName("a slot the retry triaged away (a SKIPPED_TRIAGED disposition written on retry) "
            + "outweighs the precursor's failed result row: the place got through")
    void retryTriage_outweighsPrecursorFailure() {
        firstBatchWith(
                List.of(disposition(22L, "EVALUATED"), disposition(22L, "SKIPPED_TRIAGED")),
                List.of(sky(22L, false)));

        assertThat(resolver().resolve(RUN_ID).get(22L).gotThrough()).isTrue();
    }

    @Test
    @DisplayName("collection error and a failed sky result both survive in one place's evidence")
    void collectionErrorAndSkyFailure_bothKept() {
        firstBatchWith(List.of(disposition(23L, "SKIPPED_ERROR")), List.of(sky(23L, false)));

        CyclePlaceEvidence evidence = resolver().resolve(RUN_ID).get(23L);

        assertThat(evidence.collectionFailed()).isTrue();
        assertThat(evidence.failedLanes()).isEqualTo(Set.of(Lane.SKY));
    }

    @Test
    @DisplayName("production custom ids carry suffixes (-r{evalRowId} on sky, -f when forced): "
            + "both still resolve to their place and lane")
    void suffixedProductionCustomIds_resolve() {
        firstBatchWith(List.of(), List.of(
                new BatchCallOutcome(
                        CustomIdFactory.forForecast(40L, DATE, TargetType.SUNSET, 9876L, true),
                        true, null),
                new BatchCallOutcome(
                        CustomIdFactory.forWoodland(41L, DATE, TargetType.SUNRISE, true),
                        false, "errored"),
                new BatchCallOutcome(
                        CustomIdFactory.forBluebell(42L, DATE, TargetType.SUNRISE, true),
                        true, null)));

        Map<Long, CyclePlaceEvidence> evidence = resolver().resolve(RUN_ID);

        assertThat(evidence.get(40L)).isEqualTo(CyclePlaceEvidence.scoredIn(Lane.SKY));
        assertThat(evidence.get(41L)).isEqualTo(CyclePlaceEvidence.failedIn(Lane.WOODLAND));
        assertThat(evidence.get(42L)).isEqualTo(CyclePlaceEvidence.scoredIn(Lane.BLUEBELL));
    }

    @Test
    @DisplayName("an unparseable or aurora custom id cannot be attributed to a place and is ignored")
    void unattributableCustomIds_ignored() {
        firstBatchWith(List.of(), List.of(
                new BatchCallOutcome("garbage", false, "errored"),
                new BatchCallOutcome(
                        CustomIdFactory.forAurora(AlertLevel.MODERATE, DATE), false, "errored")));

        assertThat(resolver().resolve(RUN_ID)).isEmpty();
    }

    @Test
    @DisplayName("a result row with a null succeeded flag is treated as a failure, never a success")
    void nullSucceeded_isFailure() {
        firstBatchWith(List.of(), List.of(new BatchCallOutcome(
                CustomIdFactory.forForecast(23L, DATE, TargetType.SUNSET), null, null)));

        assertThat(resolver().resolve(RUN_ID).get(23L)).isEqualTo(CyclePlaceEvidence.failedIn(Lane.SKY));
    }

    @Test
    @DisplayName("an unknown stored disposition string is no evidence either way")
    void unknownDisposition_nothing() {
        firstBatchWith(List.of(disposition(24L, "SOMETHING_FROM_A_NEWER_RELEASE")), List.of());

        assertThat(resolver().resolve(RUN_ID).get(24L)).isEqualTo(CyclePlaceEvidence.nothing());
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

        assertThat(resolver().resolve(RUN_ID).get(30L)).isEqualTo(CyclePlaceEvidence.scoredIn(Lane.SKY));
    }

    @Test
    @DisplayName("the 2026-09-29 shape (every submission failed: 589 dispositions sit on a "
            + "disposition-only anchor job run that has no forecast_batch row) resolves to nothing "
            + "and counts nobody")
    void noForecastBatchRow_resolvesToNothing() {
        when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of());

        assertThat(resolver().resolve(RUN_ID)).isEmpty();
        verifyNoInteractions(dispositionRepository);
        verifyNoInteractions(apiCallLogRepository);
    }
}
