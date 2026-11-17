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
 * Proves V225 against a real Postgres: {@code warning_version} becomes NUMERIC and sorts numerically ("10.0" after
 * "9.0"), the (warning_id, warning_version) unique key survives (3.0 collides with 3.00), likelihood and impact are
 * integers (W1a rows holding text are converted or nulled), the two new text columns exist and {@code source_url} is
 * gone. The status CHECK still admits ACTIVE and CANCELLED only.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine (see CLAUDE.md).
 */
@Testcontainers
class WeatherWarningRealFeedShapeMigrationTest {

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

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    @Test
    @DisplayName("W1a rows survive V225: integer versions become numeric, text likelihood and impact are converted")
    void convertsExistingRows() throws SQLException {
        migrate("224");
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO weather_warning_version "
                    + "(warning_id, warning_version, status, likelihood, impact, source_url, received_at) VALUES "
                    + "('W1', 2, 'ACTIVE', '3', 'Low', 'https://x.test/w1', NOW())");
        }

        migrate("225");

        try (Connection c = connect(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT warning_version, likelihood, impact "
                        + "FROM weather_warning_version WHERE warning_id = 'W1'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal("warning_version")).isEqualByComparingTo("2.0");
            assertThat(rs.getInt("likelihood")).isEqualTo(3);
            rs.getInt("impact");
            assertThat(rs.wasNull()).isTrue();
        }
    }

    @Test
    @DisplayName("versions sort numerically and the unique key treats 3.0 and 3.00 as the same version")
    void numericVersionsAndUniqueKey() throws SQLException {
        migrate("225");

        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO weather_warning_version (warning_id, warning_version, status, "
                    + "likelihood, impact, update_description, what_should_i_do, received_at) VALUES "
                    + "('W2', 9.0, 'ACTIVE', 3, 2, 'u', 'advice', NOW()), "
                    + "('W2', 10.0, 'ACTIVE', 3, 2, NULL, NULL, NOW()), "
                    + "('W2', 3.0, 'CANCELLED', NULL, NULL, NULL, NULL, NOW())");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO weather_warning_version "
                    + "(warning_id, warning_version, status, received_at) VALUES ('W2', 3.00, 'ACTIVE', NOW())"))
                    .isInstanceOf(SQLException.class);
            try (ResultSet rs = st.executeQuery("SELECT warning_version FROM weather_warning_version "
                    + "WHERE warning_id = 'W2' ORDER BY warning_version DESC")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("10.0");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("9.0");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("3.0");
            }
        }
    }

    @Test
    @DisplayName("source_url is dropped, the new text columns exist, and the status CHECK still holds")
    void columnsAndCheck() throws SQLException {
        migrate("225");

        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_name = 'weather_warning_version' AND column_name IN "
                    + "('source_url', 'update_description', 'what_should_i_do')")) {
                java.util.List<String> names = new java.util.ArrayList<>();
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                assertThat(names).containsExactlyInAnyOrder("update_description", "what_should_i_do");
            }
            try (ResultSet rs = st.executeQuery("SELECT data_type FROM information_schema.columns "
                    + "WHERE table_name = 'weather_warning_version' AND column_name = 'warning_version'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("numeric");
            }
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO weather_warning_version "
                    + "(warning_id, warning_version, status, received_at) VALUES ('W3', 1.0, 'EXPIRED', NOW())"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
