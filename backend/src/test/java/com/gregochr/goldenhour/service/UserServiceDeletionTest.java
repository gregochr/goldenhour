package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.StaleCredentialsException;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.CredentialChangeWriter.VersionExpectation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
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
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;

/**
 * Proves {@link UserService}'s two deletion paths against a real JPA stack, in the shape that can fail for
 * the reason the plan cares about (login-registration plan §2 #14 rule 1, rule 9; §2 #7).
 *
 * <p>Open Session In View is on in production, so the request's persistence context outlives every
 * transaction in it and already holds the user entity loaded BEFORE the slow BCrypt check. Each case binds a
 * request-scoped {@link EntityManager} exactly as {@code OpenEntityManagerInViewInterceptor} does, loads the
 * user into it, and commits the concurrent change through another connection ({@link JdbcTemplate} outside
 * any transaction). A deletion that re-checked by reading the locked ENTITY would be handed the stale
 * managed instance and delete the account; the scalar projection and the row-count compare-and-set do not.
 * The test runs with no surrounding transaction so a rollback in the service is observable.
 *
 * <p>What H2 cannot show is the {@code user_drive_time} foreign key (the H2 schema has none, because the
 * entity maps {@code userId} as a plain {@code Long}) — only {@code AccountDeletionIntegrationTest} on
 * Postgres can; here the purge is pinned by its effect on rows.
 */
@DataJpaTest
@Import({UserService.class, EmailClaims.class, CredentialChangeWriter.class,
        UserServiceDeletionTest.TestBeans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserServiceDeletionTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    /** The collaborators {@link UserService} injects that a data-JPA slice does not supply. */
    @TestConfiguration
    static class TestBeans {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @SuppressWarnings("deprecation")
        PasswordEncoder passwordEncoder() {
            return NoOpPasswordEncoder.getInstance();
        }
    }

    @Autowired
    private UserService userService;
    @MockitoSpyBean
    private AppUserRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private EntityManager requestScoped;
    private long userId;

    @BeforeEach
    void bindRequestScopedPersistenceContext() {
        cleanTables();
        requestScoped = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestScoped));
        userId = account("reader", "Reader@Example.com", UserRole.PRO_USER, "old-hash");
        claim("reader@example.com");
        driveTime(userId, 1L);
        driveTime(userId, 2L);
    }

    @AfterEach
    void unbindAndClean() {
        TransactionSynchronizationManager.unbindResource(entityManagerFactory);
        requestScoped.close();
        cleanTables();
    }

    private void cleanTables() {
        jdbc.update("DELETE FROM user_drive_time");
        jdbc.update("DELETE FROM app_user_email_claim");
        jdbc.update("DELETE FROM app_user");
    }

    private long account(String username, String email, UserRole role, String password) {
        return users.save(AppUserEntity.builder().username(username).email(email).password(password)
                .role(role).enabled(true).createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build()).getId();
    }

    private void claim(String key) {
        jdbc.update("INSERT INTO app_user_email_claim (email_lower, claimed_at) VALUES (?, ?)",
                key, java.sql.Timestamp.from(NOW));
    }

    private void driveTime(long forUser, long locationId) {
        jdbc.update("INSERT INTO user_drive_time (user_id, location_id, drive_duration_seconds) VALUES (?, ?, 600)",
                forUser, locationId);
    }

    private int count(String table, String where, Object... args) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }

    // ── deleteOwnAccount ────────────────────────────────────────────────────────

    @Test
    @DisplayName("deletes the account, purges ITS drive times (and only its own) and releases the email claim")
    void ownAccount_isDeletedWithItsDriveTimesAndClaim() {
        long other = account("other", "other@example.com", UserRole.LITE_USER, "other-hash");
        driveTime(other, 1L);

        DeletedAccount deleted = userService.deleteOwnAccount("reader", "old-hash", VersionExpectation.exact(0));

        assertThat(deleted).isEqualTo(new DeletedAccount("reader", "Reader@Example.com"));
        assertThat(count("app_user", "id = ?", userId)).isZero();
        assertThat(count("user_drive_time", "user_id = ?", userId)).isZero();
        assertThat(count("user_drive_time", "user_id = ?", other)).isEqualTo(1);
        assertThat(count("app_user_email_claim", "email_lower = ?", "reader@example.com")).isZero();
        assertThat(count("app_user", "id = ?", other)).isEqualTo(1);
    }

    @Test
    @DisplayName("the claim is KEPT while another account still uses the address under another casing")
    void ownAccount_claimSurvivesWhileAnotherAccountUsesTheAddress() {
        account("twin", "reader@example.com", UserRole.LITE_USER, "twin-hash");

        userService.deleteOwnAccount("reader", "old-hash", VersionExpectation.exact(0));

        assertThat(count("app_user_email_claim", "email_lower = ?", "reader@example.com")).isEqualTo(1);
    }

    @Test
    @DisplayName("a password changed through another connection AFTER the entity was loaded (and so after the "
            + "password was verified) is detected: the stale managed entity is never consulted")
    void ownAccount_passwordChangedMeanwhile_deletesNothing() {
        users.findByUsername("reader").orElseThrow();   // the request's persistence context holds "old-hash"
        jdbc.update("UPDATE app_user SET password = 'new-hash', credentials_version = 1 WHERE id = ?", userId);

        assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "old-hash", VersionExpectation.exact(0)))
                .isInstanceOf(StaleCredentialsException.class);

        assertThat(count("app_user", "id = ?", userId)).isEqualTo(1);
        assertThat(count("user_drive_time", "user_id = ?", userId)).isEqualTo(2);
        assertThat(count("app_user_email_claim", "email_lower = ?", "reader@example.com")).isEqualTo(1);
    }

    @Test
    @DisplayName("a credential version moved through another connection with the hash UNCHANGED (a disable and "
            + "re-enable) still deletes nothing: the version, not the hash alone, holds the session")
    void ownAccount_versionMovedWithSameHash_deletesNothing() {
        users.findByUsername("reader").orElseThrow();
        jdbc.update("UPDATE app_user SET credentials_version = 4 WHERE id = ?", userId);

        assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "old-hash", VersionExpectation.exact(0)))
                .isInstanceOf(StaleCredentialsException.class);

        assertThat(count("app_user", "id = ?", userId)).isEqualTo(1);
        assertThat(count("user_drive_time", "user_id = ?", userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a hash changed through another connection with the credential version UNCHANGED is refused "
            + "too: the hash condition holds on its own, not only through the version")
    void ownAccount_hashMovedWithSameVersion_deletesNothing() {
        users.findByUsername("reader").orElseThrow();
        jdbc.update("UPDATE app_user SET password = 'swapped-hash' WHERE id = ?", userId);

        assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "old-hash", VersionExpectation.exact(0)))
                .isInstanceOf(StaleCredentialsException.class);

        assertThat(count("app_user", "id = ?", userId)).isEqualTo(1);
        assertThat(count("user_drive_time", "user_id = ?", userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("the delete's compare-and-set holds the row to ALL THREE conditions on its own, whatever the "
            + "scalar check before it said: a wrong hash, a wrong version and an ADMIN role each delete 0 rows")
    void compareAndSet_eachConditionAloneRefuses() {
        assertThat(users.deleteOwnAccountIfUnchanged(userId, "wrong-hash", 0L, UserRole.ADMIN)).isZero();
        assertThat(users.deleteOwnAccountIfUnchanged(userId, "old-hash", 1L, UserRole.ADMIN)).isZero();
        jdbc.update("UPDATE app_user SET role = 'ADMIN' WHERE id = ?", userId);
        assertThat(users.deleteOwnAccountIfUnchanged(userId, "old-hash", 0L, UserRole.ADMIN)).isZero();
        assertThat(count("app_user", "id = ?", userId)).isEqualTo(1);

        jdbc.update("UPDATE app_user SET role = 'PRO_USER' WHERE id = ?", userId);
        assertThat(users.deleteOwnAccountIfUnchanged(userId, "old-hash", 0L, UserRole.ADMIN)).isEqualTo(1);
        assertThat(count("app_user", "id = ?", userId)).isZero();
    }

    @Test
    @DisplayName("an account an admin promoted through another connection after the entity was loaded is "
            + "refused with the admin sentence and survives, drive times and all")
    void ownAccount_promotedMeanwhile_isRefused() {
        users.findByUsername("reader").orElseThrow();   // the stale entity still says PRO_USER
        jdbc.update("UPDATE app_user SET role = 'ADMIN' WHERE id = ?", userId);

        assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "old-hash", VersionExpectation.exact(0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Admin accounts are deleted by another admin");

        assertThat(count("app_user", "id = ?", userId)).isEqualTo(1);
        assertThat(count("user_drive_time", "user_id = ?", userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a legacy token (no cv) deletes a never-changed row and is refused on a changed one")
    void ownAccount_legacyExpectation() {
        jdbc.update("UPDATE app_user SET credentials_version = 1 WHERE id = ?", userId);
        assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "old-hash",
                VersionExpectation.legacyUnchanged())).isInstanceOf(StaleCredentialsException.class);
        assertThat(count("app_user", "id = ?", userId)).isEqualTo(1);

        jdbc.update("UPDATE app_user SET credentials_version = 0 WHERE id = ?", userId);
        userService.deleteOwnAccount("reader", "old-hash", VersionExpectation.legacyUnchanged());
        assertThat(count("app_user", "id = ?", userId)).isZero();
    }

    @Test
    @DisplayName("when the delete's compare-and-set removes no row the whole transaction rolls back: the purge "
            + "of the drive times is undone and the claim is kept")
    void ownAccount_compareAndSetLoses_rollsThePurgeBack() {
        doReturn(0).when(users).deleteOwnAccountIfUnchanged(anyLong(), any(), anyLong(), any());

        assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "old-hash", VersionExpectation.exact(0)))
                .isInstanceOf(StaleCredentialsException.class);

        assertThat(count("app_user", "id = ?", userId)).isEqualTo(1);
        assertThat(count("user_drive_time", "user_id = ?", userId)).isEqualTo(2);
        assertThat(count("app_user_email_claim", "email_lower = ?", "reader@example.com")).isEqualTo(1);
    }

    // ── deleteUser (admin path) ─────────────────────────────────────────────────

    @Test
    @DisplayName("the admin path purges the drive times, deletes the account and releases its claim; it can "
            + "delete an ADMIN, as it always could")
    void adminPath_deletesAnyOtherAccount() {
        long boss = account("boss", "boss@example.com", UserRole.ADMIN, "boss-hash");
        driveTime(boss, 1L);
        claim("boss@example.com");

        DeletedAccount deleted = userService.deleteUser(boss, "reader");

        assertThat(deleted).isEqualTo(new DeletedAccount("boss", "boss@example.com"));
        assertThat(count("app_user", "id = ?", boss)).isZero();
        assertThat(count("user_drive_time", "user_id = ?", boss)).isZero();
        assertThat(count("app_user_email_claim", "email_lower = ?", "boss@example.com")).isZero();
        assertThat(count("app_user", "id = ?", userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("the admin path refuses self-deletion and leaves everything in place")
    void adminPath_selfDeletion_isRefused() {
        assertThatThrownBy(() -> userService.deleteUser(userId, "reader"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot delete your own account");

        assertThat(count("app_user", "id = ?", userId)).isEqualTo(1);
        assertThat(count("user_drive_time", "user_id = ?", userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("the admin path reads the STORED address under the lock, not the stale loaded entity: an "
            + "address changed through another connection is the one handed back for the email")
    void adminPath_returnsTheAddressStoredNow() {
        users.findByUsername("reader").orElseThrow();   // stale: Reader@Example.com
        jdbc.update("UPDATE app_user SET email = 'moved@example.com' WHERE id = ?", userId);

        DeletedAccount deleted = userService.deleteUser(userId, "boss");

        assertThat(deleted.email()).isEqualTo("moved@example.com");
    }
}
