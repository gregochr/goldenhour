package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.OutcomeRatingEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.SlotRatingRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link OutcomeRatingRepository} on H2 with the schema generated from the entity: the slot read
 * with usernames joined (newest first), the user's own range read, the conditional update, and the
 * write-discipline rule (a whole-entity save of a loaded copy writes none of the rating's columns).
 * Every assertion about stored values reads through {@link JdbcTemplate}, never the persistence
 * context.
 */
@DataJpaTest
class OutcomeRatingRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final Instant EVENT = Instant.parse("2026-10-14T06:41:00Z");
    private static final long LOCATION = 31L;

    @Autowired
    private OutcomeRatingRepository repository;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    private long alice;
    private long bob;

    @BeforeEach
    void users() {
        alice = newUser("alice");
        bob = newUser("bob");
    }

    private long newUser(String name) {
        return users.save(AppUserEntity.builder().username(name).email(name + "@example.com")
                .password("{bcrypt}hash").role(UserRole.PRO_USER).enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build()).getId();
    }

    private void insert(long user, long location, LocalDate date, TargetType type, Integer rating, Instant at) {
        repository.insertRow(user, location, date, type.name(), rating, EVENT, 5L,
                Instant.parse("2026-10-14T01:05:00Z"), 8L, 3, "v2.28.0", at);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("the slot read joins every user's username, newest first, and only that slot's rows")
    void findAllForSlot_joinsUsernamesNewestFirst() {
        insert(alice, LOCATION, DAY, TargetType.SUNRISE, 4, Instant.parse("2026-10-14T08:00:00Z"));
        insert(bob, LOCATION, DAY, TargetType.SUNRISE, null, Instant.parse("2026-10-14T09:00:00Z"));
        insert(alice, LOCATION, DAY, TargetType.SUNSET, 2, Instant.parse("2026-10-14T10:00:00Z"));
        insert(alice, LOCATION + 1, DAY, TargetType.SUNRISE, 1, Instant.parse("2026-10-14T10:00:00Z"));
        insert(alice, LOCATION, DAY.minusDays(1), TargetType.SUNRISE, 5, Instant.parse("2026-10-14T10:00:00Z"));

        List<SlotRatingRow> rows = repository.findAllForSlot(LOCATION, DAY, TargetType.SUNRISE);

        assertThat(rows).extracting(SlotRatingRow::username, SlotRatingRow::rating, SlotRatingRow::userId)
                .containsExactly(
                        tuple("bob", null, bob),
                        tuple("alice", 4, alice));
        assertThat(rows.getFirst().updatedAt()).isEqualTo(Instant.parse("2026-10-14T09:00:00Z"));
        assertThat(rows.getFirst().eventTime()).isEqualTo(EVENT);
        assertThat(rows.getFirst().forecastRunAt()).isEqualTo(Instant.parse("2026-10-14T01:05:00Z"));
        assertThat(rows.getFirst().pipelineRunId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("V182: insertRow stores the build, a null build stores NULL, and updateRow replaces it")
    void appBuild_isStoredAndReplaced() {
        Instant at = Instant.parse("2026-10-14T08:00:00Z");
        insert(alice, LOCATION, DAY, TargetType.SUNRISE, 4, at);
        repository.insertRow(bob, LOCATION, DAY, TargetType.SUNRISE.name(), 3, EVENT, null, null, null,
                null, null, at);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByUserIdAndLocationIdAndEventDateAndEventType(
                alice, LOCATION, DAY, TargetType.SUNRISE).orElseThrow().getAppBuild())
                .isEqualTo("v2.28.0");
        assertThat(repository.findByUserIdAndLocationIdAndEventDateAndEventType(
                bob, LOCATION, DAY, TargetType.SUNRISE).orElseThrow().getAppBuild()).isNull();

        int updated = repository.updateRow(alice, LOCATION, DAY, TargetType.SUNRISE, 2, EVENT, null, null,
                null, null, "v2.29.0", at.plusSeconds(60));
        entityManager.clear();

        assertThat(updated).isEqualTo(1);
        assertThat(repository.findByUserIdAndLocationIdAndEventDateAndEventType(
                alice, LOCATION, DAY, TargetType.SUNRISE).orElseThrow().getAppBuild())
                .isEqualTo("v2.29.0");
    }

    @Test
    @DisplayName("the slot read of a window nobody has rated is empty")
    void findAllForSlot_noRatings_empty() {
        assertThat(repository.findAllForSlot(LOCATION, DAY, TargetType.SUNRISE)).isEmpty();
    }

    @Test
    @DisplayName("the range read returns only the user's own rows, both ends inclusive, oldest event first")
    void findByUserIdAndEventDateBetween_ownRowsInclusiveOrdered() {
        Instant at = Instant.parse("2026-10-14T08:00:00Z");
        insert(alice, LOCATION, DAY.minusDays(3), TargetType.SUNSET, 1, at);
        insert(alice, LOCATION, DAY.minusDays(2), TargetType.SUNRISE, 2, at);
        insert(alice, LOCATION + 1, DAY.minusDays(2), TargetType.SUNRISE, 3, at);
        insert(alice, LOCATION, DAY, TargetType.SUNSET, 4, at);
        insert(alice, LOCATION, DAY.plusDays(1), TargetType.SUNRISE, 5, at);
        insert(bob, LOCATION, DAY.minusDays(1), TargetType.SUNRISE, 5, at);

        List<OutcomeRatingEntity> rows = repository.findByUserIdAndEventDateBetweenOrderByEventDateAscIdAsc(
                alice, DAY.minusDays(2), DAY);

        assertThat(rows).extracting(OutcomeRatingEntity::getRating).containsExactly(2, 3, 4);
    }

    @Test
    @DisplayName("exists reflects the unique key exactly, and updateRow on a missing row touches nothing")
    void existsAndUpdate_onTheKey() {
        insert(alice, LOCATION, DAY, TargetType.SUNRISE, 4, Instant.parse("2026-10-14T08:00:00Z"));

        assertThat(repository.existsByUserIdAndLocationIdAndEventDateAndEventType(
                alice, LOCATION, DAY, TargetType.SUNRISE)).isTrue();
        assertThat(repository.existsByUserIdAndLocationIdAndEventDateAndEventType(
                alice, LOCATION, DAY, TargetType.SUNSET)).isFalse();
        assertThat(repository.existsByUserIdAndLocationIdAndEventDateAndEventType(
                bob, LOCATION, DAY, TargetType.SUNRISE)).isFalse();

        int missing = repository.updateRow(bob, LOCATION, DAY, TargetType.SUNRISE, 1, EVENT, null, null,
                null, null, null, Instant.parse("2026-10-14T12:00:00Z"));
        assertThat(missing).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outcome_rating", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a whole-entity save of a loaded copy writes none of the rating's columns")
    void wholeEntitySave_cannotRewriteTheRow() {
        insert(alice, LOCATION, DAY, TargetType.SUNRISE, 4, Instant.parse("2026-10-14T08:00:00Z"));
        OutcomeRatingEntity loaded = repository.findAll().getFirst();
        entityManager.clear();
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM outcome_rating");

        // A second writer holding a copy with different values and the same id: merge() runs, but
        // updatable = false on every column means no UPDATE is issued.
        OutcomeRatingEntity stale = OutcomeRatingEntity.builder().id(loaded.getId()).userId(alice)
                .locationId(LOCATION).eventDate(DAY).eventType(TargetType.SUNRISE).rating(1)
                .eventTime(EVENT.plusSeconds(600)).pipelineRunId(null).forecastRunAt(null)
                .forecastEvaluationId(null).forecastRating(null)
                .createdAt(Instant.parse("2020-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2030-01-01T00:00:00Z")).build();
        repository.saveAndFlush(stale);
        entityManager.clear();

        // every column, not a sample of them: the rule is that NONE of them can be rewritten
        assertThat(jdbc.queryForMap("SELECT * FROM outcome_rating")).isEqualTo(before)
                .containsEntry("RATING", 4).containsEntry("FORECAST_RATING", 3);
    }

    @Test
    @DisplayName("the derived finder returns a user's own row for the exact key, and nothing for another")
    void findByKey() {
        insert(alice, LOCATION, DAY, TargetType.SUNRISE, 4, Instant.parse("2026-10-14T08:00:00Z"));

        assertThat(repository.findByUserIdAndLocationIdAndEventDateAndEventType(
                alice, LOCATION, DAY, TargetType.SUNRISE)).get()
                .extracting(OutcomeRatingEntity::getForecastRunAt)
                .isEqualTo(Instant.parse("2026-10-14T01:05:00Z"));
        assertThat(repository.findByUserIdAndLocationIdAndEventDateAndEventType(
                bob, LOCATION, DAY, TargetType.SUNRISE)).isEmpty();
    }
}
