package com.gregochr.goldenhour.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V174 against a real Postgres: the intraday row's V141 description is reworded so it no
 * longer says "mid-afternoon", a hand-edited description is left alone, and no other column or row
 * of {@code scheduler_job_config} changes.
 *
 * <p>CI-only — it starts its own Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md).
 */
@Testcontainers
class IntradayJobDescriptionMigrationTest {

    private static final String JOB = "intraday_forecast_refresh";

    @Container
    @SuppressWarnings("resource")
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("goldenhour_migration_test")
                    .withUsername("test")
                    .withPassword("test");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    /** Every row rendered as one string of all the columns V174 must not touch, plus description. */
    private Map<String, String> rows(Statement st) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ResultSet rs = st.executeQuery("SELECT job_key, description, schedule_type, "
                + "cron_expression, fixed_delay_ms, status, solar_event, solar_offset_minutes "
                + "FROM scheduler_job_config ORDER BY job_key")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2) + "|" + rs.getString(3) + "|"
                        + rs.getString(4) + "|" + rs.getString(5) + "|" + rs.getString(6) + "|"
                        + rs.getString(7) + "|" + rs.getString(8));
            }
        }
        return out;
    }

    private String description(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT description FROM scheduler_job_config WHERE job_key = '" + JOB + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("V141's description is reworded, and nothing else in the table changes")
    void rewordsTheSeededDescriptionOnly() throws SQLException {
        flyway("173").migrate();
        Map<String, String> before;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(description(st)).contains("mid-afternoon");
            before = rows(st);
        }

        flyway("174").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            String after = description(st);
            assertThat(after)
                    .doesNotContain("mid-afternoon")
                    // A December sun-relative run is ~11:40 UK, so no time of day may be claimed.
                    .doesNotContainIgnoringCase("afternoon")
                    .contains("ahead of tonight's sunset")
                    .contains("sun-relative schedule")
                    .contains("(SKIPPED_NO_REFRESH_NEEDED)")
                    .hasSizeLessThanOrEqualTo(500);
            Map<String, String> others = rows(st);
            String beforeJob = before.remove(JOB);
            String afterJob = others.remove(JOB);
            assertThat(others).isEqualTo(before);
            // Only the description moved on the intraday row itself.
            assertThat(afterJob.substring(afterJob.indexOf('|')))
                    .isEqualTo(beforeJob.substring(beforeJob.indexOf('|')));
        }
    }

    @Test
    @DisplayName("A hand-edited description is left exactly as the operator wrote it")
    void leavesAHandEditedDescriptionAlone() throws SQLException {
        flyway("173").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE scheduler_job_config SET description = 'Operator note' "
                    + "WHERE job_key = '" + JOB + "'");
        }

        flyway("174").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(description(st)).isEqualTo("Operator note");
        }
    }
}
