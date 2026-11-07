package com.gregochr.goldenhour.repository;

import java.time.Instant;
import java.time.LocalDateTime;

import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository.DormancyState;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests the derived state predicates on the locked-row records of {@link AppUserRepository}. */
class AppUserRepositoryStateRecordsTest {

    private static LockedAccount account(boolean enabled, String password) {
        return new LockedAccount(enabled, password, "a@example.com", "alice", UserRole.LITE_USER, 3L,
                Instant.parse("2026-11-01T00:00:00Z"), false, false, null);
    }

    @Test
    @DisplayName("a disabled account holding the empty-password marker is a pending registration")
    void lockedAccount_pending() {
        assertThat(account(false, "").pending()).isTrue();
        assertThat(account(false, "").active()).isFalse();
    }

    @Test
    @DisplayName("an enabled account with a password hash is active and not pending")
    void lockedAccount_active() {
        assertThat(account(true, "hash").active()).isTrue();
        assertThat(account(true, "hash").pending()).isFalse();
    }

    @Test
    @DisplayName("a disabled account with a real hash is neither pending nor active")
    void lockedAccount_disabledWithHash_isNeither() {
        assertThat(account(false, "hash").pending()).isFalse();
        assertThat(account(false, "hash").active()).isFalse();
    }

    @Test
    @DisplayName("a null password is neither pending nor active, enabled or not")
    void lockedAccount_nullPassword_isNeither() {
        assertThat(account(false, null).pending()).isFalse();
        assertThat(account(true, null).active()).isFalse();
    }

    @Test
    @DisplayName("an enabled account with the empty-password marker is not active")
    void lockedAccount_enabledWithEmptyPassword_isNotActive() {
        assertThat(account(true, "").active()).isFalse();
        assertThat(account(true, "").pending()).isFalse();
    }

    private static DormancyState dormancy(UserRole role, boolean enabled, String password, String email,
            LocalDateTime lastActiveAt) {
        return new DormancyState(role, enabled, password, email, "alice", LocalDateTime.of(2026, 1, 1, 9, 0),
                lastActiveAt, null);
    }

    @Test
    @DisplayName("last activity is the last-active stamp, falling back to creation when never stamped")
    void dormancyState_lastActivity() {
        LocalDateTime active = LocalDateTime.of(2026, 10, 1, 8, 0);

        assertThat(dormancy(UserRole.LITE_USER, true, "h", "a@example.com", active).lastActivity())
                .isEqualTo(active);
        assertThat(dormancy(UserRole.LITE_USER, true, "h", "a@example.com", null).lastActivity())
                .isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0));
    }

    @Test
    @DisplayName("only an enabled, addressable LITE_USER holding a password is touchable by the sweep")
    void dormancyState_touchable() {
        assertThat(dormancy(UserRole.LITE_USER, true, "h", "a@example.com", null).touchable()).isTrue();
        assertThat(dormancy(UserRole.PRO_USER, true, "h", "a@example.com", null).touchable()).isFalse();
        assertThat(dormancy(UserRole.ADMIN, true, "h", "a@example.com", null).touchable()).isFalse();
        assertThat(dormancy(UserRole.LITE_USER, false, "h", "a@example.com", null).touchable()).isFalse();
        assertThat(dormancy(UserRole.LITE_USER, true, null, "a@example.com", null).touchable()).isFalse();
        assertThat(dormancy(UserRole.LITE_USER, true, "", "a@example.com", null).touchable()).isFalse();
        assertThat(dormancy(UserRole.LITE_USER, true, "h", null, null).touchable()).isFalse();
        assertThat(dormancy(UserRole.LITE_USER, true, "h", "  ", null).touchable()).isFalse();
    }
}
