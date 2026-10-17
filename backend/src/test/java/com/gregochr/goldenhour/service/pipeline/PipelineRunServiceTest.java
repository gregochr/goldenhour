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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    @DisplayName("recordBestBetStatus persists the best-bet outcome on the run")
    void recordBestBetStatus_persists() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0.minusSeconds(60));
        run.setId(RUN_ID);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        service.recordBestBetStatus(RUN_ID,
                com.gregochr.goldenhour.model.BestBetStatus.SUCCESS_NO_PICKS);

        assertThat(run.getBestBetStatus())
                .isEqualTo(com.gregochr.goldenhour.model.BestBetStatus.SUCCESS_NO_PICKS);
        verify(runRepository).save(run);
    }

    @Test
    @DisplayName("recordBestBetStatus is a no-op on a null status (nothing to record)")
    void recordBestBetStatus_nullIsNoOp() {
        service.recordBestBetStatus(RUN_ID, null);

        verify(runRepository, never()).findById(RUN_ID);
        verify(runRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("failPhase marks the latest matching phase row FAILED with the supplied detail")
    void failPhase_marks_failed() {
        PipelineRunPhaseEntity briefingPhase = new PipelineRunPhaseEntity(
                RUN_ID, PipelinePhase.BRIEFING, 3, T0.minusSeconds(30));
        when(phaseRepository.findByPipelineRunIdOrderBySequenceOrderAsc(RUN_ID))
                .thenReturn(List.of(
                        new PipelineRunPhaseEntity(RUN_ID, PipelinePhase.FORECAST_BATCH_SUBMIT, 1,
                                T0.minusSeconds(120)),
                        new PipelineRunPhaseEntity(RUN_ID, PipelinePhase.FORECAST_BATCH_WAIT, 2,
                                T0.minusSeconds(90)),
                        briefingPhase));

        service.failPhase(RUN_ID, PipelinePhase.BRIEFING, "briefing crashed");

        assertThat(briefingPhase.getStatus()).isEqualTo(PipelinePhaseStatus.FAILED);
        assertThat(briefingPhase.getCompletedAt()).isEqualTo(T0);
        assertThat(briefingPhase.getDetail()).isEqualTo("briefing crashed");
    }

    @Test
    @DisplayName("updateWaitingOn writes the progress text and persists")
    void updateWaitingOn_writes_field() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0);
        run.setId(RUN_ID);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        service.updateWaitingOn(RUN_ID, "forecast batch set (2 of 4 complete)");

        assertThat(run.getWaitingOn()).isEqualTo("forecast batch set (2 of 4 complete)");
    }

    @Test
    @DisplayName("findRunning delegates to the RUNNING-status query, newest first")
    void findRunning_delegates_to_repo() {
        PipelineRunEntity running = new PipelineRunEntity(CycleType.NIGHTLY, T0);
        when(runRepository.findByStatusOrderByTriggerTimeDesc(PipelineRunStatus.RUNNING))
                .thenReturn(List.of(running));

        List<PipelineRunEntity> result = service.findRunning();

        assertThat(result).containsExactly(running);
    }

    @Test
    @DisplayName("findById delegates to the repository")
    void findById_delegates_to_repo() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0);
        run.setId(RUN_ID);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        Optional<PipelineRunEntity> result = service.findById(RUN_ID);

        assertThat(result).containsSame(run);
    }

    @Test
    @DisplayName("findRecent delegates to the top-50 query")
    void findRecent_delegates_to_repo() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0);
        when(runRepository.findTop50ByOrderByTriggerTimeDesc()).thenReturn(List.of(run));

        List<PipelineRunEntity> result = service.findRecent();

        assertThat(result).containsExactly(run);
    }

    @Test
    @DisplayName("findPhases delegates to the phase repository's ordered query")
    void findPhases_delegates_to_repo() {
        PipelineRunPhaseEntity phase = new PipelineRunPhaseEntity(
                RUN_ID, PipelinePhase.FORECAST_BATCH_SUBMIT, 1, T0);
        when(phaseRepository.findByPipelineRunIdOrderBySequenceOrderAsc(RUN_ID))
                .thenReturn(List.of(phase));

        List<PipelineRunPhaseEntity> result = service.findPhases(RUN_ID);

        assertThat(result).containsExactly(phase);
    }

    @Test
    @DisplayName("degradeRun sets status DEGRADED and failureReason, clears currentPhase and "
            + "waitingOn, and stamps completedAt — the same terminal shape as completeRun/failRun, "
            + "distinguished only by the status value")
    void degradeRun_setsDegradedStatusAndClearsState() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.INTRADAY, T0.minusSeconds(900));
        run.setId(RUN_ID);
        run.setCurrentPhase(PipelinePhase.FORECAST_BATCH_WAIT);
        run.setWaitingOn("forecast batch set (2 of 3 complete)");
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        service.degradeRun(RUN_ID, "2 of 3 forecast batch submissions failed "
                + "(340 requests not submitted — near-term inland, far-term coastal)");

        assertThat(run.getStatus()).isEqualTo(PipelineRunStatus.DEGRADED);
        assertThat(run.getFailureReason()).isEqualTo("2 of 3 forecast batch submissions failed "
                + "(340 requests not submitted — near-term inland, far-term coastal)");
        assertThat(run.getCurrentPhase()).isNull();
        assertThat(run.getWaitingOn()).isNull();
        assertThat(run.getCompletedAt()).isEqualTo(T0);
        verify(runRepository).save(run);
    }

    @Test
    @DisplayName("findLatestPhase returns the LATEST row when a phase has more than one (e.g. a "
            + "retry-phase resume left a duplicate-looking entry) — ordered by sequence, not by "
            + "insertion or id")
    void findLatestPhase_duplicateRows_returnsTheLatestOne() {
        PipelineRunPhaseEntity earlierSubmit = new PipelineRunPhaseEntity(
                RUN_ID, PipelinePhase.FORECAST_BATCH_SUBMIT, 1, T0.minusSeconds(120));
        earlierSubmit.setStatus(PipelinePhaseStatus.COMPLETED);
        PipelineRunPhaseEntity wait = new PipelineRunPhaseEntity(
                RUN_ID, PipelinePhase.FORECAST_BATCH_WAIT, 2, T0.minusSeconds(90));
        // A second FORECAST_BATCH_SUBMIT row, later in sequence — findLatestPhase must return
        // THIS one, not the earlier COMPLETED one, since it is the true latest by sequence order.
        PipelineRunPhaseEntity laterSubmit = new PipelineRunPhaseEntity(
                RUN_ID, PipelinePhase.FORECAST_BATCH_SUBMIT, 3, T0.minusSeconds(30));
        laterSubmit.setStatus(PipelinePhaseStatus.FAILED);
        laterSubmit.setDetail("retry batch submission failed (1 requests)");
        when(phaseRepository.findByPipelineRunIdOrderBySequenceOrderAsc(RUN_ID))
                .thenReturn(List.of(earlierSubmit, wait, laterSubmit));

        Optional<PipelineRunPhaseEntity> result =
                service.findLatestPhase(RUN_ID, PipelinePhase.FORECAST_BATCH_SUBMIT);

        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(laterSubmit);
        assertThat(result.get().getStatus()).isEqualTo(PipelinePhaseStatus.FAILED);
    }

    @Test
    @DisplayName("findLatestPhase returns empty (never throws) when the phase never ran for this "
            + "run — unlike completePhase/failPhase's own private lookup, this is a query, not a "
            + "lifecycle transition")
    void findLatestPhase_phaseNeverRan_returnsEmpty() {
        when(phaseRepository.findByPipelineRunIdOrderBySequenceOrderAsc(RUN_ID))
                .thenReturn(List.of(
                        new PipelineRunPhaseEntity(
                                RUN_ID, PipelinePhase.FORECAST_BATCH_SUBMIT, 1, T0)));

        Optional<PipelineRunPhaseEntity> result =
                service.findLatestPhase(RUN_ID, PipelinePhase.RETRY_FAILED);

        assertThat(result).isEmpty();
    }
}
