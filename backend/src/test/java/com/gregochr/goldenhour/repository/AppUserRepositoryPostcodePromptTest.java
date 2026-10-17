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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link AppUserRepository#updatePostcodePromptDismissedAtByUsername} and the
 * {@code updatable = false} mapping of {@code postcode_prompt_dismissed_at} on a real JPA stack (H2,
 * schema from the entity annotations; V181 itself is proven in CI by
 * {@code PostcodePromptDismissedMigrationTest} on Postgres).
 *
 * <p>Its own class rather than a nested one in {@code AppUserRepositoryTest}: the contract is small,
 * and a separate file keeps the shared settings test free of unrelated churn. Every assertion reads
 * the row through {@link JdbcTemplate}, never the persistence context.
 */
@DataJpaTest
class AppUserRepositoryPostcodePromptTest {

    private static final String USERNAME = "prompted";
    private static final Instant FIRST = Instant.parse("2026-10-17T08:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-10-18T09:30:00Z");

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
                .email("prompted@example.com")
                .comingUpLastSeenAt(Instant.parse("2026-08-30T19:00:00Z"))
                .build();
        userId = entityManager.persistAndGetId(user, Long.class);
        entityManager.flush();
        entityManager.clear();
    }

    private Instant dismissedAt() {
        entityManager.flush();
        OffsetDateTime value = jdbcTemplate.queryForObject(
                "SELECT postcode_prompt_dismissed_at FROM app_user WHERE id = ?", OffsetDateTime.class, userId);
        return value == null ? null : value.toInstant();
    }

    @Test
    @DisplayName("a new user row has no dismissal — null is 'never dismissed', the existing-user case")
    void newRow_hasNoDismissal() {
        assertThat(dismissedAt()).isNull();
        assertThat(repository.findByUsername(USERNAME).orElseThrow().getPostcodePromptDismissedAt()).isNull();
    }

    @Test
    @DisplayName("the update writes the instant for the named user and reports one row")
    void update_writesTheInstant() {
        assertThat(repository.updatePostcodePromptDismissedAtByUsername(USERNAME, FIRST)).isEqualTo(1);

        assertThat(dismissedAt()).isEqualTo(FIRST);
        // The read-back in the same transaction sees it (clearAutomatically evicted the stale copy).
        assertThat(repository.findByUsername(USERNAME).orElseThrow().getPostcodePromptDismissedAt())
                .isEqualTo(FIRST);
    }

    @Test
    @DisplayName("the update is idempotent — a second dismissal overwrites the instant")
    void update_overwrites() {
        repository.updatePostcodePromptDismissedAtByUsername(USERNAME, FIRST);
        repository.updatePostcodePromptDismissedAtByUsername(USERNAME, SECOND);

        assertThat(dismissedAt()).isEqualTo(SECOND);
    }

    @Test
    @DisplayName("the update touches no other column, and an unknown username updates zero rows")
    void update_writesOnlyItsOwnColumn() {
        Map<String, Object> before = jdbcTemplate.queryForMap(
                "SELECT coming_up_last_seen_at, home_postcode, email, role, enabled FROM app_user "
                        + "WHERE id = ?", userId);

        assertThat(repository.updatePostcodePromptDismissedAtByUsername("nobody", FIRST)).isZero();
        assertThat(dismissedAt()).isNull();
        repository.updatePostcodePromptDismissedAtByUsername(USERNAME, FIRST);

        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT coming_up_last_seen_at, home_postcode, email, role, enabled FROM app_user "
                        + "WHERE id = ?", userId);
        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("a whole-entity save of a copy read before the dismissal does not write it back — "
            + "the column is updatable = false")
    void staleWholeEntitySave_cannotRevertTheDismissal() {
        AppUserEntity staleCopy = repository.findByUsername(USERNAME).orElseThrow();
        entityManager.detach(staleCopy);
        assertThat(staleCopy.getPostcodePromptDismissedAt()).isNull();

        repository.updatePostcodePromptDismissedAtByUsername(USERNAME, FIRST);

        LocalDateTime nowActive = LocalDateTime.of(2026, 10, 17, 9, 0);
        staleCopy.setLastActiveAt(nowActive);
        repository.save(staleCopy);

        // Positive control: the whole-entity save really wrote an updatable column ...
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT last_active_at FROM app_user WHERE id = ?",
                java.sql.Timestamp.class, userId).toLocalDateTime()).isEqualTo(nowActive);
        // ... and the dismissal survived it.
        assertThat(dismissedAt()).isEqualTo(FIRST);
    }

    @Test
    @DisplayName("setting the field and saving an existing row writes nothing — only the scoped update does")
    void settingTheFieldAndSaving_writesNothing() {
        AppUserEntity user = repository.findByUsername(USERNAME).orElseThrow();
        user.setPostcodePromptDismissedAt(SECOND);
        user.setLastActiveAt(LocalDateTime.of(2026, 10, 17, 9, 5));
        repository.save(user);

        assertThat(dismissedAt()).isNull();
    }
}
