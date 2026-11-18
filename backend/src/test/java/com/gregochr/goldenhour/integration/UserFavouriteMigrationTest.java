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
 * Proves V226 against a real Postgres (find-and-favourites-plan F3): the {@code user_favourite} table's
 * exactly-one-target CHECK, the two partial unique indexes (a place or an area once per reader, but the same
 * place for two readers, and a place and an area independently), and all three {@code ON DELETE CASCADE} keys.
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md), so this test has never been run locally. The CI context's boot under {@code ddl-auto: validate}
 * is the separate proof that {@code UserFavouriteEntity} maps the table exactly.
 */
@Testcontainers
class UserFavouriteMigrationTest {

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

    private void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("226")
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

    private static void star(Statement st, long user, String target, Long id) throws SQLException {
        st.executeUpdate("INSERT INTO user_favourite (user_id, " + target + ", created_at) VALUES (" + user + ", "
                + id + ", now())");
    }

    @Test
    @DisplayName("a favourite names exactly one target: both and neither are refused by the CHECK")
    void exactlyOneTarget() throws SQLException {
        migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            long user = plantUser(st, "checker");
            long place = plantLocation(st, "Check Hill");
            long area = plantArea(st, "Check Area");

            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO user_favourite "
                    + "(user_id, location_id, area_id, created_at) VALUES (" + user + ", " + place + ", " + area
                    + ", now())")).hasMessageContaining("ck_user_favourite_one_target");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO user_favourite (user_id, created_at) VALUES ("
                    + user + ", now())")).hasMessageContaining("ck_user_favourite_one_target");
            star(st, user, "location_id", place);
            star(st, user, "area_id", area);
            assertThat(count(st, "SELECT count(*) FROM user_favourite WHERE user_id = " + user)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a place and an area are each starred once per reader, but two readers may star the same one")
    void partialUniqueIndexes() throws SQLException {
        migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            long first = plantUser(st, "first");
            long second = plantUser(st, "second");
            long place = plantLocation(st, "Shared Hill");
            long area = plantArea(st, "Shared Area");
            star(st, first, "location_id", place);
            star(st, first, "area_id", area);

            assertThatThrownBy(() -> star(st, first, "location_id", place))
                    .hasMessageContaining("uq_user_favourite_place");
            assertThatThrownBy(() -> star(st, first, "area_id", area))
                    .hasMessageContaining("uq_user_favourite_area");
            star(st, second, "location_id", place);
            star(st, second, "area_id", area);
            assertThat(count(st, "SELECT count(*) FROM user_favourite")).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("deleting the account removes its favourites and leaves the place and the area")
    void deletingTheAccountCascades() throws SQLException {
        migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            long user = plantUser(st, "leaver");
            long place = plantLocation(st, "Leaver Hill");
            long area = plantArea(st, "Leaver Area");
            star(st, user, "location_id", place);
            star(st, user, "area_id", area);

            st.executeUpdate("DELETE FROM app_user WHERE id = " + user);

            assertThat(count(st, "SELECT count(*) FROM user_favourite WHERE user_id = " + user)).isZero();
            assertThat(count(st, "SELECT count(*) FROM locations WHERE id = " + place)).isEqualTo(1);
            assertThat(count(st, "SELECT count(*) FROM area WHERE id = " + area)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("deleting a location removes its stars, and deleting an area removes its stars; the account stays")
    void deletingATargetCascades() throws SQLException {
        migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            long user = plantUser(st, "stayer");
            long place = plantLocation(st, "Doomed Hill");
            long area = plantArea(st, "Doomed Area");
            star(st, user, "location_id", place);
            star(st, user, "area_id", area);

            st.executeUpdate("DELETE FROM locations WHERE id = " + place);
            assertThat(count(st, "SELECT count(*) FROM user_favourite WHERE user_id = " + user)).isEqualTo(1);
            st.executeUpdate("DELETE FROM area WHERE id = " + area);

            assertThat(count(st, "SELECT count(*) FROM user_favourite WHERE user_id = " + user)).isZero();
            assertThat(count(st, "SELECT count(*) FROM app_user WHERE id = " + user)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a favourite must name an existing account, location and area")
    void needsExistingParents() throws SQLException {
        migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            long user = plantUser(st, "orphan-maker");

            assertThatThrownBy(() -> star(st, 999_999L, "location_id", plantLocation(st, "Real Hill")))
                    .hasMessageContaining("violates foreign key");
            assertThatThrownBy(() -> star(st, user, "location_id", 999_999L))
                    .hasMessageContaining("violates foreign key");
            assertThatThrownBy(() -> star(st, user, "area_id", 999_999L))
                    .hasMessageContaining("violates foreign key");
        }
    }
}
