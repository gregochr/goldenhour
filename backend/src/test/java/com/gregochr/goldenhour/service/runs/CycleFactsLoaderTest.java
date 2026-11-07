package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CycleFactsLoader} over mocked stores: how a cycle's batches and member job runs are found,
 * including the disposition run a cycle names.
 */
@ExtendWith(MockitoExtension.class)
class CycleFactsLoaderTest {

    @Mock
    private ForecastBatchRepository batches;
    @Mock
    private JobRunRepository jobRuns;

    private CycleFactsLoader loader() {
        return new CycleFactsLoader(batches, jobRuns);
    }

    /** A cycle; the disposition link has no setter (it is a column-scoped write), so the entity is a mock. */
    private static PipelineRunEntity cycle(long id, Long dispositionJobRunId) {
        PipelineRunEntity cycle = mock(PipelineRunEntity.class);
        when(cycle.getId()).thenReturn(id);
        when(cycle.getDispositionJobRunId()).thenReturn(dispositionJobRunId);
        return cycle;
    }

    private static ForecastBatchEntity batch(String anthropicId, long cycleId, Long jobRunId) {
        ForecastBatchEntity b = new ForecastBatchEntity(anthropicId, BatchType.FORECAST, 10,
                Instant.parse("2031-06-10T00:00:00Z"));
        b.setPipelineRunId(cycleId);
        b.setJobRunId(jobRunId);
        return b;
    }

    private static JobRunEntity run(long id) {
        return JobRunEntity.builder().id(id).runType(RunType.SCHEDULED_BATCH).build();
    }

    @Test
    @DisplayName("no cycles load nothing and query nothing")
    void noCycles() {
        CycleFacts facts = loader().load(List.of());

        assertThat(facts.batchesByCycle()).isEmpty();
        assertThat(facts.membersByCycle()).isEmpty();
        // Never called at all, so no argument matters.
        verify(batches, never()).findByPipelineRunIdIn(any());
    }

    @Test
    @DisplayName("a cycle's members are the runs its batches link to, plus its disposition run once")
    void membersAreBatchRunsAndDispositionRun() {
        PipelineRunEntity cycle = cycle(1, 30L);
        ForecastBatchEntity b = batch("msgbatch_1", 1, 20L);
        when(batches.findByPipelineRunIdIn(List.of(1L))).thenReturn(List.of(b));
        JobRunEntity batchRun = run(20);
        JobRunEntity dispositionRun = run(30);
        when(jobRuns.findAllById(Set.of(20L, 30L))).thenReturn(List.of(batchRun, dispositionRun));

        CycleFacts facts = loader().load(List.of(cycle));

        assertThat(facts.batches(1)).containsExactly(b);
        assertThat(facts.members(1)).containsExactly(batchRun, dispositionRun);
    }

    @Test
    @DisplayName("a disposition run that no longer exists, and a batch run that no longer exists, are left out")
    void missingRunsAreSkipped() {
        PipelineRunEntity cycle = cycle(1, 30L);
        ForecastBatchEntity b = batch("msgbatch_1", 1, 20L);
        when(batches.findByPipelineRunIdIn(List.of(1L))).thenReturn(List.of(b));
        JobRunEntity other = run(99);
        when(jobRuns.findAllById(Set.of(20L, 30L))).thenReturn(List.of(other));

        CycleFacts facts = loader().load(List.of(cycle));

        assertThat(facts.members(1)).isEmpty();
    }

    @Test
    @DisplayName("a batch whose run link was never written is matched on the batch id its run's notes name")
    void unlinkedBatchMatchedOnNotes() {
        PipelineRunEntity cycle = cycle(1, null);
        ForecastBatchEntity b = batch("msgbatch_77", 1, null);
        when(batches.findByPipelineRunIdIn(List.of(1L))).thenReturn(List.of(b));
        JobRunEntity named = run(40);
        named.setNotes("Anthropic batch: msgbatch_77");
        JobRunEntity stranger = run(41);
        stranger.setNotes("something else");
        when(jobRuns.findByRunTypeAndNotesIn(RunType.SCHEDULED_BATCH, List.of("Anthropic batch: msgbatch_77")))
                .thenReturn(List.of(named, stranger));

        CycleFacts facts = loader().load(List.of(cycle));

        assertThat(facts.members(1)).containsExactly(named);
        // Never called at all, so no argument matters.
        verify(jobRuns, never()).findAllById(any());
    }

    @Test
    @DisplayName("a cycle with no batches has no batches and no members")
    void cycleWithoutBatches() {
        PipelineRunEntity cycle = cycle(5, null);
        when(batches.findByPipelineRunIdIn(List.of(5L))).thenReturn(List.of());

        CycleFacts facts = loader().load(List.of(cycle));

        assertThat(facts.batches(5)).isEmpty();
        assertThat(facts.membersByCycle()).isEqualTo(Map.of(5L, List.of()));
    }
}
