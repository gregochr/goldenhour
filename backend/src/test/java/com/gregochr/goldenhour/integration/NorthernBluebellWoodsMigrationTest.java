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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V169 ({@code northern_bluebell_woods}) against a real Postgres 17 engine.
 *
 * <p>Flyway is driven directly, as {@link ClearDriveTimeStampWithoutRowsMigrationTest} does, so a
 * test can stop at V168, seed a hand-added row (production locations are admin-managed), and only
 * then apply V169. The container is non-static for the same reason that class gives: each method
 * gets a fresh schema.
 */
@Testcontainers
class NorthernBluebellWoodsMigrationTest {

    /** Every name V169 inserts, in alphabetical order, mapped to the region it must land in. */
    private static final Map<String, String> EXPECTED_REGIONS = new TreeMap<>(Map.of(
            "Newton Wood, Roseberry Topping", "North York Moors & Coast",
            "Nidd Gorge", "The Yorkshire Dales",
            "Letah Wood", "Northumberland & Tyneside",
            "Irthing Gorge", "Northumberland & Tyneside",
            "Jesmond Dene Woods", "Northumberland & Tyneside",
            "Denton Dene", "Northumberland & Tyneside",
            "Ragpath Wood", "Northumberland & Tyneside",
            "Lands Wood, Winlaton Mill", "Northumberland & Tyneside",
            "Washingwell Wood", "Northumberland & Tyneside",
            "Dorothy Farrer's Spring Wood", "The Lake District"));

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

    private List<String> stringColumn(Statement st, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private List<String> typesOf(Statement st, String name) throws SQLException {
        return stringColumn(st, "SELECT t.location_type FROM location_location_type t "
                + "JOIN locations l ON l.id = t.location_id WHERE l.name = " + quoted(name)
                + " ORDER BY t.location_type");
    }

    private List<String> eventsOf(Statement st, String name) throws SQLException {
        return stringColumn(st, "SELECT e.solar_event_type FROM location_solar_event_type e "
                + "JOIN locations l ON l.id = e.location_id WHERE l.name = " + quoted(name)
                + " ORDER BY e.solar_event_type");
    }

    @Test
    @DisplayName("V169 adds all ten woods, each an enabled WOODLAND-exposure bluebell wood in its "
            + "region, typed BLUEBELL + WOODLAND (never LANDSCAPE), with both solar events")
    void v169_addsEveryWoodAsAnEnclosedBluebellWood() throws SQLException {
        flywayTo(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (Map.Entry<String, String> wood : EXPECTED_REGIONS.entrySet()) {
                String name = wood.getKey();
                try (ResultSet rs = st.executeQuery("SELECT r.name, l.enabled, l.bluebell_exposure, "
                        + "l.is_coastal_tidal, l.lat, l.lon FROM locations l "
                        + "JOIN regions r ON r.id = l.region_id WHERE l.name = " + quoted(name))) {
                    assertThat(rs.next()).as("%s inserted", name).isTrue();
                    assertThat(rs.getString(1)).as("%s region", name).isEqualTo(wood.getValue());
                    assertThat(rs.getBoolean(2)).as("%s enabled", name).isTrue();
                    assertThat(rs.getString(3)).as("%s exposure", name).isEqualTo("WOODLAND");
                    assertThat(rs.getBoolean(4)).as("%s inland", name).isFalse();
                    // Northern England, every one of them — catches a swapped lat/lon pair.
                    assertThat(rs.getDouble(5)).as("%s lat", name).isBetween(53.9, 55.1);
                    assertThat(rs.getDouble(6)).as("%s lon", name).isBetween(-3.0, -1.0);
                    assertThat(rs.next()).as("%s exactly once", name).isFalse();
                }
                assertThat(typesOf(st, name)).as("%s types", name)
                        .containsExactly("BLUEBELL", "WOODLAND");
                assertThat(eventsOf(st, name)).as("%s events", name)
                        .containsExactly("SUNRISE", "SUNSET");
            }
        }
    }

    @Test
    @DisplayName("V169 adopts a wood an admin already added by hand: no duplicate, the admin's "
            + "exposure and extra type kept, the missing types and events filled in")
    void v169_adoptsAHandAddedRowWithoutOverwritingIt() throws SQLException {
        flywayTo("168").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            long id;
            try (ResultSet rs = st.executeQuery("INSERT INTO locations (name, lat, lon, region_id, "
                    + "enabled, is_coastal_tidal, overlooks_water, consecutive_failures, "
                    + "bluebell_exposure, created_at) VALUES ('Nidd Gorge', 54.02, -1.50, NULL, "
                    + "TRUE, FALSE, FALSE, 0, 'OPEN_FELL', NOW()) RETURNING id")) {
                rs.next();
                id = rs.getLong(1);
            }
            st.execute("INSERT INTO location_location_type (location_id, location_type) "
                    + "VALUES (" + id + ", 'LANDSCAPE')");
            st.execute("INSERT INTO location_solar_event_type (location_id, solar_event_type) "
                    + "VALUES (" + id + ", 'SUNSET')");
        }

        flywayTo(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT r.name, l.bluebell_exposure, l.lat "
                    + "FROM locations l LEFT JOIN regions r ON r.id = l.region_id "
                    + "WHERE l.name = 'Nidd Gorge'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).as("absent region filled in")
                        .isEqualTo("The Yorkshire Dales");
                assertThat(rs.getString(2)).as("the admin's exposure stands")
                        .isEqualTo("OPEN_FELL");
                assertThat(rs.getDouble(3)).as("the admin's coordinates stand").isEqualTo(54.02);
                assertThat(rs.next()).as("adopted, not duplicated").isFalse();
            }
            assertThat(typesOf(st, "Nidd Gorge"))
                    .containsExactly("BLUEBELL", "LANDSCAPE", "WOODLAND");
            assertThat(eventsOf(st, "Nidd Gorge")).containsExactly("SUNRISE", "SUNSET");
        }
    }
}
