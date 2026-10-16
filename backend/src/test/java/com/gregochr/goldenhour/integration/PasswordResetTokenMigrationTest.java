package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.PasswordResetTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.PasswordResetTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the {@code password_reset_token} half of V179 against a real Postgres (Flyway runs every
 * migration in {@link IntegrationTestBase}): the columns, types and nullability, the unique token hash,
 * the user cascade, the {@code (user_id, created_at)} index, and the repository's compare-and-set
 * {@code markUsed} on the production SQL dialect.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md), so the migration is proven before merge only by CI's Backend job. The same queries run
 * locally on H2 in {@code PasswordResetTokenRepositoryTest}.
 */
class PasswordResetTokenMigrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordResetTokenRepository tokens;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM password_reset_token");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'v179-%'");
    }

    private long newUser(String name) {
        return users.save(AppUserEntity.builder().username(name).email(name + "@example.com")
                .password("{bcrypt}hash").role(UserRole.PRO_USER).enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build()).getId();
    }

    private PasswordResetTokenEntity newToken(String hash, long userId) {
        return tokens.save(PasswordResetTokenEntity.builder().tokenHash(hash).userId(userId)
                .expiresAt(NOW.plusSeconds(3600)).createdAt(NOW).build());
    }

    private static Map<String, Object> column(List<Map<String, Object>> columns, String name) {
        return columns.stream().filter(c -> name.equals(c.get("column_name"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("password_reset_token has the expected columns, types and nullability")
    void columnsExist() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'password_reset_token' ORDER BY column_name");

        assertThat(columns).extracting(c -> c.get("column_name"))
                .containsExactly("created_at", "expires_at", "id", "token_hash", "used_at", "user_id");
        for (String notNull : List.of("created_at", "expires_at", "token_hash", "user_id")) {
            assertThat(column(columns, notNull)).as(notNull).containsEntry("is_nullable", "NO");
        }
        assertThat(column(columns, "used_at")).containsEntry("is_nullable", "YES");
        for (String instant : List.of("created_at", "expires_at", "used_at")) {
            assertThat(column(columns, instant)).as(instant)
                    .containsEntry("data_type", "timestamp with time zone");
        }
        assertThat(column(columns, "token_hash")).containsEntry("data_type", "character varying");
    }

    @Test
    @DisplayName("the (user_id, created_at) index exists")
    void userIndexExists() {
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'password_reset_token'",
                String.class)).contains("idx_password_reset_token_user_created");
    }

    @Test
    @DisplayName("the token hash is unique")
    void tokenHashIsUnique() {
        long user = newUser("v179-unique");
        newToken("same-hash", user);

        assertThatThrownBy(() -> newToken("same-hash", user)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a token for a user that does not exist is refused: the foreign key is real")
    void foreignKeyIsEnforced() {
        assertThatThrownBy(() -> newToken("orphan", 987_654_321L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("deleting a user takes their reset links with it (ON DELETE CASCADE) and leaves others' alone")
    void deletingAUserCascades() {
        long user = newUser("v179-cascade");
        long bystander = newUser("v179-bystander");
        newToken("mine", user);
        newToken("theirs", bystander);

        users.deleteById(user);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_token WHERE user_id = ?",
                Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_token WHERE user_id = ?",
                Integer.class, bystander)).isEqualTo(1);
    }

    @Test
    @DisplayName("markUsed is a compare-and-set on Postgres: the first consumer wins, the second reads 0")
    void markUsedIsACompareAndSet() {
        long user = newUser("v179-cas");
        long token = newToken("cas", user).getId();

        assertThat(tokens.markUsed(token, NOW)).isEqualTo(1);
        assertThat(tokens.markUsed(token, NOW.plusSeconds(1))).isZero();
        assertThat(tokens.deleteByUserIdAndUsedAtIsNull(user)).isZero();
        assertThat(tokens.findByTokenHash("cas").orElseThrow().getUsedAt()).isEqualTo(NOW);
    }
}
