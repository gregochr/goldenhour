package com.gregochr.goldenhour.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V214 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}): {@code job_run}
 * gains a nullable {@code pipeline_run_id}, indexed, so a cycle's chained briefing and ask-ready runs can be found
 * by the cycle, and a row written without it (every row before V214, every manual run) still inserts and reads null.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md), so the migration is proven before merge only by CI's Backend job.
 */
class JobRunPipelineRunIdMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("job_run.pipeline_run_id exists, is a nullable bigint, and has no foreign key")
    void columnIsNullableBigintWithoutForeignKey() {
        String type = jdbc.queryForObject("SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'job_run' AND column_name = 'pipeline_run_id'", String.class);
        String nullable = jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'job_run' AND column_name = 'pipeline_run_id'", String.class);
        Integer foreignKeys = jdbc.queryForObject("SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'job_run' AND constraint_type = 'FOREIGN KEY'", Integer.class);

        assertThat(type).isEqualTo("bigint");
        assertThat(nullable).isEqualTo("YES");
        assertThat(foreignKeys).isZero();
    }

    @Test
    @DisplayName("the column is indexed, so a cycle's children are found by id")
    void columnIsIndexed() {
        Integer indexes = jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename = 'job_run' "
                + "AND indexname = 'idx_job_run_pipeline_run_id'", Integer.class);

        assertThat(indexes).isEqualTo(1);
    }

    @Test
    @DisplayName("a run inserted without a cycle reads back null, and one with a cycle keeps it")
    void insertWithAndWithoutCycle() {
        jdbc.update("INSERT INTO job_run (run_type, started_at, triggered_manually) VALUES (?, now(), false)",
                "TIDE");
        jdbc.update("INSERT INTO job_run (run_type, started_at, triggered_manually, pipeline_run_id) "
                + "VALUES (?, now(), false, ?)", "BRIEFING", 4242L);
        try {
            Long tide = jdbc.queryForObject("SELECT pipeline_run_id FROM job_run WHERE run_type = 'TIDE' "
                    + "ORDER BY id DESC LIMIT 1", Long.class);
            Long briefing = jdbc.queryForObject("SELECT pipeline_run_id FROM job_run WHERE run_type = 'BRIEFING' "
                    + "AND pipeline_run_id = 4242", Long.class);

            assertThat(tide).isNull();
            assertThat(briefing).isEqualTo(4242L);
        } finally {
            jdbc.update("DELETE FROM job_run WHERE run_type = 'TIDE' OR pipeline_run_id = 4242");
        }
    }
}
