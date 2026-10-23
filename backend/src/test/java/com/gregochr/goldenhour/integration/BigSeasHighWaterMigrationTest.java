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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V188 ({@code big_seas_high_water}) against a real Postgres 17 engine.
 *
 * <p>Flyway is driven directly, as {@link NorthernBluebellWoodsMigrationTest} does, so the test can
 * stop at V187, add the production locations the seed matches by name, and only then apply V188.
 * V138 has already inserted Roker, Seaham North Pier and Hartlepool Headland, and
 * {@code locations.name} is UNIQUE, so only the other four structure names are inserted here, plus a
 * near-miss decoy ({@code Whitby Abbey}) that must not be seeded.
 */
@Testcontainers
class BigSeasHighWaterMigrationTest {

    /** The seven seeded structures: name to {@code kind|faces|label}. */
    private static final Map<String, String> EXPECTED = new TreeMap<>(Map.of(
            "Roker Pier & Lighthouse", "PIER|45|Roker Pier",
            "St. Mary's Lighthouse", "LIGHTHOUSE|90|St Mary's",
            "Amble", "PIER|45|Amble piers",
            "Saltburn-by-the-Sea", "PIER|0|Saltburn Pier",
            "Whitby Piers", "PIER|0|Whitby piers",
            "Seaham North Pier & Lighthouse", "PIER|90|Seaham Pier",
            "Hartlepool Headland", "BREAKWATER|90|Hartlepool"));

    /** Locations V138 does not insert, hand-added in the test as production has them. */
    private static final List<String> HAND_ADDED = List.of(
            "St. Mary's Lighthouse", "Amble", "Saltburn-by-the-Sea", "Whitby Piers", "Whitby Abbey");

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

    private static String quoted(String name) {
        return "'" + name.replace("'", "''") + "'";
    }

    private void insertLocation(Statement st, String name) throws SQLException {
        st.execute("INSERT INTO locations (name, lat, lon, region_id, enabled, is_coastal_tidal, "
                + "overlooks_water, consecutive_failures, created_at) VALUES (" + quoted(name)
                + ", 54.90, -1.40, NULL, TRUE, TRUE, FALSE, 0, NOW())");
    }

    private long idOf(Statement st, String name) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT id FROM locations WHERE name = " + quoted(name))) {
            assertThat(rs.next()).as("%s exists", name).isTrue();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("V188 seeds exactly the seven structures, by name, with the right kind, faces and label; "
            + "a near-miss decoy name is not seeded")
    void v188_seedsExactlySevenStructures() throws SQLException {
        flywayTo("187").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String name : HAND_ADDED) {
                insertLocation(st, name);
            }
        }

        flywayTo(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            Map<String, String> seeded = new TreeMap<>();
            try (ResultSet rs = st.executeQuery("SELECT name, coastal_structure, structure_faces_deg, "
                    + "structure_label FROM locations WHERE coastal_structure IS NOT NULL")) {
                while (rs.next()) {
                    seeded.put(rs.getString(1),
                            rs.getString(2) + "|" + rs.getInt(3) + "|" + rs.getString(4));
                }
            }
            assertThat(seeded).as("exactly the seven, nothing else").isEqualTo(EXPECTED);

            try (ResultSet rs = st.executeQuery("SELECT coastal_structure, structure_faces_deg, "
                    + "structure_label FROM locations WHERE name = 'Whitby Abbey'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).as("decoy structure").isNull();
                assertThat(rs.getObject(2)).as("decoy faces").isNull();
                assertThat(rs.getString(3)).as("decoy label").isNull();
            }
        }
    }

    @Test
    @DisplayName("V188 CHECKs structure_faces_deg to 0-359; marine_high_water keeps one row per "
            + "(location, hw_time) and refuses an unknown location")
    void v188_constraintsHold() throws SQLException {
        flywayTo(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            long roker = idOf(st, "Roker Pier & Lighthouse");

            assertThatThrownBy(() -> st.execute(
                    "UPDATE locations SET structure_faces_deg = 360 WHERE id = " + roker))
                    .as("faces 360 refused").isInstanceOf(SQLException.class);
            st.execute("UPDATE locations SET structure_faces_deg = 359 WHERE id = " + roker);

            String insert = "INSERT INTO marine_high_water (location_id, hw_time, hw_height_m, "
                    + "window_start, window_end, peak_time, wave_height_m, wave_period_s, "
                    + "swell_wave_height_m, swell_wave_period_s, wave_direction_deg, evaluated_at) "
                    + "VALUES (%d, '2026-12-10 12:00:00', 5.250, '2026-12-10 10:30:00', "
                    + "'2026-12-10 13:30:00', '2026-12-10 12:00:00', 4.0, 11.0, 3.2, 12.0, 30, NOW())";
            st.execute(String.format(insert, roker));

            assertThatThrownBy(() -> st.execute(String.format(insert, roker)))
                    .as("duplicate (location, hw_time) refused").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute(String.format(insert, -1L)))
                    .as("unknown location refused").isInstanceOf(SQLException.class);

            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM marine_high_water")) {
                rs.next();
                assertThat(rs.getInt(1)).as("only the first insert landed").isEqualTo(1);
            }
        }
    }
}
