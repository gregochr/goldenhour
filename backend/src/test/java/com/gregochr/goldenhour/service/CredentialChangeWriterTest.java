package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.EmailVerificationTokenEntity;
import com.gregochr.goldenhour.entity.PasswordResetTokenEntity;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.StaleCredentialsException;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
import com.gregochr.goldenhour.repository.PasswordResetTokenRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import com.gregochr.goldenhour.service.CredentialChangeWriter.Change;
import com.gregochr.goldenhour.service.CredentialChangeWriter.VersionExpectation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

/**
 * Proves {@link CredentialChangeWriter} against a real JPA stack, in the one shape that can fail for the
 * reason the plan cares about (login-registration plan §2 #14 rule 1, rule 9).
 *
 * <p>Open Session In View is on in production, so the request's persistence context outlives every
 * transaction in it and already holds the user entity that was loaded BEFORE the slow BCrypt hash. These
 * tests reproduce that: a request-scoped {@link EntityManager} is bound to the thread exactly as
 * {@code OpenEntityManagerInViewInterceptor} does, the user is loaded into it, and the concurrent change
 * is then committed through another connection (the test runs with no surrounding transaction, so every
 * {@link JdbcTemplate} write is committed at once, and a rollback in the writer is observable). A writer
 * that re-checked the account by reading a locked ENTITY would be handed the stale managed instance and
 * fail these cases; the scalar projection under the lock does not.
 *
 * <p>What this cannot show is the row lock making a second writer WAIT — that is Postgres behaviour,
 * covered by the row-count compare-and-sets that decide every outcome here and, for the wait itself, by
 * {@code TokenIssuerTest}'s two-thread case.
 */
@DataJpaTest
@Import({CredentialChangeWriter.class, CredentialChangeWriterTest.FixedClock.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CredentialChangeWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    /** Supplies the application {@link Clock} bean the writer injects: fixed at {@link #NOW}. */
    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private CredentialChangeWriter writer;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordResetTokenRepository resetTokens;
    @Autowired
    private EmailVerificationTokenRepository verificationTokens;
    @MockitoSpyBean
    private RefreshTokenRepository refreshTokens;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private EntityManager requestScoped;
    private long userId;

    @BeforeEach
    void bindRequestScopedPersistenceContext() {
        requestScoped = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestScoped));
        userId = account("reader", "reader@example.com", true, "old-hash", true);
    }

    @AfterEach
    void unbindAndClean() {
        TransactionSynchronizationManager.unbindResource(entityManagerFactory);
        requestScoped.close();
        jdbc.update("DELETE FROM password_reset_token");
        jdbc.update("DELETE FROM email_verification_token");
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM app_user");
    }

    private long account(String username, String email, boolean enabled, String password,
            boolean passwordChangeRequired) {
        return users.save(AppUserEntity.builder().username(username).email(email).password(password)
                .role(UserRole.PRO_USER).enabled(enabled).passwordChangeRequired(passwordChangeRequired)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build()).getId();
    }

    private void refreshToken(String hash, long forUser) {
        refreshTokens.save(RefreshTokenEntity.builder().tokenHash(hash).userId(forUser)
                .expiresAt(LocalDateTime.of(2026, 12, 1, 0, 0)).revoked(false).build());
    }

    private long resetToken(String hash, long forUser, Instant usedAt) {
        return resetTokens.save(PasswordResetTokenEntity.builder().tokenHash(hash).userId(forUser)
                .expiresAt(NOW.plusSeconds(3600)).createdAt(NOW).usedAt(usedAt).build()).getId();
    }

    private long verificationToken(String hash, long forUser, boolean verified) {
        return verificationTokens.save(EmailVerificationTokenEntity.builder().tokenHash(hash).userId(forUser)
                .expiresAt(LocalDateTime.of(2026, 12, 1, 0, 0)).verified(verified)
                .createdAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build()).getId();
    }

    private void setVersion(long forUser, long version) {
        jdbc.update("UPDATE app_user SET credentials_version = ? WHERE id = ?", version, forUser);
    }

    private LockedAccount row(long forUser) {
        return users.lockAccount(forUser).orElseThrow();
    }

    private int count(String table, String where, Object... args) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }

    @Nested
    class Apply {

        @Test
        @DisplayName("a change-password applies the WHOLE change: hash, cleared first-login gate, version 0 -> 1, "
                + "the stamp, every active refresh token revoked and every unused reset link deleted (a used "
                + "one kept)")
        void changePassword_appliesTheWholeChange() {
            refreshToken("r1", userId);
            refreshToken("r2", userId);
            resetToken("unused", userId, null);
            resetToken("used", userId, NOW.minusSeconds(30));

            CredentialChangeOutcome outcome = writer.apply(userId, "new-hash", Change.changePassword(),
                    VersionExpectation.exact(0));

            assertThat(outcome).isEqualTo(new CredentialChangeOutcome(userId, "reader", "reader@example.com", 1L));
            LockedAccount after = row(userId);
            assertThat(after.password()).isEqualTo("new-hash");
            assertThat(after.passwordChangeRequired()).isFalse();
            assertThat(after.enabled()).isTrue();
            assertThat(after.credentialsVersion()).isEqualTo(1L);
            assertThat(after.credentialsChangedAt()).isEqualTo(NOW);
            assertThat(count("refresh_token", "revoked = FALSE AND user_id = ?", userId)).isZero();
            assertThat(count("refresh_token", "revoked = TRUE AND user_id = ?", userId)).isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT token_hash FROM password_reset_token", String.class))
                    .containsExactly("used");
        }

        @Test
        @DisplayName("two serialised changes in the SAME instant leave versions 1 then 2: a counter cannot collide "
                + "the way an epoch-millisecond stamp could")
        void twoChangesInOneInstant_stillAdvanceTheVersion() {
            writer.apply(userId, "first", Change.changePassword(), VersionExpectation.exact(0));
            CredentialChangeOutcome second = writer.apply(userId, "second", Change.changePassword(),
                    VersionExpectation.exact(1));

            assertThat(second.credentialsVersion()).isEqualTo(2L);
            assertThat(row(userId).credentialsChangedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("exact(v): a row that has moved is refused with StaleCredentialsException and NOTHING is "
                + "written — the stolen session finishing its hash after the legitimate change loses")
        void exact_staleExpectation_isRefusedAndWritesNothing() {
            setVersion(userId, 3);
            refreshToken("r1", userId);
            resetToken("unused", userId, null);

            assertThatThrownBy(() -> writer.apply(userId, "stolen-session-hash", Change.changePassword(),
                    VersionExpectation.exact(2)))
                    .isInstanceOf(StaleCredentialsException.class)
                    .hasMessage("Your session has changed. Sign in again.");

            LockedAccount after = row(userId);
            assertThat(after.password()).isEqualTo("old-hash");
            assertThat(after.credentialsVersion()).isEqualTo(3L);
            assertThat(after.credentialsChangedAt()).isNull();
            assertThat(after.passwordChangeRequired()).isTrue();
            assertThat(count("refresh_token", "revoked = FALSE AND user_id = ?", userId)).isEqualTo(1);
            assertThat(count("password_reset_token", "user_id = ?", userId)).isEqualTo(1);
        }

        @Test
        @DisplayName("exact(v) is accepted when the row is exactly at v (and is then v + 1)")
        void exact_matchingExpectation_isAccepted() {
            setVersion(userId, 3);

            CredentialChangeOutcome outcome = writer.apply(userId, "new-hash", Change.changePassword(),
                    VersionExpectation.exact(3));

            assertThat(outcome.credentialsVersion()).isEqualTo(4L);
        }

        @Test
        @DisplayName("legacyUnchanged() is accepted on a never-changed row and refused on a changed one")
        void legacyUnchanged_acceptedOnlyWhileTheRowIsAtZero() {
            writer.apply(userId, "first", Change.changePassword(), VersionExpectation.legacyUnchanged());
            assertThat(row(userId).password()).isEqualTo("first");

            assertThatThrownBy(() -> writer.apply(userId, "second", Change.changePassword(),
                    VersionExpectation.legacyUnchanged()))
                    .isInstanceOf(StaleCredentialsException.class);
            assertThat(row(userId).password()).isEqualTo("first");
        }

        @Test
        @DisplayName("trusted() succeeds on a never-changed row and on a changed one: no version check at all")
        void trusted_ignoresTheVersion() {
            writer.apply(userId, "first", Change.changePassword(), VersionExpectation.trusted());
            writer.apply(userId, "second", Change.changePassword(), VersionExpectation.trusted());

            assertThat(row(userId).password()).isEqualTo("second");
            assertThat(row(userId).credentialsVersion()).isEqualTo(2L);
        }

        @Test
        @DisplayName("the admin temporary password SETS the first-login gate, applies to a disabled account too, "
                + "and still bumps the version")
        void adminTemporaryPassword_setsTheGate() {
            jdbc.update("UPDATE app_user SET enabled = FALSE, password_change_required = FALSE WHERE id = ?", userId);

            CredentialChangeOutcome outcome = writer.apply(userId, "temp-hash", Change.adminTemporaryPassword(),
                    VersionExpectation.trusted());

            LockedAccount after = row(userId);
            assertThat(after.password()).isEqualTo("temp-hash");
            assertThat(after.passwordChangeRequired()).isTrue();
            assertThat(after.enabled()).isFalse();
            assertThat(outcome.credentialsVersion()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a NULL password leaves the hash alone even when the request holds the OLD entity and the "
                + "hash moved after it was loaded: a disable behind a concurrent password change cannot "
                + "restore the stale hash")
        void disable_withNullPassword_neverRestoresAStaleHash() {
            AppUserEntity loadedBeforeTheChange = users.findById(userId).orElseThrow();
            jdbc.update("UPDATE app_user SET password = 'hash-committed-meanwhile' WHERE id = ?", userId);

            writer.apply(userId, null, Change.disable(), VersionExpectation.trusted());

            LockedAccount after = row(userId);
            assertThat(after.password()).isEqualTo("hash-committed-meanwhile");
            assertThat(after.enabled()).isFalse();
            assertThat(after.credentialsVersion()).isEqualTo(1L);
            // The trap, pinned: the managed entity still carries the old hash and the old enabled flag.
            assertThat(loadedBeforeTheChange.getPassword()).isEqualTo("old-hash");
            assertThat(loadedBeforeTheChange.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("a disable bumps the version, revokes every session and voids unused reset links AND every "
                + "verification link — so re-enabling revives nothing")
        void disable_endsEverythingTheAccountHeld() {
            refreshToken("r1", userId);
            resetToken("unused", userId, null);
            verificationToken("v1", userId, false);
            verificationToken("v2", userId, true);

            writer.apply(userId, null, Change.disable(), VersionExpectation.trusted());

            assertThat(count("refresh_token", "revoked = FALSE AND user_id = ?", userId)).isZero();
            assertThat(count("password_reset_token", "user_id = ?", userId)).isZero();
            assertThat(count("email_verification_token", "user_id = ?", userId)).isZero();
            assertThat(row(userId).passwordChangeRequired()).isTrue();
        }

        @Test
        @DisplayName("a plain password change does NOT touch verification links (only an enabled-state move does)")
        void changePassword_leavesVerificationLinksAlone() {
            verificationToken("v1", userId, false);

            writer.apply(userId, "new-hash", Change.changePassword(), VersionExpectation.trusted());

            assertThat(count("email_verification_token", "user_id = ?", userId)).isEqualTo(1);
        }

        @Test
        @DisplayName("a change-password for an account that was disabled after the session was issued is refused "
                + "as stale and writes nothing")
        void changePassword_onADisabledAccount_isRefused() {
            users.findById(userId).orElseThrow();
            jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", userId);

            assertThatThrownBy(() -> writer.apply(userId, "new-hash", Change.changePassword(),
                    VersionExpectation.exact(0)))
                    .isInstanceOf(StaleCredentialsException.class);

            assertThat(row(userId).password()).isEqualTo("old-hash");
            assertThat(row(userId).credentialsVersion()).isZero();
        }

        @Test
        @DisplayName("activation enables a pending account with the chosen password, clears the gate and "
                + "refuses an account that is not pending ('Account is not awaiting activation')")
        void activation_requiresAPendingAccount() {
            long pending = account("pending", "p@example.com", false, "", false);

            CredentialChangeOutcome outcome = writer.apply(pending, "chosen-hash", Change.activation(),
                    VersionExpectation.trusted());

            LockedAccount after = row(pending);
            assertThat(after.enabled()).isTrue();
            assertThat(after.password()).isEqualTo("chosen-hash");
            assertThat(after.passwordChangeRequired()).isFalse();
            assertThat(outcome.credentialsVersion()).isEqualTo(1L);

            assertThatThrownBy(() -> writer.apply(userId, "chosen-hash", Change.activation(),
                    VersionExpectation.trusted()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Account is not awaiting activation");
            assertThat(row(userId).password()).isEqualTo("old-hash");
        }

        @Test
        @DisplayName("an unknown account is refused 'User not found'")
        void unknownAccount_isRefused() {
            assertThatThrownBy(() -> writer.apply(userId + 1000, "hash", Change.changePassword(),
                    VersionExpectation.trusted()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("User not found: " + (userId + 1000));
        }

        @Test
        @DisplayName("the outcome is read under the lock AFTER the change: an email change that committed "
                + "after the entity was loaded is what the caller gets, never the stale entity's address")
        void outcome_isAScalarReadNotTheStaleEntity() {
            AppUserEntity loadedBeforeTheHash = users.findById(userId).orElseThrow();
            jdbc.update("UPDATE app_user SET email = 'moved@example.com' WHERE id = ?", userId);

            CredentialChangeOutcome outcome = writer.apply(userId, null, Change.disable(),
                    VersionExpectation.trusted());

            assertThat(outcome.email()).isEqualTo("moved@example.com");
            assertThat(loadedBeforeTheHash.getEmail()).isEqualTo("reader@example.com");
        }

        @Test
        @DisplayName("the change is ATOMIC: a failure after the password write rolls back the hash, the stamp, "
                + "the flags and the revocation together")
        void failureAfterThePasswordWrite_rollsEverythingBack() {
            refreshToken("r1", userId);
            resetToken("unused", userId, null);
            doThrow(new IllegalStateException("revoke failed")).when(refreshTokens)
                    .revokeAllActiveByUserId(userId);

            assertThatThrownBy(() -> writer.apply(userId, "new-hash", Change.changePassword(),
                    VersionExpectation.exact(0)))
                    .isInstanceOf(IllegalStateException.class);

            LockedAccount after = row(userId);
            assertThat(after.password()).isEqualTo("old-hash");
            assertThat(after.passwordChangeRequired()).isTrue();
            assertThat(after.credentialsVersion()).isZero();
            assertThat(after.credentialsChangedAt()).isNull();
            assertThat(count("refresh_token", "revoked = FALSE AND user_id = ?", userId)).isEqualTo(1);
            assertThat(count("password_reset_token", "user_id = ?", userId)).isEqualTo(1);
        }

        @Test
        @DisplayName("another account's sessions, links, hash and version are untouched")
        void touchesOnlyThisAccount() {
            long other = account("other", "other@example.com", true, "other-hash", false);
            refreshToken("o1", other);
            resetToken("other-link", other, null);
            verificationToken("other-verification", other, false);

            writer.apply(userId, null, Change.disable(), VersionExpectation.trusted());

            assertThat(count("refresh_token", "revoked = FALSE AND user_id = ?", other)).isEqualTo(1);
            assertThat(count("password_reset_token", "user_id = ?", other)).isEqualTo(1);
            assertThat(count("email_verification_token", "user_id = ?", other)).isEqualTo(1);
            assertThat(row(other).credentialsVersion()).isZero();
            assertThat(row(other).password()).isEqualTo("other-hash");
        }
    }

    @Nested
    class ConsumeVerification {

        private long pendingId;
        private long tokenId;

        @BeforeEach
        void pendingAccountWithAVerifiedLink() {
            pendingId = account("pending", "p@example.com", false, "", false);
            tokenId = verificationToken("link", pendingId, true);
        }

        @Test
        @DisplayName("activates the account, deletes EVERY verification token of the user (not only the one "
                + "presented) and leaves another user's alone")
        void activatesAndDeletesEveryToken() {
            verificationToken("older-link", pendingId, false);
            long other = account("other", "o@example.com", false, "", false);
            verificationToken("other-link", other, true);

            CredentialChangeOutcome outcome = writer.consumeVerificationAndApply(tokenId, pendingId,
                    "chosen-hash", Change.activation());

            assertThat(outcome.userId()).isEqualTo(pendingId);
            LockedAccount after = row(pendingId);
            assertThat(after.enabled()).isTrue();
            assertThat(after.password()).isEqualTo("chosen-hash");
            assertThat(after.credentialsVersion()).isEqualTo(1L);
            assertThat(count("email_verification_token", "user_id = ?", pendingId)).isZero();
            assertThat(count("email_verification_token", "user_id = ?", other)).isEqualTo(1);
        }

        @Test
        @DisplayName("a second redemption of the same link is refused 'already been used' and the password "
                + "stays")
        void secondRedemption_isRefused() {
            writer.consumeVerificationAndApply(tokenId, pendingId, "chosen-hash", Change.activation());

            assertThatThrownBy(() -> writer.consumeVerificationAndApply(tokenId, pendingId, "another-hash",
                    Change.activation()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("This verification link has already been used");

            assertThat(row(pendingId).password()).isEqualTo("chosen-hash");
        }

        @Test
        @DisplayName("a reader who verified two links and activated with one cannot overwrite the live password "
                + "with the other — the pending state is re-checked under the lock, against a request that "
                + "still holds the account as PENDING")
        void accountActivatedMeanwhile_isRefused() {
            long secondLink = verificationToken("second-link", pendingId, true);
            AppUserEntity loadedWhilePending = users.findById(pendingId).orElseThrow();
            jdbc.update("UPDATE app_user SET enabled = TRUE, password = 'live-hash' WHERE id = ?", pendingId);

            assertThatThrownBy(() -> writer.consumeVerificationAndApply(secondLink, pendingId, "other-hash",
                    Change.activation()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("This verification link has already been used");

            assertThat(row(pendingId).password()).isEqualTo("live-hash");
            assertThat(row(pendingId).credentialsVersion()).isZero();
            assertThat(count("email_verification_token", "id = ?", secondLink)).isEqualTo(1);
            // The trap, pinned: the managed entity still says pending.
            assertThat(loadedWhilePending.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("a token deleted by a concurrent redemption between the read and the lock is 'already "
                + "used': the delete's row count, not a read, decides, and nothing is activated")
        void tokenDeletedConcurrently_isRefused() {
            verificationTokens.findById(tokenId).orElseThrow();
            jdbc.update("DELETE FROM email_verification_token WHERE id = ?", tokenId);

            assertThatThrownBy(() -> writer.consumeVerificationAndApply(tokenId, pendingId, "chosen-hash",
                    Change.activation()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("This verification link has already been used");

            assertThat(row(pendingId).enabled()).isFalse();
        }

        @Test
        @DisplayName("a failure after the token was consumed rolls the consumption back with the activation")
        void failureAfterConsumption_rollsBackTheToken() {
            doThrow(new IllegalStateException("revoke failed")).when(refreshTokens)
                    .revokeAllActiveByUserId(pendingId);

            assertThatThrownBy(() -> writer.consumeVerificationAndApply(tokenId, pendingId, "chosen-hash",
                    Change.activation()))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(count("email_verification_token", "id = ?", tokenId)).isEqualTo(1);
            assertThat(row(pendingId).enabled()).isFalse();
            assertThat(row(pendingId).password()).isEmpty();
        }

        @Test
        @DisplayName("an account that is not pending (an admin-created, enabled one) is refused")
        void notPending_isRefused() {
            long active = account("active", "a@example.com", true, "live-hash", false);
            long token = verificationToken("active-link", active, true);

            assertThatThrownBy(() -> writer.consumeVerificationAndApply(token, active, "overwrite",
                    Change.activation()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("This verification link has already been used");
            assertThat(row(active).password()).isEqualTo("live-hash");
        }
    }

    @Nested
    class ConsumeReset {

        private long tokenId;

        @BeforeEach
        void aReset() {
            tokenId = resetToken("hash-1", userId, null);
        }

        private String storedPassword() {
            return jdbc.queryForObject("SELECT password FROM app_user WHERE id = ?", String.class, userId);
        }

        private Object usedAt(long id) {
            return jdbc.queryForObject("SELECT used_at FROM password_reset_token WHERE id = ?", Object.class, id);
        }

        @Test
        @DisplayName("redemption consumes the token, sets the password, clears the first-login gate, bumps the "
                + "version, voids sibling links and revokes every active refresh token")
        void appliesTheWholeChange() {
            long siblingId = resetToken("hash-2", userId, null);
            refreshToken("r1", userId);
            refreshToken("r2", userId);

            CredentialChangeOutcome outcome = writer.consumeResetAndApply(tokenId, userId, "new-hash");

            assertThat(storedPassword()).isEqualTo("new-hash");
            assertThat(row(userId).passwordChangeRequired()).isFalse();
            assertThat(outcome).isEqualTo(new CredentialChangeOutcome(userId, "reader", "reader@example.com", 1L));
            assertThat(usedAt(tokenId)).isNotNull();
            assertThat(count("password_reset_token", "id = ?", siblingId)).isZero();
            assertThat(count("refresh_token", "revoked = FALSE AND user_id = ?", userId)).isZero();
        }

        @Test
        @DisplayName("redemption is 'trusted': it succeeds against a row whose version has long moved on")
        void succeedsWhateverTheRowsVersion() {
            setVersion(userId, 9);

            CredentialChangeOutcome outcome = writer.consumeResetAndApply(tokenId, userId, "new-hash");

            assertThat(outcome.credentialsVersion()).isEqualTo(10L);
        }

        @Test
        @DisplayName("an admin who disabled the account while the password was being hashed wins: refused "
                + "'invalid', token unconsumed, password untouched — though the request holds it ENABLED")
        void accountDisabledAfterTheEntityWasLoaded_isRefused() {
            AppUserEntity loadedBeforeTheHash = users.findById(userId).orElseThrow();
            jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", userId);

            assertThatThrownBy(() -> writer.consumeResetAndApply(tokenId, userId, "new-hash"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("This reset link is invalid");

            assertThat(usedAt(tokenId)).isNull();
            assertThat(storedPassword()).isEqualTo("old-hash");
            assertThat(loadedBeforeTheHash.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("a pending registration's link is refused 'invalid' (a reset must not activate an account)")
        void pendingAccount_isRefused() {
            users.findById(userId).orElseThrow();
            jdbc.update("UPDATE app_user SET enabled = FALSE, password = '' WHERE id = ?", userId);

            assertThatThrownBy(() -> writer.consumeResetAndApply(tokenId, userId, "new-hash"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("This reset link is invalid");

            assertThat(usedAt(tokenId)).isNull();
            assertThat(storedPassword()).isEmpty();
        }

        @Test
        @DisplayName("a link whose account has been deleted is refused 'invalid'")
        void deletedAccount_isRefused() {
            jdbc.update("DELETE FROM password_reset_token");
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);

            assertThatThrownBy(() -> writer.consumeResetAndApply(tokenId, userId, "new-hash"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("This reset link is invalid");
        }

        @Test
        @DisplayName("a second redemption of the same link is refused 'already been used' and changes nothing")
        void secondRedemption_isRefused() {
            writer.consumeResetAndApply(tokenId, userId, "new-hash");

            assertThatThrownBy(() -> writer.consumeResetAndApply(tokenId, userId, "another-hash"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("This reset link has already been used");

            assertThat(storedPassword()).isEqualTo("new-hash");
            assertThat(row(userId).credentialsVersion()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a token consumed by a concurrent request (used_at set after this request read it as "
                + "unused) is refused 'already been used' and the password is not changed")
        void consumedByAConcurrentRequest_isRefused() {
            resetTokens.findById(tokenId).orElseThrow();
            jdbc.update("UPDATE password_reset_token SET used_at = ? WHERE id = ?",
                    Timestamp.from(NOW.minusSeconds(1)), tokenId);

            assertThatThrownBy(() -> writer.consumeResetAndApply(tokenId, userId, "new-hash"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("This reset link has already been used");

            assertThat(storedPassword()).isEqualTo("old-hash");
        }

        @Test
        @DisplayName("the redemption is ATOMIC: a failure after the token was consumed and the password set "
                + "rolls both back")
        void failureAfterTheWrites_rollsEverythingBack() {
            doThrow(new IllegalStateException("revoke failed")).when(refreshTokens)
                    .revokeAllActiveByUserId(userId);

            assertThatThrownBy(() -> writer.consumeResetAndApply(tokenId, userId, "new-hash"))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(usedAt(tokenId)).isNull();
            assertThat(storedPassword()).isEqualTo("old-hash");
            assertThat(row(userId).passwordChangeRequired()).isTrue();
            assertThat(row(userId).credentialsVersion()).isZero();
        }

        @Test
        @DisplayName("a link that expired while the new password was being hashed is refused 'has expired', "
                + "stays unconsumed and changes nothing")
        void expiredByTheWritersOwnInstant_isRefused() {
            jdbc.update("UPDATE password_reset_token SET expires_at = ? WHERE id = ?",
                    Timestamp.from(NOW.minusSeconds(1)), tokenId);
            resetTokens.findById(tokenId).orElseThrow();

            assertThatThrownBy(() -> writer.consumeResetAndApply(tokenId, userId, "new-hash"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("This reset link has expired");

            assertThat(usedAt(tokenId)).isNull();
            assertThat(storedPassword()).isEqualTo("old-hash");
        }

        @Test
        @DisplayName("a link expiring exactly at the writer's instant is expired; one second left still redeems")
        void expiryBoundary() {
            jdbc.update("UPDATE password_reset_token SET expires_at = ? WHERE id = ?",
                    Timestamp.from(NOW), tokenId);
            assertThatThrownBy(() -> writer.consumeResetAndApply(tokenId, userId, "new-hash"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("This reset link has expired");

            jdbc.update("UPDATE password_reset_token SET expires_at = ? WHERE id = ?",
                    Timestamp.from(NOW.plusSeconds(1)), tokenId);
            writer.consumeResetAndApply(tokenId, userId, "new-hash");

            assertThat(storedPassword()).isEqualTo("new-hash");
        }

        @Test
        @DisplayName("another user's refresh tokens and links are untouched")
        void touchesOnlyThisUsersRows() {
            long other = account("other", "other@example.com", true, "other-hash", false);
            refreshToken("o1", other);
            resetToken("other-link", other, null);

            writer.consumeResetAndApply(tokenId, userId, "new-hash");

            assertThat(count("refresh_token", "revoked = FALSE AND user_id = ?", other)).isEqualTo(1);
            assertThat(count("password_reset_token", "user_id = ?", other)).isEqualTo(1);
            assertThat(row(other).password()).isEqualTo("other-hash");
        }
    }
}
