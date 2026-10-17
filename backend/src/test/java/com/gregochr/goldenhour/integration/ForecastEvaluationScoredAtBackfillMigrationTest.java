package com.gregochr.goldenhour.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V183 ({@code backfill_forecast_evaluation_scored_at}) against a real Postgres. Flyway has already
 * applied every migration when {@link IntegrationTestBase} starts, on an empty table, so the test seeds the
 * legacy shapes and then runs the migration's own SQL file (read from the classpath, so an edit to the file
 * is what is tested, not a copy of it).
 *
 * <p>The bound is V178's own {@code installed_on}, which here is the moment this container migrated: a row
 * "before V178" is dated in January, a row "after" is dated tomorrow.
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

    private int runMigration() throws IOException {
        String sql = new ClassPathResource("db/migration/V183__backfill_forecast_evaluation_scored_at.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        return jdbc.update(sql);
    }

    @Test
    @DisplayName("V183 stamps a legacy rated or triaged row with its submission instant, and touches nothing else")
    void stampsOnlyTheBoundedLegacyPopulation() throws IOException {
        long location = newLocation("v182-backfill");
        LocalDateTime afterV178 = LocalDateTime.now().plusDays(1);
        long legacyRated = evaluation(location, LEGACY_RUN, 4, null, null);
        long legacyTriaged = evaluation(location, LEGACY_RUN.plusHours(1), null, "HIGH_CLOUD", null);
        long legacyPending = evaluation(location, LEGACY_RUN.plusHours(2), null, null, null);
        long alreadyStamped = evaluation(location, LEGACY_RUN.plusHours(3), 3, null, ALREADY_STAMPED);
        long afterRated = evaluation(location, afterV178, 5, null, null);

        int updated = runMigration();

        assertThat(updated).as("exactly the two legacy rows with something to say").isEqualTo(2);
        assertThat(scoredAt(legacyRated)).as("rated legacy row: stamped with forecast_run_at read as UTC")
                .isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
        assertThat(scoredAt(legacyTriaged)).as("triaged legacy row: a decision the reader saw")
                .isEqualTo(Instant.parse("2026-01-01T11:00:00Z"));
        assertThat(scoredAt(legacyPending)).as("PENDING legacy row has nothing to say: untouched").isNull();
        assertThat(scoredAt(alreadyStamped)).as("a row that already has its stamp is never overwritten")
                .isEqualTo(ALREADY_STAMPED);
        assertThat(scoredAt(afterRated)).as("a row submitted after V178 is not in the bounded population")
                .isNull();
    }

    @Test
    @DisplayName("V183 is idempotent: a second run finds nothing left to stamp")
    void secondRunChangesNothing() throws IOException {
        long location = newLocation("v182-idempotent");
        long legacy = evaluation(location, LEGACY_RUN, 4, null, null);

        assertThat(runMigration()).isEqualTo(1);
        assertThat(runMigration()).isZero();
        assertThat(scoredAt(legacy)).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
    }
}
