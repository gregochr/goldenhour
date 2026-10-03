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
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.atomic.AtomicLong;

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

    /** Pipeline run ids must keep increasing: the service ignores a cycle it already settled. */
    private static final AtomicLong NEXT_RUN_ID = new AtomicLong(700_000L);

    @Autowired
    private LocationService locationService;

    @Autowired
    private LocationFailureService locationFailureService;

    @Autowired
    private com.gregochr.goldenhour.service.batch.CycleDispositionJobRuns
            cycleDispositionJobRuns;

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
        angel = freshLocation("Angel of the North", 54.9141, -1.5895);
        keswick = freshLocation("Keswick", 54.6, -3.13);
    }

    @AfterEach
    void forgetSettledCycles() {
        // The Spring context (and so the service's in-memory settled-cycle set) is cached and
        // shared with other test classes; leave it clean.
        locationFailureService.forgetSettledCycles();
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
        long runId = NEXT_RUN_ID.incrementAndGet();
        String batchId = "msgbatch_tracking_" + runId;
        ForecastBatchEntity batch = new ForecastBatchEntity(
                batchId, BatchType.FORECAST, 2, Instant.parse("2026-10-03T01:00:00Z"));
        batch.setPipelineRunId(runId);
        batch.setJobRunId(runId);
        forecastBatchRepository.save(batch);
        seedResult(batchId, runId, angel, angelGotThrough);
        seedResult(batchId, runId, keswick, keswickGotThrough);
        jdbcTemplate.update(
                "INSERT INTO forecast_run_disposition (job_run_id, location_id, location_name, "
                        + "evaluation_date, event_type, disposition, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                runId, angel.getId(), angel.getName(), Date.valueOf(DATE), "SUNSET", "EVALUATED",
                Timestamp.from(Instant.parse("2026-10-02T01:00:00Z")));

        PipelineRunEntity run = new PipelineRunEntity(
                CycleType.NIGHTLY, Instant.parse("2026-10-02T01:00:00Z"));
        run.setId(runId);
        locationFailureService.settleCycle(run);
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
        long runId = NEXT_RUN_ID.incrementAndGet();
        seedDispositionsOnJobRun(runId, "SKIPPED_ERROR");
        PipelineRunEntity run = new PipelineRunEntity(
                CycleType.NIGHTLY, Instant.parse("2026-10-02T01:00:00Z"));
        run.setId(runId);

        locationFailureService.settleCycle(run);

        assertThat(reload(angel).getConsecutiveFailures()).isEqualTo(2);
        assertThat(reload(angel).isEnabled()).isTrue();
        assertThat(reload(keswick).getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("a batch whose job-run bookkeeping failed (null job run, so no api_call_log row) "
            + "whose place SCORED, as its forecast_score row for the cycle shows, resets the place")
    void nullJobRunBatch_scoredPlace_resetsCounter() {
        setCounter(angel, 2);
        long runId = NEXT_RUN_ID.incrementAndGet();
        ForecastBatchEntity batch = new ForecastBatchEntity(
                "msgbatch_nulljob_" + runId, BatchType.FORECAST, 1,
                Instant.parse("2026-10-03T01:00:00Z"));
        batch.setPipelineRunId(runId);
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
        long runId = NEXT_RUN_ID.incrementAndGet();
        seedDispositionsOnJobRun(runId, "SKIPPED_TRIAGED");
        cycleDispositionJobRuns.remember(runId, runId);

        locationFailureService.settleCycle(newRun(runId));

        assertThat(reload(angel).getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("a batchless cycle whose anchor run holds only SUBMISSION_FAILED rows (the "
            + "2026-09-29 shape) leaves the counter at 2: nobody is counted")
    void batchlessCycleSubmissionFailed_countsNobody() {
        setCounter(angel, 2);
        long runId = NEXT_RUN_ID.incrementAndGet();
        seedDispositionsOnJobRun(runId, "SUBMISSION_FAILED");
        cycleDispositionJobRuns.remember(runId, runId);

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

    private static PipelineRunEntity newRun(long runId) {
        PipelineRunEntity run = new PipelineRunEntity(
                CycleType.NIGHTLY, Instant.parse("2026-10-02T01:00:00Z"));
        run.setId(runId);
        return run;
    }
}
