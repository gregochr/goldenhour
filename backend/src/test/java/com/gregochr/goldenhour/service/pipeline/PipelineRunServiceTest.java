package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelinePhaseStatus;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunPhaseEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.repository.PipelineRunPhaseRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PipelineRunService} — the transactional CRUD helper that
 * the orchestrator delegates persistence to.
 */
@ExtendWith(MockitoExtension.class)
class PipelineRunServiceTest {

    private static final Instant T0 = Instant.parse("2026-05-26T01:00:00Z");
    private static final Long RUN_ID = 7L;

    @Mock
    private PipelineRunRepository runRepository;

    @Mock
    private PipelineRunPhaseRepository phaseRepository;

    private PipelineRunService service;

    @BeforeEach
    void setUp() {
        service = new PipelineRunService(runRepository, phaseRepository,
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("startRun creates a RUNNING run with the given cycle type and trigger time")
    void startRun_creates_running_entity() {
        ArgumentCaptor<PipelineRunEntity> captor =
                ArgumentCaptor.forClass(PipelineRunEntity.class);
        PipelineRunEntity saved = new PipelineRunEntity(CycleType.NIGHTLY, T0);
        saved.setId(RUN_ID);
        when(runRepository.save(any(PipelineRunEntity.class))).thenReturn(saved);

        PipelineRunEntity result = service.startRun(CycleType.NIGHTLY);

        org.mockito.Mockito.verify(runRepository).save(captor.capture());
        assertThat(captor.getValue().getCycleType()).isEqualTo(CycleType.NIGHTLY);
        assertThat(captor.getValue().getTriggerTime()).isEqualTo(T0);
        assertThat(captor.getValue().getStatus()).isEqualTo(PipelineRunStatus.RUNNING);
        assertThat(result.getId()).isEqualTo(RUN_ID);
    }

    @Test
    @DisplayName("startPhase assigns the next sequence_order based on existing phases")
    void startPhase_assigns_next_sequence() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0);
        run.setId(RUN_ID);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(phaseRepository.findByPipelineRunIdOrderBySequenceOrderAsc(RUN_ID))
                .thenReturn(List.of(
                        new PipelineRunPhaseEntity(RUN_ID, PipelinePhase.FORECAST_BATCH_SUBMIT, 1, T0)));
        ArgumentCaptor<PipelineRunPhaseEntity> phaseCaptor =
                ArgumentCaptor.forClass(PipelineRunPhaseEntity.class);

        service.startPhase(RUN_ID, PipelinePhase.FORECAST_BATCH_WAIT);

        org.mockito.Mockito.verify(phaseRepository).save(phaseCaptor.capture());
        assertThat(phaseCaptor.getValue().getSequenceOrder()).isEqualTo(2);
        assertThat(phaseCaptor.getValue().getPhase()).isEqualTo(PipelinePhase.FORECAST_BATCH_WAIT);
        assertThat(phaseCaptor.getValue().getStatus()).isEqualTo(PipelinePhaseStatus.RUNNING);
        assertThat(run.getCurrentPhase()).isEqualTo(PipelinePhase.FORECAST_BATCH_WAIT);
        assertThat(run.getWaitingOn()).isNull();
    }

    @Test
    @DisplayName("completePhase marks the latest matching phase row COMPLETED")
    void completePhase_marks_completed() {
        PipelineRunPhaseEntity waitPhase = new PipelineRunPhaseEntity(
                RUN_ID, PipelinePhase.FORECAST_BATCH_WAIT, 2, T0.minusSeconds(60));
        when(phaseRepository.findByPipelineRunIdOrderBySequenceOrderAsc(RUN_ID))
                .thenReturn(List.of(
                        new PipelineRunPhaseEntity(RUN_ID, PipelinePhase.FORECAST_BATCH_SUBMIT, 1,
                                T0.minusSeconds(120)),
                        waitPhase));

        service.completePhase(RUN_ID, PipelinePhase.FORECAST_BATCH_WAIT, "done");

        assertThat(waitPhase.getStatus()).isEqualTo(PipelinePhaseStatus.COMPLETED);
        assertThat(waitPhase.getCompletedAt()).isEqualTo(T0);
        assertThat(waitPhase.getDetail()).isEqualTo("done");
    }

    @Test
    @DisplayName("completePhase throws when no matching phase row exists")
    void completePhase_throws_when_no_row() {
        when(phaseRepository.findByPipelineRunIdOrderBySequenceOrderAsc(RUN_ID))
                .thenReturn(List.of());

        assertThatThrownBy(() ->
                service.completePhase(RUN_ID, PipelinePhase.BRIEFING, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No phase row");
    }

    @Test
    @DisplayName("completeRun clears currentPhase and waitingOn, sets COMPLETED + completedAt")
    void completeRun_clears_state() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0.minusSeconds(900));
        run.setId(RUN_ID);
        run.setCurrentPhase(PipelinePhase.BRIEFING);
        run.setWaitingOn("(stale)");
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        service.completeRun(RUN_ID);

        assertThat(run.getStatus()).isEqualTo(PipelineRunStatus.COMPLETED);
        assertThat(run.getCurrentPhase()).isNull();
        assertThat(run.getWaitingOn()).isNull();
        assertThat(run.getCompletedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("failRun records the failure reason on the run entity")
    void failRun_records_reason() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0.minusSeconds(60));
        run.setId(RUN_ID);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        service.failRun(RUN_ID, "Safety timeout: ...");

        assertThat(run.getStatus()).isEqualTo(PipelineRunStatus.FAILED);
        assertThat(run.getFailureReason()).isEqualTo("Safety timeout: ...");
        assertThat(run.getWaitingOn()).isNull();
        assertThat(run.getCompletedAt()).isEqualTo(T0);
    }
}
