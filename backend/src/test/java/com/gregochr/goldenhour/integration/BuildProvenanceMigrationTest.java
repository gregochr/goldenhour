package com.gregochr.goldenhour.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V182 against a real Postgres: {@code forecast_evaluation} gains the nullable
 * {@code app_build VARCHAR(40)} and {@code prompt_hash VARCHAR(64)}, {@code outcome_rating} gains the
 * nullable {@code app_build VARCHAR(40)}, and a row written without them reads NULL (no backfill, no
 * default).
 *
 * <p>CI-only (Postgres Testcontainer; no Docker on the development machine — see CLAUDE.md).
 */
class BuildProvenanceMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    private List<Map<String, Object>> column(String table, String column) {
        return jdbc.queryForList(
                "SELECT data_type, is_nullable, character_maximum_length FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?", table, column);
    }

    @Test
    @DisplayName("forecast_evaluation.app_build is a nullable varchar(40)")
    void forecastEvaluationAppBuild() {
        List<Map<String, Object>> columns = column("forecast_evaluation", "app_build");

        assertThat(columns).hasSize(1);
        assertThat(columns.getFirst()).containsEntry("data_type", "character varying")
                .containsEntry("is_nullable", "YES").containsEntry("character_maximum_length", 40);
    }

    @Test
    @DisplayName("forecast_evaluation.prompt_hash is a nullable varchar(64), the width of a SHA-256 in hex")
    void forecastEvaluationPromptHash() {
        List<Map<String, Object>> columns = column("forecast_evaluation", "prompt_hash");

        assertThat(columns).hasSize(1);
        assertThat(columns.getFirst()).containsEntry("data_type", "character varying")
                .containsEntry("is_nullable", "YES").containsEntry("character_maximum_length", 64);
    }

    @Test
    @DisplayName("outcome_rating.app_build is a nullable varchar(40)")
    void outcomeRatingAppBuild() {
        List<Map<String, Object>> columns = column("outcome_rating", "app_build");

        assertThat(columns).hasSize(1);
        assertThat(columns.getFirst()).containsEntry("data_type", "character varying")
                .containsEntry("is_nullable", "YES").containsEntry("character_maximum_length", 40);
    }

    @Test
    @DisplayName("a forecast row written without them reads NULL, and the columns hold what is stamped")
    void freshRowReadsNullAndStampedRowKeepsValues() {
        Long location = jdbc.queryForObject(
                "INSERT INTO locations (name, lat, lon) VALUES ('V182 Test Hill', 55.0, -1.5) RETURNING id",
                Long.class);
        Long bare = jdbc.queryForObject(
                "INSERT INTO forecast_evaluation (location_id, location_lat, location_lon, target_date, "
                        + "target_type, forecast_run_at, days_ahead) VALUES (?, 55.0, -1.5, CURRENT_DATE, "
                        + "'SUNRISE', CURRENT_TIMESTAMP, 0) RETURNING id", Long.class, location);
        Long stamped = jdbc.queryForObject(
                "INSERT INTO forecast_evaluation (location_id, location_lat, location_lon, target_date, "
                        + "target_type, forecast_run_at, days_ahead, app_build, prompt_hash) "
                        + "VALUES (?, 55.0, -1.5, CURRENT_DATE, 'SUNSET', CURRENT_TIMESTAMP, 0, 'v2.28.0', ?) "
                        + "RETURNING id", Long.class, location, "f".repeat(64));

        Map<String, Object> bareRow = jdbc.queryForMap(
                "SELECT app_build, prompt_hash FROM forecast_evaluation WHERE id = ?", bare);
        Map<String, Object> stampedRow = jdbc.queryForMap(
                "SELECT app_build, prompt_hash FROM forecast_evaluation WHERE id = ?", stamped);

        assertThat(bareRow.get("app_build")).isNull();
        assertThat(bareRow.get("prompt_hash")).isNull();
        assertThat(stampedRow).containsEntry("app_build", "v2.28.0")
                .containsEntry("prompt_hash", "f".repeat(64));
    }

    @Test
    @DisplayName("a value wider than the column is refused, which is why AppBuild cuts to 40 first")
    void overWideValueIsRefused() {
        Long location = jdbc.queryForObject(
                "INSERT INTO locations (name, lat, lon) VALUES ('V182 Wide Hill', 55.0, -1.5) RETURNING id",
                Long.class);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO forecast_evaluation (location_id, location_lat, location_lon, target_date, "
                        + "target_type, forecast_run_at, days_ahead, app_build) "
                        + "VALUES (?, 55.0, -1.5, CURRENT_DATE, 'SUNRISE', CURRENT_TIMESTAMP, 0, ?)",
                location, "x".repeat(41))).isInstanceOf(DataIntegrityViolationException.class);
    }
}
