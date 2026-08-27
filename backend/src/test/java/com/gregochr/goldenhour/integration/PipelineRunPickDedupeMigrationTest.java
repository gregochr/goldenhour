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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V148 ({@code dedupe_and_constrain_pipeline_run_pick}) against a real Postgres 17
 * engine, not H2 — Flyway is driven directly (not through the Spring Boot context) so the
 * test can seed pre-existing duplicate {@code (pipeline_run_id, pick_rank)} rows
 * <em>before</em> V148 runs, the exact case that breaks the deploy if the dedupe is missing
 * or wrong (a migration test against an empty table proves nothing about that case).
 *
 * <p>Under the {@code integration} package so it is picked up by
 * {@code -Dtest='**&#47;integration/**'} and excluded from the fast local gate
 * ({@code -Dtest='!**&#47;integration/**'}), same as the {@link IntegrationTestBase}
 * subclasses — but this class does not extend it: booting the full Spring context would run
 * Flyway to the latest version in one shot, against an empty database, which can never
 * reproduce "duplicates already exist when V148 runs". Driving Flyway directly lets the test
 * stop at V147, seed the duplicates by hand, then apply V148 alone and inspect the result.
 */
@Testcontainers
class PipelineRunPickDedupeMigrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("goldenhour_migration_test")
                    .withUsername("test")
                    .withPassword("test");

    private static Flyway flywayTo(String targetVersion) {
        FluentConfiguration config = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (targetVersion != null) {
            config = config.target(targetVersion);
        }
        return config.load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("V148 dedupes pre-existing duplicate (pipeline_run_id, pick_rank) rows, keeping "
            + "the highest id, then adds the unique constraint — the migration must survive "
            + "exactly the state a pre-fix production database could already be in")
    void v148_dedupesPreexistingDuplicatesThenConstrains() throws SQLException {
        // 1. Migrate only up to V147 — the schema exactly as it stood before this fix, with the
        // old NON-unique idx_pick_pipeline_run index from V104 and no constraint at all.
        flywayTo("147").migrate();

        // 2. Seed exactly the corrupt state the defect produces: two pipeline runs, one of
        // which already carries a duplicate rank-1 pick (as if a pre-fix crash mid-BRIEFING
        // had re-persisted it) and a duplicate rank-2 pick too, while the other run is clean.
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO pipeline_run (id, cycle_type, status, trigger_time) "
                    + "VALUES (1, 'NIGHTLY', 'COMPLETED', now())");
            st.execute("INSERT INTO pipeline_run (id, cycle_type, status, trigger_time) "
                    + "VALUES (2, 'NIGHTLY', 'COMPLETED', now())");

            // Run 1, rank 1: three duplicate rows: ids 10 (oldest) < 11 < 12 (newest/highest).
            // V148 must keep only id 12.
            st.execute("INSERT INTO pipeline_run_pick "
                    + "(id, pipeline_run_id, pick_rank, headline, recorded_at) "
                    + "VALUES (10, 1, 1, 'pre-crash attempt 1', now() - interval '2 minutes')");
            st.execute("INSERT INTO pipeline_run_pick "
                    + "(id, pipeline_run_id, pick_rank, headline, recorded_at) "
                    + "VALUES (11, 1, 1, 'pre-crash attempt 2', now() - interval '1 minutes')");
            st.execute("INSERT INTO pipeline_run_pick "
                    + "(id, pipeline_run_id, pick_rank, headline, recorded_at) "
                    + "VALUES (12, 1, 1, 'final attempt (keep me)', now())");

            // Run 1, rank 2: a duplicate pair, ids 20 (oldest) < 21 (newest/highest).
            st.execute("INSERT INTO pipeline_run_pick "
                    + "(id, pipeline_run_id, pick_rank, headline, recorded_at) "
                    + "VALUES (20, 1, 2, 'plan b, pre-crash', now() - interval '1 minutes')");
            st.execute("INSERT INTO pipeline_run_pick "
                    + "(id, pipeline_run_id, pick_rank, headline, recorded_at) "
                    + "VALUES (21, 1, 2, 'plan b, keep me', now())");

            // Run 2, rank 1: a single, non-duplicated row — must survive untouched.
            st.execute("INSERT INTO pipeline_run_pick "
                    + "(id, pipeline_run_id, pick_rank, headline, recorded_at) "
                    + "VALUES (30, 2, 1, 'clean run, untouched', now())");
        }

        // 3. Apply V148 (and nothing else — it is the latest migration in this worktree).
        flywayTo(null).migrate();

        // 4. Exactly one row per (pipeline_run_id, pick_rank) group remains, and it is the
        // highest-id (most-recently-written) row from each duplicate set.
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            List<Long> run1Rank1Ids = idsFor(st, 1, 1);
            assertThat(run1Rank1Ids).as("run 1 / rank 1 survivors").containsExactly(12L);

            List<Long> run1Rank2Ids = idsFor(st, 1, 2);
            assertThat(run1Rank2Ids).as("run 1 / rank 2 survivors").containsExactly(21L);

            List<Long> run2Rank1Ids = idsFor(st, 2, 1);
            assertThat(run2Rank1Ids).as("clean run's row is untouched").containsExactly(30L);

            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pipeline_run_pick")) {
                rs.next();
                assertThat(rs.getLong(1)).as("total surviving rows").isEqualTo(3L);
            }
        }

        // 5. The unique constraint is live: a second row for an already-occupied
        // (pipeline_run_id, pick_rank) pair is now rejected at the database level.
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThatThrownBy(() -> st.execute("INSERT INTO pipeline_run_pick "
                    + "(pipeline_run_id, pick_rank, headline, recorded_at) "
                    + "VALUES (1, 1, 'a second rank-1 pick for run 1', now())"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_pipeline_run_pick_rank");
        }

        // 6. A different rank on the same run, and the same rank on a different run, are both
        // still allowed — the constraint is exactly (pipeline_run_id, pick_rank), not broader.
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO pipeline_run_pick "
                    + "(pipeline_run_id, pick_rank, headline, recorded_at) "
                    + "VALUES (2, 2, 'run 2 plan b', now())");
            st.execute("INSERT INTO pipeline_run_pick "
                    + "(pipeline_run_id, pick_rank, headline, recorded_at) "
                    + "VALUES (3, 1, 'a third pipeline run', now())");
        } catch (SQLException e) {
            throw new AssertionError("distinct (run, rank) pairs must remain insertable", e);
        }

        // 7. The old non-unique V104 index is gone (superseded by the constraint's own index),
        // not left behind as dead, redundant weight on every write.
        try (Connection conn = connect(); Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT indexname FROM pg_indexes "
                                + "WHERE tablename = 'pipeline_run_pick' "
                                + "AND indexname = 'idx_pick_pipeline_run'")) {
            assertThat(rs.next()).as("V104's non-unique index must be dropped by V148").isFalse();
        }
    }

    @Test
    @DisplayName("the dedupe statement is idempotent — re-running it once the table already "
            + "holds at most one row per (pipeline_run_id, pick_rank) deletes nothing")
    void dedupeStatement_isIdempotentOnceClean() throws SQLException {
        flywayTo(null).migrate();

        String dedupeSql = "DELETE FROM pipeline_run_pick p WHERE EXISTS ("
                + "SELECT 1 FROM pipeline_run_pick newer "
                + "WHERE newer.pipeline_run_id = p.pipeline_run_id "
                + "AND newer.pick_rank = p.pick_rank AND newer.id > p.id)";

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO pipeline_run (id, cycle_type, status, trigger_time) "
                    + "VALUES (1, 'NIGHTLY', 'COMPLETED', now())");
            st.execute("INSERT INTO pipeline_run_pick "
                    + "(pipeline_run_id, pick_rank, headline, recorded_at) "
                    + "VALUES (1, 1, 'only one row', now())");

            int deleted = st.executeUpdate(dedupeSql);

            assertThat(deleted).as("a clean table has nothing to dedupe").isZero();
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM pipeline_run_pick WHERE pipeline_run_id = 1")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(1L);
            }
        }
    }

    private List<Long> idsFor(Statement st, long runId, int rank) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(
                "SELECT id FROM pipeline_run_pick "
                        + "WHERE pipeline_run_id = " + runId + " AND pick_rank = " + rank
                        + " ORDER BY id")) {
            while (rs.next()) {
                ids.add(rs.getLong("id"));
            }
        }
        return ids;
    }
}
