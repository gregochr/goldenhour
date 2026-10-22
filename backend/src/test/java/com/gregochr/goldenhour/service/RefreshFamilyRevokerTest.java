package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link RefreshFamilyRevoker} commits its own transaction: with no surrounding test transaction
 * ({@code NOT_SUPPORTED}) the revocation is visible to a second connection the moment {@code revokeAll}
 * returns — which is exactly what lets {@code AuthenticationService.refresh} revoke the family and THEN
 * throw the refusal without the revocation rolling back (login-registration plan §2 #14 rule 7).
 */
@DataJpaTest
@Import(RefreshFamilyRevoker.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefreshFamilyRevokerTest {

    @Autowired
    private RefreshFamilyRevoker revoker;
    @Autowired
    private RefreshTokenRepository refreshTokens;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM app_user");
    }

    private long user(String name) {
        return users.save(AppUserEntity.builder().username(name).password("hash").role(UserRole.LITE_USER)
                .enabled(true).createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build()).getId();
    }

    private void token(String hash, long userId, boolean revoked) {
        refreshTokens.save(RefreshTokenEntity.builder().tokenHash(hash).userId(userId)
                .expiresAt(LocalDateTime.now().plusDays(10)).revoked(revoked).build());
    }

    @Test
    @DisplayName("revokes every active refresh token of the user, COMMITTED, and leaves another user's alone")
    void revokesTheFamilyAndCommits() {
        long victim = user("victim");
        long other = user("other");
        token("a", victim, false);
        token("b", victim, false);
        token("already", victim, true);
        token("o", other, false);

        revoker.revokeAll(victim, "already-revoked token presented");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoked = FALSE",
                Integer.class, victim)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoked = FALSE",
                Integer.class, other)).isEqualTo(1);
    }

    @Test
    @DisplayName("a user with no tokens is a quiet no-op")
    void noTokens_isANoOp() {
        long victim = user("victim");

        revoker.revokeAll(victim, "lost rotation race");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token", Integer.class)).isZero();
    }
}
