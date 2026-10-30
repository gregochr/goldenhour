package com.gregochr.goldenhour.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V184 and V203 against a real Postgres (Flyway runs every migration in
 * {@link IntegrationTestBase}): V184 seeds exactly one {@code evening_sunrise_refresh} row, PAUSED,
 * with no solar rule, and V203 moves it to a CRON at 21:00 UK time ({@code Europe/London}). Also,
 * {@code pipeline_run.cycle_type} (a plain {@code VARCHAR(20)} since V102) accepts the new {@code EVENING} value.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md), so the migration is proven before merge only by CI's Backend job.
 */
class EveningSunriseRefreshSeedMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("V184 seeds the evening_sunrise_refresh row PAUSED with no solar rule; V203 moves it "
            + "to CRON 0 0 21 * * * in Europe/London")
    void seedsTheEveningJobPaused() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT schedule_type, cron_expression, time_zone, status, solar_event, solar_offset_minutes "
                        + "FROM scheduler_job_config WHERE job_key = 'evening_sunrise_refresh'");

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row)
                .containsEntry("schedule_type", "CRON")
                .containsEntry("cron_expression", "0 0 21 * * *")
                .containsEntry("time_zone", "Europe/London")
                .containsEntry("status", "PAUSED")
                .containsEntry("solar_event", null)
                .containsEntry("solar_offset_minutes", null);
    }

    @Test
    @DisplayName("pipeline_run.cycle_type accepts EVENING: it is a plain VARCHAR(20)")
    void cycleTypeAcceptsEvening() {
        jdbc.update("INSERT INTO pipeline_run (cycle_type, status, trigger_time) "
                + "VALUES ('EVENING', 'COMPLETED', now())");
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pipeline_run WHERE cycle_type = 'EVENING'", Integer.class);
            assertThat(count).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM pipeline_run WHERE cycle_type = 'EVENING'");
        }
    }
}
