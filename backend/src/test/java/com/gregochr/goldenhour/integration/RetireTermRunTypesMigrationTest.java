package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.RunType;
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

/**
 * Proves V196 ({@code retire_term_run_types}) against a real Postgres 17 engine.
 *
 * <p>Flyway is driven directly, as {@link RetireDeadOptimisationStrategiesMigrationTest} does, so
 * the test can stop at V195, seed the rows V196 exists to remove with raw SQL, and only then apply
 * it. A Spring-booted test would migrate straight to latest and never see them.
 *
 * <p>⚠️ What makes this migration load-bearing: {@code job_run.run_type} and
 * {@code model_selection.run_type} map through {@code @Enumerated(STRING)}, and the three term
 * constants are deleted from {@code RunType} in the same change. One row left behind makes
 * Hibernate throw on read. So the assertion that matters most is that every distinct stored run
 * type is a constant the enum still declares.
 */
@Testcontainers
class RetireTermRunTypesMigrationTest {

    private static final List<String> TERM_TYPES = List.of("VERY_SHORT_TERM", "SHORT_TERM", "LONG_TERM");

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

    private static long insertJobRun(Statement st, String runType) throws SQLException {
        try (ResultSet rs = st.executeQuery("INSERT INTO job_run (run_type, started_at) VALUES ('"
                + runType + "', CURRENT_TIMESTAMP) RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void insertApiCall(Statement st, long jobRunId) throws SQLException {
        st.execute("INSERT INTO api_call_log (job_run_id, service, called_at) VALUES ("
                + jobRunId + ", 'ANTHROPIC', CURRENT_TIMESTAMP)");
    }

    private static long count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<String> distinct(Statement st, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT DISTINCT run_type FROM " + table)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    @DisplayName("V196 removes the term-type job_run and model_selection rows with their children, empties "
            + "the Prompt Test tables, drops optimisation_strategy, and leaves live rows alone")
    void v196_retiresTermRunTypes() throws SQLException {
        // 1. Stop at V195: the schema as production holds it before this change.
        flywayTo("195").migrate();

        long controlRun;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String term : TERM_TYPES) {
                insertApiCall(st, insertJobRun(st, term));
            }
            controlRun = insertJobRun(st, "SCHEDULED_BATCH");
            insertApiCall(st, controlRun);

            st.execute("INSERT INTO locations (name, lat, lon) VALUES ('V196 test location', 55.0, -1.5)");
            st.execute("INSERT INTO prompt_test_run (started_at, target_date, target_type, evaluation_model, "
                    + "run_type) VALUES (CURRENT_TIMESTAMP, CURRENT_DATE, 'SUNRISE', 'HAIKU', 'SHORT_TERM'), "
                    + "(CURRENT_TIMESTAMP, CURRENT_DATE, 'SUNSET', 'HAIKU', 'LONG_TERM')");
            // A parent/child pair (fk_ptr_parent, V44) so the single DELETE exercises the self-FK.
            st.execute("INSERT INTO prompt_test_run (started_at, target_date, target_type, evaluation_model, "
                    + "run_type, parent_run_id) SELECT CURRENT_TIMESTAMP, CURRENT_DATE, 'SUNRISE', 'HAIKU', "
                    + "'SHORT_TERM', MIN(id) FROM prompt_test_run");
            st.execute("INSERT INTO prompt_test_result (test_run_id, location_id, location_name, target_date, "
                    + "target_type, evaluation_model, created_at) "
                    + "SELECT r.id, l.id, l.name, CURRENT_DATE, 'SUNRISE', 'HAIKU', CURRENT_TIMESTAMP "
                    + "FROM prompt_test_run r, locations l WHERE l.name = 'V196 test location'");

            // Positive controls: the rows V196 exists to remove really are there.
            assertThat(count(st, "SELECT count(*) FROM job_run WHERE run_type IN ('VERY_SHORT_TERM',"
                    + "'SHORT_TERM','LONG_TERM')")).isEqualTo(3);
            assertThat(count(st, "SELECT count(*) FROM api_call_log")).isEqualTo(4);
            assertThat(count(st, "SELECT count(*) FROM model_selection WHERE run_type IN ('VERY_SHORT_TERM',"
                    + "'SHORT_TERM','LONG_TERM')")).isEqualTo(3);
            assertThat(count(st, "SELECT count(*) FROM prompt_test_run")).isEqualTo(3);
            assertThat(count(st, "SELECT count(*) FROM prompt_test_result")).isEqualTo(3);
            assertThat(count(st, "SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_name = 'optimisation_strategy'")).isEqualTo(1);
        }

        // 2. Apply V196 only: V198 drops the Prompt Test tables this test still queries afterwards.
        flywayTo("196").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT count(*) FROM job_run WHERE run_type IN ('VERY_SHORT_TERM',"
                    + "'SHORT_TERM','LONG_TERM')")).isZero();
            assertThat(count(st, "SELECT count(*) FROM model_selection WHERE run_type IN ('VERY_SHORT_TERM',"
                    + "'SHORT_TERM','LONG_TERM')")).isZero();

            // The children of the term runs went by cascade; the control run and its child remain.
            assertThat(count(st, "SELECT count(*) FROM api_call_log")).isEqualTo(1);
            assertThat(count(st, "SELECT count(*) FROM api_call_log WHERE job_run_id = " + controlRun))
                    .isEqualTo(1);
            assertThat(count(st, "SELECT count(*) FROM job_run WHERE run_type = 'SCHEDULED_BATCH'"))
                    .isEqualTo(1);

            // Live model_selection rows are untouched.
            assertThat(count(st, "SELECT count(*) FROM model_selection WHERE run_type = 'BATCH_NEAR_TERM'"))
                    .isEqualTo(1);

            // Prompt Test rows are gone, the tables stay (V198 drops them).
            assertThat(count(st, "SELECT count(*) FROM prompt_test_result")).isZero();
            assertThat(count(st, "SELECT count(*) FROM prompt_test_run")).isZero();

            assertThat(count(st, "SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_name = 'optimisation_strategy'")).isZero();

            // Guard: no batch-side row points at a job run that no longer exists.
            assertThat(count(st, "SELECT count(*) FROM forecast_batch WHERE job_run_id IS NOT NULL "
                    + "AND job_run_id NOT IN (SELECT id FROM job_run)")).isZero();
            assertThat(count(st, "SELECT count(*) FROM forecast_run_disposition WHERE job_run_id IS NOT NULL "
                    + "AND job_run_id NOT IN (SELECT id FROM job_run)")).isZero();
            assertThat(count(st, "SELECT count(*) FROM pipeline_run WHERE disposition_job_run_id IS NOT NULL "
                    + "AND disposition_job_run_id NOT IN (SELECT id FROM job_run)")).isZero();

            // The load-bearing property: nothing left for @Enumerated(STRING) to choke on.
            List<String> live = java.util.Arrays.stream(RunType.values()).map(Enum::name).toList();
            assertThat(live).containsAll(distinct(st, "job_run"));
            assertThat(live).containsAll(distinct(st, "model_selection"));
        }
    }
}
