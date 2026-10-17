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

/**
 * Proves V160 against a real Postgres: after every migration the slot-keyed index on
 * {@code forecast_run_disposition} exists, with its four columns in the order the per-slot
 * "latest decision" lookups correlate on.
 *
 * <p>CI-only — extends nothing but starts its own Testcontainer, like the other migration tests in
 * this package; there is no Docker on the development machine (see CLAUDE.md). The index is the
 * belt-and-braces half of the 2026-09-30 fix; the query rewrite it accompanies is proven on H2 in
 * {@code ForecastRunDispositionRepositoryTest}.
 */
@Testcontainers
class DispositionSlotIndexMigrationTest {

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

    @Test
    @DisplayName("V160 creates idx_forecast_run_disposition_slot on "
            + "(location_name, evaluation_date, event_type, created_at)")
    void slotIndexExistsWithTheCorrelationColumnsInOrder() throws SQLException {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        String sql = "SELECT indexdef FROM pg_indexes WHERE tablename = 'forecast_run_disposition' "
                + "AND indexname = 'idx_forecast_run_disposition_slot'";
        try (Connection c = connect(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as("V160's index exists after migrate()").isTrue();
            assertThat(rs.getString(1))
                    .endsWith("(location_name, evaluation_date, event_type, created_at)");
        }
    }
}
