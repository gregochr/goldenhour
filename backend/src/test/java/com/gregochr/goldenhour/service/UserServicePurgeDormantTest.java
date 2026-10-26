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

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link UserService#purgeDormant} and the dormancy queries behind it against a real JPA stack: the purge
 * deletes a warned, still-dormant LITE_USER with its drive-time child and email claim, and refuses (deleting
 * nothing) a Pro, an Admin, a disabled, a pending and an address-less account, and a reader who signed in after
 * the warning. The conditional {@code deleteDormantIfUnchanged} is tested on its own too, because its WHERE is
 * the last line of defence after the locked read.
 */
@DataJpaTest
@Import({UserService.class, EmailClaims.class, CredentialChangeWriter.class,
        UserServicePurgeDormantTest.TestBeans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserServicePurgeDormantTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2031, 6, 30, 10, 0);
    private static final LocalDateTime INACTIVE_CUTOFF = NOW.minusDays(90);
    private static final LocalDateTime WARN_CUTOFF = NOW.minusDays(14);

    /** The collaborators {@link UserService} injects that a data-JPA slice does not supply. */
    @TestConfiguration
    static class TestBeans {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2031-06-30T10:00:00Z"), ZoneOffset.UTC);
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

    private long dormantId;

    @BeforeEach
    void setUp() {
        clean();
        dormantId = dormant("sleeper", UserRole.LITE_USER, true, "a-hash", "sleeper@example.com");
        jdbc.update("INSERT INTO app_user_email_claim (email_lower, claimed_at) VALUES (?, ?)",
                "sleeper@example.com", Timestamp.valueOf(NOW));
        jdbc.update("INSERT INTO user_drive_time (user_id, location_id, drive_duration_seconds) VALUES (?, 1, 600)",
                dormantId);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM user_drive_time");
        jdbc.update("DELETE FROM app_user_email_claim");
        jdbc.update("DELETE FROM app_user");
    }

    /** A user last active 100 days ago and warned 20 days ago: dormant, warned, and past its grace. */
    private long dormant(String name, UserRole role, boolean enabled, String password, String email) {
        long id = users.save(AppUserEntity.builder().username(name).email(email).password(password).role(role)
                .enabled(enabled).createdAt(NOW.minusDays(300)).lastActiveAt(NOW.minusDays(100)).build()).getId();
        warn(id, NOW.minusDays(20));
        return id;
    }

    private void warn(long id, LocalDateTime at) {
        jdbc.update("UPDATE app_user SET dormancy_warning_sent_at = ? WHERE id = ?", Timestamp.valueOf(at), id);
    }

    private int count(String table, String where, Object... args) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }

    private void assertKept(long id) {
        assertThat(count("app_user", "id = ?", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("deletes the warned dormant Lite account with its drive time and email claim, and returns who")
    void purgesTheDormantAccount() {
        Optional<DeletedAccount> deleted = userService.purgeDormant(dormantId, INACTIVE_CUTOFF, WARN_CUTOFF);

        assertThat(deleted).contains(new DeletedAccount("sleeper", "sleeper@example.com"));
        assertThat(count("app_user", "id = ?", dormantId)).isZero();
        assertThat(count("user_drive_time", "user_id = ?", dormantId)).isZero();
        assertThat(count("app_user_email_claim", "email_lower = ?", "sleeper@example.com")).isZero();
    }

    @Test
    @DisplayName("an account never active is judged from created_at and is deleted once warned")
    void neverActiveAccountIsDeletedFromCreatedAt() {
        jdbc.update("UPDATE app_user SET last_active_at = NULL WHERE id = ?", dormantId);

        assertThat(userService.purgeDormant(dormantId, INACTIVE_CUTOFF, WARN_CUTOFF)).isPresent();
    }

    @Test
    @DisplayName("refuses a Pro, an Admin, a disabled, a pending and an address-less account, keeping all of them")
    void refusesEveryoneButAnEnabledLite() {
        long pro = dormant("pro", UserRole.PRO_USER, true, "a-hash", "pro@example.com");
        long admin = dormant("boss", UserRole.ADMIN, true, "a-hash", "boss@example.com");
        long disabled = dormant("off", UserRole.LITE_USER, false, "a-hash", "off@example.com");
        long pending = dormant("pending", UserRole.LITE_USER, false, "", "pending@example.com");
        long noEmail = dormant("blank", UserRole.LITE_USER, true, "a-hash", "   ");

        for (long id : new long[] {pro, admin, disabled, pending, noEmail}) {
            assertThat(userService.purgeDormant(id, INACTIVE_CUTOFF, WARN_CUTOFF)).isEmpty();
            assertKept(id);
        }
        assertThat(userService.purgeDormant(dormantId + 1000, INACTIVE_CUTOFF, WARN_CUTOFF)).isEmpty();
    }

    @Test
    @DisplayName("a promotion to Pro after the warning cancels the deletion")
    void promotionAfterTheWarningSavesTheAccount() {
        jdbc.update("UPDATE app_user SET role = 'PRO_USER' WHERE id = ?", dormantId);

        assertThat(userService.purgeDormant(dormantId, INACTIVE_CUTOFF, WARN_CUTOFF)).isEmpty();
        assertKept(dormantId);
        assertThat(count("user_drive_time", "user_id = ?", dormantId)).isEqualTo(1);
        assertThat(count("app_user_email_claim", "email_lower = ?", "sleeper@example.com")).isEqualTo(1);
    }

    @Test
    @DisplayName("a disable after the warning cancels the deletion")
    void disableAfterTheWarningSavesTheAccount() {
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", dormantId);

        assertThat(userService.purgeDormant(dormantId, INACTIVE_CUTOFF, WARN_CUTOFF)).isEmpty();
        assertKept(dormantId);
    }

    @Test
    @DisplayName("a sign-in one second after the warning cancels the deletion")
    void signInAfterTheWarningSavesTheAccount() {
        jdbc.update("UPDATE app_user SET last_active_at = ? WHERE id = ?",
                Timestamp.valueOf(NOW.minusDays(20).plusSeconds(1)), dormantId);

        assertThat(userService.purgeDormant(dormantId, INACTIVE_CUTOFF, WARN_CUTOFF)).isEmpty();
        assertKept(dormantId);
    }

    @Test
    @DisplayName("a sign-in at the very instant of the warning also saves it (a warning must be strictly later)")
    void signInAtTheWarningInstantSavesTheAccount() {
        jdbc.update("UPDATE app_user SET last_active_at = ? WHERE id = ?",
                Timestamp.valueOf(NOW.minusDays(20)), dormantId);

        assertThat(userService.purgeDormant(dormantId, INACTIVE_CUTOFF, WARN_CUTOFF)).isEmpty();
        assertKept(dormantId);
    }

    @Test
    @DisplayName("an unwarned account is never deleted")
    void unwarnedAccountIsKept() {
        jdbc.update("UPDATE app_user SET dormancy_warning_sent_at = NULL WHERE id = ?", dormantId);

        assertThat(userService.purgeDormant(dormantId, INACTIVE_CUTOFF, WARN_CUTOFF)).isEmpty();
        assertKept(dormantId);
    }

    @Test
    @DisplayName("a warning still inside its grace period is not deleted; one exactly at the cutoff is")
    void graceBoundary() {
        warn(dormantId, WARN_CUTOFF.plusSeconds(1));
        assertThat(userService.purgeDormant(dormantId, INACTIVE_CUTOFF, WARN_CUTOFF)).isEmpty();
        assertKept(dormantId);

        warn(dormantId, WARN_CUTOFF);
        assertThat(userService.purgeDormant(dormantId, INACTIVE_CUTOFF, WARN_CUTOFF)).isPresent();
    }

    @Test
    @DisplayName("an account active inside the inactivity window is kept even with a stamped warning")
    void recentlyActiveAccountIsKept() {
        jdbc.update("UPDATE app_user SET last_active_at = ? WHERE id = ?",
                Timestamp.valueOf(INACTIVE_CUTOFF.plusSeconds(1)), dormantId);

        assertThat(userService.purgeDormant(dormantId, INACTIVE_CUTOFF, WARN_CUTOFF)).isEmpty();
        assertKept(dormantId);
    }

    @Test
    @DisplayName("the conditional delete alone refuses each changed condition (role, enabled, password, email, "
            + "activity, warning), and deletes the account when none changed")
    void conditionalDeleteChecksEveryCondition() {
        String[] breakers = {
            "UPDATE app_user SET role = 'PRO_USER' WHERE id = ?",
            "UPDATE app_user SET role = 'ADMIN' WHERE id = ?",
            "UPDATE app_user SET enabled = FALSE WHERE id = ?",
            "UPDATE app_user SET password = '' WHERE id = ?",
            "UPDATE app_user SET email = '  ' WHERE id = ?",
            "UPDATE app_user SET email = NULL WHERE id = ?",
            "UPDATE app_user SET last_active_at = TIMESTAMP '2031-06-29 09:00:00' WHERE id = ?",
            "UPDATE app_user SET last_active_at = TIMESTAMP '2031-06-12 10:00:00' WHERE id = ?",
            "UPDATE app_user SET dormancy_warning_sent_at = NULL WHERE id = ?",
            "UPDATE app_user SET dormancy_warning_sent_at = TIMESTAMP '2031-06-20 10:00:00' WHERE id = ?"
        };
        for (String breaker : breakers) {
            long id = dormant("b" + Math.abs(breaker.hashCode()), UserRole.LITE_USER, true, "a-hash",
                    "b" + Math.abs(breaker.hashCode()) + "@example.com");
            jdbc.update(breaker, id);

            assertThat(users.deleteDormantIfUnchanged(id, UserRole.LITE_USER, INACTIVE_CUTOFF, WARN_CUTOFF))
                    .as(breaker).isZero();
            assertKept(id);
        }

        assertThat(users.deleteDormantIfUnchanged(dormantId, UserRole.LITE_USER, INACTIVE_CUTOFF, WARN_CUTOFF))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the locked read returns the scalar state, or empty for an unknown id")
    void lockedReadReturnsTheState() {
        AppUserRepository.DormancyState state = users.lockDormancyState(dormantId).orElseThrow();

        assertThat(state.role()).isEqualTo(UserRole.LITE_USER);
        assertThat(state.enabled()).isTrue();
        assertThat(state.username()).isEqualTo("sleeper");
        assertThat(state.lastActivity()).isEqualTo(NOW.minusDays(100));
        assertThat(state.warningSentAt()).isEqualTo(NOW.minusDays(20));
        assertThat(state.touchable()).isTrue();
        assertThat(users.lockDormancyState(dormantId + 1000)).isEmpty();
    }
}
