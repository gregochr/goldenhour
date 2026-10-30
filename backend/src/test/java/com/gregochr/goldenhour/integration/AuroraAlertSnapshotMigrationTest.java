package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AuroraAlertSnapshotEntity;
import com.gregochr.goldenhour.repository.AuroraAlertSnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V203 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}):
 * the one-row {@code aurora_alert_snapshot} table, its columns, types and nullability, the
 * {@code id = 1} CHECK, and that the entity round-trips under {@code ddl-auto: validate}.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development
 * machine (see CLAUDE.md). The migration is therefore proven before merge only by CI's Backend job.
 */
class AuroraAlertSnapshotMigrationTest extends IntegrationTestBase {

    private static final String INSERT = "INSERT INTO aurora_alert_snapshot (id, night_date, alert_level, "
            + "active_since, dark_sky_count, scores, saved_at) "
            + "VALUES (?, '2027-01-14', 'MODERATE', '2027-01-14 19:30:00+00', 3, '[]', '2027-01-14 19:31:00+00')";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuroraAlertSnapshotRepository repository;

    @Test
    @DisplayName("aurora_alert_snapshot has the expected columns, types and nullability")
    void columnsExist() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable, character_maximum_length "
                        + "FROM information_schema.columns WHERE table_name = 'aurora_alert_snapshot' "
                        + "ORDER BY column_name");

        assertThat(columns).extracting(c -> c.get("column_name")).containsExactly("active_since",
                "alert_level", "clear_count", "dark_sky_count", "held_level", "held_trigger", "id",
                "night_date", "saved_at", "scores", "trigger_kp", "trigger_type");
        assertThat(column(columns, "id")).containsEntry("data_type", "smallint").containsEntry("is_nullable", "NO");
        assertThat(column(columns, "night_date")).containsEntry("data_type", "date")
                .containsEntry("is_nullable", "NO");
        assertThat(column(columns, "alert_level")).containsEntry("character_maximum_length", 20)
                .containsEntry("is_nullable", "NO");
        assertThat(column(columns, "active_since")).containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "NO");
        assertThat(column(columns, "saved_at")).containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "NO");
        assertThat(column(columns, "trigger_type")).containsEntry("character_maximum_length", 30)
                .containsEntry("is_nullable", "YES");
        assertThat(column(columns, "trigger_kp")).containsEntry("data_type", "double precision")
                .containsEntry("is_nullable", "YES");
        assertThat(column(columns, "dark_sky_count")).containsEntry("data_type", "integer")
                .containsEntry("is_nullable", "NO");
        assertThat(column(columns, "clear_count")).containsEntry("data_type", "integer")
                .containsEntry("is_nullable", "YES");
        assertThat(column(columns, "scores")).containsEntry("data_type", "text").containsEntry("is_nullable", "NO");
        assertThat(column(columns, "held_level")).containsEntry("character_maximum_length", 20)
                .containsEntry("is_nullable", "YES");
        assertThat(column(columns, "held_trigger")).containsEntry("character_maximum_length", 30)
                .containsEntry("is_nullable", "YES");
    }

    private static Map<String, Object> column(List<Map<String, Object>> columns, String name) {
        return columns.stream().filter(c -> name.equals(c.get("column_name"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the table holds one row: id = 1 is accepted, id = 2 violates the CHECK")
    void onlyRowOneIsAllowed() {
        jdbc.update("DELETE FROM aurora_alert_snapshot");

        jdbc.update(INSERT, 1);

        assertThatThrownBy(() -> jdbc.update(INSERT, 2)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(INSERT, 1)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM aurora_alert_snapshot");
    }

    @Test
    @DisplayName("the entity round-trips, and the confirm and hold updates act on row 1")
    void entityRoundTripsAndUpdatesAct() {
        repository.deleteAll();
        AuroraAlertSnapshotEntity row = new AuroraAlertSnapshotEntity();
        row.setNightDate(LocalDate.of(2027, 1, 14));
        row.setAlertLevel("MODERATE");
        row.setActiveSince(Instant.parse("2027-01-14T19:30:00Z"));
        row.setTriggerType("FORECAST_LOOKAHEAD");
        row.setTriggerKp(5.67);
        row.setDarkSkyCount(3);
        row.setClearCount(2);
        row.setScores("[{\"locationId\":1}]");
        row.setSavedAt(Instant.parse("2027-01-14T19:31:00Z"));
        repository.save(row);

        assertThat(repository.setHold("MINOR", "REALTIME")).isEqualTo(1);
        assertThat(repository.confirmNight(LocalDate.of(2027, 1, 14))).as("a hold is dropped").isEqualTo(1);
        assertThat(repository.confirmNight(LocalDate.of(2027, 1, 14))).as("nothing to change").isZero();
        assertThat(repository.confirmNight(LocalDate.of(2027, 1, 15))).as("the night moves on").isEqualTo(1);

        AuroraAlertSnapshotEntity read = repository.findById(AuroraAlertSnapshotEntity.ONLY_ID).orElseThrow();
        assertThat(read.getNightDate()).isEqualTo(LocalDate.of(2027, 1, 15));
        assertThat(read.getHeldLevel()).isNull();
        assertThat(read.getActiveSince()).isEqualTo(Instant.parse("2027-01-14T19:30:00Z"));
        assertThat(read.getTriggerKp()).isEqualTo(5.67);
        assertThat(read.getClearCount()).isEqualTo(2);
        assertThat(read.getScores()).isEqualTo("[{\"locationId\":1}]");
        repository.deleteAll();
    }
}
