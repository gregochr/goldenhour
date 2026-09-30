package com.gregochr.goldenhour.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice test for the round-14 disposition allow-list queries,
 * {@link ForecastRunDispositionRepository#findSupersedingDispositions} and
 * {@link ForecastRunDispositionRepository#existsSupersedingDisposition}. Runs on H2 (schema
 * generated from entity annotations via {@code ddl-auto: create-drop}, no Flyway, no Docker) per
 * this project's {@code @DataJpaTest} pattern.
 *
 * <p>Rows are inserted via raw JDBC, not the repository's {@code save()} — {@code created_at} is
 * {@code insertable = false} on {@link com.gregochr.goldenhour.entity.ForecastRunDispositionEntity}
 * (a real DB-side {@code DEFAULT CURRENT_TIMESTAMP} in production, per V101), so JPA can never set
 * it to a literal test instant. Raw JDBC is the only way to pin the exact {@code created_at} these
 * tests need to prove the allow-list and time-boundary filters precisely.
 */
@DataJpaTest
class ForecastRunDispositionRepositoryTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 29);
    private static final String SUNSET = "SUNSET";

    @Autowired
    private ForecastRunDispositionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void insertDisposition(long jobRunId, String locationName, LocalDate date,
            String eventType, String disposition, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO forecast_run_disposition "
                        + "(job_run_id, location_name, evaluation_date, event_type, disposition, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?)",
                jobRunId, locationName, Date.valueOf(date), eventType, disposition,
                Timestamp.from(createdAt));
    }

    @Test
    @DisplayName("SKIPPED_STABILITY at or after the boundary is returned")
    void skippedStability_atOrAfterBoundary_returned() {
        Instant boundary = Instant.parse("2026-09-29T14:00:00Z");
        insertDisposition(1818L, "X", DATE, SUNSET, "SKIPPED_STABILITY", boundary);

        List<Object[]> rows = repository.findSupersedingDispositions(
                DATE, SUNSET, List.of("X"), boundary);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo("X");
        assertThat(rows.get(0)[1]).isEqualTo(boundary);
    }

    @Test
    @DisplayName("SKIPPED_TRIAGED at or after the boundary is returned")
    void skippedTriaged_atOrAfterBoundary_returned() {
        Instant boundary = Instant.parse("2026-09-29T14:00:00Z");
        Instant createdAt = boundary.plusSeconds(30);
        insertDisposition(1818L, "X", DATE, SUNSET, "SKIPPED_TRIAGED", createdAt);

        List<Object[]> rows = repository.findSupersedingDispositions(
                DATE, SUNSET, List.of("X"), boundary);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo("X");
    }

    @Test
    @DisplayName("the production case: EVALUATED rows at or after the boundary are NEVER returned, "
            + "even though the pipeline_run row for their cycle exists")
    void evaluated_neverReturned_theProductionCase() {
        Instant boundary = Instant.parse("2026-09-29T14:00:00Z");
        // 2026-09-29, pipeline run 249: 510 EVALUATED rows anchored to a disposition-only run —
        // production evidence that this category must never supersede anything.
        insertDisposition(1818L, "X", DATE, SUNSET, "EVALUATED", boundary.plusSeconds(5));

        List<Object[]> rows = repository.findSupersedingDispositions(
                DATE, SUNSET, List.of("X"), boundary);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("FORCE_EVALUATED at or after the boundary is never returned")
    void forceEvaluated_neverReturned() {
        Instant boundary = Instant.parse("2026-09-29T14:00:00Z");
        insertDisposition(1818L, "X", DATE, SUNSET, "FORCE_EVALUATED", boundary.plusSeconds(5));

        List<Object[]> rows = repository.findSupersedingDispositions(
                DATE, SUNSET, List.of("X"), boundary);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("every other excluded category is never returned: SKIPPED_HARD_CONSTRAINT, "
            + "SKIPPED_NO_PROMPT, SKIPPED_CACHED, SKIPPED_PAST_DATE, SKIPPED_TRAVEL_DAY, "
            + "SKIPPED_UNKNOWN_LOCATION, SKIPPED_ERROR, SKIPPED_NO_REFRESH_NEEDED")
    void everyOtherExcludedCategory_neverReturned() {
        Instant boundary = Instant.parse("2026-09-29T14:00:00Z");
        String[] excluded = {
                "SKIPPED_HARD_CONSTRAINT", "SKIPPED_NO_PROMPT", "SKIPPED_CACHED",
                "SKIPPED_PAST_DATE", "SKIPPED_TRAVEL_DAY", "SKIPPED_UNKNOWN_LOCATION",
                "SKIPPED_ERROR", "SKIPPED_NO_REFRESH_NEEDED",
        };
        long jobRunId = 1000L;
        for (String category : excluded) {
            insertDisposition(jobRunId++, "X", DATE, SUNSET, category, boundary.plusSeconds(5));
        }

        List<Object[]> rows = repository.findSupersedingDispositions(
                DATE, SUNSET, List.of("X"), boundary);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("a disposition strictly BEFORE the boundary is not returned — a same-cycle row")
    void beforeBoundary_notReturned() {
        Instant boundary = Instant.parse("2026-09-29T14:00:00Z");
        insertDisposition(1818L, "X", DATE, SUNSET, "SKIPPED_TRIAGED", boundary.minusSeconds(1));

        List<Object[]> rows = repository.findSupersedingDispositions(
                DATE, SUNSET, List.of("X"), boundary);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("bulk: only the requested location names are returned, from the requested date "
            + "and event type alone")
    void bulk_filtersByLocationDateAndEventType() {
        Instant boundary = Instant.parse("2026-09-29T14:00:00Z");
        insertDisposition(1818L, "X", DATE, SUNSET, "SKIPPED_TRIAGED", boundary.plusSeconds(1));
        insertDisposition(1818L, "Y", DATE, SUNSET, "SKIPPED_TRIAGED", boundary.plusSeconds(1));
        insertDisposition(1818L, "Z", DATE, SUNSET, "SKIPPED_TRIAGED", boundary.plusSeconds(1));
        insertDisposition(1818L, "X", DATE.plusDays(1), SUNSET, "SKIPPED_TRIAGED",
                boundary.plusSeconds(1));
        insertDisposition(1818L, "X", DATE, "SUNRISE", "SKIPPED_TRIAGED", boundary.plusSeconds(1));

        List<Object[]> rows = repository.findSupersedingDispositions(
                DATE, SUNSET, List.of("X", "Y"), boundary);

        assertThat(rows).extracting(r -> r[0]).containsExactlyInAnyOrder("X", "Y");
    }

    @Test
    @DisplayName("existsSupersedingDisposition: true for an allow-listed category at the boundary")
    void existsSupersedingDisposition_trueForAllowListedCategory() {
        Instant boundary = Instant.parse("2026-09-29T14:00:00Z");
        insertDisposition(1818L, "X", DATE, SUNSET, "SKIPPED_STABILITY", boundary);

        assertThat(repository.existsSupersedingDisposition(
                "X", DATE, SUNSET, boundary)).isTrue();
    }

    @Test
    @DisplayName("existsSupersedingDisposition: false for EVALUATED, even at or after the boundary")
    void existsSupersedingDisposition_falseForEvaluated() {
        Instant boundary = Instant.parse("2026-09-29T14:00:00Z");
        insertDisposition(1818L, "X", DATE, SUNSET, "EVALUATED", boundary.plusSeconds(5));

        assertThat(repository.existsSupersedingDisposition(
                "X", DATE, SUNSET, boundary)).isFalse();
    }

    @Test
    @DisplayName("existsSupersedingDisposition: false when no row exists for that slot at all")
    void existsSupersedingDisposition_falseWhenNoRow() {
        Instant boundary = Instant.parse("2026-09-29T14:00:00Z");

        assertThat(repository.existsSupersedingDisposition(
                "X", DATE, SUNSET, boundary)).isFalse();
    }
}
