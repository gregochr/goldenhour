package com.gregochr.goldenhour.integration;

import org.flywaydb.core.Flyway;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V192 against a real Postgres (sub-regions-plan S2): the {@code app_user_sub_region} table (primary key,
 * both cascades), the two {@code app_user} change columns (nullable, so every existing account reads null), and
 * the single-row {@code sub_region_policy} (seeded with 3, the {@code id = 1} CHECK, the 1..24 CHECK).
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md). Like {@link SubRegionsMigrationTest} it migrates to V191 first so it can plant an account that
 * predates V192. The CI context's boot under {@code ddl-auto: validate} is the separate proof that the entities
 * map these columns and tables exactly.
 */
@Testcontainers
class SubRegionPicksMigrationTest {

    @Container
    @SuppressWarnings("resource")
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("goldenhour_migration_test")
                    .withUsername("test")
                    .withPassword("test");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static long count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long plantUser(Statement st, String username) throws SQLException {
        st.executeUpdate("INSERT INTO app_user (username, password, role, enabled, created_at, "
                + "password_change_required, marketing_email_opt_in) "
                + "VALUES ('" + username + "', 'hash', 'LITE_USER', TRUE, now(), FALSE, TRUE)");
        return count(st, "SELECT id FROM app_user WHERE username = '" + username + "'");
    }

    private static long anySubRegion(Statement st) throws SQLException {
        return count(st, "SELECT id FROM area WHERE kind = 'SUB_REGION' ORDER BY id LIMIT 1");
    }

    @Test
    @DisplayName("V192 seeds the policy row with a limit of 3, and an account that predates it reads null "
            + "for both change columns")
    void seedsThePolicyAndLeavesExistingAccountsNull() throws SQLException {
        flyway("191").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            plantUser(st, "before-v192");
        }

        flyway("192").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(count(st, "SELECT lite_limit FROM sub_region_policy WHERE id = 1")).isEqualTo(3);
            assertThat(count(st, "SELECT count(*) FROM sub_region_policy")).isEqualTo(1);
            assertThat(count(st, "SELECT count(*) FROM app_user WHERE username = 'before-v192' "
                    + "AND sub_regions_grace_until IS NULL AND sub_regions_changed_at IS NULL")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the policy is a single row (id = 1) and its limit is 1..24")
    void policyChecks() throws SQLException {
        flyway("192").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate(
                    "INSERT INTO sub_region_policy (id, lite_limit) VALUES (2, 3)"))
                    .hasMessageContaining("sub_region_policy");
            assertThatThrownBy(() -> st.executeUpdate("UPDATE sub_region_policy SET lite_limit = 0"))
                    .hasMessageContaining("sub_region_policy");
            assertThatThrownBy(() -> st.executeUpdate("UPDATE sub_region_policy SET lite_limit = 25"))
                    .hasMessageContaining("sub_region_policy");
            st.executeUpdate("UPDATE sub_region_policy SET lite_limit = 24");
            st.executeUpdate("UPDATE sub_region_policy SET lite_limit = 1");
            assertThat(count(st, "SELECT lite_limit FROM sub_region_policy")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a pick is one row per reader and sub-region: the primary key refuses a repeat")
    void picksPrimaryKey() throws SQLException {
        flyway("192").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            long user = plantUser(st, "picker");
            long area = anySubRegion(st);
            st.executeUpdate("INSERT INTO app_user_sub_region (user_id, area_id, created_at) VALUES ("
                    + user + ", " + area + ", now())");

            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO app_user_sub_region "
                    + "(user_id, area_id, created_at) VALUES (" + user + ", " + area + ", now())"))
                    .hasMessageContaining("app_user_sub_region_pkey");
        }
    }

    @Test
    @DisplayName("deleting the account (a plain DELETE, as both deletion paths issue) removes its picks")
    void deletingTheAccountCascades() throws SQLException {
        flyway("192").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            long user = plantUser(st, "leaver");
            long area = anySubRegion(st);
            st.executeUpdate("INSERT INTO app_user_sub_region (user_id, area_id, created_at) VALUES ("
                    + user + ", " + area + ", now())");

            st.executeUpdate("DELETE FROM app_user WHERE id = " + user);

            assertThat(count(st, "SELECT count(*) FROM app_user_sub_region WHERE user_id = " + user)).isZero();
            assertThat(count(st, "SELECT count(*) FROM area WHERE id = " + area)).as("the area stays").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("deleting a sub-region area removes the picks of it, and the account stays")
    void deletingTheAreaCascades() throws SQLException {
        flyway("192").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            long user = plantUser(st, "stayer");
            st.executeUpdate("INSERT INTO area (name, kind, region_id) VALUES ('Scratch Sub-region', "
                    + "'SUB_REGION', (SELECT id FROM regions ORDER BY id LIMIT 1))");
            long area = count(st, "SELECT id FROM area WHERE name = 'Scratch Sub-region'");
            st.executeUpdate("INSERT INTO app_user_sub_region (user_id, area_id, created_at) VALUES ("
                    + user + ", " + area + ", now())");

            st.executeUpdate("DELETE FROM area WHERE id = " + area);

            assertThat(count(st, "SELECT count(*) FROM app_user_sub_region WHERE user_id = " + user)).isZero();
            assertThat(count(st, "SELECT count(*) FROM app_user WHERE id = " + user)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a pick must name an existing account and an existing area")
    void picksNeedBothParents() throws SQLException {
        flyway("192").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            long user = plantUser(st, "orphan-maker");
            long area = anySubRegion(st);

            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO app_user_sub_region "
                    + "(user_id, area_id, created_at) VALUES (999999, " + area + ", now())"))
                    .hasMessageContaining("violates foreign key");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO app_user_sub_region "
                    + "(user_id, area_id, created_at) VALUES (" + user + ", 999999, now())"))
                    .hasMessageContaining("violates foreign key");
        }
    }
}
