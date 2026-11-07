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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V223 against a real Postgres: {@code imo_calendar_fetch} exists with its outcome CHECK and its
 * (year, attempted_at DESC, id DESC) index, and the {@code imo_calendar_refresh} job is seeded as an ACTIVE CRON
 * {@code 0 17 3 * * MON} in {@code Europe/London}.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine (see CLAUDE.md).
 */
@Testcontainers
class ImoCalendarFetchMigrationTest {

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

    private void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("223")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("V223 seeds imo_calendar_refresh as an ACTIVE Monday 03:17 CRON in Europe/London")
    void seedsTheJob() throws SQLException {
        migrate();

        try (Connection c = connect(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT schedule_type, cron_expression, time_zone, status "
                        + "FROM scheduler_job_config WHERE job_key = 'imo_calendar_refresh'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("schedule_type")).isEqualTo("CRON");
            assertThat(rs.getString("cron_expression")).isEqualTo("0 17 3 * * MON");
            assertThat(rs.getString("time_zone")).isEqualTo("Europe/London");
            assertThat(rs.getString("status")).isEqualTo("ACTIVE");
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    @DisplayName("imo_calendar_fetch accepts the five outcomes, rejects any other, and has its lookup index")
    void tableAndIndex() throws SQLException {
        migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            for (String outcome : new String[] {"STORED", "UNCHANGED", "NOT_PUBLISHED", "FAILED", "INVALID"}) {
                st.executeUpdate("INSERT INTO imo_calendar_fetch (calendar_year, attempted_at, outcome) "
                        + "VALUES (2026, NOW(), '" + outcome + "')");
            }
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO imo_calendar_fetch "
                    + "(calendar_year, attempted_at, outcome) VALUES (2026, NOW(), 'MAYBE')"))
                    .isInstanceOf(SQLException.class);
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM imo_calendar_fetch")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(5);
            }
            try (ResultSet rs = st.executeQuery("SELECT indexdef FROM pg_indexes "
                    + "WHERE indexname = 'idx_imo_calendar_fetch_year_attempted'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).contains("calendar_year", "attempted_at DESC", "id DESC");
            }
        }
    }
}
