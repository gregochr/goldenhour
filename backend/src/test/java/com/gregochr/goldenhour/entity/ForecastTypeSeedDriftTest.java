package com.gregochr.goldenhour.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift canary for the {@link ForecastType} enum / {@code forecast_type}
 * lookup pairing (V107). The enum mirrors the seeded rows on (id, code,
 * display_name, scale_max) and the pairing is load-bearing: the converter
 * writes enum ids as FK values, and Pass 2+ writers trust the enum's
 * scale_max.
 *
 * <p>Parses every Flyway migration file for {@code INSERT INTO forecast_type}
 * tuples rather than querying a database — the seeds can only change via a
 * migration file, so the files ARE the source of truth, and this runs in
 * every build with no database or Docker. If this test fails, someone
 * seeded a type without the enum constant, added a constant without the
 * seed row, or renamed/rescaled one side only.
 */
class ForecastTypeSeedDriftTest {

    private static final Path MIGRATION_DIR =
            Path.of("src", "main", "resources", "db", "migration");

    /** Matches one seed tuple: (id, 'CODE', 'Display Name', scale_max). */
    private static final Pattern SEED_TUPLE = Pattern.compile(
            "\\(\\s*(\\d+)\\s*,\\s*'([A-Z_]+)'\\s*,\\s*'([^']+)'\\s*,\\s*(\\d+)\\s*\\)");

    private record SeedRow(long id, String code, String displayName, int scaleMax) {
    }

    @Test
    @DisplayName("every seeded forecast_type row has a matching enum constant and vice versa")
    void seedRowsAndEnumConstantsAreABijection() throws IOException {
        List<SeedRow> seeds = parseSeedRows();

        assertThat(seeds)
                .as("forecast_type seed rows found in migration files")
                .isNotEmpty();

        // Seed -> enum: every row matches a constant exactly.
        for (SeedRow seed : seeds) {
            ForecastType type = ForecastType.valueOf(seed.code());
            assertThat(type.getId())
                    .as("id for code %s", seed.code())
                    .isEqualTo(seed.id());
            assertThat(type.getDisplayName())
                    .as("display_name for code %s", seed.code())
                    .isEqualTo(seed.displayName());
            assertThat(type.getScaleMax())
                    .as("scale_max for code %s", seed.code())
                    .isEqualTo(seed.scaleMax());
        }

        // Enum -> seed: every constant is seeded exactly once.
        assertThat(seeds).extracting(SeedRow::code)
                .containsExactlyInAnyOrder(
                        Stream.of(ForecastType.values())
                                .map(Enum::name)
                                .toArray(String[]::new));
    }

    @Test
    @DisplayName("seeded ids and codes are unique across all migrations")
    void seedIdsAndCodesAreUnique() throws IOException {
        List<SeedRow> seeds = parseSeedRows();

        assertThat(seeds).extracting(SeedRow::id).doesNotHaveDuplicates();
        assertThat(seeds).extracting(SeedRow::code).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("combiner-peer types are 1-5 scale; display products are 0-100")
    void scaleMaxMatchesProductKind() {
        assertThat(ForecastType.SKY.getScaleMax()).isEqualTo(5);
        assertThat(ForecastType.TIDAL.getScaleMax()).isEqualTo(5);
        assertThat(ForecastType.BLUEBELL.getScaleMax()).isEqualTo(5);
        assertThat(ForecastType.FIERY_SKY.getScaleMax()).isEqualTo(100);
        assertThat(ForecastType.GOLDEN_HOUR.getScaleMax()).isEqualTo(100);
    }

    @Test
    @DisplayName("fromId resolves every constant and rejects unknown ids")
    void fromIdRoundTripsAndRejectsUnknown() {
        for (ForecastType type : ForecastType.values()) {
            assertThat(ForecastType.fromId(type.getId())).isEqualTo(type);
        }
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ForecastType.fromId(999L));
    }

    /**
     * Scans every migration file (not just V107) so future seed additions
     * are caught the moment they land without an enum change.
     */
    private List<SeedRow> parseSeedRows() throws IOException {
        assertThat(MIGRATION_DIR)
                .as("migration directory (test must run from the backend module)")
                .exists();

        List<SeedRow> rows = new ArrayList<>();
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
                String sql = Files.readString(file);
                int insertAt = sql.indexOf("INSERT INTO forecast_type");
                while (insertAt >= 0) {
                    int end = sql.indexOf(';', insertAt);
                    String statement = end > insertAt
                            ? sql.substring(insertAt, end)
                            : sql.substring(insertAt);
                    Matcher matcher = SEED_TUPLE.matcher(statement);
                    while (matcher.find()) {
                        rows.add(new SeedRow(
                                Long.parseLong(matcher.group(1)),
                                matcher.group(2),
                                matcher.group(3),
                                Integer.parseInt(matcher.group(4))));
                    }
                    insertAt = sql.indexOf("INSERT INTO forecast_type", insertAt + 1);
                }
            }
        }
        return rows;
    }
}
