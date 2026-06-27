package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SkyRatingEvalResultEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.eval.MissDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests for the sky-rating eval tables. Validates the entity↔column mapping
 * (including the {@link MissDirection} enum and the {@code TEXT} summary) and the derived queries
 * the admin view relies on.
 */
@DataJpaTest
class SkyRatingEvalRepositoryTest {

    @Autowired
    private SkyRatingEvalRunRepository runRepository;

    @Autowired
    private SkyRatingEvalResultRepository resultRepository;

    @Test
    @DisplayName("a run round-trips with all aggregate, cost, and git fields")
    void runRoundTrips() {
        SkyRatingEvalRunEntity saved = runRepository.save(buildRun(
                LocalDateTime.of(2026, 6, 27, 3, 0), SkyRatingEvalStatus.COMPLETED));

        SkyRatingEvalRunEntity found = runRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getModel()).isEqualTo(EvaluationModel.SONNET);
        assertThat(found.getTriggerSource()).isEqualTo(SkyRatingEvalTrigger.SCHEDULED);
        assertThat(found.getRunsPerFixture()).isEqualTo(8);
        assertThat(found.getPassRate()).isEqualTo(1.0);
        assertThat(found.getCostMicroDollars()).isEqualTo(300_000L);
        assertThat(found.getGitCommitHash()).isEqualTo("abc1234");
    }

    @Test
    @DisplayName("findTop100ByOrderByRunTimestampDesc returns newest run first")
    void recentRunsNewestFirst() {
        runRepository.save(buildRun(LocalDateTime.of(2026, 6, 20, 3, 0), SkyRatingEvalStatus.COMPLETED));
        runRepository.save(buildRun(LocalDateTime.of(2026, 6, 27, 3, 0), SkyRatingEvalStatus.COMPLETED));
        runRepository.save(buildRun(LocalDateTime.of(2026, 6, 13, 3, 0), SkyRatingEvalStatus.COMPLETED));

        List<SkyRatingEvalRunEntity> recent = runRepository.findTop100ByOrderByRunTimestampDesc();

        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).getRunTimestamp()).isEqualTo(LocalDateTime.of(2026, 6, 27, 3, 0));
        assertThat(recent.get(2).getRunTimestamp()).isEqualTo(LocalDateTime.of(2026, 6, 13, 3, 0));
    }

    @Test
    @DisplayName("findByStatus filters and orders oldest-first for the trend series")
    void trendSeriesExcludesNonCompletedRuns() {
        runRepository.save(buildRun(LocalDateTime.of(2026, 6, 20, 3, 0), SkyRatingEvalStatus.COMPLETED));
        runRepository.save(buildRun(LocalDateTime.of(2026, 6, 27, 3, 0), SkyRatingEvalStatus.FAILED));

        List<SkyRatingEvalRunEntity> completed =
                runRepository.findByStatusOrderByRunTimestampAsc(SkyRatingEvalStatus.COMPLETED);

        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).getRunTimestamp()).isEqualTo(LocalDateTime.of(2026, 6, 20, 3, 0));
    }

    @Test
    @DisplayName("results round-trip with sub-scores, band, miss direction and are fetched by run")
    void resultsRoundTripAndFetchByRun() {
        SkyRatingEvalRunEntity run = runRepository.save(buildRun(
                LocalDateTime.of(2026, 6, 27, 3, 0), SkyRatingEvalStatus.COMPLETED));

        resultRepository.save(buildResult(run.getId(), "strong-clearing-canvas", 1, 4,
                MissDirection.IN_BAND));
        resultRepository.save(buildResult(run.getId(), "flat-grey-overcast", 1, 1,
                MissDirection.IN_BAND));
        resultRepository.save(buildResult(run.getId(), "strong-clearing-canvas", 2, 3,
                MissDirection.BELOW));

        List<SkyRatingEvalResultEntity> results =
                resultRepository.findByRunIdOrderByFixtureNameAscRunIndexAsc(run.getId());

        assertThat(results).hasSize(3);
        assertThat(results.get(0).getFixtureName()).isEqualTo("flat-grey-overcast");
        assertThat(results.get(1).getFixtureName()).isEqualTo("strong-clearing-canvas");
        assertThat(results.get(1).getRunIndex()).isEqualTo(1);
        assertThat(results.get(2).getMissDirection()).isEqualTo(MissDirection.BELOW);
        assertThat(results.get(1).getFierySky()).isEqualTo(70);
        assertThat(results.get(1).getExpectedMin()).isEqualTo(4);
    }

    @Test
    @DisplayName("findByRunIdIn fetches results across multiple runs in one query")
    void fetchResultsAcrossRuns() {
        SkyRatingEvalRunEntity runA = runRepository.save(buildRun(
                LocalDateTime.of(2026, 6, 20, 3, 0), SkyRatingEvalStatus.COMPLETED));
        SkyRatingEvalRunEntity runB = runRepository.save(buildRun(
                LocalDateTime.of(2026, 6, 27, 3, 0), SkyRatingEvalStatus.COMPLETED));
        resultRepository.save(buildResult(runA.getId(), "strong-clearing-canvas", 1, 4,
                MissDirection.IN_BAND));
        resultRepository.save(buildResult(runB.getId(), "strong-clearing-canvas", 1, 5,
                MissDirection.IN_BAND));

        List<SkyRatingEvalResultEntity> results =
                resultRepository.findByRunIdIn(List.of(runA.getId(), runB.getId()));

        assertThat(results).hasSize(2);
    }

    private static SkyRatingEvalRunEntity buildRun(LocalDateTime timestamp, SkyRatingEvalStatus status) {
        return SkyRatingEvalRunEntity.builder()
                .runTimestamp(timestamp)
                .startedAt(timestamp)
                .completedAt(timestamp.plusMinutes(5))
                .durationMs(300_000L)
                .model(EvaluationModel.SONNET)
                .runsPerFixture(8)
                .fixtureCount(6)
                .totalRuns(48)
                .totalPasses(48)
                .passRate(1.0)
                .inputTokens(180_000L)
                .outputTokens(9_000L)
                .costMicroDollars(300_000L)
                .triggerSource(SkyRatingEvalTrigger.SCHEDULED)
                .status(status)
                .gitCommitHash("abc1234")
                .gitBranch("main")
                .gitDirty(false)
                .build();
    }

    private static SkyRatingEvalResultEntity buildResult(Long runId, String fixture, int runIndex,
            int rating, MissDirection direction) {
        return SkyRatingEvalResultEntity.builder()
                .runId(runId)
                .fixtureName(fixture)
                .runIndex(runIndex)
                .rating(rating)
                .fierySky(70)
                .goldenHour(72)
                .expectedMin(4)
                .expectedMax(5)
                .missDirection(direction)
                .summary("A textbook clearance with a surviving canvas.")
                .inputTokens(3_800L)
                .outputTokens(180L)
                .durationMs(2_500L)
                .build();
    }
}
