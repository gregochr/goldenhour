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
 * Proves V186 against a real Postgres: {@code forecast_evaluation.mist_potential} (nullable
 * VARCHAR(10), NULL on every row planted before the migration) and the {@code MIST}
 * {@code forecast_type} seed (id 8, scale 10) that lets {@code ForecastScoreWriter} upsert Claude's
 * band as a {@code forecast_score} component without breaking the foreign key.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md). It migrates to V185 first so it can plant a row before V186 runs, which
 * {@code IntegrationTestBase} (already fully migrated) cannot do.
 */
@Testcontainers
class ValleyMistStarMigrationTest {

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

    @Test
    @DisplayName("V186 adds a nullable VARCHAR(10) mist_potential and seeds the MIST forecast_type")
    void addsColumnAndSeedsTheType() throws SQLException {
        flyway("185").migrate();
        long evaluationId;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO locations (name, lat, lon) "
                    + "VALUES ('Mist Star Migration Point', 54.43, -3.03)");
            long locationId;
            try (ResultSet rs = st.executeQuery(
                    "SELECT id FROM locations WHERE name = 'Mist Star Migration Point'")) {
                rs.next();
                locationId = rs.getLong(1);
            }
            try (ResultSet rs = st.executeQuery("INSERT INTO forecast_evaluation (location_id, location_lat, "
                    + "location_lon, target_date, target_type, forecast_run_at, days_ahead) VALUES ("
                    + locationId + ", 54.43, -3.03, DATE '2026-10-17', 'SUNRISE', "
                    + "TIMESTAMP '2026-10-16 20:00:00', 0) RETURNING id")) {
                rs.next();
                evaluationId = rs.getLong(1);
            }
        }

        flyway("186").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT data_type, is_nullable, character_maximum_length "
                    + "FROM information_schema.columns WHERE table_name = 'forecast_evaluation' "
                    + "AND column_name = 'mist_potential'")) {
                assertThat(rs.next()).as("forecast_evaluation.mist_potential exists").isTrue();
                assertThat(rs.getString("data_type")).isEqualTo("character varying");
                assertThat(rs.getString("is_nullable")).isEqualTo("YES");
                assertThat(rs.getInt("character_maximum_length"))
                        .as("VARCHAR(10) holds the longest band name FOGBOUND").isEqualTo(10);
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT mist_potential FROM forecast_evaluation WHERE id = " + evaluationId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).as("a row planted before V186 has no band").isNull();
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT code, display_name, scale_max FROM forecast_type WHERE id = 8")) {
                assertThat(rs.next()).as("forecast_type 8 is seeded").isTrue();
                assertThat(rs.getString("code")).isEqualTo("MIST");
                assertThat(rs.getString("display_name")).isEqualTo("Valley Mist Forecast");
                assertThat(rs.getInt("scale_max")).isEqualTo(10);
                assertThat(rs.next()).isFalse();
            }

            st.executeUpdate("UPDATE forecast_evaluation SET mist_potential = 'FOGBOUND' WHERE id = "
                    + evaluationId);
            try (ResultSet rs = st.executeQuery(
                    "SELECT mist_potential FROM forecast_evaluation WHERE id = " + evaluationId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("FOGBOUND");
            }
        }
    }

    @Test
    @DisplayName("a forecast_score MIST component satisfies the type foreign key and the unique key")
    void mistComponentCanBeWritten() throws SQLException {
        flyway("186").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO locations (name, lat, lon) "
                    + "VALUES ('Mist Component Test Point', 54.43, -3.03)");
            long locationId;
            try (ResultSet rs = st.executeQuery(
                    "SELECT id FROM locations WHERE name = 'Mist Component Test Point'")) {
                rs.next();
                locationId = rs.getLong(1);
            }
            st.executeUpdate("INSERT INTO forecast_score (forecast_type_id, location_id, evaluation_date, "
                    + "event_type, score, summary, evaluated_at) VALUES (8, " + locationId
                    + ", DATE '2026-10-18', 'SUNRISE', 8, 'LIKELY', TIMESTAMPTZ '2026-10-17 20:00:00+00')");
            try (ResultSet rs = st.executeQuery("SELECT score, summary FROM forecast_score "
                    + "WHERE forecast_type_id = 8 AND location_id = " + locationId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("score")).isEqualTo(8);
                assertThat(rs.getString("summary")).isEqualTo("LIKELY");
            }
        }
    }
}
