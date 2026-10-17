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
 * <p>⚠️ Never hard-code a primary key here. By V156, {@code app_user} already holds V10's seeded
 * admin (an identity-assigned id, so it is {@code 1} on a fresh database but that is an
 * implementation detail, not a guarantee) and {@code locations} already holds rows from V84, V138
 * and V143 (also identity-assigned, no explicit ids). A literal {@code id = 1} in either INSERT
 * collided with one of those seeded rows and failed CI (2026-09-29) — every id this class uses now
 * comes back from {@code RETURNING id}, and every FK to {@code locations} reuses a row Flyway
 * already seeded rather than inserting a new one, so there is nothing here for a future seed
 * migration to collide with either.
 *
 * <p>The container field is deliberately non-static, the same reasoning
 * {@link PipelineRunPickDedupeMigrationTest}'s own javadoc gives: each test method gets a fresh
 * container and therefore a fresh schema, so the two methods below cannot see each other's rows
 * regardless of run order — and every assertion here additionally selects by the ids the method
 * itself created (never "every app_user row"), so even the seeded admin row cannot change a result.
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

    /**
     * Inserts a user with the given username and stamp (nullable) and returns its identity-assigned
     * id. NOT NULL columns supplied: {@code username}, {@code password}, {@code role} — every other
     * {@code app_user} column added up to V156 either allows null (including
     * {@code drive_times_calculated_at}, V67) or carries its own {@code DEFAULT}.
     */
    private long insertUser(Statement st, String username, String stampOrNull) throws SQLException {
        String stampSql = stampOrNull == null ? "NULL" : "'" + stampOrNull + "'";
        try (ResultSet rs = st.executeQuery(
                "INSERT INTO app_user (username, password, role, drive_times_calculated_at) "
                        + "VALUES ('" + username + "', 'x', 'PRO_USER', " + stampSql + ") "
                        + "RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Returns a location id already seeded by an earlier migration (V84 is the first to insert
     * one), so no new {@code locations} row — and no new name to keep unique — is needed at all.
     */
    private long anySeededLocationId(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT id FROM locations LIMIT 1")) {
            assertThat(rs.next()).as("V84 has already seeded at least one location by V156").isTrue();
            return rs.getLong(1);
        }
    }

    private void insertDriveTime(Statement st, long userId, long locationId) throws SQLException {
        st.execute("INSERT INTO user_drive_time (user_id, location_id, drive_duration_seconds) "
                + "VALUES (" + userId + ", " + locationId + ", 2700)");
    }

    @Test
    @DisplayName("V157 clears drive_times_calculated_at only for a user with a stamp and zero "
            + "user_drive_time rows, and leaves the other three combinations exactly as seeded")
    void v157_clearsStampWithoutRows_leavesEveryOtherCombinationUntouched() throws SQLException {
        // 1. Stop at V156: the schema as a production database holds it before this repair.
        flywayTo("156").migrate();

        long stampAndRowsId;
        long stampNoRowsId;
        long noStampNoRowsId;
        long noStampHasRowsId;

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            long locationId = anySeededLocationId(st);

            // A: stamp present, rows present — the common, correct case. Must be KEPT.
            stampAndRowsId = insertUser(st, "v157_a_stamp_and_rows", "2026-09-20T03:10:00Z");
            insertDriveTime(st, stampAndRowsId, locationId);

            // B: stamp present, ZERO rows — the state this migration repairs. Must be CLEARED.
            stampNoRowsId = insertUser(st, "v157_b_stamp_no_rows", "2026-09-20T03:10:00Z");

            // C: no stamp, no rows — never measured. Must stay untouched (still null).
            noStampNoRowsId = insertUser(st, "v157_c_no_stamp_no_rows", null);

            // D: no stamp, rows present — the migration only ever conditions on the stamp being
            // present, so a null stamp is left alone regardless of what user_drive_time holds.
            noStampHasRowsId = insertUser(st, "v157_d_no_stamp_has_rows", null);
            insertDriveTime(st, noStampHasRowsId, locationId);
        }

        // 2. Apply V157 alone — it is the latest migration in this change.
        flywayTo(null).migrate();

        // 3. Assert by the ids THIS METHOD created — never "every app_user row" — so the seeded
        // admin (no stamp, no rows, and therefore untouched either way) cannot affect the result.
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(stampFor(st, stampAndRowsId)).as("A: stamp with rows — kept").isNotNull();
            assertThat(stampFor(st, stampNoRowsId))
                    .as("B: stamp without rows — cleared, the repair this migration exists for")
                    .isNull();
            assertThat(stampFor(st, noStampNoRowsId)).as("C: no stamp, no rows — untouched").isNull();
            assertThat(stampFor(st, noStampHasRowsId))
                    .as("D: no stamp, rows present — untouched (the migration never matches a null "
                            + "stamp)").isNull();
        }
    }

    private String stampFor(Statement st, long userId) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT drive_times_calculated_at FROM app_user WHERE id = " + userId)) {
            assertThat(rs.next()).isTrue();
            Object stamp = rs.getObject(1);
            return stamp == null ? null : stamp.toString();
        }
    }

    @Test
    @DisplayName("V157 is idempotent — re-running the same UPDATE a second time changes nothing "
            + "further, because the repaired row no longer matches its own WHERE clause")
    void v157_isIdempotent() throws SQLException {
        flywayTo("156").migrate();

        long userId;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            userId = insertUser(st, "v157_idempotent_stamp_no_rows", "2026-09-20T03:10:00Z");
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
            assertThat(stampFor(st, userId)).isNull();
        }
    }
}
