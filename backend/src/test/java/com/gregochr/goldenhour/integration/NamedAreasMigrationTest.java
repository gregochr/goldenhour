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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V189 against a real Postgres: the {@code area}, {@code area_alias}, {@code area_location} and
 * {@code location_alias} tables, the case-insensitive unique index on the area name, and the by-name seed
 * (Hadrian's Wall members, including a curly-apostrophe spelling, and the Heugh aliases).
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md). It migrates to V188 first so it can plant locations before V189 seeds by name, which
 * {@code IntegrationTestBase} (already fully migrated) cannot do. The CI context's boot under
 * {@code ddl-auto: validate} is the separate proof that the entities map these tables exactly.
 */
@Testcontainers
class NamedAreasMigrationTest {

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

    private static List<String> strings(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    @DisplayName("V189 seeds Hadrian's Wall with its aliases and the matching planted locations, and the Heugh aliases")
    void seedsAreaMembersAndAliases() throws SQLException {
        flyway("188").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO locations (name, lat, lon) VALUES "
                    + "('Walltown Crags', 55.0, -2.5), "
                    + "('Cuddy’s Crags', 55.0, -2.4), "
                    + "('Steel Rigg & Winshield Crags', 55.0, -2.3), "
                    + "('Allen Banks', 54.95, -2.3)");
        }

        flyway("189").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(strings(st, "SELECT name FROM area")).containsExactly("Hadrian's Wall");
            assertThat(strings(st, "SELECT aa.alias FROM area_alias aa JOIN area a ON a.id = aa.area_id "
                    + "WHERE a.name = 'Hadrian''s Wall'"))
                    .containsExactlyInAnyOrder("Hadrian Wall", "Roman Wall");
            assertThat(strings(st, "SELECT l.name FROM area_location al JOIN locations l ON l.id = al.location_id"))
                    .as("the three planted Wall spellings joined, plus Irthing Gorge, which V169 inserts on a "
                            + "fresh database; Allen Banks did not")
                    .containsExactlyInAnyOrder("Walltown Crags", "Cuddy’s Crags",
                            "Steel Rigg & Winshield Crags", "Irthing Gorge")
                    .doesNotContain("Allen Banks");
            assertThat(strings(st, "SELECT la.alias FROM location_alias la JOIN locations l "
                    + "ON l.id = la.location_id WHERE l.name = 'Hartlepool Headland'"))
                    .containsExactlyInAnyOrder("The Heugh", "Heugh Lighthouse", "Heugh Battery");
        }
    }

    @Test
    @DisplayName("the unique index refuses a case-variant area name")
    void uniqueIndexIsCaseInsensitive() throws SQLException {
        flyway("189").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO area (name) VALUES ('hadrian''s wall')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_area_name_lower");
            st.executeUpdate("INSERT INTO area (name) VALUES ('Kielder')");
            try (ResultSet rs = st.executeQuery("SELECT created_at FROM area WHERE name = 'Kielder'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getTimestamp(1)).as("created_at defaults").isNotNull();
            }
        }
    }
}
