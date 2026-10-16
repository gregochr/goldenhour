package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the email-matching and reset-support queries on {@link AppUserRepository} against a real JPA
 * stack (login-registration plan §1 #5, §2 #1, §2 #10): the case-insensitive lookups return EVERY matching
 * row so the service can treat two as no match, the pending delete is a compare-and-set on the pending
 * shape, the reset update is a compare-and-set on {@code enabled}, and the locked projection reads
 * column values straight from the database.
 */
@DataJpaTest
class AppUserRepositoryEmailTest {

    @Autowired
    private AppUserRepository users;
    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    private AppUserEntity account(String username, String email, boolean enabled, String password) {
        return entityManager.persistFlushFind(AppUserEntity.builder().username(username).email(email)
                .password(password).role(UserRole.LITE_USER).enabled(enabled).passwordChangeRequired(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build());
    }

    @Test
    @DisplayName("findActiveByEmailLower returns BOTH rows of a legacy case-duplicate pair, so the service "
            + "can treat the pair as no match")
    void findActiveByEmailLower_returnsBothCaseVariants() {
        account("upper", "A@x.com", true, "hash");
        account("lower", "a@x.com", true, "hash");

        assertThat(users.findActiveByEmailLower("a@x.com")).extracting(AppUserEntity::getUsername)
                .containsExactlyInAnyOrder("upper", "lower");
    }

    @Test
    @DisplayName("findActiveByEmailLower ignores disabled accounts and pending registrations")
    void findActiveByEmailLower_onlyActiveRows() {
        account("active", "a@x.com", true, "hash");
        account("disabled", "A@x.com", false, "hash");
        account("pending", "A@X.com", false, "");
        account("enabled-no-password", "a@X.com", true, "");
        account("someone-else", "b@x.com", true, "hash");

        assertThat(users.findActiveByEmailLower("a@x.com")).extracting(AppUserEntity::getUsername)
                .containsExactly("active");
    }

    @Test
    @DisplayName("findActiveByEmailLower takes a lower-cased key: an upper-case key matches nothing, and a "
            + "Turkish dotted capital I is not folded onto an ASCII address")
    void findActiveByEmailLower_isKeyedLowerCase() {
        account("alice", "alice@x.com", true, "hash");

        assertThat(users.findActiveByEmailLower("ALICE@X.COM")).isEmpty();
        assertThat(users.findActiveByEmailLower("alİce@x.com")).isEmpty();
    }

    @Test
    @DisplayName("findPendingByEmailLower returns only the disabled-with-empty-password shape")
    void findPendingByEmailLower_onlyPendingRows() {
        account("pending", "Pending@x.com", false, "");
        account("active", "pending@X.com", true, "hash");
        account("disabled", "PENDING@x.com", false, "hash");

        assertThat(users.findPendingByEmailLower("pending@x.com")).extracting(AppUserEntity::getUsername)
                .containsExactly("pending");
    }

    @Test
    @DisplayName("deletePendingById deletes a pending row (1) and refuses an activated one (0): the row an "
            + "activation has just enabled can never be erased by a retried sign-up")
    void deletePendingById_isACompareAndSet() {
        AppUserEntity pending = account("pending", "p@x.com", false, "");
        AppUserEntity activated = account("activated", "a@x.com", true, "hash");
        AppUserEntity disabledWithHash = account("disabled", "d@x.com", false, "hash");

        assertThat(users.deletePendingById(activated.getId())).isZero();
        assertThat(users.deletePendingById(disabledWithHash.getId())).isZero();
        assertThat(users.deletePendingById(pending.getId())).isEqualTo(1);
        assertThat(users.deletePendingById(pending.getId())).isZero();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("applyPasswordReset sets the hash and clears the first-login gate for an enabled account (1) "
            + "and writes nothing for a disabled one (0)")
    void applyPasswordReset_isACompareAndSetOnEnabled() {
        AppUserEntity enabled = account("on", "on@x.com", true, "old");
        AppUserEntity disabled = account("off", "off@x.com", false, "old");

        assertThat(users.applyPasswordReset(enabled.getId(), "new")).isEqualTo(1);
        assertThat(users.applyPasswordReset(disabled.getId(), "new")).isZero();

        assertThat(jdbc.queryForObject("SELECT password FROM app_user WHERE id = ?", String.class,
                enabled.getId())).isEqualTo("new");
        assertThat(jdbc.queryForObject("SELECT password_change_required FROM app_user WHERE id = ?",
                Boolean.class, enabled.getId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT password FROM app_user WHERE id = ?", String.class,
                disabled.getId())).isEqualTo("old");
    }

    @Test
    @DisplayName("lockAccount reads enabled, hash, STORED email, username and role as column values; "
            + "pending() and active() classify the shapes")
    void lockAccount_readsTheRow() {
        AppUserEntity user = account("reader", "Reader@X.com", true, "hash");
        AppUserEntity pending = account("pending", "p@x.com", false, "");

        LockedAccount locked = users.lockAccount(user.getId()).orElseThrow();
        assertThat(locked).isEqualTo(new LockedAccount(true, "hash", "Reader@X.com", "reader",
                UserRole.LITE_USER));
        assertThat(locked.active()).isTrue();
        assertThat(locked.pending()).isFalse();

        LockedAccount lockedPending = users.lockAccount(pending.getId()).orElseThrow();
        assertThat(lockedPending.pending()).isTrue();
        assertThat(lockedPending.active()).isFalse();

        assertThat(users.lockAccount(user.getId() + 1000)).isEmpty();
    }

    @Test
    @DisplayName("findByIdForUpdate loads the row, or nothing")
    void findByIdForUpdate() {
        AppUserEntity user = account("reader", "r@x.com", true, "hash");

        assertThat(users.findByIdForUpdate(user.getId())).isPresent();
        assertThat(users.findByIdForUpdate(user.getId() + 1000)).isEmpty();
    }
}
