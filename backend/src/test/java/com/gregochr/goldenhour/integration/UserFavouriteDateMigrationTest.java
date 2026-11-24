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
 * Proves V229 against a real Postgres (one-screen-plan D16): {@code user_favourite.almanac_key} as the third target
 * of a favourite. One test per rule: the one-target CHECK now reads "exactly one of three"; a date is watched once
 * per reader, but by two readers, alongside a place and an area; the account's deletion removes its date rows; and
 * the rows written under V226 survive the upgrade and still satisfy the new CHECK.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine (see CLAUDE.md), so
 * this test has never been run locally.
 */
@Testcontainers
class UserFavouriteDateMigrationTest {

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

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
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
                + "VALUES ('" + username + "', 'hash', 'PRO_USER', TRUE, now(), FALSE, TRUE)");
        return count(st, "SELECT id FROM app_user WHERE username = '" + username + "'");
    }

    private static long plantLocation(Statement st, String name) throws SQLException {
        st.executeUpdate("INSERT INTO locations (name, lat, lon) VALUES ('" + name + "', 55.0, -1.5)");
        return count(st, "SELECT id FROM locations WHERE name = '" + name + "'");
    }

    private static long plantArea(Statement st, String name) throws SQLException {
        st.executeUpdate("INSERT INTO area (name) VALUES ('" + name + "')");
        return count(st, "SELECT id FROM area WHERE name = '" + name + "'");
    }

    private static void watch(Statement st, long user, String key) throws SQLException {
        st.executeUpdate("INSERT INTO user_favourite (user_id, almanac_key, created_at) VALUES (" + user + ", '"
                + key + "', now())");
    }

    @Test
    @DisplayName("a favourite names exactly one of place, area and date: none, any two and all three are refused")
    void exactlyOneOfThreeTargets() throws SQLException {
        migrateTo("229");
        try (Connection c = connect(); Statement st = c.createStatement()) {
            long user = plantUser(st, "checker");
            long place = plantLocation(st, "Check Hill");
            long area = plantArea(st, "Check Area");

            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO user_favourite (user_id, created_at) VALUES ("
                    + user + ", now())")).hasMessageContaining("ck_user_favourite_one_target");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO user_favourite "
                    + "(user_id, location_id, area_id, created_at) VALUES (" + user + ", " + place + ", " + area
                    + ", now())")).hasMessageContaining("ck_user_favourite_one_target");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO user_favourite "
                    + "(user_id, location_id, almanac_key, created_at) VALUES (" + user + ", " + place
                    + ", 'king-tide:2026-12-03', now())")).hasMessageContaining("ck_user_favourite_one_target");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO user_favourite "
                    + "(user_id, area_id, almanac_key, created_at) VALUES (" + user + ", " + area
                    + ", 'king-tide:2026-12-03', now())")).hasMessageContaining("ck_user_favourite_one_target");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO user_favourite "
                    + "(user_id, location_id, area_id, almanac_key, created_at) VALUES (" + user + ", " + place
                    + ", " + area + ", 'king-tide:2026-12-03', now())"))
                    .hasMessageContaining("ck_user_favourite_one_target");
            assertThat(count(st, "SELECT count(*) FROM user_favourite WHERE user_id = " + user)).isZero();

            watch(st, user, "king-tide:2026-12-03");
            assertThat(count(st, "SELECT count(*) FROM user_favourite WHERE user_id = " + user)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a date is watched once per reader, by two readers, and beside a place and an area")
    void partialUniqueIndexOnDates() throws SQLException {
        migrateTo("229");
        try (Connection c = connect(); Statement st = c.createStatement()) {
            long first = plantUser(st, "first");
            long second = plantUser(st, "second");
            long place = plantLocation(st, "Shared Hill");
            long area = plantArea(st, "Shared Area");
            st.executeUpdate("INSERT INTO user_favourite (user_id, location_id, created_at) VALUES (" + first + ", "
                    + place + ", now())");
            st.executeUpdate("INSERT INTO user_favourite (user_id, area_id, created_at) VALUES (" + first + ", "
                    + area + ", now())");
            watch(st, first, "king-tide:2026-12-03");

            assertThatThrownBy(() -> watch(st, first, "king-tide:2026-12-03"))
                    .hasMessageContaining("uq_user_favourite_date");
            watch(st, first, "spring-tide:2026-12-03");
            watch(st, first, "king-tide:2026-12-17");
            watch(st, second, "king-tide:2026-12-03");
            assertThat(count(st, "SELECT count(*) FROM user_favourite WHERE almanac_key IS NOT NULL")).isEqualTo(4);
            assertThat(count(st, "SELECT count(*) FROM user_favourite WHERE user_id = " + first)).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("deleting the account removes its watched dates")
    void deletingTheAccountCascadesDates() throws SQLException {
        migrateTo("229");
        try (Connection c = connect(); Statement st = c.createStatement()) {
            long leaver = plantUser(st, "leaver");
            long stayer = plantUser(st, "stayer");
            watch(st, leaver, "meteor:2026-12-14");
            watch(st, stayer, "meteor:2026-12-14");

            st.executeUpdate("DELETE FROM app_user WHERE id = " + leaver);

            assertThat(count(st, "SELECT count(*) FROM user_favourite WHERE user_id = " + leaver)).isZero();
            assertThat(count(st, "SELECT count(*) FROM user_favourite WHERE user_id = " + stayer)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("favourites written under V226 survive the upgrade, with no almanac key, and the old rules still hold")
    void existingRowsSurviveTheUpgrade() throws SQLException {
        migrateTo("226");
        long user;
        long place;
        long area;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            user = plantUser(st, "veteran");
            place = plantLocation(st, "Old Hill");
            area = plantArea(st, "Old Area");
            st.executeUpdate("INSERT INTO user_favourite (user_id, location_id, created_at) VALUES (" + user + ", "
                    + place + ", now())");
            st.executeUpdate("INSERT INTO user_favourite (user_id, area_id, created_at) VALUES (" + user + ", "
                    + area + ", now())");
        }

        migrateTo("229");

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(count(st, "SELECT count(*) FROM user_favourite WHERE user_id = " + user
                    + " AND almanac_key IS NULL")).isEqualTo(2);
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO user_favourite "
                    + "(user_id, location_id, created_at) VALUES (" + user + ", " + place + ", now())"))
                    .hasMessageContaining("uq_user_favourite_place");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO user_favourite "
                    + "(user_id, area_id, created_at) VALUES (" + user + ", " + area + ", now())"))
                    .hasMessageContaining("uq_user_favourite_area");
            watch(st, user, "supermoon:2026-12-04");
            assertThat(count(st, "SELECT count(*) FROM user_favourite WHERE user_id = " + user)).isEqualTo(3);
        }
    }
}
