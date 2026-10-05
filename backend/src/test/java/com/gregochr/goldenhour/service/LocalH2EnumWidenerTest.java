package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.LocalH2EnumWidener.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link LocalH2EnumWidener} against real, throwaway in-memory H2 databases. */
class LocalH2EnumWidenerTest {

    /** A Java enum standing in for one that gained a value after the table was made. */
    enum Kind { A, B, C }

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

    private static String stored(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                var rows = s.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static Target kind() {
        return new Target("t", "k", Kind.class);
    }

    @Test
    @DisplayName("an old column gains the new values, keeps its rows and its NOT NULL, and then accepts them")
    void widensAnOldColumn() throws SQLException {
        DataSource ds = database();
        sql(ds, "create table t (id int primary key, k enum('A','B') not null)",
                "insert into t values (1,'A'),(2,'B')");

        int altered = new LocalH2EnumWidener(ds).widen(List.of(kind()));

        assertThat(altered).isEqualTo(1);
        sql(ds, "insert into t values (3,'C')");
        assertThat(stored(ds, "select group_concat(k order by id) from t")).isEqualTo("A,B,C");
        assertThatThrownBy(() -> sql(ds, "insert into t values (4, null)")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql(ds, "insert into t values (5, 'Z')")).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("a second run changes nothing: a column already wide enough is not altered")
    void idempotent() throws SQLException {
        DataSource ds = database();
        sql(ds, "create table t (id int primary key, k enum('A','B') not null)");
        LocalH2EnumWidener widener = new LocalH2EnumWidener(ds);

        assertThat(widener.widen(List.of(kind()))).isEqualTo(1);
        assertThat(widener.widen(List.of(kind()))).isZero();
    }

    @Test
    @DisplayName("a column that already allows every value is left alone")
    void alreadyWide() throws SQLException {
        DataSource ds = database();
        sql(ds, "create table t (id int primary key, k enum('C','B','A') not null)");

        assertThat(new LocalH2EnumWidener(ds).widen(List.of(kind()))).isZero();
    }

    @Test
    @DisplayName("a value the file allows that the Java enum no longer declares is kept, and its row survives")
    void retiredValueIsKept() throws SQLException {
        DataSource ds = database();
        sql(ds, "create table t (id int primary key, k enum('A','OLD') not null)",
                "insert into t values (1,'OLD')");

        new LocalH2EnumWidener(ds).widen(List.of(kind()));

        assertThat(stored(ds, "select k from t where id = 1")).isEqualTo("OLD");
        sql(ds, "insert into t values (2,'B'),(3,'C'),(4,'OLD')");
    }

    @Test
    @DisplayName("a nullable enum column stays nullable")
    void nullableStaysNullable() throws SQLException {
        DataSource ds = database();
        sql(ds, "create table t (id int primary key, k enum('A'))");

        new LocalH2EnumWidener(ds).widen(List.of(kind()));

        sql(ds, "insert into t values (1, null)", "insert into t values (2, 'C')");
    }

    @Test
    @DisplayName("a column that is not an enum, and a table that does not exist, are skipped without error")
    void nonEnumAndMissingAreSkipped() throws SQLException {
        DataSource ds = database();
        sql(ds, "create table t (id int primary key, k varchar(20))");

        assertThat(new LocalH2EnumWidener(ds).widen(List.of(kind(), new Target("nope", "k", Kind.class))))
                .isZero();
    }

    @Test
    @DisplayName("the real target: an old job_run.run_type gains ASK and ASK_READY")
    void realTargetGainsTheAskRunTypes() throws SQLException {
        DataSource ds = database();
        sql(ds, "create table job_run (id bigint primary key, run_type enum("
                + LocalH2OldSchemaFixtures.OLD_RUN_TYPES + ") not null)");

        assertThat(new LocalH2EnumWidener(ds).widen(LocalH2EnumWidener.TARGETS)).isEqualTo(1);

        sql(ds, "insert into job_run values (1, '" + RunType.ASK.name() + "')",
                "insert into job_run values (2, '" + RunType.ASK_READY.name() + "')");
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

        assertThat(new LocalH2EnumWidener(ds).widen(List.of(kind()))).isZero();

        verify(connection, never()).createStatement();
        verify(connection, never()).prepareStatement(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("a table or column name that is not a plain identifier is refused before any DDL")
    void identifiersAreValidated() {
        DataSource ds = database();
        LocalH2EnumWidener widener = new LocalH2EnumWidener(ds);

        assertThatThrownBy(() -> widener.widen(List.of(new Target("t; drop table t", "k", Kind.class))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> widener.widen(List.of(new Target("t", "k'--", Kind.class))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("startup never fails on a database error: it logs and carries on")
    void startupSwallowsFailures() throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("down"));

        assertThatCode(() -> new LocalH2EnumWidener(ds).afterSingletonsInstantiated()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("startup on a real old database widens the registered column")
    void startupWidens() throws SQLException {
        DataSource ds = database();
        sql(ds, "create table job_run (id bigint primary key, run_type enum("
                + LocalH2OldSchemaFixtures.OLD_RUN_TYPES + ") not null)");

        new LocalH2EnumWidener(ds).afterSingletonsInstantiated();

        sql(ds, "insert into job_run values (1, 'ASK')");
    }
}
