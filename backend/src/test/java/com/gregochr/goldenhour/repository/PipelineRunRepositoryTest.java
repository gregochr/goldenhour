package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice test for {@link PipelineRunRepository#findTriggerTimesAfter} — the round-14
 * primitive {@code SupersedingDispositionService} builds on. Runs on H2 (schema generated from
 * entity annotations via {@code ddl-auto: create-drop}, no Flyway, no Docker) per this project's
 * {@code @DataJpaTest} pattern (see {@code ForecastScoreRepositoryTest}).
 */
@DataJpaTest
class PipelineRunRepositoryTest {

    @Autowired
    private PipelineRunRepository repository;

    @Autowired
    private org.springframework.boot.jpa.test.autoconfigure.TestEntityManager entityManager;

    private static PipelineRunEntity run(CycleType type, Instant triggerTime) {
        return new PipelineRunEntity(type, triggerTime);
    }

    @Test
    @DisplayName("returns every trigger time strictly after the threshold, ascending")
    void returnsLaterTriggerTimesAscending() {
        Instant threshold = Instant.parse("2026-09-29T01:05:00Z");
        Instant intraday = Instant.parse("2026-09-29T14:00:00Z");
        Instant nextNightly = Instant.parse("2026-09-30T01:05:00Z");
        repository.save(run(CycleType.NIGHTLY, threshold));
        repository.save(run(CycleType.INTRADAY, intraday));
        repository.save(run(CycleType.NIGHTLY, nextNightly));

        List<Instant> result = repository.findTriggerTimesAfter(threshold);

        assertThat(result).containsExactly(intraday, nextNightly);
    }

    @Test
    @DisplayName("the threshold instant itself is excluded — strictly after, not at or after")
    void excludesTheThresholdItself() {
        Instant threshold = Instant.parse("2026-09-29T14:00:00Z");
        repository.save(run(CycleType.INTRADAY, threshold));

        List<Instant> result = repository.findTriggerTimesAfter(threshold);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("no pipeline run has been triggered since the threshold: empty list — the common, "
            + "cheap-path case a delayed result almost never lands in")
    void noLaterRun_returnsEmpty() {
        Instant threshold = Instant.parse("2026-09-29T14:00:00Z");
        repository.save(run(CycleType.NIGHTLY, Instant.parse("2026-09-29T01:05:00Z")));

        List<Instant> result = repository.findTriggerTimesAfter(threshold);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("a run's own disposition-only anchor path does not change this: the row in "
            + "pipeline_run exists regardless of whether any forecast_batch row was ever created "
            + "for it — this query never touches forecast_batch at all")
    void answersFromPipelineRunAlone_noBatchRowNeeded() {
        // Simulates pipeline run 249 (2026-09-29, all three Anthropic batch submissions failed):
        // the PipelineRunEntity row exists exactly as it would for any other cycle.
        Instant threshold = Instant.parse("2026-09-29T01:05:00Z");
        Instant anchorRunTrigger = Instant.parse("2026-09-29T14:00:00Z");
        repository.save(run(CycleType.INTRADAY, anchorRunTrigger));

        List<Instant> result = repository.findTriggerTimesAfter(threshold);

        assertThat(result).containsExactly(anchorRunTrigger);
    }

    @Test
    @DisplayName("claimFailureSettle is atomic and one-shot: the first claim updates the row, every "
            + "later claim updates nothing and leaves the first instant in place")
    void claimFailureSettle_isOneShot() {
        PipelineRunEntity saved = repository.save(
                run(CycleType.NIGHTLY, Instant.parse("2026-10-02T01:00:00Z")));
        Instant first = Instant.parse("2026-10-02T01:30:00Z");

        int firstClaim = repository.claimFailureSettle(saved.getId(), first);
        int secondClaim = repository.claimFailureSettle(
                saved.getId(), Instant.parse("2026-10-02T02:00:00Z"));
        entityManager.flush();
        entityManager.clear();

        assertThat(firstClaim).isEqualTo(1);
        assertThat(secondClaim).isZero();
        assertThat(repository.findById(saved.getId()).orElseThrow().getFailuresSettledAt())
                .isEqualTo(first);
    }

    @Test
    @DisplayName("findNewestSettledTriggerTime is the maximum trigger over SETTLED runs only, and "
            + "null when none is settled")
    void findNewestSettledTriggerTime_overSettledRunsOnly() {
        PipelineRunEntity older = repository.save(
                run(CycleType.NIGHTLY, Instant.parse("2026-10-01T01:00:00Z")));
        PipelineRunEntity newer = repository.save(
                run(CycleType.INTRADAY, Instant.parse("2026-10-02T14:00:00Z")));
        repository.save(run(CycleType.NIGHTLY, Instant.parse("2026-10-03T01:00:00Z")));
        assertThat(repository.findNewestSettledTriggerTime()).isNull();

        repository.claimFailureSettle(older.getId(), Instant.parse("2026-10-01T02:00:00Z"));
        assertThat(repository.findNewestSettledTriggerTime())
                .isEqualTo(Instant.parse("2026-10-01T01:00:00Z"));
        repository.claimFailureSettle(newer.getId(), Instant.parse("2026-10-02T15:00:00Z"));

        assertThat(repository.findNewestSettledTriggerTime())
                .isEqualTo(Instant.parse("2026-10-02T14:00:00Z"));
    }

    @Test
    @DisplayName("findUnsettledSince returns only runs with no claim, at or after the bound, not in "
            + "the excluded status, oldest trigger first")
    void findUnsettledSince_filtersAndOrders() {
        Instant since = Instant.parse("2026-09-26T00:00:00Z");
        PipelineRunEntity tooOld = repository.save(
                run(CycleType.NIGHTLY, Instant.parse("2026-09-25T23:59:59Z")));
        tooOld.setStatus(PipelineRunStatus.COMPLETED);
        PipelineRunEntity atBound = repository.save(run(CycleType.NIGHTLY, since));
        atBound.setStatus(PipelineRunStatus.FAILED);
        PipelineRunEntity later = repository.save(
                run(CycleType.INTRADAY, Instant.parse("2026-10-02T14:00:00Z")));
        later.setStatus(PipelineRunStatus.DEGRADED);
        PipelineRunEntity claimed = repository.save(
                run(CycleType.NIGHTLY, Instant.parse("2026-10-01T01:00:00Z")));
        claimed.setStatus(PipelineRunStatus.COMPLETED);
        PipelineRunEntity stillRunning = repository.save(
                run(CycleType.NIGHTLY, Instant.parse("2026-10-02T01:00:00Z")));
        entityManager.flush();
        repository.claimFailureSettle(claimed.getId(), Instant.parse("2026-10-01T02:00:00Z"));
        entityManager.clear();

        List<PipelineRunEntity> found = repository.findUnsettledSince(since, PipelineRunStatus.RUNNING);

        assertThat(found).extracting(PipelineRunEntity::getId)
                .containsExactly(atBound.getId(), later.getId());
        assertThat(stillRunning.getStatus()).isEqualTo(PipelineRunStatus.RUNNING);
    }

    @Test
    @DisplayName("recordDispositionJobRun stores the link and findDispositionJobRunId reads it back "
            + "from the database, null when none was recorded")
    void dispositionJobRun_recordedAndReadBack() {
        PipelineRunEntity saved = repository.save(
                run(CycleType.NIGHTLY, Instant.parse("2026-10-02T01:00:00Z")));
        assertThat(repository.findDispositionJobRunId(saved.getId())).isEmpty();

        int rows = repository.recordDispositionJobRun(saved.getId(), 555L);

        assertThat(rows).isEqualTo(1);
        assertThat(repository.findDispositionJobRunId(saved.getId())).contains(555L);
    }

    @Test
    @DisplayName("the two settle columns are updatable = false: saving a stale loaded copy of the "
            + "run cannot clear a claim or a link written by the scoped updates")
    void settleColumns_notOverwrittenByAWholeEntitySave() {
        PipelineRunEntity saved = repository.save(
                run(CycleType.NIGHTLY, Instant.parse("2026-10-02T01:00:00Z")));
        entityManager.flush();
        entityManager.clear();
        PipelineRunEntity stale = repository.findById(saved.getId()).orElseThrow();
        Instant claimedAt = Instant.parse("2026-10-02T01:30:00Z");
        repository.claimFailureSettle(saved.getId(), claimedAt);
        repository.recordDispositionJobRun(saved.getId(), 555L);

        stale.setWaitingOn("something else");
        repository.saveAndFlush(stale);
        entityManager.clear();

        PipelineRunEntity found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getWaitingOn()).isEqualTo("something else");
        assertThat(found.getFailuresSettledAt()).isEqualTo(claimedAt);
        assertThat(found.getDispositionJobRunId()).isEqualTo(555L);
    }
}
