package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.EmailClaims;
import com.gregochr.goldenhour.service.UserService;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the email reservation table's queries and {@link EmailClaims} against a real JPA stack
 * (login-registration plan §5 #6, §2 #14 rules 2, 3 and 9).
 *
 * <p>⚠️ Where a unique violation is asserted it is the LAST statement of its test: H2 does not abort the
 * transaction after one, Postgres does, and a test that went on to run another statement would pass here
 * and be impossible on the production engine.
 */
@DataJpaTest
@Import({EmailClaims.class, AppUserEmailClaimRepositoryTest.FixedClock.class})
class AppUserEmailClaimRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    /** Supplies the application {@link Clock} bean {@link EmailClaims} injects. */
    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private AppUserEmailClaimRepository claims;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private EmailClaims emailClaims;
    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    private AppUserEntity account(String username, String email) {
        return entityManager.persistFlushFind(AppUserEntity.builder().username(username).email(email)
                .password("hash").role(UserRole.LITE_USER).enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build());
    }

    private int reservations(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_user_email_claim WHERE email_lower = ?",
                Integer.class, key);
    }

    @Test
    @DisplayName("insertClaim writes the reservation immediately (visible to the next query, no flush needed)")
    void insertClaim_isImmediate() {
        assertThat(claims.insertClaim("foo@x.com", NOW)).isEqualTo(1);

        assertThat(reservations("foo@x.com")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT claimed_at FROM app_user_email_claim WHERE email_lower = ?",
                java.sql.Timestamp.class, "foo@x.com").toInstant()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("findByEmailLowerForUpdate finds an existing reservation and nothing for a new key")
    void findForUpdate() {
        claims.insertClaim("foo@x.com", NOW);

        assertThat(claims.findByEmailLowerForUpdate("foo@x.com")).isPresent();
        assertThat(claims.findByEmailLowerForUpdate("other@x.com")).isEmpty();
    }

    @Test
    @DisplayName("existsAnyOccupant: a NEW account is refused against a live case variant whose reservation "
            + "exists; nobody occupies a key no account uses")
    void existsAnyOccupant_seesACaseVariant() {
        account("alice", "Foo@X.com");
        claims.insertClaim("foo@x.com", NOW);

        assertThat(claims.existsAnyOccupant("foo@x.com")).isTrue();
        assertThat(claims.existsAnyOccupant("nobody@x.com")).isFalse();
    }

    @Test
    @DisplayName("existsOtherOccupant excludes the account being edited, so a casing-only change of an address "
            + "that account alone holds is not a conflict — and includes everybody else")
    void existsOtherOccupant_excludesTheEditedAccount() {
        AppUserEntity alice = account("alice", "Foo@X.com");
        AppUserEntity bob = account("bob", "bar@x.com");

        assertThat(claims.existsOtherOccupant("foo@x.com", alice.getId())).isFalse();
        assertThat(claims.existsOtherOccupant("foo@x.com", bob.getId())).isTrue();
    }

    @Test
    @DisplayName("releaseIfUnused keeps the key while ANY account still uses it, and frees it for the last: "
            + "a legacy case-duplicate pair shares one reservation")
    void releaseIfUnused_isConditional() {
        AppUserEntity first = account("first", "Foo@x.com");
        AppUserEntity second = account("second", "FOO@x.com");
        claims.insertClaim("foo@x.com", NOW);

        users.delete(first);
        users.flush();
        assertThat(claims.releaseIfUnused("foo@x.com")).isZero();
        assertThat(reservations("foo@x.com")).isEqualTo(1);

        users.delete(second);
        users.flush();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isZero();
        assertThat(claims.releaseIfUnused("foo@x.com")).isEqualTo(1);
        assertThat(reservations("foo@x.com")).isZero();
    }

    @Test
    @DisplayName("releaseIfUnused flushes the pending account write first, so a just-deleted account no "
            + "longer holds the key")
    void releaseIfUnused_seesThePendingDelete() {
        AppUserEntity only = account("only", "Foo@x.com");
        claims.insertClaim("foo@x.com", NOW);

        users.delete(only);

        assertThat(claims.releaseIfUnused("foo@x.com")).isEqualTo(1);
    }

    @Test
    @DisplayName("EmailClaims.claim: a brand-new address is reserved; claiming it again for another new "
            + "account while the first exists is refused")
    void claim_newThenOccupied() {
        emailClaims.claim("Foo@X.com", null);
        assertThat(reservations("foo@x.com")).isEqualTo(1);

        account("alice", "Foo@X.com");
        assertThatThrownBy(() -> emailClaims.claim("foo@x.com", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email already registered");
        assertThat(reservations("foo@x.com")).isEqualTo(1);
    }

    @Test
    @DisplayName("EmailClaims.claim reuses an orphaned reservation silently — the pending sign-up retry that "
            + "has just deleted its own abandoned row")
    void claim_orphanIsReused() {
        claims.insertClaim("foo@x.com", NOW);

        assertThatCode(() -> emailClaims.claim("Foo@x.com", null)).doesNotThrowAnyException();

        assertThat(reservations("foo@x.com")).isEqualTo(1);
    }

    @Test
    @DisplayName("EmailClaims.claim for an edit: the account's own address (any casing) is fine, another "
            + "account's is refused")
    void claim_forAnEdit() {
        AppUserEntity alice = account("alice", "Foo@X.com");
        account("bob", "bar@x.com");
        claims.insertClaim("foo@x.com", NOW);
        claims.insertClaim("bar@x.com", NOW);

        assertThatCode(() -> emailClaims.claim("FOO@x.com", alice.getId())).doesNotThrowAnyException();
        assertThatThrownBy(() -> emailClaims.claim("bar@x.com", alice.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email already registered");
    }

    private UserService userService() {
        PasswordEncoder encoder = org.mockito.Mockito.mock(PasswordEncoder.class);
        org.mockito.Mockito.when(encoder.encode(org.mockito.ArgumentMatchers.any())).thenReturn("hash");
        return new UserService(users, encoder, org.mockito.Mockito.mock(UserEmailService.class), emailClaims);
    }

    @Test
    @DisplayName("createPendingUser with the REAL claim: a retried sign-up replaces its abandoned pending row, "
            + "reuses the surviving reservation and leaves exactly one account and one reservation")
    void createPendingUser_retryReplacesTheAbandonedRow() {
        UserService service = userService();
        AppUserEntity first = service.createPendingUser("first", "Retry@X.com", true, UserRole.LITE_USER);
        assertThat(reservations("retry@x.com")).isEqualTo(1);

        AppUserEntity second = service.createPendingUser("second", "retry@x.com", false, UserRole.LITE_USER);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE LOWER(email) = 'retry@x.com'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT username FROM app_user WHERE LOWER(email) = 'retry@x.com'",
                String.class)).isEqualTo("second");
        assertThat(reservations("retry@x.com")).isEqualTo(1);
    }

    @Test
    @DisplayName("createPendingUser with the REAL claim: a case variant of an ACTIVE account's address is "
            + "refused 'Email already registered' and the active account survives")
    void createPendingUser_againstAnActiveCaseVariant_isRefused() {
        UserService service = userService();
        account("alice", "Alice@X.com");
        claims.insertClaim("alice@x.com", NOW);

        assertThatThrownBy(() -> service.createPendingUser("mallory", "ALICE@x.com", true, UserRole.LITE_USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email already registered");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("createUser with the REAL claim refuses a case variant of an existing address and "
            + "reserves a new one")
    void createUser_claimsAndRefusesCaseVariants() {
        UserService service = userService();
        service.createUser("admin-made", "pass", UserRole.LITE_USER, "Made@X.com");
        assertThat(reservations("made@x.com")).isEqualTo(1);

        assertThatThrownBy(() -> service.createUser("other", "pass", UserRole.LITE_USER, "MADE@x.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email already registered");
    }

    @Test
    @DisplayName("a second INSERT of the same key violates the primary key (the concurrent first claim) — "
            + "the last statement of this test")
    void insertClaim_duplicateKey_violates() {
        claims.insertClaim("foo@x.com", NOW);

        assertThatThrownBy(() -> claims.insertClaim("foo@x.com", NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
