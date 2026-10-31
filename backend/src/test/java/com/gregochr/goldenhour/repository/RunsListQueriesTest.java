package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests (H2, no Flyway, no Docker) for the set queries behind the Runs list: the
 * cycle-batch and disposition exclusion, the notes fallback, the batch-type lookup and the range scans.
 */
@DataJpaTest
class RunsListQueriesTest {

    private static final Instant T0 = Instant.parse("2026-10-31T01:00:00Z");
    private static final LocalDateTime D0 = LocalDateTime.of(2026, 10, 31, 1, 0);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private ForecastBatchRepository batches;

    @Autowired
    private PipelineRunRepository pipelineRuns;

    @Autowired
    private JobRunRepository jobRuns;

    private JobRunEntity job(RunType type, LocalDateTime startedAt, LocalDateTime completedAt) {
        return em.persist(JobRunEntity.builder().runType(type).startedAt(startedAt).completedAt(completedAt)
                .succeeded(0).failed(0).locationsProcessed(0).triggeredManually(false).build());
    }

    private PipelineRunEntity cycle() {
        return em.persist(new PipelineRunEntity(CycleType.NIGHTLY, T0));
    }

    private ForecastBatchEntity batch(String id, BatchType type, Long cycleId, Long jobRunId) {
        ForecastBatchEntity batch = new ForecastBatchEntity(id, type, 5, T0.plusSeconds(86_400));
        batch.setPipelineRunId(cycleId);
        batch.setJobRunId(jobRunId);
        return em.persist(batch);
    }

    @Test
    @DisplayName("cycle batch job run ids: only batches with a cycle and a run, from the minimum id up")
    void cycleBatchJobRunIds() {
        JobRunEntity belowMin = job(RunType.SCHEDULED_BATCH, D0, null);
        JobRunEntity cycleBatch = job(RunType.SCHEDULED_BATCH, D0, null);
        JobRunEntity handBatch = job(RunType.SCHEDULED_BATCH, D0, null);
        PipelineRunEntity cycle = cycle();
        batch("msgbatch_below", BatchType.FORECAST, cycle.getId(), belowMin.getId());
        batch("msgbatch_cycle", BatchType.AURORA, cycle.getId(), cycleBatch.getId());
        batch("msgbatch_hand", BatchType.FORECAST, null, handBatch.getId());
        batch("msgbatch_unlinked", BatchType.FORECAST, cycle.getId(), null);
        em.flush();

        assertThat(batches.findCycleBatchJobRunIdsFrom(belowMin.getId()))
                .containsExactlyInAnyOrder(belowMin.getId(), cycleBatch.getId());
        assertThat(batches.findCycleBatchJobRunIdsFrom(cycleBatch.getId())).containsExactly(cycleBatch.getId());
    }

    @Test
    @DisplayName("disposition anchor ids: only set ones, from the minimum id up")
    void dispositionJobRunIds() {
        JobRunEntity low = job(RunType.SCHEDULED_BATCH, D0, D0);
        JobRunEntity high = job(RunType.SCHEDULED_BATCH, D0, D0);
        PipelineRunEntity withLow = cycle();
        PipelineRunEntity withHigh = cycle();
        cycle();
        em.flush();
        pipelineRuns.recordDispositionJobRun(withLow.getId(), low.getId());
        pipelineRuns.recordDispositionJobRun(withHigh.getId(), high.getId());

        assertThat(pipelineRuns.findDispositionJobRunIdsFrom(low.getId()))
                .containsExactlyInAnyOrder(low.getId(), high.getId());
        assertThat(pipelineRuns.findDispositionJobRunIdsFrom(high.getId())).containsExactly(high.getId());
    }

    @Test
    @DisplayName("batches by Anthropic batch id: only the ids asked about, cycle or not")
    void cycleAnthropicBatchIds() {
        PipelineRunEntity cycle = cycle();
        batch("msgbatch_cycle", BatchType.FORECAST, cycle.getId(), null);
        batch("msgbatch_other_cycle", BatchType.FORECAST, cycle.getId(), null);
        batch("msgbatch_hand", BatchType.FORECAST, null, null);
        em.flush();

        assertThat(batches.findByAnthropicBatchIdIn(List.of("msgbatch_cycle", "msgbatch_hand", "msgbatch_none")))
                .extracting(ForecastBatchEntity::getAnthropicBatchId)
                .containsExactlyInAnyOrder("msgbatch_cycle", "msgbatch_hand");
    }

    @Test
    @DisplayName("batch types are read for a set of job runs in one query; cycle batches in another")
    void batchLookups() {
        JobRunEntity aurora = job(RunType.SCHEDULED_BATCH, D0, D0);
        JobRunEntity forecast = job(RunType.SCHEDULED_BATCH, D0, D0);
        JobRunEntity none = job(RunType.SCHEDULED_BATCH, D0, D0);
        PipelineRunEntity cycle = cycle();
        batch("msgbatch_a", BatchType.AURORA, null, aurora.getId());
        batch("msgbatch_f", BatchType.FORECAST, cycle.getId(), forecast.getId());
        em.flush();

        assertThat(batches.findByJobRunIdIn(List.of(aurora.getId(), forecast.getId(), none.getId())))
                .extracting(ForecastBatchEntity::getBatchType)
                .containsExactlyInAnyOrder(BatchType.AURORA, BatchType.FORECAST);
        assertThat(batches.findByPipelineRunIdIn(List.of(cycle.getId())))
                .extracting(ForecastBatchEntity::getAnthropicBatchId).containsExactly("msgbatch_f");
    }

    @Test
    @DisplayName("range scans: job runs from a start (inclusive, id-descending ties), open runs, cycles")
    void rangeScans() {
        JobRunEntity before = job(RunType.TIDE, D0.minusSeconds(1), D0);
        JobRunEntity atStart = job(RunType.TIDE, D0, D0);
        JobRunEntity sameSecond = job(RunType.TIDE, D0, null);
        JobRunEntity later = job(RunType.TIDE, D0.plusHours(1), null);
        PipelineRunEntity oldCycle = em.persist(new PipelineRunEntity(CycleType.NIGHTLY, T0.minusSeconds(1)));
        PipelineRunEntity newCycle = em.persist(new PipelineRunEntity(CycleType.NIGHTLY, T0));
        em.flush();

        assertThat(jobRuns.findByStartedAtGreaterThanEqualOrderByStartedAtDescIdDesc(D0, PageRequest.of(0, 10)))
                .extracting(JobRunEntity::getId)
                .containsExactly(later.getId(), sameSecond.getId(), atStart.getId());
        assertThat(jobRuns.findByCompletedAtIsNullAndStartedAtGreaterThanEqual(D0))
                .extracting(JobRunEntity::getId).containsExactlyInAnyOrder(sameSecond.getId(), later.getId());
        assertThat(before.getId()).isNotNull();
        assertThat(pipelineRuns.findByTriggerTimeGreaterThanEqualOrderByTriggerTimeDescIdDesc(T0))
                .extracting(PipelineRunEntity::getId).containsExactly(newCycle.getId());
        assertThat(oldCycle.getId()).isNotNull();
    }
}
