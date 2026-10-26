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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V191 against a real Postgres (sub-regions-plan S1): the {@code area.kind} and {@code area.region_id}
 * columns and their CHECKs and key, the {@code location_sub_region} table (primary key, cascade, restrict), the
 * seed of the 24 sub-regions with their aliases, the by-name-and-region membership seed (stored and corrected
 * spellings, curly apostrophes, a same-named location in another region), the pre-flight that raises on a name
 * clash (including the guard's folding), and the one allowed containment ("Hadrian's Wall &amp; Tynedale").
 *
 * <p>CI-only: it starts its own Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md). Like {@link NamedAreasMigrationTest} it migrates to V190 first so it can plant rows before
 * V191 seeds by name, which {@code IntegrationTestBase} (already fully migrated) cannot do. The CI context's
 * boot under {@code ddl-auto: validate} is the separate proof that the entities map these columns and the new
 * table exactly.
 */
@Testcontainers
class SubRegionsMigrationTest {

    private static final String NT = "Northumberland & Tyneside";
    private static final String LAKES = "The Lake District";
    private static final String DALES = "The Yorkshire Dales";
    private static final String NYM = "North York Moors & Coast";

    /** The 24 sub-regions of plan §3, each with its region. */
    private static final Map<String, String> SUB_REGIONS = new LinkedHashMap<>();

    static {
        for (String name : List.of("Berwick & Holy Island", "Bamburgh to Howick", "Alnwick & the Coquet Coast",
                "Druridge Bay to Tynemouth", "Cheviots & Coquetdale", "Hadrian's Wall & Tynedale",
                "Newcastle & Durham", "Sunderland & the Durham Coast")) {
            SUB_REGIONS.put(name, NT);
        }
        for (String name : List.of("Windermere & Coniston", "The Langdales", "Ambleside, Grasmere & Rydal",
                "Wasdale, Eskdale & Duddon", "Buttermere, Loweswater & Ennerdale", "Keswick & Borrowdale",
                "Ullswater & Haweswater")) {
            SUB_REGIONS.put(name, LAKES);
        }
        for (String name : List.of("The Three Peaks", "Malhamdale & Airedale", "Wensleydale", "Swaledale",
                "Wharfedale", "Nidderdale")) {
            SUB_REGIONS.put(name, DALES);
        }
        for (String name : List.of("Redcar to Whitby", "Robin Hood's Bay to Flamborough",
                "Inland North York Moors")) {
            SUB_REGIONS.put(name, NYM);
        }
    }

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

    private static Map<String, String> pairs(Statement st, String sql) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        }
        return out;
    }

    private static long count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String quoted(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /** Plants an enabled location in a region (null for none), by region name. */
    private static void plant(Statement st, String name, String region) throws SQLException {
        String regionId = region == null ? "NULL" : "(SELECT id FROM regions WHERE name = " + quoted(region) + ")";
        st.executeUpdate("INSERT INTO locations (name, lat, lon, region_id) VALUES (" + quoted(name)
                + ", 54.5, -2.0, " + regionId + ")");
    }

    /** Migrates to V190, lets the caller plant, then asserts V191 refuses with the pre-flight's message. */
    private void assertPreflightRaises(PlantAction plant, String... messageParts) throws SQLException {
        flyway("190").migrate();
        long areasBefore;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            plant.run(st);
            areasBefore = count(st, "SELECT count(*) FROM area");
        }

        assertThatThrownBy(() -> flyway("191").migrate())
                .hasMessageContaining("V191 pre-flight")
                .satisfies(e -> {
                    for (String part : messageParts) {
                        assertThat(e.getMessage()).contains(part);
                    }
                });

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(count(st, "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_name = 'area' AND column_name = 'kind'"))
                    .as("a raised pre-flight rolls the whole migration back").isZero();
            assertThat(count(st, "SELECT count(*) FROM area")).as("no sub-region was inserted")
                    .isEqualTo(areasBefore);
        }
    }

    /** What a test plants between V190 and V191. */
    @FunctionalInterface
    private interface PlantAction {
        void run(Statement st) throws SQLException;
    }

    @Test
    @DisplayName("V191 seeds the 24 sub-regions, each of kind SUB_REGION in its region, and leaves V189's "
            + "Hadrian's Wall a NAMED area with no region")
    void seedsTheTwentyFourSubRegions() throws SQLException {
        flyway("190").migrate();
        flyway("191").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            Map<String, String> subRegions = pairs(st, "SELECT a.name, r.name FROM area a "
                    + "JOIN regions r ON r.id = a.region_id WHERE a.kind = 'SUB_REGION'");
            assertThat(subRegions).containsExactlyInAnyOrderEntriesOf(SUB_REGIONS);
            assertThat(pairs(st, "SELECT name, kind || '/' || coalesce(region_id::text, 'none') FROM area "
                    + "WHERE name = 'Hadrian''s Wall'")).containsEntry("Hadrian's Wall", "NAMED/none");
            assertThat(count(st, "SELECT count(*) FROM area")).isEqualTo(25);
        }
    }

    @Test
    @DisplayName("V191 seeds the two-word and one-word aliases, 'Tynedale' and 'Kielder' on the Wall "
            + "sub-region, and leaves the Wall area's aliases alone")
    void seedsTheAliases() throws SQLException {
        flyway("190").migrate();
        flyway("191").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(strings(st, aliasesOf("Hadrian's Wall & Tynedale")))
                    .containsExactlyInAnyOrder("South Tyne", "North Tyne", "Tynedale", "Kielder");
            assertThat(strings(st, aliasesOf("Swaledale"))).containsExactlyInAnyOrder("Upper Teesdale", "Teesdale");
            assertThat(strings(st, aliasesOf("The Three Peaks"))).containsExactlyInAnyOrder(
                    "Three Peaks", "Yorkshire Three Peaks", "Ribblesdale", "Dentdale");
            assertThat(strings(st, aliasesOf("Keswick & Borrowdale")))
                    .containsExactlyInAnyOrder("Derwentwater", "Borrowdale", "Keswick");
            assertThat(strings(st, aliasesOf("Sunderland & the Durham Coast")))
                    .containsExactlyInAnyOrder("Durham Heritage Coast", "Durham coast", "Sunderland coast");
            assertThat(strings(st, aliasesOf("Hadrian's Wall")))
                    .as("the NAMED Wall area is untouched").containsExactlyInAnyOrder("Hadrian Wall", "Roman Wall");
            assertThat(count(st, "SELECT count(*) FROM area_alias aa JOIN area a ON a.id = aa.area_id "
                    + "WHERE a.kind = 'SUB_REGION'")).as("40 aliases in all").isEqualTo(40);
        }
    }

    private static String aliasesOf(String area) {
        return "SELECT aa.alias FROM area_alias aa JOIN area a ON a.id = aa.area_id WHERE a.name = " + quoted(area);
    }

    @Test
    @DisplayName("the seeded sample locations land in their sub-regions: matched by name AND region, by the "
            + "stored and the corrected spelling, folding a curly apostrophe; a same-named location in another "
            + "region, one with no region and an unlisted one join none")
    void membershipIsMatchedByNameAndRegion() throws SQLException {
        flyway("190").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            plant(st, "High Force", DALES);
            plant(st, "Houseteads Fort", NT);
            plant(st, "Edlingham", NT);
            plant(st, "Lodore Falls", LAKES);
            plant(st, "Peel Crags", NT);
            plant(st, "Pen-y-ghent", DALES);
            plant(st, "Cuddy’s Crags", NT);
            plant(st, "Robin Hood’s Bay", NYM);
            plant(st, "Low Force", LAKES);
            plant(st, "Tynemouth", null);
            plant(st, "Mystery Crag", DALES);
        }

        flyway("191").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            Map<String, String> members = pairs(st, "SELECT l.name, a.name FROM location_sub_region m "
                    + "JOIN locations l ON l.id = m.location_id JOIN area a ON a.id = m.area_id");
            assertThat(members)
                    .containsEntry("High Force", "Swaledale")
                    .containsEntry("Houseteads Fort", "Hadrian's Wall & Tynedale")
                    .containsEntry("Edlingham", "Alnwick & the Coquet Coast")
                    .containsEntry("Lodore Falls", "Keswick & Borrowdale")
                    .containsEntry("Peel Crags", "Hadrian's Wall & Tynedale")
                    .containsEntry("Pen-y-ghent", "The Three Peaks")
                    .containsEntry("Cuddy’s Crags", "Hadrian's Wall & Tynedale")
                    .containsEntry("Robin Hood’s Bay", "Robin Hood's Bay to Flamborough")
                    .as("seeded by earlier migrations: Hardcastle Crags, Nidd Gorge, Plessey Woods")
                    .containsEntry("Hardcastle Crags", "Malhamdale & Airedale")
                    .containsEntry("Nidd Gorge", "Nidderdale")
                    .containsEntry("Plessey Woods", "Druridge Bay to Tynemouth")
                    .doesNotContainKeys("Low Force", "Tynemouth", "Mystery Crag");
            assertThat(count(st, "SELECT count(*) FROM location_sub_region m JOIN locations l ON l.id = m.location_id "
                    + "JOIN area a ON a.id = m.area_id WHERE l.region_id <> a.region_id"))
                    .as("no location is in a sub-region of another region").isZero();
        }
    }

    @Test
    @DisplayName("every location V190 seeds on a fresh database is covered, so the post-check reports none")
    void freshDatabaseLeavesNothingUncovered() throws SQLException {
        flyway("190").migrate();
        flyway("191").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(count(st, "SELECT count(*) FROM locations l WHERE l.enabled AND l.region_id IS NOT NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM location_sub_region m WHERE m.location_id = l.id)")).isZero();
            assertThat(count(st, "SELECT count(*) FROM location_sub_region")).isEqualTo(
                    count(st, "SELECT count(*) FROM locations WHERE region_id IS NOT NULL"));
        }
    }

    @Test
    @DisplayName("the primary key on location_id refuses a second sub-region for a location")
    void primaryKeyRefusesASecondSubRegion() throws SQLException {
        flyway("190").migrate();
        flyway("191").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            long location = count(st, "SELECT location_id FROM location_sub_region LIMIT 1");
            long other = count(st, "SELECT a.id FROM area a WHERE a.kind = 'SUB_REGION' AND a.id <> "
                    + "(SELECT area_id FROM location_sub_region WHERE location_id = " + location + ") LIMIT 1");

            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO location_sub_region (location_id, area_id) "
                    + "VALUES (" + location + ", " + other + ")"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("location_sub_region_pkey");
            assertThat(count(st, "UPDATE location_sub_region SET area_id = " + other
                    + " WHERE location_id = " + location + " RETURNING 1")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("both CHECKs hold: a kind outside NAMED and SUB_REGION, and a SUB_REGION with no region")
    void checksRefuseABadKindAndARegionlessSubRegion() throws SQLException {
        flyway("190").migrate();
        flyway("191").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO area (name, kind) VALUES ('Bogus One', 'BOGUS')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_area_kind");
            assertThatThrownBy(() -> st.executeUpdate(
                    "INSERT INTO area (name, kind) VALUES ('Regionless One', 'SUB_REGION')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_area_sub_region_has_region");
            st.executeUpdate("INSERT INTO area (name) VALUES ('Plain Named Area')");
            assertThat(strings(st, "SELECT kind FROM area WHERE name = 'Plain Named Area'"))
                    .as("the column defaults to NAMED").containsExactly("NAMED");
        }
    }

    @Test
    @DisplayName("deleting a region with sub-regions, or a sub-region with members, is restricted; deleting a "
            + "location cascades its membership")
    void foreignKeysRestrictAndCascade() throws SQLException {
        flyway("190").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            plant(st, "High Force", DALES);
        }
        flyway("191").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("DELETE FROM regions WHERE name = " + quoted(DALES)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.executeUpdate("DELETE FROM area WHERE name = 'Swaledale'"))
                    .as("High Force is a member")
                    .isInstanceOf(SQLException.class).hasMessageContaining("location_sub_region_area_id_fkey");

            long before = count(st, "SELECT count(*) FROM location_sub_region");
            st.executeUpdate("DELETE FROM locations WHERE name = 'High Force'");
            assertThat(count(st, "SELECT count(*) FROM location_sub_region")).isEqualTo(before - 1);
            st.executeUpdate("DELETE FROM area WHERE name = 'Swaledale'");
            assertThat(count(st, "SELECT count(*) FROM area WHERE name = 'Swaledale'")).isZero();
            assertThat(count(st, "SELECT count(*) FROM area_alias aa WHERE aa.alias = 'Teesdale'"))
                    .as("the area's aliases went with it").isZero();
        }
    }

    @Test
    @DisplayName("pre-flight: a location name containing a sub-region name stops the migration")
    void preflightRaisesOnALocationNameContainingAName() throws SQLException {
        assertPreflightRaises(st -> plant(st, "Swaledale Meadows", DALES), "Swaledale", "Swaledale Meadows");
    }

    @Test
    @DisplayName("pre-flight: the folding strips a curly apostrophe, so 'Robin Hood’s Bay to Flamborough Path' "
            + "contains the sub-region name and stops the migration")
    void preflightFoldsACurlyApostrophe() throws SQLException {
        assertPreflightRaises(st -> plant(st, "Robin Hood’s Bay to Flamborough Path", NYM),
                "Robin Hood's Bay to Flamborough");
    }

    @Test
    @DisplayName("pre-flight: the folding turns punctuation into word gaps, so a one-word alias inside "
            + "'Keswick-on-Derwent' stops the migration")
    void preflightFoldsPunctuationToGaps() throws SQLException {
        assertPreflightRaises(st -> plant(st, "Keswick-on-Derwent", LAKES), "Keswick");
    }

    @Test
    @DisplayName("pre-flight: only whole words clash, so 'Swaledales Rest' does not contain 'Swaledale'")
    void preflightMatchesWholeWordsOnly() throws SQLException {
        flyway("190").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            plant(st, "Swaledales Rest", DALES);
        }

        flyway("191").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(count(st, "SELECT count(*) FROM area WHERE kind = 'SUB_REGION'")).isEqualTo(24);
        }
    }

    @Test
    @DisplayName("pre-flight: a location alias containing a sub-region alias stops the migration")
    void preflightRaisesOnALocationAlias() throws SQLException {
        assertPreflightRaises(st -> {
            plant(st, "Some Quarry", LAKES);
            st.executeUpdate("INSERT INTO location_alias (location_id, alias) "
                    + "SELECT id, 'The  Teesdale Walk' FROM locations WHERE name = 'Some Quarry'");
        }, "Teesdale", "location alias");
    }

    @Test
    @DisplayName("pre-flight: an existing area whose name contains a sub-region alias stops the migration")
    void preflightRaisesOnAnExistingAreaContainingAnAlias() throws SQLException {
        assertPreflightRaises(st -> st.executeUpdate("INSERT INTO area (name) VALUES ('Upper Wharfedale Way')"),
                "Upper Wharfedale", "Upper Wharfedale Way");
    }

    @Test
    @DisplayName("pre-flight: a sub-region name that already exists as an area (in any case or punctuation) "
            + "stops the migration with a clear message")
    void preflightRaisesWhenASeededNameAlreadyExistsAsAnArea() throws SQLException {
        assertPreflightRaises(st -> st.executeUpdate("INSERT INTO area (name) VALUES ('swaledale')"),
                "already exist as an area", "Swaledale");
        // Same folding as the guard: the curly apostrophe and a dash for the ampersand do not make a different name.
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM area WHERE name = 'swaledale'");
            st.executeUpdate("INSERT INTO area (name) VALUES ('Hadrian’s Wall - Tynedale')");
        }
        assertThatThrownBy(() -> flyway("191").migrate()).hasMessageContaining("V191 pre-flight");
    }

    @Test
    @DisplayName("pre-flight: an existing area whose alias is a sub-region's name stops the migration")
    void preflightRaisesOnAnExistingAreaAliasEqualToAName() throws SQLException {
        assertPreflightRaises(st -> {
            st.executeUpdate("INSERT INTO area (name) VALUES ('Dales Walk')");
            st.executeUpdate("INSERT INTO area_alias (area_id, alias) SELECT id, 'Nidderdale' FROM area "
                    + "WHERE name = 'Dales Walk'");
        }, "Nidderdale", "Dales Walk");
    }

    @Test
    @DisplayName("pre-flight: the one allowed containment is the Wall area inside 'Hadrian's Wall & Tynedale' "
            + "(a fresh database migrates); a different area containing that sub-region's name still raises")
    void preflightAllowsOnlyTheWallPair() throws SQLException {
        assertPreflightRaises(st -> st.executeUpdate(
                "INSERT INTO area (name) VALUES ('Hadrian''s Wall & Tynedale Way')"),
                "Hadrian's Wall & Tynedale Way");
    }

    @Test
    @DisplayName("pre-flight: an enabled region name containing a sub-region alias stops the migration, but a "
            + "disabled region of the same name (V31's Teesdale) does not")
    void preflightChecksEnabledRegionNamesOnly() throws SQLException {
        assertPreflightRaises(st -> st.executeUpdate("INSERT INTO regions (name, enabled) VALUES "
                + "('Around Keswick', TRUE)"), "Keswick", "Around Keswick");

        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM regions WHERE name = 'Around Keswick'");
            assertThat(strings(st, "SELECT name FROM regions WHERE name = 'Teesdale' AND NOT enabled"))
                    .as("V31's disabled Teesdale region, which equals the Swaledale alias 'Teesdale'")
                    .containsExactly("Teesdale");
        }
        flyway("191").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(count(st, "SELECT count(*) FROM area WHERE kind = 'SUB_REGION'")).isEqualTo(24);
        }
    }

    @Test
    @DisplayName("pre-flight: a missing region stops the migration, naming it")
    void preflightRaisesOnAMissingRegion() throws SQLException {
        assertPreflightRaises(st -> st.executeUpdate("UPDATE regions SET name = 'The Lakes' "
                + "WHERE name = " + quoted(LAKES)), LAKES);
    }
}
