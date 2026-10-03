package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of location failure tracking against the real wiring and an H2 database: seeded
 * {@code forecast_batch}, {@code api_call_log} and {@code forecast_run_disposition} rows for a
 * scheduled cycle go through the real {@link CycleLocationOutcomeResolver} and
 * {@link LocationFailureService} and the repository's column-scoped writes, then the admin
 * "re-enable" path ({@link LocationService#resetFailures}) undoes an auto-disable.
 */
@SpringBootTest
class LocationFailureTrackingTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 2);

    private static final Instant TRIGGER = Instant.parse("2026-10-02T01:00:00Z");

    /** The clock every service under test uses, so the sweep's seven-day window is fixed. */
    private static final java.time.Clock CLOCK = java.time.Clock.fixed(
            Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);

    /** Each persisted run triggers one hour after the previous one, so cycles are in order. */
    private int runsPersisted;

    private LocationFailureService locationFailureService;

    @Autowired
    private LocationService locationService;

    @Autowired
    private com.gregochr.goldenhour.service.batch.ForecastDispositionService dispositionService;

    @Autowired
    private com.gregochr.goldenhour.repository.PipelineRunRepository pipelineRunRepository;

    @Autowired
    private com.gregochr.goldenhour.repository.ForecastRunDispositionRepository
            dispositionRepository;

    @Autowired
    private com.gregochr.goldenhour.repository.ForecastScoreRepository forecastScoreRepository;

    @Autowired
    private com.gregochr.goldenhour.service.notification.AdminAlertService adminAlertService;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private ForecastBatchRepository forecastBatchRepository;

    @Autowired
    private ApiCallLogRepository apiCallLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LocationEntity angel;
    private LocationEntity keswick;

    @BeforeEach
    void setUp() {
        // Ordering reads the newest SETTLED trigger from the database and the sweep reads every
        // unsettled run: start each test from an empty pipeline_run table.
        jdbcTemplate.update("DELETE FROM forecast_score");
        jdbcTemplate.update("DELETE FROM pipeline_run");
        runsPersisted = 0;
        locationFailureService = restartedService();
        angel = freshLocation("Angel of the North", 54.9141, -1.5895);
        keswick = freshLocation("Keswick", 54.6, -3.13);
    }

    /** Persists a pipeline run and returns its id; the claim and the link live on this row. */
    private long persistedRunId() {
        return pipelineRunRepository.save(new PipelineRunEntity(CycleType.NIGHTLY,
                TRIGGER.plusSeconds(3600L * runsPersisted++))).getId();
    }

    /** Marks a persisted run as finished, as it is by the time the sweep looks for it. */
    private void complete(long runId) {
        jdbcTemplate.update("UPDATE pipeline_run SET status = 'COMPLETED' WHERE id = ?", runId);
    }

    private PipelineRunEntity newRun(long runId) {
        return pipelineRunRepository.findById(runId).orElseThrow();
    }

    /**
     * Builds a service graph that shares no memory with the Spring-managed one, which is what a
     * process restart leaves: the state it relies on can only come from the database.
     */
    private LocationFailureService restartedService() {
        CycleLocationOutcomeResolver resolver = new CycleLocationOutcomeResolver(
                forecastBatchRepository, dispositionRepository, apiCallLogRepository,
                pipelineRunRepository, forecastScoreRepository);
        return new LocationFailureService(resolver, locationRepository, adminAlertService,
                CLOCK, transactionManager, pipelineRunRepository,
                forecastBatchRepository);
    }

    /** A transaction manager whose commit can be made to fail after rolling the work back. */
    private static final class FailingCommitTransactionManager
            implements org.springframework.transaction.PlatformTransactionManager {
        private final org.springframework.transaction.PlatformTransactionManager delegate;
        private volatile boolean failCommit;

        FailingCommitTransactionManager(
                org.springframework.transaction.PlatformTransactionManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {
            if (failCommit) {
                delegate.rollback(status);
                throw new org.springframework.transaction.TransactionSystemException(
                        "commit failed");
            }
            delegate.commit(status);
        }

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {
            delegate.rollback(status);
        }
    }

    private LocationEntity freshLocation(String name, double lat, double lon) {
        LocationEntity location = locationRepository.findByName(name)
                .orElseGet(() -> locationRepository.save(LocationEntity.builder()
                        .name(name).lat(lat).lon(lon)
                        .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                        .build()));
        LocationEntity saved = locationRepository.save(location);
        // enabled and the failure columns are updatable = false on the entity: reset them with SQL.
        jdbcTemplate.update("UPDATE locations SET enabled = TRUE, consecutive_failures = 0, "
                + "last_failure_at = NULL, disabled_reason = NULL WHERE id = ?", saved.getId());
        return saved;
    }

    private LocationEntity reload(LocationEntity location) {
        return locationRepository.findById(location.getId()).orElseThrow();
    }

    private void setCounter(LocationEntity location, int failures) {
        jdbcTemplate.update("UPDATE locations SET consecutive_failures = ? WHERE id = ?",
                failures, location.getId());
    }

    /**
     * Seeds one scheduled cycle holding one batch and one result per given outcome, then settles it.
     *
     * @param angelGotThrough   whether Angel's request succeeded (otherwise it errored)
     * @param keswickGotThrough whether Keswick's request succeeded (otherwise it errored)
     */
    private void runCycle(boolean angelGotThrough, boolean keswickGotThrough) {
        long runId = persistedRunId();
        String batchId = "msgbatch_tracking_" + runId;
        ForecastBatchEntity batch = new ForecastBatchEntity(
                batchId, BatchType.FORECAST, 2, Instant.parse("2026-10-03T01:00:00Z"));
        batch.setPipelineRunId(runId);
        batch.setJobRunId(runId);
        batch.setStatus(ForecastBatchEntity.BatchStatus.COMPLETED);
        forecastBatchRepository.save(batch);
        seedResult(batchId, runId, angel, angelGotThrough);
        seedResult(batchId, runId, keswick, keswickGotThrough);
        jdbcTemplate.update(
                "INSERT INTO forecast_run_disposition (job_run_id, location_id, location_name, "
                        + "evaluation_date, event_type, disposition, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                runId, angel.getId(), angel.getName(), Date.valueOf(DATE), "SUNSET", "EVALUATED",
                Timestamp.from(Instant.parse("2026-10-02T01:00:00Z")));

        locationFailureService.settleCycle(newRun(runId));
    }

    private void seedResult(String batchId, long runId, LocationEntity location, boolean succeeded) {
        apiCallLogRepository.save(ApiCallLogEntity.builder()
                .jobRunId(runId)
                .service(ServiceName.ANTHROPIC)
                .calledAt(LocalDateTime.of(2026, 10, 2, 1, 5))
                .succeeded(succeeded)
                .isBatch(true)
                .batchId(batchId)
                .customId(CustomIdFactory.forForecast(location.getId(), DATE, TargetType.SUNSET))
                .errorType(succeeded ? null : "errored")
                .build());
    }

    @Test
    @DisplayName("a place whose request errors in three scheduled cycles in a row, while another "
            + "place gets through each time, is auto-disabled with the fixed-shape reason; the "
            + "admin's Re-enable (resetFailures) then puts it back on the roster")
    void threeConsecutiveFailedCycles_autoDisable_thenReEnable() {
        runCycle(false, true);
        assertThat(reload(angel).getConsecutiveFailures()).isEqualTo(1);
        assertThat(reload(angel).isEnabled()).isTrue();

        runCycle(false, true);
        assertThat(reload(angel).getConsecutiveFailures()).isEqualTo(2);
        assertThat(reload(angel).isEnabled()).isTrue();
        assertThat(reload(angel).getDisabledReason()).isNull();

        runCycle(false, true);
        LocationEntity disabled = reload(angel);
        assertThat(disabled.getConsecutiveFailures()).isEqualTo(3);
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.getLastFailureAt()).isNotNull();
        assertThat(disabled.getDisabledReason()).startsWith(
                "Auto-disabled after 3 consecutive failed scheduled runs (last ");
        assertThat(disabled.getDisabledReason()).endsWith(
                ": the Claude evaluation request failed).");
        assertThat(reload(keswick).isEnabled()).isTrue();
        assertThat(locationRepository.findAllByEnabledTrueOrderByNameAsc())
                .extracting(LocationEntity::getName).doesNotContain("Angel of the North");

        LocationEntity reenabled = locationService.resetFailures("Angel of the North");
        assertThat(reenabled.isEnabled()).isTrue();
        assertThat(reenabled.getConsecutiveFailures()).isZero();
        assertThat(reenabled.getDisabledReason()).isNull();
        assertThat(reenabled.getLastFailureAt()).isNull();
        assertThat(locationRepository.findAllByEnabledTrueOrderByNameAsc())
                .extracting(LocationEntity::getName).contains("Angel of the North");
    }

    @Test
    @DisplayName("the admin toggle through LocationService still disables and enables, and "
            + "enabling clears an auto-disable's failure state, all through scoped updates")
    void adminToggle_disablesAndEnables_clearingFailureState() {
        jdbcTemplate.update("UPDATE locations SET consecutive_failures = 3, "
                + "disabled_reason = 'Auto-disabled' WHERE id = ?", angel.getId());

        locationService.setEnabled(angel.getId(), false);
        assertThat(reload(angel).isEnabled()).isFalse();
        assertThat(reload(angel).getConsecutiveFailures()).isEqualTo(3);

        locationService.setEnabled(angel.getId(), true);
        LocationEntity enabled = reload(angel);
        assertThat(enabled.isEnabled()).isTrue();
        assertThat(enabled.getConsecutiveFailures()).isZero();
        assertThat(enabled.getDisabledReason()).isNull();
    }

    @Test
    @DisplayName("a place at 2 failures that gets through a scheduled cycle goes back to 0")
    void gettingThrough_resetsCounter() {
        setCounter(keswick, 2);

        runCycle(true, true);

        assertThat(reload(keswick).getConsecutiveFailures()).isZero();
        assertThat(reload(keswick).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("a cycle in which both attempted places fail counts for neither: for each, none "
            + "of the other attempted places got through")
    void everythingFailed_countsForNobody() {
        setCounter(angel, 2);
        setCounter(keswick, 1);

        runCycle(false, false);

        assertThat(reload(angel).getConsecutiveFailures()).isEqualTo(2);
        assertThat(reload(angel).isEnabled()).isTrue();
        assertThat(reload(keswick).getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("dispositions on a job run with no forecast_batch row and no remembered link "
            + "(what a restart leaves) are never found, so nobody is counted")
    void dispositionsWithoutAForecastBatchRowOrLink_countNobody() {
        setCounter(angel, 2);
        long runId = persistedRunId();
        seedDispositionsOnJobRun(runId, "SKIPPED_ERROR");
        locationFailureService.settleCycle(newRun(runId));

        assertThat(reload(angel).getConsecutiveFailures()).isEqualTo(2);
        assertThat(reload(angel).isEnabled()).isTrue();
        assertThat(reload(keswick).getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("a batch whose job-run bookkeeping failed (null job run, so no api_call_log row) "
            + "whose place SCORED, as its forecast_score row for the cycle shows, resets the place")
    void nullJobRunBatch_scoredPlace_resetsCounter() {
        setCounter(angel, 2);
        long runId = persistedRunId();
        ForecastBatchEntity batch = new ForecastBatchEntity(
                "msgbatch_nulljob_" + runId, BatchType.FORECAST, 1,
                Instant.parse("2026-10-03T01:00:00Z"));
        batch.setPipelineRunId(runId);
        batch.setStatus(ForecastBatchEntity.BatchStatus.COMPLETED);
        forecastBatchRepository.save(batch);
        jdbcTemplate.update(
                "INSERT INTO forecast_score (forecast_type_id, location_id, evaluation_date, "
                        + "event_type, score, pipeline_run_id, evaluated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                1L, angel.getId(), Date.valueOf(DATE), "SUNSET", 4, runId,
                Timestamp.from(Instant.parse("2026-10-02T02:00:00Z")));

        locationFailureService.settleCycle(newRun(runId));

        assertThat(reload(angel).getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("a batchless cycle that triaged every candidate away, whose anchor run is "
            + "remembered against the pipeline run, resets a place at 2 to 0")
    void batchlessCycleTriagedEverything_resetsCounters() {
        setCounter(angel, 2);
        long runId = persistedRunId();
        seedDispositionsOnJobRun(runId, "SKIPPED_TRIAGED");
        pipelineRunRepository.recordDispositionJobRun(runId, runId);

        locationFailureService.settleCycle(newRun(runId));

        assertThat(reload(angel).getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("a batchless cycle whose anchor run holds only SUBMISSION_FAILED rows (the "
            + "2026-09-29 shape) leaves the counter at 2: nobody is counted")
    void batchlessCycleSubmissionFailed_countsNobody() {
        setCounter(angel, 2);
        long runId = persistedRunId();
        seedDispositionsOnJobRun(runId, "SUBMISSION_FAILED");
        pipelineRunRepository.recordDispositionJobRun(runId, runId);

        locationFailureService.settleCycle(newRun(runId));

        assertThat(reload(angel).getConsecutiveFailures()).isEqualTo(2);
        assertThat(reload(angel).isEnabled()).isTrue();
    }

    private void seedDispositionsOnJobRun(long jobRunId, String disposition) {
        for (LocationEntity location : java.util.List.of(angel, keswick)) {
            jdbcTemplate.update(
                    "INSERT INTO forecast_run_disposition (job_run_id, location_id, location_name, "
                            + "evaluation_date, event_type, disposition, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    jobRunId, location.getId(), location.getName(), Date.valueOf(DATE), "SUNSET",
                    disposition, Timestamp.from(Instant.parse("2026-10-02T01:00:00Z")));
        }
    }

    @Test
    @DisplayName("RESTART: a batchless cycle's link was recorded on the pipeline run before the "
            + "process stopped; a service graph sharing no memory with the first still finds its "
            + "triaged dispositions and resets a place at 2 to 0")
    void restart_batchlessCycle_stillResetsCounter() {
        setCounter(angel, 2);
        long runId = persistedRunId();
        seedDispositionsOnJobRun(runId, "SKIPPED_TRIAGED");
        pipelineRunRepository.recordDispositionJobRun(runId, runId);

        restartedService().settleCycle(newRun(runId));

        assertThat(reload(angel).getConsecutiveFailures()).isZero();
        assertThat(newRun(runId).getFailuresSettledAt()).isNotNull();
    }

    @Test
    @DisplayName("a run resumed with failures_settled_at null (stopped before the settle) is settled "
            + "once; a run resumed with it set is not settled again, even by a fresh service graph")
    void resumedRun_settledOnce_neverTwice() {
        setCounter(angel, 0);
        long runId = persistedRunId();
        String batchId = "msgbatch_resume_" + runId;
        ForecastBatchEntity batch = new ForecastBatchEntity(
                batchId, BatchType.FORECAST, 2, Instant.parse("2026-10-03T01:00:00Z"));
        batch.setPipelineRunId(runId);
        batch.setJobRunId(runId);
        batch.setStatus(ForecastBatchEntity.BatchStatus.COMPLETED);
        forecastBatchRepository.save(batch);
        seedResult(batchId, runId, angel, false);
        seedResult(batchId, runId, keswick, true);
        assertThat(newRun(runId).getFailuresSettledAt()).isNull();

        restartedService().settleCycle(newRun(runId));
        assertThat(reload(angel).getConsecutiveFailures()).isEqualTo(1);
        assertThat(newRun(runId).getFailuresSettledAt()).isNotNull();

        restartedService().settleCycle(newRun(runId));
        locationFailureService.settleCycle(newRun(runId));
        assertThat(reload(angel).getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("an older cycle settled after a newer one is settled RESETS_ONLY, not refused: "
            + "its success resets a place at 2, its failure counts nothing, and it is claimed")
    void olderCycleAfterNewer_settledResetsOnly() {
        setCounter(angel, 2);
        setCounter(keswick, 1);
        PipelineRunEntity older = pipelineRunRepository.save(
                new PipelineRunEntity(CycleType.NIGHTLY, TRIGGER.plusSeconds(86_400)));
        PipelineRunEntity newer = pipelineRunRepository.save(
                new PipelineRunEntity(CycleType.INTRADAY, TRIGGER.plusSeconds(2 * 86_400)));
        // The newer cycle settles first with nothing recorded for either place; the older cycle,
        // settled afterwards, has Angel succeed and Keswick fail.
        seedBatch(older.getId(), true, false);

        locationFailureService.settleCycle(newRun(newer.getId()));
        locationFailureService.settleCycle(newRun(older.getId()));

        assertThat(reload(angel).getConsecutiveFailures()).isZero();
        assertThat(reload(keswick).getConsecutiveFailures()).isEqualTo(1);
        assertThat(newRun(older.getId()).getFailuresSettledAt()).isNotNull();
    }

    @Test
    @DisplayName("the tail settle of the newest cycle is still FULL: three consecutive counted "
            + "failures disable (see threeConsecutiveFailedCycles_autoDisable_thenReEnable), and "
            + "the cycle is claimed")
    void tailSettleOfNewestCycle_isFull() {
        setCounter(angel, 2);
        long runId = persistedRunId();
        seedBatch(runId, false, true);

        locationFailureService.settleCycle(newRun(runId));

        assertThat(reload(angel).isEnabled()).isFalse();
        assertThat(newRun(runId).getFailuresSettledAt()).isNotNull();
    }

    @Test
    @DisplayName("a settle whose commit fails leaves failures_settled_at null and counters "
            + "untouched, the run having already completed; the next sweep settles it RESETS_ONLY: a "
            + "place at 2 that succeeded is reset, a place that failed is not counted")
    void settleCommitFails_runUnclaimed_nextSweepSettlesResetsOnly() {
        setCounter(angel, 2);
        setCounter(keswick, 1);
        long runId = persistedRunId();
        seedBatch(runId, true, false);
        complete(runId);
        FailingCommitTransactionManager failing = new FailingCommitTransactionManager(
                transactionManager);
        failing.failCommit = true;
        CycleLocationOutcomeResolver resolver = new CycleLocationOutcomeResolver(
                forecastBatchRepository, dispositionRepository, apiCallLogRepository,
                pipelineRunRepository, forecastScoreRepository);
        LocationFailureService unlucky = new LocationFailureService(resolver, locationRepository,
                adminAlertService, CLOCK, failing, pipelineRunRepository, forecastBatchRepository);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> unlucky.settleCycle(newRun(runId)))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);

        assertThat(newRun(runId).getFailuresSettledAt()).isNull();
        assertThat(newRun(runId).getStatus())
                .isEqualTo(com.gregochr.goldenhour.entity.PipelineRunStatus.COMPLETED);
        assertThat(reload(angel).getConsecutiveFailures()).isEqualTo(2);
        assertThat(reload(keswick).getConsecutiveFailures()).isEqualTo(1);

        int settled = locationFailureService.sweepUnsettledRuns();

        assertThat(settled).isEqualTo(1);
        assertThat(newRun(runId).getFailuresSettledAt()).isNotNull();
        assertThat(reload(angel).getConsecutiveFailures()).isZero();
        assertThat(reload(keswick).getConsecutiveFailures()).isEqualTo(1);
        assertThat(locationFailureService.sweepUnsettledRuns()).isZero();
    }

    @Test
    @DisplayName("the startup sweep settles an unclaimed recent run, and ignores one triggered "
            + "more than seven days before the clock and one still RUNNING")
    void startupSweep_settlesRecent_ignoresOldAndRunning() {
        setCounter(angel, 2);
        setCounter(keswick, 2);
        long recent = persistedRunId();
        seedBatch(recent, true, false);
        complete(recent);
        PipelineRunEntity old = pipelineRunRepository.save(new PipelineRunEntity(
                CycleType.NIGHTLY, CLOCK.instant().minus(java.time.Duration.ofDays(7))
                        .minusSeconds(1)));
        seedBatch(old.getId(), false, true);
        complete(old.getId());
        long running = persistedRunId();
        seedBatch(running, false, true);

        int settled = locationFailureService.sweepUnsettledRuns();

        assertThat(settled).isEqualTo(1);
        assertThat(newRun(recent).getFailuresSettledAt()).isNotNull();
        assertThat(newRun(old.getId()).getFailuresSettledAt()).isNull();
        assertThat(newRun(running).getFailuresSettledAt()).isNull();
        // Angel's reset came from the recent run; Keswick's counter is untouched by the other two,
        // whose evidence (a Keswick success) would have reset it had either been settled.
        assertThat(reload(angel).getConsecutiveFailures()).isZero();
        assertThat(reload(keswick).getConsecutiveFailures()).isEqualTo(2);
    }

    /**
     * Gives {@code created_at} the default Flyway's schema gives it (the entity never inserts it,
     * and H2's schema-from-entities carries none), so the real service can insert rows here.
     */
    private void giveDispositionCreatedAtItsProductionDefault() {
        jdbcTemplate.update("ALTER TABLE forecast_run_disposition "
                + "ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP");
    }

    private void setBatchStatus(long runId, String status) {
        jdbcTemplate.update("UPDATE forecast_batch SET status = ? WHERE pipeline_run_id = ?",
                status, runId);
    }

    @Test
    @DisplayName("a FAILED run (restarted mid-submit) whose persisted batch is still SUBMITTED is not "
            + "claimed by the sweep; once the batch is terminal the next sweep claims it "
            + "RESETS_ONLY, and the success written in between resets the place")
    void sweep_failedRunWithSubmittedBatch_deferredUntilBatchTerminal() {
        setCounter(angel, 2);
        setCounter(keswick, 1);
        long runId = persistedRunId();
        String batchId = "msgbatch_polling_" + runId;
        ForecastBatchEntity batch = new ForecastBatchEntity(
                batchId, BatchType.FORECAST, 2, Instant.parse("2026-10-03T01:00:00Z"));
        batch.setPipelineRunId(runId);
        batch.setJobRunId(runId);
        batch.setStatus(ForecastBatchEntity.BatchStatus.SUBMITTED);
        forecastBatchRepository.save(batch);
        jdbcTemplate.update("UPDATE pipeline_run SET status = 'FAILED' WHERE id = ?", runId);

        assertThat(locationFailureService.sweepUnsettledRuns()).isZero();
        assertThat(newRun(runId).getFailuresSettledAt()).isNull();

        // The poller lands the batch's results (Angel scored, Keswick errored), then finishes it.
        seedResult(batchId, runId, angel, true);
        seedResult(batchId, runId, keswick, false);
        setBatchStatus(runId, "COMPLETED");

        assertThat(locationFailureService.sweepUnsettledRuns()).isEqualTo(1);

        assertThat(newRun(runId).getFailuresSettledAt()).isNotNull();
        assertThat(reload(angel).getConsecutiveFailures()).isZero();
        assertThat(reload(keswick).getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("a run whose batches are all terminal is claimed by the sweep as before, and a "
            + "run with an AURORA batch still polling is NOT held up by it: the resolver reads "
            + "only FORECAST batches, so an aurora batch is no evidence for a place")
    void sweep_terminalBatches_claimed_auroraBatchIgnored() {
        setCounter(angel, 2);
        long runId = persistedRunId();
        seedBatch(runId, true, true);
        setBatchStatus(runId, "FAILED");
        ForecastBatchEntity aurora = new ForecastBatchEntity(
                "msgbatch_aurora_" + runId, BatchType.AURORA, 1,
                Instant.parse("2026-10-03T01:00:00Z"));
        aurora.setPipelineRunId(runId);
        aurora.setJobRunId(runId);
        forecastBatchRepository.save(aurora);
        complete(runId);

        assertThat(locationFailureService.sweepUnsettledRuns()).isEqualTo(1);

        assertThat(newRun(runId).getFailuresSettledAt()).isNotNull();
        assertThat(reload(angel).getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("ATOMIC LINK: a link that cannot be written (no such pipeline run) rolls the "
            + "dispositions back with it, so no disposition rows are left without the link")
    void dispositionsAndLink_failTogether() {
        giveDispositionCreatedAtItsProductionDefault();
        long jobRunId = 987_001L;
        com.gregochr.goldenhour.model.CandidateDisposition disposition =
                new com.gregochr.goldenhour.model.CandidateDisposition(angel.getId(),
                        angel.getName(), DATE, TargetType.SUNSET, 0,
                        com.gregochr.goldenhour.entity.DispositionCategory.SKIPPED_TRIAGED, "cloud");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dispositionService.persist(
                        999_999_999L, jobRunId, java.util.List.of(disposition)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM forecast_run_disposition WHERE job_run_id = ?",
                Integer.class, jobRunId)).isZero();
    }

    @Test
    @DisplayName("ATOMIC LINK: dispositions and the link to their job run are written together")
    void dispositionsAndLink_writtenTogether() {
        giveDispositionCreatedAtItsProductionDefault();
        long runId = persistedRunId();
        long jobRunId = 987_002L;
        com.gregochr.goldenhour.model.CandidateDisposition disposition =
                new com.gregochr.goldenhour.model.CandidateDisposition(angel.getId(),
                        angel.getName(), DATE, TargetType.SUNSET, 0,
                        com.gregochr.goldenhour.entity.DispositionCategory.SKIPPED_TRIAGED, "cloud");

        dispositionService.persist(runId, jobRunId, java.util.List.of(disposition));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM forecast_run_disposition WHERE job_run_id = ?",
                Integer.class, jobRunId)).isEqualTo(1);
        assertThat(pipelineRunRepository.findDispositionJobRunId(runId)).contains(jobRunId);
    }

    @Test
    @DisplayName("a cycle whose audit evidence is incomplete (one forecast batch has a null job "
            + "run) counts no failure: a place at 2 with a failed row stays at 2 and is not "
            + "disabled, while the other place's success still resets it")
    void incompleteAuditEvidence_countsNoFailure() {
        setCounter(angel, 2);
        setCounter(keswick, 1);
        long runId = persistedRunId();
        seedBatchWithResults(runId, false);
        ForecastBatchEntity unlogged = new ForecastBatchEntity(
                "msgbatch_unlogged_" + runId, BatchType.FORECAST, 1,
                Instant.parse("2026-10-03T01:00:00Z"));
        unlogged.setPipelineRunId(runId);
        unlogged.setStatus(ForecastBatchEntity.BatchStatus.COMPLETED);
        forecastBatchRepository.save(unlogged);

        locationFailureService.settleCycle(newRun(runId));

        LocationEntity unchanged = reload(angel);
        assertThat(unchanged.getConsecutiveFailures()).isEqualTo(2);
        assertThat(unchanged.isEnabled()).isTrue();
        assertThat(reload(keswick).getConsecutiveFailures()).isZero();
    }

    private void seedBatchWithResults(long runId, boolean angelSucceeded) {
        seedBatch(runId, angelSucceeded, true);
    }

    private void seedBatch(long runId, boolean angelSucceeded, boolean keswickSucceeded) {
        String batchId = "msgbatch_order_" + runId;
        ForecastBatchEntity batch = new ForecastBatchEntity(
                batchId, BatchType.FORECAST, 2, Instant.parse("2026-10-03T01:00:00Z"));
        batch.setPipelineRunId(runId);
        batch.setJobRunId(runId);
        batch.setStatus(ForecastBatchEntity.BatchStatus.COMPLETED);
        forecastBatchRepository.save(batch);
        seedResult(batchId, runId, angel, angelSucceeded);
        seedResult(batchId, runId, keswick, keswickSucceeded);
    }
}
