package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelinePhaseStatus;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunPhaseEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.repository.PipelineRunPhaseRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Transactional CRUD + phase-lifecycle helpers for {@link PipelineRunEntity} and
 * {@link PipelineRunPhaseEntity}.
 *
 * <p>The orchestrator calls these helpers as it advances through phases; this
 * service is the single place that updates the {@code pipeline_run} and
 * {@code pipeline_run_phase} tables. Keeping it separate from the orchestrator
 * keeps the orchestrator's main sequence method free of persistence noise — the
 * sequence reads top-to-bottom as intended.
 */
@Service
public class PipelineRunService {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineRunService.class);

    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineRunPhaseRepository pipelineRunPhaseRepository;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param pipelineRunRepository       pipeline run repository
     * @param pipelineRunPhaseRepository  per-phase repository
     * @param clock                       injectable clock for deterministic tests
     */
    public PipelineRunService(PipelineRunRepository pipelineRunRepository,
            PipelineRunPhaseRepository pipelineRunPhaseRepository,
            Clock clock) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineRunPhaseRepository = pipelineRunPhaseRepository;
        this.clock = clock;
    }

    /**
     * Creates and persists a new pipeline run in {@code RUNNING} state.
     *
     * @param cycleType the cycle type
     * @return the persisted run entity (with id assigned)
     */
    @Transactional
    public PipelineRunEntity startRun(CycleType cycleType) {
        Instant now = clock.instant();
        PipelineRunEntity run = new PipelineRunEntity(cycleType, now);
        return pipelineRunRepository.save(run);
    }

    /**
     * Marks the given phase as RUNNING on this run, persists a new phase row,
     * and updates {@code currentPhase} on the parent run.
     *
     * @param runId  pipeline run id
     * @param phase  phase to start
     * @return the persisted phase row
     */
    @Transactional
    public PipelineRunPhaseEntity startPhase(Long runId, PipelinePhase phase) {
        PipelineRunEntity run = pipelineRunRepository.findById(runId).orElseThrow();
        Instant now = clock.instant();
        int next = pipelineRunPhaseRepository
                .findByPipelineRunIdOrderBySequenceOrderAsc(runId).size() + 1;
        PipelineRunPhaseEntity row = new PipelineRunPhaseEntity(runId, phase, next, now);
        pipelineRunPhaseRepository.save(row);

        run.setCurrentPhase(phase);
        run.setWaitingOn(null);
        pipelineRunRepository.save(run);
        LOG.info("Pipeline run {}: phase {} started (seq={})", runId, phase, next);
        return row;
    }

    /**
     * Marks the given phase as COMPLETED with the optional detail string.
     *
     * @param runId  pipeline run id
     * @param phase  phase to complete
     * @param detail optional final detail (e.g. last waiting_on); may be null
     */
    @Transactional
    public void completePhase(Long runId, PipelinePhase phase, String detail) {
        PipelineRunPhaseEntity row = requireLatestPhase(runId, phase);
        row.setStatus(PipelinePhaseStatus.COMPLETED);
        row.setCompletedAt(clock.instant());
        if (detail != null) {
            row.setDetail(detail);
        }
        pipelineRunPhaseRepository.save(row);
        LOG.info("Pipeline run {}: phase {} completed", runId, phase);
    }

    /**
     * Marks the given phase as FAILED with the failure detail.
     *
     * @param runId   pipeline run id
     * @param phase   phase that failed
     * @param detail  failure reason
     */
    @Transactional
    public void failPhase(Long runId, PipelinePhase phase, String detail) {
        PipelineRunPhaseEntity row = requireLatestPhase(runId, phase);
        row.setStatus(PipelinePhaseStatus.FAILED);
        row.setCompletedAt(clock.instant());
        row.setDetail(detail);
        pipelineRunPhaseRepository.save(row);
        LOG.warn("Pipeline run {}: phase {} failed — {}", runId, phase, detail);
    }

    /**
     * Updates the run's {@code waitingOn} string — surfaces live progress in the UX.
     *
     * @param runId      pipeline run id
     * @param waitingOn  short human description of what we're waiting for
     */
    @Transactional
    public void updateWaitingOn(Long runId, String waitingOn) {
        PipelineRunEntity run = pipelineRunRepository.findById(runId).orElseThrow();
        run.setWaitingOn(waitingOn);
        pipelineRunRepository.save(run);
    }

    /**
     * Marks the entire run COMPLETED. Clears {@code currentPhase} and {@code waitingOn}.
     *
     * @param runId pipeline run id
     */
    @Transactional
    public void completeRun(Long runId) {
        PipelineRunEntity run = pipelineRunRepository.findById(runId).orElseThrow();
        run.setStatus(PipelineRunStatus.COMPLETED);
        run.setCurrentPhase(null);
        run.setWaitingOn(null);
        run.setCompletedAt(clock.instant());
        pipelineRunRepository.save(run);
        LOG.info("Pipeline run {}: COMPLETED", runId);
    }

    /**
     * Marks the entire run FAILED with the given reason. Clears {@code waitingOn}.
     *
     * @param runId  pipeline run id
     * @param reason failure reason
     */
    @Transactional
    public void failRun(Long runId, String reason) {
        PipelineRunEntity run = pipelineRunRepository.findById(runId).orElseThrow();
        run.setStatus(PipelineRunStatus.FAILED);
        run.setFailureReason(reason);
        run.setWaitingOn(null);
        run.setCompletedAt(clock.instant());
        pipelineRunRepository.save(run);
        LOG.warn("Pipeline run {}: FAILED — {}", runId, reason);
    }

    /**
     * Marks the entire run {@link PipelineRunStatus#DEGRADED}: the cycle completed every phase —
     * WAIT and BRIEFING both ran, briefing from whatever cache existed — but one or more forecast
     * batch submissions failed, so some or all of the served ratings predate this cycle.
     *
     * <p>Called instead of {@link #completeRun(Long)} exactly once, at the same point in the
     * sequence, when the {@code FORECAST_BATCH_SUBMIT} phase row was left {@code FAILED} by
     * {@link #failPhase}. Never called when the run is already going to be marked {@link #failRun
     * FAILED} for an unrelated reason — {@code PipelineOrchestrator}'s try/catch structure means
     * those paths return before reaching this call, so FAILED always outranks DEGRADED by
     * construction, never by a check here.
     *
     * @param runId  pipeline run id
     * @param reason the same detail recorded on the failed {@code FORECAST_BATCH_SUBMIT} phase
     *               row — e.g. "3 of 3 forecast batch submissions failed (510 requests not
     *               submitted)"
     */
    @Transactional
    public void degradeRun(Long runId, String reason) {
        PipelineRunEntity run = pipelineRunRepository.findById(runId).orElseThrow();
        run.setStatus(PipelineRunStatus.DEGRADED);
        run.setFailureReason(reason);
        run.setCurrentPhase(null);
        run.setWaitingOn(null);
        run.setCompletedAt(clock.instant());
        pipelineRunRepository.save(run);
        LOG.warn("Pipeline run {}: DEGRADED — {}", runId, reason);
    }

    /**
     * Records the best-bet advisor's outcome for this run.
     *
     * <p>Distinct from the run's lifecycle {@code status}: a COMPLETED run can carry any of
     * the three best-bet outcomes. A no-op on a null status (nothing to record). Never throws
     * into the caller's path beyond a missing-run lookup, which is a programmer error.
     *
     * @param runId  pipeline run id
     * @param status the best-bet outcome (ignored if null)
     */
    @Transactional
    public void recordBestBetStatus(Long runId, com.gregochr.goldenhour.model.BestBetStatus status) {
        if (status == null) {
            return;
        }
        PipelineRunEntity run = pipelineRunRepository.findById(runId).orElseThrow();
        run.setBestBetStatus(status);
        pipelineRunRepository.save(run);
        LOG.info("Pipeline run {}: best-bet status {}", runId, status);
    }

    /**
     * Finds all pipeline runs currently in RUNNING status.
     *
     * <p>Called on application startup by the orchestrator to detect runs that
     * were interrupted by a process restart.
     *
     * @return RUNNING pipeline runs, newest first
     */
    public List<PipelineRunEntity> findRunning() {
        return pipelineRunRepository.findByStatusOrderByTriggerTimeDesc(PipelineRunStatus.RUNNING);
    }

    /**
     * Finds a run by id.
     *
     * @param id pipeline run id
     * @return optional containing the entity if present
     */
    public Optional<PipelineRunEntity> findById(Long id) {
        return pipelineRunRepository.findById(id);
    }

    /**
     * Returns the most recent N runs for the list view.
     *
     * @return up to 50 runs, newest first
     */
    public List<PipelineRunEntity> findRecent() {
        return pipelineRunRepository.findTop50ByOrderByTriggerTimeDesc();
    }

    /**
     * Returns all phases for a run in execution order.
     *
     * @param runId pipeline run id
     * @return phases ordered by sequence_order
     */
    public List<PipelineRunPhaseEntity> findPhases(Long runId) {
        return pipelineRunPhaseRepository.findByPipelineRunIdOrderBySequenceOrderAsc(runId);
    }

    /**
     * Finds the most recent row for the given phase on this run, or empty if the phase never
     * started. Unlike {@link #completePhase}/{@link #failPhase}'s own private lookup, this never
     * throws — it is a query, not a lifecycle transition, so a caller deciding what to do next
     * (e.g. the orchestrator reading whether {@code FORECAST_BATCH_SUBMIT} was left FAILED before
     * deciding COMPLETED vs DEGRADED) can call it unconditionally, including after a process
     * restart where the phase row is the only durable record of what happened.
     *
     * @param runId pipeline run id
     * @param phase phase to look up
     * @return the latest row for that phase, or empty if none exists
     */
    public Optional<PipelineRunPhaseEntity> findLatestPhase(Long runId, PipelinePhase phase) {
        List<PipelineRunPhaseEntity> all = findPhases(runId);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).getPhase() == phase) {
                return Optional.of(all.get(i));
            }
        }
        return Optional.empty();
    }

    private PipelineRunPhaseEntity requireLatestPhase(Long runId, PipelinePhase phase) {
        return findLatestPhase(runId, phase).orElseThrow(() -> new IllegalStateException(
                "No phase row for run " + runId + " phase " + phase
                        + " — startPhase must be called before completePhase/failPhase"));
    }
}
