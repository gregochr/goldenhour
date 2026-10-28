package com.gregochr.goldenhour.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
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

/**
 * Proves V198 ({@code drop_prompt_test_tables}) against a real Postgres 17 engine.
 *
 * <p>Flyway is driven directly, as {@link RetireTermRunTypesMigrationTest} does, so the test can stop
 * at V197 (after V196 and the growing-table indexes), confirm both tables still exist, and only then apply V198.
 */
@Testcontainers
class DropPromptTestTablesMigrationTest {

    @Container
    @SuppressWarnings("resource")
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("goldenhour_migration_test")
                    .withUsername("test")
                    .withPassword("test");

    private Flyway flywayTo(String targetVersion) {
        FluentConfiguration config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (targetVersion != null) {
            config = config.target(targetVersion);
        }
        return config.load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static long tableCount(Statement st, String table) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_name = '" + table + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("V198 drops prompt_test_result and prompt_test_run")
    void v198_dropsPromptTestTables() throws SQLException {
        flywayTo("197").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(tableCount(st, "prompt_test_run")).isEqualTo(1);
            assertThat(tableCount(st, "prompt_test_result")).isEqualTo(1);
        }

        flywayTo(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(tableCount(st, "prompt_test_run")).isZero();
            assertThat(tableCount(st, "prompt_test_result")).isZero();
        }
    }
}
