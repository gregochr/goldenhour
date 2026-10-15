package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.OutcomeRatingRepository;
import com.gregochr.goldenhour.service.OutcomeRatingStore;
import com.gregochr.goldenhour.service.OutcomeRatingStore.RatedRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V177 and V178 against a real Postgres (Flyway runs every migration in
 * {@link IntegrationTestBase}): the {@code outcome_rating} columns, types and nullability; the unique
 * slot key and both CHECK constraints; the user cascade, the location foreign key and the
 * {@code forecast_evaluation_id} SET NULL; the nullable {@code forecast_evaluation.scored_at}; and the
 * REAL {@link OutcomeRatingStore}'s insert race on the production SQL dialect — the proof that the
 * store's lack of a surrounding transaction (and the {@code CAST} of its nullable binds) works on
 * Postgres, which H2 cannot show.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md), so the migration is proven before merge only by CI's Backend job. The same store
 * logic is proven locally on H2 by {@code OutcomeRatingStoreTest}.
 */
class OutcomeRatingMigrationTest extends IntegrationTestBase {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final Instant EVENT = Instant.parse("2026-10-14T06:41:00Z");
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private OutcomeRatingRepository ratings;
    @Autowired
    private OutcomeRatingStore store;

    private final List<Long> createdUsers = new ArrayList<>();
    private final List<Long> createdLocations = new ArrayList<>();

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM outcome_rating");
        users.deleteAllById(createdUsers);
        for (Long id : createdLocations) {
            jdbc.update("DELETE FROM forecast_evaluation WHERE location_id = ?", id);
            jdbc.update("DELETE FROM locations WHERE id = ?", id);
        }
    }

    private long newUser(String name) {
        AppUserEntity user = AppUserEntity.builder().username(name).email(name + "@example.com")
                .password("{bcrypt}hash").role(UserRole.PRO_USER).enabled(true)
                .createdAt(java.time.LocalDateTime.of(2026, 1, 1, 12, 0)).build();
        long id = users.save(user).getId();
        createdUsers.add(id);
        return id;
    }

    private long newLocation(String name) {
        Long id = jdbc.queryForObject("INSERT INTO locations (name, lat, lon) VALUES (?, 55.0, -1.5) RETURNING id",
                Long.class, name);
        createdLocations.add(id);
        return id;
    }

    private long newEvaluation(long locationId) {
        return jdbc.queryForObject("INSERT INTO forecast_evaluation (location_id, location_lat, location_lon, "
                + "target_date, target_type, forecast_run_at, days_ahead) "
                + "VALUES (?, 55.0, -1.5, ?, 'SUNRISE', CURRENT_TIMESTAMP, 0) RETURNING id", Long.class,
                locationId, java.sql.Date.valueOf(DAY));
    }

    private static Map<String, Object> column(List<Map<String, Object>> columns, String name) {
        return columns.stream().filter(c -> name.equals(c.get("column_name"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("outcome_rating has the expected columns, types and nullability")
    void columnsExist() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'outcome_rating' ORDER BY column_name");

        assertThat(columns).extracting(c -> c.get("column_name")).containsExactly("created_at", "event_date",
                "event_time", "event_type", "forecast_evaluation_id", "forecast_rating", "forecast_run_at",
                "id", "location_id", "pipeline_run_id", "rating", "updated_at", "user_id");
        for (String notNull : List.of("user_id", "location_id", "event_date", "event_type", "event_time",
                "created_at", "updated_at")) {
            assertThat(column(columns, notNull)).as(notNull).containsEntry("is_nullable", "NO");
        }
        for (String nullable : List.of("rating", "pipeline_run_id", "forecast_run_at",
                "forecast_evaluation_id", "forecast_rating")) {
            assertThat(column(columns, nullable)).as(nullable).containsEntry("is_nullable", "YES");
        }
        assertThat(column(columns, "event_time")).containsEntry("data_type", "timestamp with time zone");
        assertThat(column(columns, "forecast_run_at")).containsEntry("data_type", "timestamp with time zone");
        assertThat(column(columns, "event_date")).containsEntry("data_type", "date");
        assertThat(column(columns, "rating")).containsEntry("data_type", "integer");
    }

    @Test
    @DisplayName("forecast_evaluation.scored_at exists as a nullable timestamptz and a fresh row reads NULL")
    void scoredAtColumn() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'forecast_evaluation' AND column_name = 'scored_at'");
        assertThat(columns).hasSize(1);
        assertThat(columns.getFirst()).containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "YES");

        long evaluation = newEvaluation(newLocation("V178 Hill"));
        assertThat(jdbc.queryForList("SELECT scored_at FROM forecast_evaluation WHERE id = ?", evaluation)
                .getFirst().get("scored_at")).isNull();
    }

    @Test
    @DisplayName("(user, location, date, type) is unique; another user, location, date or type is not")
    void uniquePerSlot() {
        long user = newUser("rating-unique");
        long other = newUser("rating-unique-other");
        long location = newLocation("V177 Unique Hill");
        ratings.insertRow(user, location, DAY, "SUNRISE", 4, EVENT, null, null, null, null, NOW);

        assertThatThrownBy(() -> ratings.insertRow(user, location, DAY, "SUNRISE", 2, EVENT, null, null, null,
                null, NOW)).isInstanceOf(DataIntegrityViolationException.class);
        ratings.insertRow(other, location, DAY, "SUNRISE", 2, EVENT, null, null, null, null, NOW);
        ratings.insertRow(user, location, DAY, "SUNSET", 2, EVENT, null, null, null, null, NOW);
        ratings.insertRow(user, location, DAY.minusDays(1), "SUNRISE", 2, EVENT, null, null, null, null, NOW);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outcome_rating", Integer.class)).isEqualTo(4);
    }

    @Test
    @DisplayName("the rating CHECK refuses 0 and 6 and accepts NULL, 1 and 5; the event_type CHECK refuses HOURLY")
    void checkConstraints() {
        long user = newUser("rating-check");
        long location = newLocation("V177 Check Hill");

        assertThatThrownBy(() -> ratings.insertRow(user, location, DAY, "SUNRISE", 0, EVENT, null, null, null,
                null, NOW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> ratings.insertRow(user, location, DAY, "SUNRISE", 6, EVENT, null, null, null,
                null, NOW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> ratings.insertRow(user, location, DAY, "HOURLY", 3, EVENT, null, null, null,
                null, NOW)).isInstanceOf(DataIntegrityViolationException.class);

        ratings.insertRow(user, location, DAY, "SUNRISE", null, EVENT, null, null, null, null, NOW);
        ratings.insertRow(user, location, DAY, "SUNSET", 1, EVENT, null, null, null, null, NOW);
        ratings.insertRow(user, location, DAY.minusDays(1), "SUNRISE", 5, EVENT, null, null, null, null, NOW);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outcome_rating WHERE rating IS NULL", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a rating for a user or a location that does not exist is refused: the foreign keys are real")
    void foreignKeysAreEnforced() {
        long user = newUser("rating-fk");
        long location = newLocation("V177 FK Hill");

        assertThatThrownBy(() -> ratings.insertRow(987_654_321L, location, DAY, "SUNRISE", 3, EVENT, null, null,
                null, null, NOW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> ratings.insertRow(user, 987_654_321L, DAY, "SUNRISE", 3, EVENT, null, null,
                null, null, NOW)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("deleting a user takes their ratings with it (ON DELETE CASCADE) and leaves others' alone")
    void deletingAUserCascades() {
        long user = newUser("rating-cascade");
        long bystander = newUser("rating-bystander");
        long location = newLocation("V177 Cascade Hill");
        store.upsert(user, location, DAY, TargetType.SUNRISE, 3, EVENT, RatedRun.NONE, NOW);
        store.upsert(bystander, location, DAY, TargetType.SUNRISE, 4, EVENT, RatedRun.NONE, NOW);

        users.deleteById(user);
        createdUsers.remove(Long.valueOf(user));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outcome_rating WHERE user_id = ?", Integer.class,
                user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outcome_rating WHERE user_id = ?", Integer.class,
                bystander)).isEqualTo(1);
    }

    @Test
    @DisplayName("deleting the forecast_evaluation row a rating points at nulls the pointer and keeps the rating "
            + "(ON DELETE SET NULL)")
    void deletingTheEvaluationNullsThePointer() {
        long user = newUser("rating-setnull");
        long location = newLocation("V177 SetNull Hill");
        long evaluation = newEvaluation(location);
        store.upsert(user, location, DAY, TargetType.SUNRISE, 3, EVENT,
                new RatedRun(null, NOW, evaluation, 4), NOW);

        jdbc.update("DELETE FROM forecast_evaluation WHERE id = ?", evaluation);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT rating, forecast_evaluation_id, forecast_rating FROM outcome_rating WHERE user_id = ?",
                user);
        assertThat(row).containsEntry("rating", 3).containsEntry("forecast_rating", 4);
        assertThat(row.get("forecast_evaluation_id")).isNull();
    }

    @Test
    @DisplayName("the real store inserts and updates on Postgres, with NULL provenance and NULL rating bound "
            + "through the CASTs")
    void storeInsertsAndUpdatesWithNulls() {
        long user = newUser("rating-store");
        long location = newLocation("V177 Store Hill");

        store.upsert(user, location, DAY, TargetType.SUNRISE, null, EVENT, RatedRun.NONE, NOW);
        Map<String, Object> first = jdbc.queryForMap("SELECT * FROM outcome_rating WHERE user_id = ?", user);
        assertThat(first.get("rating")).isNull();
        assertThat(first.get("forecast_run_at")).isNull();
        assertThat(first.get("pipeline_run_id")).isNull();

        long evaluation = newEvaluation(location);
        store.upsert(user, location, DAY, TargetType.SUNRISE, 5, EVENT.plusSeconds(60),
                new RatedRun(null, Instant.parse("2026-10-14T01:05:00Z"), evaluation, 4), NOW.plusSeconds(60));
        Map<String, Object> second = jdbc.queryForMap("SELECT * FROM outcome_rating WHERE user_id = ?", user);
        assertThat(second).containsEntry("rating", 5).containsEntry("forecast_rating", 4)
                .containsEntry("forecast_evaluation_id", evaluation);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outcome_rating", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("the first-rating insert race on Postgres: twelve concurrent upserts make one row and none throws")
    void insertRace() throws Exception {
        long user = newUser("rating-race");
        long location = newLocation("V177 Race Hill");
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int rating = 1 + (i % 5);
                Instant at = NOW.plusSeconds(i);
                futures.add(pool.submit(() -> {
                    go.await();
                    store.upsert(user, location, DAY, TargetType.SUNRISE, rating, EVENT, RatedRun.NONE, at);
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get();
            }

            assertThat(jdbc.queryForObject("SELECT count(*) FROM outcome_rating WHERE user_id = ?", Integer.class,
                    user)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
