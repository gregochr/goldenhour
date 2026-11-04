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
 * Proves V218 against a real Postgres: the {@code ASK} and {@code ASK_READY} {@code model_selection}
 * rows exist after migrating, seeded HAIKU with extended thinking off, a row an operator already made is
 * left alone, and no other run type's row changes.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md).
 */
@Testcontainers
class AskModelSelectionMigrationTest {

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

    /** Every row as "model|extended_thinking", keyed by run type. */
    private Map<String, String> rows(Statement st) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ResultSet rs = st.executeQuery(
                "SELECT run_type, active_model, extended_thinking FROM model_selection ORDER BY run_type")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2) + "|" + rs.getBoolean(3));
            }
        }
        return out;
    }

    @Test
    @DisplayName("V218 adds the ASK and ASK_READY rows on HAIKU and changes no other row")
    void addsTheTwoRows() throws SQLException {
        flyway("217").migrate();
        Map<String, String> before;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            before = rows(st);
            assertThat(before).doesNotContainKeys("ASK", "ASK_READY");
        }

        flyway("218").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            Map<String, String> after = rows(st);
            assertThat(after).containsEntry("ASK", "HAIKU|false").containsEntry("ASK_READY", "HAIKU|false");
            Map<String, String> others = new LinkedHashMap<>(after);
            others.remove("ASK");
            others.remove("ASK_READY");
            assertThat(others).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("V218 leaves a row that already exists exactly as it is")
    void leavesAnExistingRowAlone() throws SQLException {
        flyway("217").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO model_selection (active_model, run_type, updated_at) "
                    + "VALUES ('SONNET', 'ASK', CURRENT_TIMESTAMP)");
        }

        flyway("218").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            Map<String, String> after = rows(st);
            assertThat(after).containsEntry("ASK", "SONNET|false").containsEntry("ASK_READY", "HAIKU|false");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM model_selection WHERE run_type = 'ASK'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }
}
