package com.gregochr.goldenhour.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V202 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}):
 * the evening sunrise refresh runs at 21:00 UK time and every other job keeps a null (UTC) zone.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md), so the migration is proven before merge only by CI's Backend job.
 */
class SchedulerJobTimeZoneMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("V202 puts the evening refresh in Europe/London at 21:00 and leaves its status as seeded")
    void eveningRefreshRunsInUkTime() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT time_zone, cron_expression, status FROM scheduler_job_config "
                        + "WHERE job_key = 'evening_sunrise_refresh'");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("time_zone", "Europe/London")
                .containsEntry("cron_expression", "0 0 21 * * *")
                .containsEntry("status", "PAUSED");
    }

    @Test
    @DisplayName("V202 leaves every other job's time_zone null, meaning UTC")
    void otherJobsStayUtc() {
        List<String> zoned = jdbc.queryForList(
                "SELECT job_key FROM scheduler_job_config WHERE time_zone IS NOT NULL "
                        + "AND job_key <> 'evening_sunrise_refresh'", String.class);

        assertThat(zoned).isEmpty();
    }
}
