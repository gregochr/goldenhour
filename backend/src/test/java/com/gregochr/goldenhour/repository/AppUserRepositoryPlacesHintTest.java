package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link AppUserRepository#updatePlacesHintDismissedAtByUsernameIfNull} and the
 * {@code updatable = false} mapping of {@code places_hint_dismissed_at} on a real JPA stack (H2; V230
 * itself is proven in CI by {@code PlacesHintDismissedMigrationTest} on Postgres). Every assertion reads
 * the row through {@link JdbcTemplate}, never the persistence context.
 */
@DataJpaTest
class AppUserRepositoryPlacesHintTest {

    private static final String USERNAME = "hinted";
    private static final Instant FIRST = Instant.parse("2026-11-25T08:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-11-26T09:30:00Z");

    @Autowired
    private AppUserRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void persistReader() {
        AppUserEntity user = AppUserEntity.builder()
                .username(USERNAME)
                .password("{bcrypt}hash")
                .role(UserRole.LITE_USER)
                .enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0))
                .email("hinted@example.com")
                .build();
        userId = entityManager.persistAndGetId(user, Long.class);
        entityManager.flush();
        entityManager.clear();
    }

    private Instant stamp() {
        entityManager.flush();
        OffsetDateTime value = jdbcTemplate.queryForObject(
                "SELECT places_hint_dismissed_at FROM app_user WHERE id = ?", OffsetDateTime.class, userId);
        return value == null ? null : value.toInstant();
    }

    @Test
    @DisplayName("a new row is unstamped: null is 'the hint is still due'")
    void newRow_isUnstamped() {
        assertThat(stamp()).isNull();
        assertThat(repository.findByUsername(USERNAME).orElseThrow().getPlacesHintDismissedAt()).isNull();
    }

    @Test
    @DisplayName("the update stamps the named user and reports one row")
    void update_stamps() {
        assertThat(repository.updatePlacesHintDismissedAtByUsernameIfNull(USERNAME, FIRST)).isEqualTo(1);

        assertThat(stamp()).isEqualTo(FIRST);
    }

    @Test
    @DisplayName("the first instant stands: a second call writes nothing and reports zero rows")
    void update_isFirstWriteWins() {
        repository.updatePlacesHintDismissedAtByUsernameIfNull(USERNAME, FIRST);

        assertThat(repository.updatePlacesHintDismissedAtByUsernameIfNull(USERNAME, SECOND)).isZero();

        assertThat(stamp()).isEqualTo(FIRST);
    }

    @Test
    @DisplayName("an unknown username updates zero rows")
    void update_unknownUser() {
        assertThat(repository.updatePlacesHintDismissedAtByUsernameIfNull("nobody", FIRST)).isZero();

        assertThat(stamp()).isNull();
    }

    @Test
    @DisplayName("setting the field and saving an existing row writes nothing: only the scoped update does")
    void settingTheFieldAndSaving_writesNothing() {
        AppUserEntity user = repository.findByUsername(USERNAME).orElseThrow();
        user.setPlacesHintDismissedAt(SECOND);
        user.setLastActiveAt(LocalDateTime.of(2026, 11, 25, 9, 5));
        repository.save(user);

        assertThat(stamp()).isNull();
    }
}
