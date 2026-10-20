package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
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
    @DisplayName("runs of one cycle type at or after an instant come back oldest first, the instant "
            + "included, the other cycle type and earlier runs left out")
    void findsRunsOfACycleTypeAtOrAfter() {
        Instant since = Instant.parse("2026-10-13T00:00:00Z");
        repository.save(run(CycleType.NIGHTLY, since.minusSeconds(1)));
        PipelineRunEntity atBoundary = repository.save(run(CycleType.NIGHTLY, since));
        PipelineRunEntity later = repository.save(run(CycleType.NIGHTLY, since.plusSeconds(3600)));
        repository.save(run(CycleType.INTRADAY, since.plusSeconds(60)));

        List<PipelineRunEntity> result = repository
                .findByCycleTypeAndTriggerTimeGreaterThanEqualOrderByTriggerTimeAsc(
                        CycleType.NIGHTLY, since);

        assertThat(result).extracting(PipelineRunEntity::getId)
                .containsExactly(atBoundary.getId(), later.getId());
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

    private static final java.util.List<CycleType> COUNTED =
            java.util.List.of(CycleType.NIGHTLY, CycleType.INTRADAY);

    @Test
    @DisplayName("findNewestSettledTriggerTime ignores a settled cycle of a type outside the list")
    void findNewestSettledTriggerTime_ignoresUnlistedTypes() {
        PipelineRunEntity counted = repository.save(
                run(CycleType.NIGHTLY, Instant.parse("2026-10-01T01:00:00Z")));
        PipelineRunEntity evening = repository.save(
                run(CycleType.EVENING, Instant.parse("2026-10-01T20:00:00Z")));
        repository.claimFailureSettle(counted.getId(), Instant.parse("2026-10-01T02:00:00Z"));
        repository.claimFailureSettle(evening.getId(), Instant.parse("2026-10-01T21:00:00Z"));

        assertThat(repository.findNewestSettledTriggerTime(COUNTED))
                .isEqualTo(Instant.parse("2026-10-01T01:00:00Z"));
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
        assertThat(repository.findNewestSettledTriggerTime(COUNTED)).isNull();

        repository.claimFailureSettle(older.getId(), Instant.parse("2026-10-01T02:00:00Z"));
        assertThat(repository.findNewestSettledTriggerTime(COUNTED))
                .isEqualTo(Instant.parse("2026-10-01T01:00:00Z"));
        repository.claimFailureSettle(newer.getId(), Instant.parse("2026-10-02T15:00:00Z"));

        assertThat(repository.findNewestSettledTriggerTime(COUNTED))
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

    // ── endedDispositionJobRunIds: has a slot's cycle ended? ──────────────────

    private PipelineRunEntity cycle(long jobRunId, PipelineRunStatus status, String instant) {
        PipelineRunEntity saved = repository.save(run(CycleType.NIGHTLY, Instant.parse(instant)));
        saved.setStatus(status);
        repository.saveAndFlush(saved);
        repository.recordDispositionJobRun(saved.getId(), jobRunId);
        return saved;
    }

    private void batch(PipelineRunEntity run, String anthropicId,
            ForecastBatchEntity.BatchType type, ForecastBatchEntity.BatchStatus status) {
        ForecastBatchEntity b = new ForecastBatchEntity(anthropicId, type, 3,
                Instant.parse("2026-10-03T00:00:00Z"));
        b.setPipelineRunId(run.getId());
        b.setStatus(status);
        entityManager.persistAndFlush(b);
    }

    @Test
    @DisplayName("a finished cycle with every FORECAST batch terminal is ended; a RUNNING cycle, "
            + "one with a batch still SUBMITTED, and an id with no pipeline run are not")
    void endedCycles_followTheLocationFailureRule() {
        PipelineRunEntity finished = cycle(101L, PipelineRunStatus.COMPLETED,
                "2026-10-02T01:00:00Z");
        batch(finished, "b-completed", ForecastBatchEntity.BatchType.FORECAST,
                ForecastBatchEntity.BatchStatus.COMPLETED);
        batch(finished, "b-failed", ForecastBatchEntity.BatchType.FORECAST,
                ForecastBatchEntity.BatchStatus.FAILED);
        batch(finished, "b-expired", ForecastBatchEntity.BatchType.FORECAST,
                ForecastBatchEntity.BatchStatus.EXPIRED);
        cycle(102L, PipelineRunStatus.RUNNING, "2026-10-02T02:00:00Z");
        PipelineRunEntity polling = cycle(103L, PipelineRunStatus.COMPLETED,
                "2026-10-02T03:00:00Z");
        batch(polling, "b-done", ForecastBatchEntity.BatchType.FORECAST,
                ForecastBatchEntity.BatchStatus.COMPLETED);
        batch(polling, "b-retry-polling", ForecastBatchEntity.BatchType.FORECAST,
                ForecastBatchEntity.BatchStatus.SUBMITTED);
        cycle(104L, PipelineRunStatus.FAILED, "2026-10-02T04:00:00Z");
        entityManager.clear();

        List<Long> ended = repository.endedDispositionJobRunIds(
                List.of(101L, 102L, 103L, 104L, 999L));

        assertThat(ended).containsExactlyInAnyOrder(101L, 104L);
    }

    @Test
    @DisplayName("an AURORA batch still polling does not hold a cycle open")
    void auroraBatchDoesNotBlock() {
        PipelineRunEntity run = cycle(201L, PipelineRunStatus.COMPLETED, "2026-10-02T01:00:00Z");
        batch(run, "b-aurora", ForecastBatchEntity.BatchType.AURORA,
                ForecastBatchEntity.BatchStatus.SUBMITTED);
        entityManager.clear();

        assertThat(repository.endedDispositionJobRunIds(List.of(201L))).containsExactly(201L);
    }

    // ---- countRunsPastSubmission: the solar catch-up's "this cycle already ran" ----

    private static final Instant FIRE = Instant.parse("2026-10-13T02:05:00Z");

    private long pastSubmission(CycleType type) {
        return repository.countRunsPastSubmission(type, FIRE,
                com.gregochr.goldenhour.entity.PipelinePhase.PRE_SUBMISSION);
    }

    private void savedRun(CycleType type, Instant trigger, PipelineRunStatus status,
            com.gregochr.goldenhour.entity.PipelinePhase phase) {
        PipelineRunEntity r = run(type, trigger);
        r.setStatus(status);
        r.setCurrentPhase(phase);
        repository.save(r);
    }

    @Test
    @DisplayName("a restart-killed run (FAILED in FORECAST_BATCH_SUBMIT) does not count as having run")
    void failedInSubmission_doesNotCount() {
        savedRun(CycleType.NIGHTLY, FIRE, PipelineRunStatus.FAILED,
                com.gregochr.goldenhour.entity.PipelinePhase.FORECAST_BATCH_SUBMIT);

        assertThat(pastSubmission(CycleType.NIGHTLY)).isZero();
    }

    @Test
    @DisplayName("the same run still RUNNING in a pre-submission phase (either cycle's) does not "
            + "count, nor does one with no phase")
    void runningPreSubmission_doesNotCount() {
        savedRun(CycleType.NIGHTLY, FIRE, PipelineRunStatus.RUNNING,
                com.gregochr.goldenhour.entity.PipelinePhase.FORECAST_BATCH_SUBMIT);
        savedRun(CycleType.INTRADAY, FIRE, PipelineRunStatus.RUNNING,
                com.gregochr.goldenhour.entity.PipelinePhase.STABILITY_RECLASSIFY);
        savedRun(CycleType.INTRADAY, FIRE, PipelineRunStatus.RUNNING, null);
        savedRun(CycleType.NIGHTLY, FIRE, PipelineRunStatus.FAILED, null);

        assertThat(pastSubmission(CycleType.NIGHTLY)).isZero();
        assertThat(pastSubmission(CycleType.INTRADAY)).isZero();
    }

    @Test
    @DisplayName("a run RUNNING in WAIT, RETRY_FAILED or BRIEFING counts")
    void runningPastSubmission_counts() {
        for (com.gregochr.goldenhour.entity.PipelinePhase phase : List.of(
                com.gregochr.goldenhour.entity.PipelinePhase.FORECAST_BATCH_WAIT,
                com.gregochr.goldenhour.entity.PipelinePhase.RETRY_FAILED,
                com.gregochr.goldenhour.entity.PipelinePhase.BRIEFING)) {
            repository.deleteAll();
            savedRun(CycleType.NIGHTLY, FIRE, PipelineRunStatus.RUNNING, phase);

            assertThat(pastSubmission(CycleType.NIGHTLY)).as(phase.name()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a run FAILED from WAIT (the safety timeout) still counts: its batches exist")
    void failedFromWait_counts() {
        savedRun(CycleType.NIGHTLY, FIRE, PipelineRunStatus.FAILED,
                com.gregochr.goldenhour.entity.PipelinePhase.FORECAST_BATCH_WAIT);

        assertThat(pastSubmission(CycleType.NIGHTLY)).isEqualTo(1);
    }

    @Test
    @DisplayName("a COMPLETED or DEGRADED run counts (completion clears the phase)")
    void completedAndDegraded_count() {
        savedRun(CycleType.NIGHTLY, FIRE, PipelineRunStatus.COMPLETED, null);
        assertThat(pastSubmission(CycleType.NIGHTLY)).isEqualTo(1);

        repository.deleteAll();
        savedRun(CycleType.NIGHTLY, FIRE, PipelineRunStatus.DEGRADED, null);
        assertThat(pastSubmission(CycleType.NIGHTLY)).isEqualTo(1);
    }

    @Test
    @DisplayName("only runs of the cycle type at or after the fire are considered")
    void filtersByCycleAndTime() {
        savedRun(CycleType.INTRADAY, FIRE, PipelineRunStatus.COMPLETED, null);
        savedRun(CycleType.NIGHTLY, FIRE.minusSeconds(1), PipelineRunStatus.COMPLETED, null);

        assertThat(pastSubmission(CycleType.NIGHTLY)).isZero();
        assertThat(pastSubmission(CycleType.INTRADAY)).isEqualTo(1);
    }

    @Test
    @DisplayName("PipelinePhase.isPreSubmission is the one definition: null and the two submit phases")
    void preSubmissionDefinition() {
        assertThat(com.gregochr.goldenhour.entity.PipelinePhase.isPreSubmission(null)).isTrue();
        assertThat(com.gregochr.goldenhour.entity.PipelinePhase.PRE_SUBMISSION).containsExactlyInAnyOrder(
                com.gregochr.goldenhour.entity.PipelinePhase.STABILITY_RECLASSIFY,
                com.gregochr.goldenhour.entity.PipelinePhase.FORECAST_BATCH_SUBMIT);
        assertThat(com.gregochr.goldenhour.entity.PipelinePhase.isPreSubmission(
                com.gregochr.goldenhour.entity.PipelinePhase.FORECAST_BATCH_WAIT)).isFalse();
    }

    @Test
    @DisplayName("findFirstByTriggerTimeOrderByIdAsc matches the EXACT instant and answers the lowest id on a tie")
    void findFirstByTriggerTime_exactMatchLowestId() {
        Instant trigger = Instant.parse("2026-10-14T01:05:00Z");
        PipelineRunEntity first = repository.save(run(CycleType.NIGHTLY, trigger));
        repository.save(run(CycleType.INTRADAY, trigger));
        repository.save(run(CycleType.INTRADAY, trigger.plusMillis(1)));

        assertThat(repository.findFirstByTriggerTimeOrderByIdAsc(trigger)).map(PipelineRunEntity::getId)
                .contains(first.getId());
        assertThat(repository.findFirstByTriggerTimeOrderByIdAsc(trigger.minusMillis(1))).isEmpty();
        assertThat(repository.findFirstByTriggerTimeOrderByIdAsc(Instant.parse("2026-10-14T01:05:01Z")))
                .isEmpty();
    }
}
