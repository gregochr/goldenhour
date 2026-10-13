package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.service.BriefingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LateBatchBriefingRebuild} (solar-relative-refresh-plan B1).
 */
@ExtendWith(MockitoExtension.class)
class LateBatchBriefingRebuildTest {

    private static final Long RUN_ID = 7L;

    @Mock
    private PipelineRunRepository pipelineRunRepository;
    @Mock
    private ForecastBatchRepository forecastBatchRepository;
    @Mock
    private BriefingService briefingService;

    /** Collects submitted tasks so a test can prove nothing ran inline. */
    private final List<Runnable> queued = new ArrayList<>();
    private final Executor queueingExecutor = queued::add;

    private LateBatchBriefingRebuild rebuild;

    @BeforeEach
    void setUp() {
        rebuild = new LateBatchBriefingRebuild(pipelineRunRepository, forecastBatchRepository,
                briefingService, queueingExecutor);
    }

    private static ForecastBatchEntity batch(long id, BatchType type, BatchStatus status, Long runId) {
        ForecastBatchEntity b = new ForecastBatchEntity("msgbatch_" + id, type, 5,
                Instant.parse("2026-10-13T12:00:00Z"));
        ReflectionTestUtils.setField(b, "id", id);
        b.setStatus(status);
        b.setPipelineRunId(runId);
        return b;
    }

    private void runIs(PipelineRunStatus status) {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.INTRADAY,
                Instant.parse("2026-10-13T14:00:00Z"));
        run.setStatus(status);
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }

    @Test
    @DisplayName("the last batch of a FAILED run to end queues one rebuild, which runs on the executor")
    void lastBatchOfFailedRun_queuesRebuildOffThread() {
        runIs(PipelineRunStatus.FAILED);
        ForecastBatchEntity late = batch(2, BatchType.FORECAST, BatchStatus.COMPLETED, RUN_ID);
        when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of(
                batch(1, BatchType.FORECAST, BatchStatus.EXPIRED, RUN_ID), late));
        when(briefingService.refreshBriefingIfIdle()).thenReturn(true);

        rebuild.onBatchEnded(late);

        assertThat(queued).hasSize(1);
        verifyNoInteractions(briefingService);
        queued.get(0).run();
        verify(briefingService).refreshBriefingIfIdle();
    }

    @Test
    @DisplayName("a batch that itself ended FAILED counts as the last to end for a COMPLETED run")
    void failedBatchOfCompletedRun_queuesRebuild() {
        runIs(PipelineRunStatus.COMPLETED);
        ForecastBatchEntity late = batch(2, BatchType.FORECAST, BatchStatus.FAILED, RUN_ID);
        when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of(late));

        rebuild.onBatchEnded(late);

        assertThat(queued).hasSize(1);
    }

    @Test
    @DisplayName("a DEGRADED run is a finished run")
    void degradedRun_queuesRebuild() {
        runIs(PipelineRunStatus.DEGRADED);
        ForecastBatchEntity late = batch(2, BatchType.FORECAST, BatchStatus.CANCELLED, RUN_ID);
        when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of(late));

        rebuild.onBatchEnded(late);

        assertThat(queued).hasSize(1);
    }

    @Test
    @DisplayName("a RUNNING run rebuilds the briefing itself, so nothing is queued")
    void runningRun_queuesNothing() {
        runIs(PipelineRunStatus.RUNNING);
        ForecastBatchEntity ended = batch(2, BatchType.FORECAST, BatchStatus.COMPLETED, RUN_ID);

        rebuild.onBatchEnded(ended);

        assertThat(queued).isEmpty();
        verifyNoInteractions(forecastBatchRepository);
    }

    @Test
    @DisplayName("nothing is queued while another forecast batch of the run is still SUBMITTED")
    void siblingStillSubmitted_queuesNothing() {
        runIs(PipelineRunStatus.FAILED);
        ForecastBatchEntity ended = batch(2, BatchType.FORECAST, BatchStatus.COMPLETED, RUN_ID);
        when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of(
                batch(1, BatchType.FORECAST, BatchStatus.SUBMITTED, RUN_ID), ended));

        rebuild.onBatchEnded(ended);

        assertThat(queued).isEmpty();
    }

    @Test
    @DisplayName("an aurora batch of the same run neither blocks nor triggers a rebuild")
    void auroraSibling_isIgnoredAndAuroraBatchTriggersNothing() {
        runIs(PipelineRunStatus.FAILED);
        ForecastBatchEntity forecast = batch(2, BatchType.FORECAST, BatchStatus.COMPLETED, RUN_ID);
        ForecastBatchEntity aurora = batch(3, BatchType.AURORA, BatchStatus.SUBMITTED, RUN_ID);
        when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of(forecast, aurora));

        rebuild.onBatchEnded(aurora);
        assertThat(queued).as("an aurora batch ending triggers nothing").isEmpty();

        rebuild.onBatchEnded(forecast);
        assertThat(queued).as("an unfinished aurora sibling does not block").hasSize(1);
    }

    @Test
    @DisplayName("an ad-hoc batch with no pipeline run triggers nothing and reads nothing")
    void adHocBatch_triggersNothing() {
        ForecastBatchEntity adHoc = batch(2, BatchType.FORECAST, BatchStatus.COMPLETED, null);

        rebuild.onBatchEnded(adHoc);

        assertThat(queued).isEmpty();
        verifyNoInteractions(pipelineRunRepository, forecastBatchRepository);
    }

    @Test
    @DisplayName("a batch whose status is not terminal triggers nothing")
    void nonTerminalBatch_triggersNothing() {
        ForecastBatchEntity pending = batch(2, BatchType.FORECAST, BatchStatus.SUBMITTED, RUN_ID);

        rebuild.onBatchEnded(pending);

        assertThat(queued).isEmpty();
        verifyNoInteractions(pipelineRunRepository);
    }

    @Test
    @DisplayName("an unknown pipeline run triggers nothing")
    void missingRun_triggersNothing() {
        when(pipelineRunRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        rebuild.onBatchEnded(batch(2, BatchType.FORECAST, BatchStatus.COMPLETED, RUN_ID));

        assertThat(queued).isEmpty();
    }

    @Test
    @DisplayName("a refused rebuild is not retried and a failing one never escapes the task")
    void refusedAndFailingRebuild_areSwallowed() {
        runIs(PipelineRunStatus.FAILED);
        ForecastBatchEntity late = batch(2, BatchType.FORECAST, BatchStatus.COMPLETED, RUN_ID);
        when(forecastBatchRepository.findByPipelineRunId(RUN_ID)).thenReturn(List.of(late));
        when(briefingService.refreshBriefingIfIdle()).thenReturn(false).thenThrow(new IllegalStateException("boom"));

        rebuild.onBatchEnded(late);
        rebuild.onBatchEnded(late);
        queued.forEach(Runnable::run);

        verify(briefingService, org.mockito.Mockito.times(2)).refreshBriefingIfIdle();
    }

    @Test
    @DisplayName("a failure while checking never escapes into the poller")
    void checkFailure_neverEscapes() {
        when(pipelineRunRepository.findById(RUN_ID)).thenThrow(new IllegalStateException("db down"));

        rebuild.onBatchEnded(batch(2, BatchType.FORECAST, BatchStatus.COMPLETED, RUN_ID));

        assertThat(queued).isEmpty();
        verify(briefingService, never()).refreshBriefingIfIdle();
    }
}
