package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.CiSpendAttemptEntity;
import com.gregochr.goldenhour.entity.CiSpendModelUsageEntity;
import com.gregochr.goldenhour.entity.CiSpendRunEntity;
import com.gregochr.goldenhour.repository.CiSpendAttemptRepository;
import com.gregochr.goldenhour.repository.CiSpendRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V232 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}, with Hibernate in
 * {@code validate} mode, so a mapping that drifts from the SQL fails context start): the unique
 * (github_run_id, run_attempt, suite) key, the cascade from a run to its model rows, the composite
 * {@code ci_spend_attempt} key, the usage-state CHECK, the repositories' round trip, and the seeded
 * {@code ci_spend_poll} job (DISABLED_BY_CONFIG, hourly, gated by {@code photocast.ci-spend.enabled}).
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md), so the migration is proven before merge only by CI's Backend job.
 */
class CiSpendRunMigrationTest extends IntegrationTestBase {

    private static final Instant T = Instant.parse("2025-03-14T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CiSpendRunRepository runs;

    @Autowired
    private CiSpendAttemptRepository attempts;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM ci_spend_run");
        jdbc.update("DELETE FROM ci_spend_attempt");
    }

    private void insertRun(long runId, int attempt, String suite, String state) {
        jdbc.update("INSERT INTO ci_spend_run (github_run_id, run_attempt, suite, html_url, started_at, usage_state, "
                + "fetched_at) VALUES (?, ?, ?, 'https://github.com/o/r/actions/runs/1/attempts/1', NOW(), ?, NOW())",
                runId, attempt, suite, state);
    }

    @Test
    @DisplayName("V232 seeds ci_spend_poll DISABLED_BY_CONFIG on an hourly fixed delay gated by photocast.ci-spend")
    void seedsTheJob() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT schedule_type, fixed_delay_ms, initial_delay_ms, "
                + "time_zone, status, status_before_config_disable, config_source "
                + "FROM scheduler_job_config WHERE job_key = 'ci_spend_poll'");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("schedule_type", "FIXED_DELAY")
                .containsEntry("fixed_delay_ms", 3600000L)
                .containsEntry("initial_delay_ms", 300000L)
                .containsEntry("time_zone", "Europe/London")
                .containsEntry("status", "DISABLED_BY_CONFIG")
                .containsEntry("status_before_config_disable", "ACTIVE")
                .containsEntry("config_source", "photocast.ci-spend.enabled");
    }

    @Test
    @DisplayName("the (github_run_id, run_attempt, suite) key is unique; another attempt or suite is allowed")
    void uniqueKey() {
        insertRun(1L, 1, "sky", "KNOWN");
        insertRun(1L, 2, "sky", "KNOWN");
        insertRun(1L, 1, "ask", "KNOWN");

        assertThatThrownBy(() -> insertRun(1L, 1, "sky", "UNKNOWN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("usage_state accepts KNOWN, UNKNOWN and UNPRICED only")
    void usageStateCheck() {
        insertRun(2L, 1, "sky", "UNKNOWN");
        insertRun(2L, 1, "ask", "UNPRICED");

        assertThatThrownBy(() -> insertRun(2L, 2, "sky", "MAYBE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("deleting a run cascades to its model rows")
    void cascade() {
        insertRun(3L, 1, "sky", "KNOWN");
        Long id = jdbc.queryForObject("SELECT id FROM ci_spend_run WHERE github_run_id = 3", Long.class);
        jdbc.update("INSERT INTO ci_spend_model_usage (ci_spend_run_id, model) VALUES (?, 'claude-sonnet-5-5')", id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ci_spend_model_usage", Integer.class)).isEqualTo(1);

        jdbc.update("DELETE FROM ci_spend_run WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ci_spend_model_usage", Integer.class)).isZero();
    }

    @Test
    @DisplayName("ci_spend_attempt is keyed by (github_run_id, run_attempt) and a repeat is rejected")
    void attemptKey() {
        jdbc.update("INSERT INTO ci_spend_attempt (github_run_id, run_attempt, settled_at) VALUES (4, 1, NOW())");
        jdbc.update("INSERT INTO ci_spend_attempt (github_run_id, run_attempt, settled_at) VALUES (4, 2, NOW())");

        assertThat(jdbc.queryForObject("SELECT suites_ran FROM ci_spend_attempt WHERE run_attempt = 1",
                String.class)).isEmpty();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ci_spend_attempt (github_run_id, run_attempt, settled_at) VALUES (4, 1, NOW())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the entities round-trip through the real schema (Hibernate validate agrees with the migration)")
    void entitiesRoundTrip() {
        CiSpendRunEntity r = new CiSpendRunEntity();
        r.setGithubRunId(5L);
        r.setRunAttempt(1);
        r.setSuite("sky");
        r.setUsageState("KNOWN");
        r.setHtmlUrl("https://github.com/o/r/actions/runs/5/attempts/1");
        r.setStartedAt(T);
        r.setFetchedAt(T);
        r.setCostMicroDollars(1234L);
        r.setExchangeRateGbpPerUsd(0.8);
        CiSpendModelUsageEntity m = new CiSpendModelUsageEntity();
        m.setModel("claude-sonnet-5-5");
        m.setInputTokens(10L);
        m.setInputUsdPerMtok(2.0);
        r.getModels().add(m);
        runs.saveAndFlush(r);
        attempts.saveAndFlush(new CiSpendAttemptEntity(5L, 1, T, "sky"));

        CiSpendRunEntity found = runs.findByGithubRunIdAndRunAttemptAndSuite(5L, 1, "sky").orElseThrow();

        assertThat(found.getCostMicroDollars()).isEqualTo(1234L);
        assertThat(found.getStartedAt()).isEqualTo(T);
        assertThat(jdbc.queryForObject("SELECT input_tokens FROM ci_spend_model_usage", Long.class)).isEqualTo(10L);
        assertThat(attempts.existsById(new CiSpendAttemptEntity.Key(5L, 1))).isTrue();
    }
}
