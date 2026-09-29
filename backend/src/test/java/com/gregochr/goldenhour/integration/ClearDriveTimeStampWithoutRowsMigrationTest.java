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
 * Proves V157 ({@code clear_drive_time_stamp_without_rows}) against a real Postgres 17 engine.
 *
 * <p>Flyway is driven directly, as {@link RetireDeadOptimisationStrategiesMigrationTest} and
 * {@link PipelineRunPickDedupeMigrationTest} do, so the test can stop at V156 — the schema as a
 * production database holds it before this repair — seed the exact "stamp without rows" state
 * the pre-fix manual refresh could leave behind (fix/drive-time-refresh-skips-unchanged, second
 * review of PR #942), and only then apply V157.
 *
 * <p>Four users cover the full 2×2 of (stamp present/absent) × (rows present/absent), because the
 * migration's {@code WHERE} clause is a conjunction and each arm needs its own positive and
 * negative control: a stamp with rows must survive untouched (the common, correct case), a stamp
 * without rows must be cleared (the repair), and a null stamp must never be touched regardless of
 * whether rows happen to exist for it (rows with no stamp is not a state this migration is about —
 * that combination cannot arise from the writers audited in {@code UserSettingsService} and
 * {@code DriveTimeRefreshJob}, but the migration's own {@code WHERE} clause does not depend on
 * that; it simply never matches a null stamp).
 */
@Testcontainers
class ClearDriveTimeStampWithoutRowsMigrationTest {

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

    @Test
    @DisplayName("V157 clears drive_times_calculated_at only for a user with a stamp and zero "
            + "user_drive_time rows, and leaves the other three combinations exactly as seeded")
    void v157_clearsStampWithoutRows_leavesEveryOtherCombinationUntouched() throws SQLException {
        // 1. Stop at V156: the schema as a production database holds it before this repair.
        flywayTo("156").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            // One location, so the rows-present users have somewhere to point their FK at.
            st.execute("INSERT INTO locations (id, name, lat, lon) "
                    + "VALUES (1, 'Bempton Cliffs', 54.1381, -0.1657)");

            // A: stamp present, rows present — the common, correct case. Must be KEPT.
            st.execute("INSERT INTO app_user (id, username, password, role, drive_times_calculated_at) "
                    + "VALUES (1, 'stamp_and_rows', 'x', 'PRO_USER', '2026-09-20T03:10:00Z')");
            st.execute("INSERT INTO user_drive_time (user_id, location_id, drive_duration_seconds) "
                    + "VALUES (1, 1, 2700)");

            // B: stamp present, ZERO rows — the state this migration repairs. Must be CLEARED.
            st.execute("INSERT INTO app_user (id, username, password, role, drive_times_calculated_at) "
                    + "VALUES (2, 'stamp_no_rows', 'x', 'PRO_USER', '2026-09-20T03:10:00Z')");

            // C: no stamp, no rows — never measured. Must stay untouched (still null).
            st.execute("INSERT INTO app_user (id, username, password, role) "
                    + "VALUES (3, 'no_stamp_no_rows', 'x', 'PRO_USER')");

            // D: no stamp, rows present — the migration only ever conditions on the stamp being
            // present, so a null stamp is left alone regardless of what user_drive_time holds.
            st.execute("INSERT INTO app_user (id, username, password, role) "
                    + "VALUES (4, 'no_stamp_has_rows', 'x', 'PRO_USER')");
            st.execute("INSERT INTO user_drive_time (user_id, location_id, drive_duration_seconds) "
                    + "VALUES (4, 1, 1800)");
        }

        // 2. Apply V157 alone — it is the latest migration in this change.
        flywayTo(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT id, drive_times_calculated_at FROM app_user "
                        + "ORDER BY id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("id")).isEqualTo(1);
            assertThat(rs.getObject("drive_times_calculated_at"))
                    .as("A: stamp with rows — kept").isNotNull();

            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("id")).isEqualTo(2);
            assertThat(rs.getObject("drive_times_calculated_at"))
                    .as("B: stamp without rows — cleared, the repair this migration exists for")
                    .isNull();

            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("id")).isEqualTo(3);
            assertThat(rs.getObject("drive_times_calculated_at"))
                    .as("C: no stamp, no rows — untouched").isNull();

            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("id")).isEqualTo(4);
            assertThat(rs.getObject("drive_times_calculated_at"))
                    .as("D: no stamp, rows present — untouched (the migration never matches a null "
                            + "stamp)").isNull();

            assertThat(rs.next()).as("exactly four seeded users").isFalse();
        }
    }

    @Test
    @DisplayName("V157 is idempotent — re-running the same UPDATE a second time changes nothing "
            + "further, because the repaired row no longer matches its own WHERE clause")
    void v157_isIdempotent() throws SQLException {
        flywayTo("156").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO app_user (id, username, password, role, drive_times_calculated_at) "
                    + "VALUES (1, 'stamp_no_rows', 'x', 'PRO_USER', '2026-09-20T03:10:00Z')");
        }

        flywayTo(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            // The same statement V157 runs, executed again by hand — re-applying the migration
            // itself would just be a Flyway no-op (already at the latest version); this proves the
            // STATEMENT's own idempotence, which is what the migration's comment promises.
            int updated = st.executeUpdate("UPDATE app_user SET drive_times_calculated_at = NULL "
                    + "WHERE drive_times_calculated_at IS NOT NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM user_drive_time udt WHERE udt.user_id = app_user.id)");

            assertThat(updated).as("nothing left to match — the row is already null").isZero();

            try (ResultSet rs = st.executeQuery(
                    "SELECT drive_times_calculated_at FROM app_user WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("drive_times_calculated_at")).isNull();
            }
        }
    }
}
