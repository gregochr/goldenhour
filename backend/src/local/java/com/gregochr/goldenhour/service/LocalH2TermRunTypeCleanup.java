package com.gregochr.goldenhour.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Clears the rows of the three retired term run types ({@code VERY_SHORT_TERM}, {@code SHORT_TERM},
 * {@code LONG_TERM}) and the Prompt Test harness from an <em>existing</em> local H2 database, under
 * the {@code local} profile only. It is the local twin of Flyway's {@code V196}, which local runs
 * never apply ({@code ddl-auto: update}).
 *
 * <p><b>Why it is needed.</b> The constants are gone from {@code RunType}, so Hibernate throws when
 * it reads a {@code job_run} or {@code model_selection} row that still holds one. H2's native
 * {@code ENUM} column keeps accepting the old labels ({@link LocalH2EnumWidener} never drops one),
 * so a developer's {@code backend/data} file can still hold such rows and would fail on its first
 * Job Runs or Models read until the file was deleted.
 *
 * <p><b>What it deletes.</b> The term-type {@code job_run} rows and their {@code api_call_log}
 * children (Hibernate's H2 schema has no {@code ON DELETE CASCADE}, so they go explicitly), the
 * term-type {@code model_selection} rows, and every {@code prompt_test_result} then
 * {@code prompt_test_run} row when those tables exist. Run types are compared as text, so the
 * statement works whether the column is a native {@code ENUM} that still lists the labels or one a
 * fresh file created without them.
 *
 * <p><b>Ordering.</b> It runs in {@code afterSingletonsInstantiated}, after the schema update and
 * before the application is ready, so before any scheduled job, runner or request reads
 * {@code job_run}. It is idempotent, does nothing unless the database is H2, and never fails
 * startup: a failure is a WARN naming the symptom.
 */
@Component
@Profile("local")
public class LocalH2TermRunTypeCleanup implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(LocalH2TermRunTypeCleanup.class);

    /** The retired run types, as the literal labels an old file may still hold. */
    private static final String TERM_RUN_TYPES = "('VERY_SHORT_TERM','SHORT_TERM','LONG_TERM')";

    private final DataSource dataSource;

    /**
     * Creates the cleanup.
     *
     * @param dataSource the application's datasource
     */
    public LocalH2TermRunTypeCleanup(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            clean();
        } catch (SQLException | RuntimeException e) {
            LOG.warn("[LOCAL H2] Could not clear the retired term run-type rows; Job Runs or Models may fail "
                    + "to load until backend/data is deleted: {}", e.toString());
        }
    }

    /**
     * Deletes the retired rows when the database is H2.
     *
     * @return how many rows were deleted, across all tables; zero on a non-H2 database
     * @throws SQLException if the database cannot be read or written
     */
    int clean() throws SQLException {
        int deleted = 0;
        try (Connection connection = dataSource.getConnection()) {
            if (!"H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                return 0;
            }
            if (tableExists(connection, "job_run")) {
                if (tableExists(connection, "api_call_log")) {
                    deleted += execute(connection, "delete from api_call_log where job_run_id in "
                            + "(select id from job_run where cast(run_type as varchar) in " + TERM_RUN_TYPES + ")");
                }
                deleted += execute(connection,
                        "delete from job_run where cast(run_type as varchar) in " + TERM_RUN_TYPES);
            }
            if (tableExists(connection, "model_selection")) {
                deleted += execute(connection,
                        "delete from model_selection where cast(run_type as varchar) in " + TERM_RUN_TYPES);
            }
            if (tableExists(connection, "prompt_test_result")) {
                deleted += execute(connection, "delete from prompt_test_result");
            }
            if (tableExists(connection, "prompt_test_run")) {
                deleted += execute(connection, "delete from prompt_test_run");
            }
        }
        if (deleted > 0) {
            LOG.info("[LOCAL H2] Removed {} rows of the retired term run types and the Prompt Test harness",
                    deleted);
        }
        return deleted;
    }

    private static int execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        String sql = "select count(*) from information_schema.tables "
                + "where table_schema = schema() and upper(table_name) = upper(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) > 0;
            }
        }
    }
}
