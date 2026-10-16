package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.PasswordResetTokenEntity;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.PasswordResetTokenRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link PasswordResetRedeemer} against a real JPA stack, in the one shape that can fail for the
 * reason the plan cares about (login-registration plan §2 #14 rule 1).
 *
 * <p>Open Session In View is on in production, so the request's persistence context outlives every
 * transaction in it and already holds the user entity that was loaded BEFORE the slow BCrypt hash. These
 * tests reproduce that: a request-scoped {@link EntityManager} is bound to the thread exactly as
 * {@code OpenEntityManagerInViewInterceptor} does, the user is loaded into it, and the concurrent change
 * is then committed through another connection (the test runs with no surrounding transaction, so every
 * {@link JdbcTemplate} write is committed at once). A redeemer that re-checked the account by reading a
 * locked ENTITY would be handed the stale managed instance and fail these cases; the scalar
 * projection under the lock does not.
 *
 * <p>What this cannot show is the row lock making a second writer WAIT — that is Postgres behaviour,
 * covered by the row-count compare-and-sets that decide every outcome here.
 */
@DataJpaTest
@Import(PasswordResetRedeemer.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PasswordResetRedeemerTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    @Autowired
    private PasswordResetRedeemer redeemer;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordResetTokenRepository tokens;
    @Autowired
    private RefreshTokenRepository refreshTokens;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private EntityManager requestScoped;
    private long userId;
    private long tokenId;

    @BeforeEach
    void bindRequestScopedPersistenceContext() {
        requestScoped = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestScoped));

        AppUserEntity user = users.save(AppUserEntity.builder().username("reader").email("reader@example.com")
                .password("old-hash").role(UserRole.PRO_USER).enabled(true).passwordChangeRequired(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build());
        userId = user.getId();
        tokenId = tokens.save(PasswordResetTokenEntity.builder().tokenHash("hash-1").userId(userId)
                .expiresAt(NOW.plusSeconds(3600)).createdAt(NOW).build()).getId();
    }

    @AfterEach
    void unbindAndClean() {
        TransactionSynchronizationManager.unbindResource(entityManagerFactory);
        requestScoped.close();
        jdbc.update("DELETE FROM password_reset_token");
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM app_user");
    }

    private String storedPassword() {
        return jdbc.queryForObject("SELECT password FROM app_user WHERE id = ?", String.class, userId);
    }

    private Object usedAt(long id) {
        return jdbc.queryForObject("SELECT used_at FROM password_reset_token WHERE id = ?", Object.class, id);
    }

    @Test
    @DisplayName("redeem consumes the token, sets the password, clears the first-login gate, voids sibling "
            + "links and revokes every active refresh token")
    void redeem_appliesTheWholeChange() {
        long siblingId = tokens.save(PasswordResetTokenEntity.builder().tokenHash("hash-2").userId(userId)
                .expiresAt(NOW.plusSeconds(3600)).createdAt(NOW).build()).getId();
        refreshTokens.save(RefreshTokenEntity.builder().tokenHash("r1").userId(userId)
                .expiresAt(LocalDateTime.of(2026, 12, 1, 0, 0)).revoked(false).build());
        refreshTokens.save(RefreshTokenEntity.builder().tokenHash("r2").userId(userId)
                .expiresAt(LocalDateTime.of(2026, 12, 1, 0, 0)).revoked(false).build());

        AppUserEntity redeemed = redeemer.redeem(tokenId, userId, "new-hash", NOW);

        assertThat(storedPassword()).isEqualTo("new-hash");
        assertThat(jdbc.queryForObject("SELECT password_change_required FROM app_user WHERE id = ?",
                Boolean.class, userId)).isFalse();
        assertThat(usedAt(tokenId)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_token WHERE id = ?", Integer.class,
                siblingId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE revoked = FALSE AND user_id = ?",
                Integer.class, userId)).isZero();
        // The returned row is RE-READ after the writes, never the entity loaded before them.
        assertThat(redeemed.getPassword()).isEqualTo("new-hash");
        assertThat(redeemed.isPasswordChangeRequired()).isFalse();
    }

    @Test
    @DisplayName("an admin who disabled the account while the password was being hashed wins: the redemption "
            + "is refused 'invalid', the token left unconsumed and the password untouched — even though the "
            + "request already holds the account as ENABLED")
    void redeem_accountDisabledAfterTheEntityWasLoaded_isRefused() {
        AppUserEntity loadedBeforeTheHash = users.findById(userId).orElseThrow();
        assertThat(loadedBeforeTheHash.isEnabled()).isTrue();

        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", userId);

        assertThatThrownBy(() -> redeemer.redeem(tokenId, userId, "new-hash", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This reset link is invalid");

        assertThat(usedAt(tokenId)).isNull();
        assertThat(storedPassword()).isEqualTo("old-hash");
        // The trap, pinned: the managed entity is still stale. A check that read it would have passed.
        assertThat(loadedBeforeTheHash.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("a pending registration's link is refused 'invalid' (a reset must not activate an account)")
    void redeem_pendingAccount_isRefused() {
        users.findById(userId).orElseThrow();
        jdbc.update("UPDATE app_user SET enabled = FALSE, password = '' WHERE id = ?", userId);

        assertThatThrownBy(() -> redeemer.redeem(tokenId, userId, "new-hash", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This reset link is invalid");

        assertThat(usedAt(tokenId)).isNull();
        assertThat(storedPassword()).isEmpty();
    }

    @Test
    @DisplayName("a link whose account has been deleted is refused 'invalid'")
    void redeem_deletedAccount_isRefused() {
        jdbc.update("DELETE FROM password_reset_token");
        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);

        assertThatThrownBy(() -> redeemer.redeem(tokenId, userId, "new-hash", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This reset link is invalid");
    }

    @Test
    @DisplayName("a second redemption of the same link is refused 'already been used' and changes nothing: "
            + "the compare-and-set, not a read, decides")
    void redeem_secondRedemption_isRefused() {
        redeemer.redeem(tokenId, userId, "new-hash", NOW);

        assertThatThrownBy(() -> redeemer.redeem(tokenId, userId, "another-hash", NOW.plusSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This reset link has already been used");

        assertThat(storedPassword()).isEqualTo("new-hash");
    }

    @Test
    @DisplayName("a token already consumed by a concurrent request (used_at set after this request read it "
            + "as unused) is refused 'already been used' and the password is not changed")
    void redeem_consumedByAConcurrentRequest_isRefused() {
        tokens.findById(tokenId).orElseThrow();
        jdbc.update("UPDATE password_reset_token SET used_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(1)), tokenId);

        assertThatThrownBy(() -> redeemer.redeem(tokenId, userId, "new-hash", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This reset link has already been used");

        assertThat(storedPassword()).isEqualTo("old-hash");
    }

    @Test
    @DisplayName("another user's refresh tokens and links are untouched")
    void redeem_touchesOnlyThisUsersRows() {
        AppUserEntity other = users.save(AppUserEntity.builder().username("other").email("other@example.com")
                .password("other-hash").role(UserRole.LITE_USER).enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build());
        refreshTokens.save(RefreshTokenEntity.builder().tokenHash("o1").userId(other.getId())
                .expiresAt(LocalDateTime.of(2026, 12, 1, 0, 0)).revoked(false).build());
        tokens.save(PasswordResetTokenEntity.builder().tokenHash("other-link").userId(other.getId())
                .expiresAt(NOW.plusSeconds(3600)).createdAt(NOW).build());

        redeemer.redeem(tokenId, userId, "new-hash", NOW);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE revoked = FALSE "
                + "AND user_id = ?", Integer.class, other.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?",
                Integer.class, other.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password FROM app_user WHERE id = ?", String.class,
                other.getId())).isEqualTo("other-hash");
    }
}
