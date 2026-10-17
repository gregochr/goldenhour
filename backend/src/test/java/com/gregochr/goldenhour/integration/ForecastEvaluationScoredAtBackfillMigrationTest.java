package com.gregochr.goldenhour.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V183 ({@code backfill_forecast_evaluation_scored_at}) against a real Postgres. Flyway has already
 * applied every migration when {@link IntegrationTestBase} starts, on an empty table, so the test seeds the
 * legacy shapes and then runs the migration's own SQL file (read from the classpath, so an edit to the file
 * is what is tested, not a copy of it).
 *
 * <p>The bound is V178's own {@code installed_on}, read back here: a row a minute before it is inside, a row
 * a minute after it is outside, and January rows give exact-instant checks of the stamp itself. The SQL runs
 * on a connection whose session zone is Tokyo, so a dropped {@code AT TIME ZONE 'UTC'} fails.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md), so the migration is proven before merge only by CI's Backend job.
 */
class ForecastEvaluationScoredAtBackfillMigrationTest extends IntegrationTestBase {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final LocalDateTime LEGACY_RUN = LocalDateTime.of(2026, 1, 1, 10, 0);
    private static final Instant ALREADY_STAMPED = Instant.parse("2026-01-02T09:30:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> createdLocations = new ArrayList<>();

    @AfterEach
    void clean() {
        for (Long id : createdLocations) {
            jdbc.update("DELETE FROM forecast_evaluation WHERE location_id = ?", id);
            jdbc.update("DELETE FROM locations WHERE id = ?", id);
        }
    }

    private long newLocation(String name) {
        Long id = jdbc.queryForObject("INSERT INTO locations (name, lat, lon) VALUES (?, 55.0, -1.5) RETURNING id",
                Long.class, name);
        createdLocations.add(id);
        return id;
    }

    private long evaluation(long location, LocalDateTime runAt, Integer rating, String triageReason,
            Instant scoredAt) {
        return jdbc.queryForObject("INSERT INTO forecast_evaluation (location_id, location_lat, location_lon, "
                + "target_date, target_type, forecast_run_at, days_ahead, rating, triage_reason, scored_at) "
                + "VALUES (?, 55.0, -1.5, ?, 'SUNSET', ?, 0, ?, ?, ?) RETURNING id", Long.class, location,
                java.sql.Date.valueOf(DAY), runAt, rating, triageReason,
                scoredAt == null ? null : Timestamp.from(scoredAt));
    }

    private Instant scoredAt(long id) {
        Timestamp stamp = jdbc.queryForObject("SELECT scored_at FROM forecast_evaluation WHERE id = ?",
                Timestamp.class, id);
        return stamp == null ? null : stamp.toInstant();
    }

    /**
     * Runs the migration's own SQL on ONE connection whose session zone is Tokyo, then restores it. A
     * session in any zone but UTC shifts a bare {@code timestamp} to {@code timestamptz} cast by hours, so
     * a dropped {@code AT TIME ZONE 'UTC'} fails the exact-instant assertions instead of passing on a UTC
     * CI runner.
     */
    private int runMigration() throws IOException {
        String sql = new ClassPathResource("db/migration/V183__backfill_forecast_evaluation_scored_at.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        return jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET TIME ZONE 'Asia/Tokyo'");
                try {
                    return st.executeUpdate(sql);
                } finally {
                    st.execute("RESET TIME ZONE");
                }
            }
        });
    }

    /** V178's own install instant, as the naive value the migration's bound compares against. */
    private LocalDateTime v178InstalledOn() {
        return jdbc.queryForObject("SELECT installed_on FROM flyway_schema_history WHERE version = '178'",
                Timestamp.class).toLocalDateTime();
    }

    @Test
    @DisplayName("V183 stamps a legacy rated or triaged row with its submission instant, and touches nothing else")
    void stampsOnlyTheBoundedLegacyPopulation() throws IOException {
        long location = newLocation("v183-backfill");
        LocalDateTime bound = v178InstalledOn();
        long legacyRated = evaluation(location, LEGACY_RUN, 4, null, null);
        long legacyTriaged = evaluation(location, LEGACY_RUN.plusHours(1), null, "HIGH_CLOUD", null);
        long legacyPending = evaluation(location, LEGACY_RUN.plusHours(2), null, null, null);
        long alreadyStamped = evaluation(location, LEGACY_RUN.plusHours(3), 3, null, ALREADY_STAMPED);
        long justBefore = evaluation(location, bound.minusMinutes(1), 2, null, null);
        long justAfter = evaluation(location, bound.plusMinutes(1), 5, null, null);

        int updated = runMigration();

        assertThat(updated).as("at least the two legacy rows with something to say, and the one just before the bound "
                + "(other test classes may leave older rows)")
                .isGreaterThanOrEqualTo(3);
        assertThat(scoredAt(legacyRated)).as("rated legacy row: stamped with forecast_run_at read as UTC")
                .isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
        assertThat(scoredAt(legacyTriaged)).as("triaged legacy row: a decision the reader saw")
                .isEqualTo(Instant.parse("2026-01-01T11:00:00Z"));
        assertThat(scoredAt(legacyPending)).as("PENDING legacy row has nothing to say: untouched").isNull();
        assertThat(scoredAt(alreadyStamped)).as("a row that already has its stamp is never overwritten")
                .isEqualTo(ALREADY_STAMPED);
        assertThat(scoredAt(justBefore)).as("a minute before V178 was installed: inside the bound")
                .isEqualTo(bound.minusMinutes(1).toInstant(ZoneOffset.UTC));
        assertThat(scoredAt(justAfter)).as("a minute after V178 was installed: outside the bound").isNull();
    }

    @Test
    @DisplayName("V183 is idempotent: a second run finds nothing left to stamp")
    void secondRunChangesNothing() throws IOException {
        long location = newLocation("v183-idempotent");
        long legacy = evaluation(location, LEGACY_RUN, 4, null, null);

        assertThat(runMigration()).isGreaterThanOrEqualTo(1);
        assertThat(runMigration()).isZero();
        assertThat(scoredAt(legacy)).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
    }
}
