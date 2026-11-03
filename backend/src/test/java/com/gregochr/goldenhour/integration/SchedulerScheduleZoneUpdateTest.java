package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link SchedulerJobConfigRepository#updateScheduleAndTimeZone} against a real Postgres: {@code time_zone}
 * is {@code updatable = false} on the entity, so this scoped statement is the only thing that moves a CRON job's
 * zone after V204 set it, and it must write the cron and the zone together and nothing else.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md).
 */
class SchedulerScheduleZoneUpdateTest extends IntegrationTestBase {

    private static final String KEY = "zone_update_probe";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SchedulerJobConfigRepository configs;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM scheduler_job_config WHERE job_key = ?", KEY);
    }

    private Long insertProbe() {
        jdbc.update("INSERT INTO scheduler_job_config (job_key, display_name, schedule_type, cron_expression, "
                + "status, updated_at) VALUES (?, ?, 'CRON', '0 0 21 * * *', 'PAUSED', CURRENT_TIMESTAMP)",
                KEY, KEY);
        return jdbc.queryForObject("SELECT id FROM scheduler_job_config WHERE job_key = ?", Long.class, KEY);
    }

    @Test
    @DisplayName("the scoped update writes the cron and the zone together and leaves the status and type alone; "
            + "a null zone puts the job back in UTC")
    void writesCronAndZone() {
        Long id = insertProbe();

        int updated = configs.updateScheduleAndTimeZone(id, "0 30 6 * * *", null, "Europe/London",
                Instant.parse("2027-03-05T12:00:00Z"));

        assertThat(updated).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT cron_expression, time_zone, status, schedule_type FROM scheduler_job_config "
                        + "WHERE job_key = ?", KEY);
        assertThat(row).containsEntry("cron_expression", "0 30 6 * * *")
                .containsEntry("time_zone", "Europe/London")
                .containsEntry("status", "PAUSED")
                .containsEntry("schedule_type", "CRON");

        assertThat(configs.updateScheduleAndTimeZone(id, "0 30 6 * * *", null, null,
                Instant.parse("2027-03-05T12:00:00Z"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT time_zone FROM scheduler_job_config WHERE job_key = ?",
                String.class, KEY)).isNull();
    }
}
