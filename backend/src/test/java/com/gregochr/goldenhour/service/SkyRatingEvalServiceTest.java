package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SkyRatingEvalResultEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixture;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixtures;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.SkyRatingEvalResultRepository;
import com.gregochr.goldenhour.repository.SkyRatingEvalRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SkyRatingEvalService} — the persistence + aggregation wiring around the
 * scorer. Real Claude is stubbed: every call returns a controlled rating, so the test verifies the
 * service classifies against each fixture's band, buckets misses, sums cost, and finalises the run
 * correctly. Expected pass/miss counts are derived from {@link SkyRatingEvalFixtures#ALL} itself
 * (the same source of truth), so the assertions don't hardcode band values that could later change.
 */
@ExtendWith(MockitoExtension.class)
class SkyRatingEvalServiceTest {

    private static final int RUNS = 2;
    private static final long COST_PER_CALL = 1_234L;

    @Mock
    private EvaluationService evaluationService;
    @Mock
    private CostCalculator costCalculator;
    @Mock
    private GitInfoService gitInfoService;
    @Mock
    private SkyRatingEvalRunRepository runRepository;
    @Mock
    private SkyRatingEvalResultRepository resultRepository;
    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private SkyRatingEvalService service() {
        return new SkyRatingEvalService(evaluationService, costCalculator, gitInfoService,
                runRepository, resultRepository, dynamicSchedulerService);
    }

    @Test
    void registerJobRegistersTheWeeklyRunnableWithTheScheduler() {
        service().registerJob();
        verify(dynamicSchedulerService).registerJobTarget(eq(SkyRatingEvalService.JOB_KEY), any());
    }

    @Test
    void startRunStampsGitAndPersistsRunningRow() {
        when(gitInfoService.getCommitHash()).thenReturn("abc1234");
        when(gitInfoService.getCommitDate()).thenReturn(LocalDateTime.of(2026, 6, 27, 15, 0));
        when(gitInfoService.isDirty()).thenReturn(false);
        when(gitInfoService.getBranch()).thenReturn("main");
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SkyRatingEvalRunEntity run = service().startRun(
                EvaluationModel.SONNET, SkyRatingEvalTrigger.MANUAL, 8);

        assertThat(run.getStatus()).isEqualTo(SkyRatingEvalStatus.RUNNING);
        assertThat(run.getModel()).isEqualTo(EvaluationModel.SONNET);
        assertThat(run.getTriggerSource()).isEqualTo(SkyRatingEvalTrigger.MANUAL);
        assertThat(run.getRunsPerFixture()).isEqualTo(8);
        assertThat(run.getGitCommitHash()).isEqualTo("abc1234");
        assertThat(run.getGitBranch()).isEqualTo("main");
        assertThat(run.getCompletedAt()).isNull();
    }

    @Test
    void executeRunScoresEveryFixtureNTimesAndBucketsAgainstEachBand() {
        int rating = 3;
        stubScorer(rating);
        SkyRatingEvalRunEntity run = runningRun();
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(run));

        ArgumentCaptor<SkyRatingEvalResultEntity> results =
                ArgumentCaptor.forClass(SkyRatingEvalResultEntity.class);

        service().executeRun(run.getId());

        // one result per (fixture x run index)
        verify(resultRepository, atLeastOnce()).save(results.capture());
        int fixtures = SkyRatingEvalFixtures.ALL.size();
        assertThat(results.getAllValues()).hasSize(fixtures * RUNS);

        // each result's direction matches that fixture's band classification of the rating
        Map<String, SkyRatingEvalFixture> byName = SkyRatingEvalFixtures.ALL.stream()
                .collect(Collectors.toMap(SkyRatingEvalFixture::name, f -> f));
        for (SkyRatingEvalResultEntity r : results.getAllValues()) {
            SkyRatingEvalFixture fixture = byName.get(r.getFixtureName());
            assertThat(r.getMissDirection()).isEqualTo(fixture.band().classify(rating));
            assertThat(r.getExpectedMin()).isEqualTo(fixture.band().min());
            assertThat(r.getExpectedMax()).isEqualTo(fixture.band().max());
            assertThat(r.getRating()).isEqualTo(rating);
            assertThat(r.getFierySky()).isEqualTo(55);
        }

        // run finalised with aggregates consistent with the real fixture bands
        long expectedPasses = SkyRatingEvalFixtures.ALL.stream()
                .filter(f -> f.band().contains(rating)).count() * RUNS;
        assertThat(run.getStatus()).isEqualTo(SkyRatingEvalStatus.COMPLETED);
        assertThat(run.getTotalRuns()).isEqualTo(fixtures * RUNS);
        assertThat(run.getTotalPasses()).isEqualTo((int) expectedPasses);
        assertThat(run.getBelowMisses() + run.getAboveMisses() + run.getTotalPasses())
                .isEqualTo(run.getTotalRuns());
        assertThat(run.getPassRate())
                .isEqualTo((double) expectedPasses / (fixtures * RUNS));
        assertThat(run.getCostMicroDollars()).isEqualTo((long) fixtures * RUNS * COST_PER_CALL);
        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(run.getDurationMs()).isNotNull();
    }

    @Test
    void executeRunMarksRunFailedWhenScorerThrows() {
        when(evaluationService.evaluateWithDetails(any(), eq(EvaluationModel.SONNET), isNull()))
                .thenThrow(new IllegalStateException("API overloaded"));
        SkyRatingEvalRunEntity run = runningRun();
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service().executeRun(run.getId()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(run.getStatus()).isEqualTo(SkyRatingEvalStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("API overloaded");
        assertThat(run.getCompletedAt()).isNotNull();
    }

    @Test
    void executeRunRecordsNullDirectionWhenScorerReturnsNoRating() {
        EvaluationDetail noRating = new EvaluationDetail(
                new SunsetEvaluation(null, 40, 50, "No rating produced."),
                "prompt", "raw", 100L, new TokenUsage(10, 5, 0, 0));
        when(evaluationService.evaluateWithDetails(any(), eq(EvaluationModel.SONNET), isNull()))
                .thenReturn(noRating);
        when(costCalculator.calculateCostMicroDollars(eq(EvaluationModel.SONNET), any()))
                .thenReturn(0L);
        SkyRatingEvalRunEntity run = runningRun();
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(run));

        ArgumentCaptor<SkyRatingEvalResultEntity> results =
                ArgumentCaptor.forClass(SkyRatingEvalResultEntity.class);
        service().executeRun(run.getId());
        verify(resultRepository, atLeastOnce()).save(results.capture());

        assertThat(results.getAllValues()).allSatisfy(r -> {
            assertThat(r.getMissDirection()).isNull();
            assertThat(r.getRating()).isNull();
        });
        assertThat(run.getStatus()).isEqualTo(SkyRatingEvalStatus.COMPLETED);
        assertThat(run.getTotalPasses()).isZero();
        assertThat(run.getPassRate()).isZero();
    }

    private void stubScorer(int rating) {
        EvaluationDetail detail = new EvaluationDetail(
                new SunsetEvaluation(rating, 55, 60, "A controlled summary."),
                "prompt", "raw", 250L, new TokenUsage(3_800, 180, 0, 0));
        when(evaluationService.evaluateWithDetails(any(AtmosphericData.class),
                eq(EvaluationModel.SONNET), isNull())).thenReturn(detail);
        when(costCalculator.calculateCostMicroDollars(eq(EvaluationModel.SONNET), any()))
                .thenReturn(COST_PER_CALL);
    }

    private SkyRatingEvalRunEntity runningRun() {
        return SkyRatingEvalRunEntity.builder()
                .id(42L)
                .runTimestamp(LocalDateTime.of(2026, 6, 27, 3, 0))
                .startedAt(LocalDateTime.of(2026, 6, 27, 3, 0))
                .model(EvaluationModel.SONNET)
                .runsPerFixture(RUNS)
                .triggerSource(SkyRatingEvalTrigger.SCHEDULED)
                .status(SkyRatingEvalStatus.RUNNING)
                .build();
    }
}
