package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link UserService#purgeAbandonedPending} against a real JPA stack: it deletes a pending row with its
 * drive-time child and releases the email claim, and refuses an activated row, an admin, and any account
 * holding an unexpired verification link.
 */
@DataJpaTest
@Import({UserService.class, EmailClaims.class, CredentialChangeWriter.class,
        UserServicePurgeAbandonedTest.TestBeans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserServicePurgeAbandonedTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 12, 0);

    /** The collaborators {@link UserService} injects that a data-JPA slice does not supply. */
    @TestConfiguration
    static class TestBeans {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @SuppressWarnings("deprecation")
        PasswordEncoder passwordEncoder() {
            return NoOpPasswordEncoder.getInstance();
        }
    }

    @Autowired
    private UserService userService;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private JdbcTemplate jdbc;

    private long pendingId;

    @BeforeEach
    void setUp() {
        clean();
        pendingId = account("abandoned", false, "", UserRole.LITE_USER);
        jdbc.update("INSERT INTO app_user_email_claim (email_lower, claimed_at) VALUES (?, ?)",
                "abandoned@example.com", java.sql.Timestamp.valueOf(NOW));
        jdbc.update("INSERT INTO user_drive_time (user_id, location_id, drive_duration_seconds) VALUES (?, 1, 600)",
                pendingId);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM email_verification_token");
        jdbc.update("DELETE FROM user_drive_time");
        jdbc.update("DELETE FROM app_user_email_claim");
        jdbc.update("DELETE FROM app_user");
    }

    private long account(String name, boolean enabled, String password, UserRole role) {
        return users.save(AppUserEntity.builder().username(name).email(name + "@example.com").password(password)
                .role(role).enabled(enabled).createdAt(NOW.minusDays(8)).build()).getId();
    }

    private void token(long userId, boolean verified, LocalDateTime expiresAt) {
        jdbc.update("INSERT INTO email_verification_token (token_hash, user_id, expires_at, verified, created_at) "
                + "VALUES (?, ?, ?, ?, ?)", "h" + verified + expiresAt, userId,
                java.sql.Timestamp.valueOf(expiresAt), verified, java.sql.Timestamp.valueOf(NOW.minusDays(2)));
    }

    private int count(String table, String where, Object... args) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }

    @Test
    @DisplayName("deletes a pending row with a drive-time child and an expired link, and releases the email claim")
    void purgesThePendingRow() {
        token(pendingId, false, NOW.minusHours(1));

        assertThat(userService.purgeAbandonedPending(pendingId, NOW)).isTrue();

        assertThat(count("app_user", "id = ?", pendingId)).isZero();
        assertThat(count("user_drive_time", "user_id = ?", pendingId)).isZero();
        // The token rows cascade through the Postgres foreign key (H2's entity-built schema has none).
        assertThat(count("app_user_email_claim", "email_lower = ?", "abandoned@example.com")).isZero();
    }

    @Test
    @DisplayName("refuses an activated account")
    void refusesAnActivatedAccount() {
        long active = account("active", true, "a-hash", UserRole.LITE_USER);

        assertThat(userService.purgeAbandonedPending(active, NOW)).isFalse();

        assertThat(count("app_user", "id = ?", active)).isEqualTo(1);
    }

    @Test
    @DisplayName("refuses an admin and a missing account")
    void refusesAnAdminAndAMissingAccount() {
        long admin = account("boss", false, "", UserRole.ADMIN);

        assertThat(userService.purgeAbandonedPending(admin, NOW)).isFalse();
        assertThat(userService.purgeAbandonedPending(admin + 1000, NOW)).isFalse();

        assertThat(count("app_user", "id = ?", admin)).isEqualTo(1);
    }

    @Test
    @DisplayName("refuses while ANY unexpired link exists, verified or not, and keeps everything")
    void refusesWhileAnUnexpiredLinkExists() {
        token(pendingId, true, NOW.plusHours(2));
        assertThat(userService.purgeAbandonedPending(pendingId, NOW)).isFalse();

        jdbc.update("DELETE FROM email_verification_token");
        token(pendingId, false, NOW.plusHours(2));
        assertThat(userService.purgeAbandonedPending(pendingId, NOW)).isFalse();

        assertThat(count("app_user", "id = ?", pendingId)).isEqualTo(1);
        assertThat(count("user_drive_time", "user_id = ?", pendingId)).isEqualTo(1);
        assertThat(count("app_user_email_claim", "email_lower = ?", "abandoned@example.com")).isEqualTo(1);
    }

    @Test
    @DisplayName("an admin-cycled account (enabled then disabled with a null password) keeps the pending shape "
            + "and is purged on its age")
    void adminCycledPendingShapeIsPurged() {
        jdbc.update("UPDATE app_user SET enabled = TRUE WHERE id = ?", pendingId);
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", pendingId);

        assertThat(userService.purgeAbandonedPending(pendingId, NOW)).isTrue();
        assertThat(count("app_user", "id = ?", pendingId)).isZero();
    }
}
