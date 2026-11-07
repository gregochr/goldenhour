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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V217 against a real Postgres: the evening refresh takes tomorrow's sunset, so the intraday row's
 * description, the evening row's description and the evening row's display name are each rewritten, each
 * guarded on its own exact old value (a hand-edited column is left alone), and no other column or row of
 * {@code scheduler_job_config} changes.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md).
 */
@Testcontainers
class EveningRefreshDescriptionMigrationTest {

    private static final String INTRADAY = "intraday_forecast_refresh";
    private static final String EVENING = "evening_sunrise_refresh";

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

    /** Every row's columns other than display_name and description, keyed by job. */
    private Map<String, String> untouchedColumns(Statement st) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ResultSet rs = st.executeQuery("SELECT job_key, schedule_type, cron_expression, "
                + "fixed_delay_ms, status, solar_event, solar_offset_minutes, time_zone "
                + "FROM scheduler_job_config ORDER BY job_key")) {
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 2; i <= 8; i++) {
                    sb.append(rs.getString(i)).append('|');
                }
                out.put(rs.getString(1), sb.toString());
            }
        }
        return out;
    }

    /** The display_name and description of every job other than the two V217 edits. */
    private Map<String, String> otherJobsText(Statement st) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ResultSet rs = st.executeQuery(
                "SELECT job_key, display_name, description FROM scheduler_job_config ORDER BY job_key")) {
            while (rs.next()) {
                if (!Set.of(INTRADAY, EVENING).contains(rs.getString(1))) {
                    out.put(rs.getString(1), rs.getString(2) + "|" + rs.getString(3));
                }
            }
        }
        return out;
    }

    private String column(Statement st, String job, String column) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT " + column + " FROM scheduler_job_config WHERE job_key = '" + job + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private void edit(String job, String column, String value) throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE scheduler_job_config SET " + column + " = '" + value
                    + "' WHERE job_key = '" + job + "'");
        }
    }

    @Test
    @DisplayName("V217 rewrites the three seeded values, and nothing else in the table changes")
    void rewritesTheThreeValuesOnly() throws SQLException {
        flyway("216").migrate();
        Map<String, String> columnsBefore;
        Map<String, String> othersBefore;
        String intradayNameBefore;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(column(st, INTRADAY, "description")).contains("A T+1 sunrise that already has a rating");
            assertThat(column(st, EVENING, "description"))
                    .isEqualTo("Re-evaluates tomorrow's sunrise at 21:00 UK time all year, "
                            + "so the bedtime decision rests on a fresh look");
            assertThat(column(st, EVENING, "display_name")).isEqualTo("Evening Sunrise Refresh");
            columnsBefore = untouchedColumns(st);
            othersBefore = otherJobsText(st);
            intradayNameBefore = column(st, INTRADAY, "display_name");
        }

        flyway("217").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(column(st, INTRADAY, "description"))
                    .contains("tonight's sunset at every stability")
                    .contains("Tomorrow's sunrise and sunset are left for tonight's evening refresh")
                    .contains("(SKIPPED_NO_REFRESH_NEEDED)")
                    .hasSizeLessThanOrEqualTo(480);
            assertThat(column(st, EVENING, "description"))
                    .contains("tomorrow's sunrise and sunset")
                    .hasSizeLessThanOrEqualTo(480);
            assertThat(column(st, EVENING, "display_name")).isEqualTo("Evening Refresh");
            assertThat(column(st, INTRADAY, "display_name")).isEqualTo(intradayNameBefore);
            assertThat(untouchedColumns(st)).isEqualTo(columnsBefore);
            assertThat(otherJobsText(st)).isEqualTo(othersBefore);
        }
    }

    @Test
    @DisplayName("V217 leaves a hand-edited intraday description exactly as the operator wrote it")
    void leavesAHandEditedIntradayDescriptionAlone() throws SQLException {
        flyway("216").migrate();
        edit(INTRADAY, "description", "Operator note");

        flyway("217").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(column(st, INTRADAY, "description")).isEqualTo("Operator note");
            // The other two guards are independent: they still fired.
            assertThat(column(st, EVENING, "display_name")).isEqualTo("Evening Refresh");
            assertThat(column(st, EVENING, "description")).contains("tomorrow's sunrise and sunset");
        }
    }

    @Test
    @DisplayName("V217 leaves a hand-edited evening description exactly as the operator wrote it")
    void leavesAHandEditedEveningDescriptionAlone() throws SQLException {
        flyway("216").migrate();
        edit(EVENING, "description", "Operator note");

        flyway("217").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(column(st, EVENING, "description")).isEqualTo("Operator note");
            assertThat(column(st, EVENING, "display_name")).isEqualTo("Evening Refresh");
            assertThat(column(st, INTRADAY, "description")).contains("tonight's sunset at every stability");
        }
    }

    @Test
    @DisplayName("V217 leaves a hand-edited evening display name exactly as the operator wrote it")
    void leavesAHandEditedEveningDisplayNameAlone() throws SQLException {
        flyway("216").migrate();
        edit(EVENING, "display_name", "Bedtime look");

        flyway("217").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(column(st, EVENING, "display_name")).isEqualTo("Bedtime look");
            assertThat(column(st, EVENING, "description")).contains("tomorrow's sunrise and sunset");
            assertThat(column(st, INTRADAY, "description")).contains("tonight's sunset at every stability");
        }
    }
}
