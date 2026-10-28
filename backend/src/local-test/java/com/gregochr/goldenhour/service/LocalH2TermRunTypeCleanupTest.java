package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link LocalH2TermRunTypeCleanup} against throwaway in-memory H2 databases. */
class LocalH2TermRunTypeCleanupTest {

    private static DataSource database() {
        return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static void sql(DataSource ds, String... statements) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    private static long count(DataSource ds, String fromClause) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                var rows = s.executeQuery("select count(*) from " + fromClause)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void oldSchema(DataSource ds) throws SQLException {
        sql(ds,
                "create table job_run (id bigint primary key, run_type enum("
                        + LocalH2OldSchemaFixtures.OLD_RUN_TYPES + ") not null)",
                "create table api_call_log (id bigint primary key, job_run_id bigint not null)",
                "create table model_selection (id bigint primary key, run_type enum("
                        + LocalH2OldSchemaFixtures.OLD_RUN_TYPES + ") not null)",
                "create table prompt_test_run (id bigint primary key)",
                "create table prompt_test_result (id bigint primary key, test_run_id bigint "
                        + "references prompt_test_run(id))",
                "insert into job_run values (1,'VERY_SHORT_TERM'),(2,'SHORT_TERM'),(3,'LONG_TERM'),"
                        + "(4,'SCHEDULED_BATCH')",
                "insert into api_call_log values (1,1),(2,2),(3,3),(4,4)",
                "insert into model_selection values (1,'SHORT_TERM'),(2,'BATCH_NEAR_TERM')",
                "insert into prompt_test_run values (1)",
                "insert into prompt_test_result values (1,1)");
    }

    @Test
    @DisplayName("an old database loses the term runs, their api_call_log children, the term model rows "
            + "and the prompt-test rows, and keeps everything live")
    void clearsAnOldDatabase() throws SQLException {
        DataSource ds = database();
        oldSchema(ds);

        int deleted = new LocalH2TermRunTypeCleanup(ds).clean();

        assertThat(deleted).isEqualTo(3 + 3 + 1 + 1 + 1);
        assertThat(count(ds, "job_run")).isEqualTo(1);
        assertThat(count(ds, "job_run where run_type = 'SCHEDULED_BATCH'")).isEqualTo(1);
        assertThat(count(ds, "api_call_log")).isEqualTo(1);
        assertThat(count(ds, "api_call_log where job_run_id = 4")).isEqualTo(1);
        assertThat(count(ds, "model_selection")).isEqualTo(1);
        assertThat(count(ds, "prompt_test_result")).isZero();
        assertThat(count(ds, "prompt_test_run")).isZero();
    }

    @Test
    @DisplayName("a second run changes nothing")
    void idempotent() throws SQLException {
        DataSource ds = database();
        oldSchema(ds);
        LocalH2TermRunTypeCleanup cleanup = new LocalH2TermRunTypeCleanup(ds);

        cleanup.clean();

        assertThat(cleanup.clean()).isZero();
    }

    @Test
    @DisplayName("a fresh schema (no prompt-test tables, a run_type enum without the old labels) is a no-op, "
            + "not an error")
    void freshSchemaIsFine() throws SQLException {
        DataSource ds = database();
        sql(ds, "create table job_run (id bigint primary key, run_type enum('TIDE','SCHEDULED_BATCH') not null)",
                "create table model_selection (id bigint primary key, run_type enum('BATCH_NEAR_TERM') not null)",
                "insert into job_run values (1,'TIDE')");

        assertThat(new LocalH2TermRunTypeCleanup(ds).clean()).isZero();
        assertThat(count(ds, "job_run")).isEqualTo(1);
    }

    @Test
    @DisplayName("a database with none of the tables is skipped without error")
    void missingTablesAreSkipped() throws SQLException {
        assertThat(new LocalH2TermRunTypeCleanup(database()).clean()).isZero();
    }

    @Test
    @DisplayName("a database that is not H2 is never touched: no statement is even created")
    void nonH2IsLeftAlone() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");

        assertThat(new LocalH2TermRunTypeCleanup(ds).clean()).isZero();

        verify(connection, never()).createStatement();
        verify(connection, never()).prepareStatement(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("startup never fails on a database error: it logs and carries on")
    void startupSwallowsFailures() throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("down"));

        assertThatCode(() -> new LocalH2TermRunTypeCleanup(ds).afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("startup on a real old database clears the retired rows")
    void startupClears() throws SQLException {
        DataSource ds = database();
        oldSchema(ds);

        new LocalH2TermRunTypeCleanup(ds).afterSingletonsInstantiated();

        assertThat(count(ds, "job_run")).isEqualTo(1);
    }
}
