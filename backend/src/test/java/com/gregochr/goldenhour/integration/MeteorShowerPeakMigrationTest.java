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
 * Proves V222 against a real Postgres: {@code meteor_shower_peak} exists with its 24 seeded rows, the Leonids and
 * Geminids 2026 rows are right, the primary key rejects a duplicate and the sha CHECK rejects a short hash.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine (see CLAUDE.md).
 */
@Testcontainers
class MeteorShowerPeakMigrationTest {

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
                .target("222")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("V222 seeds 24 rows, Leonids 2026 on 17 Nov at 15, Geminids 2026 on the night of 13 Dec")
    void seedsTwentyFourRows() throws SQLException {
        migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM meteor_shower_peak")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(24);
            }
            try (ResultSet rs = st.executeQuery("SELECT peak_night, zhr, source_url, source_sha256 "
                    + "FROM meteor_shower_peak WHERE shower_code = 'LEO' AND calendar_year = 2026")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("peak_night")).isEqualTo("2026-11-17");
                assertThat(rs.getInt("zhr")).isEqualTo(15);
                assertThat(rs.getString("source_url"))
                        .isEqualTo("https://www.imo.net/files/meteor-shower/cal2026.pdf");
                assertThat(rs.getString("source_sha256")).isNull();
            }
            try (ResultSet rs = st.executeQuery("SELECT peak_night FROM meteor_shower_peak "
                    + "WHERE shower_code = 'GEM' AND calendar_year = 2026")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("2026-12-13");
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM meteor_shower_peak WHERE max_utc IS NULL")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(9);
            }
        }
    }

    @Test
    @DisplayName("the primary key rejects a duplicate (code, year) and the CHECK rejects a short sha")
    void constraintsHold() throws SQLException {
        migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO meteor_shower_peak "
                    + "(shower_code, calendar_year, peak_night, zhr, source_url, fetched_at) "
                    + "VALUES ('LEO', 2026, DATE '2026-11-17', 15, 'x', NOW())"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO meteor_shower_peak "
                    + "(shower_code, calendar_year, peak_night, zhr, source_url, source_sha256, fetched_at) "
                    + "VALUES ('LEO', 2028, DATE '2028-11-17', 15, 'x', 'abc', NOW())"))
                    .isInstanceOf(SQLException.class);
            st.executeUpdate("INSERT INTO meteor_shower_peak "
                    + "(shower_code, calendar_year, peak_night, zhr, source_url, source_sha256, fetched_at) "
                    + "VALUES ('LEO', 2028, DATE '2028-11-17', 15, 'x', repeat('a', 64), NOW())");
        }
    }
}
