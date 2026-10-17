package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.exception.StaleCredentialsException;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
import com.gregochr.goldenhour.repository.PasswordResetTokenRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * The one place an account's credentials change (login-registration plan §2 #13, §2 #14).
 *
 * <p>Every site that changes a password, activates an account, redeems a reset link, sets an admin's
 * temporary password or disables an account hashes FIRST — BCrypt never runs with a database connection
 * held — and then calls ONE {@code @Transactional} method here. Inside, in the parent-before-children
 * lock order every credential path uses:
 * <ol>
 *   <li>the user row is locked and read as a SCALAR projection
 *       ({@link AppUserRepository#lockAccount}), never as an entity: under open-session-in-view the
 *       request's persistence context already holds the entity from before the hash;</li>
 *   <li>the caller's {@link VersionExpectation} is checked against the locked row's
 *       {@code credentials_version}, and the {@link Change}'s required state against its enabled/pending
 *       shape;</li>
 *   <li>one {@code UPDATE} sets the password (or, for a disable, leaves it out), the account-state
 *       flags, increments {@code credentials_version} and stamps {@code credentials_changed_at}, as a
 *       compare-and-set whose row count must be one;</li>
 *   <li>every active refresh token is revoked and the account's unused reset links are deleted — and
 *       its verification links too when the change moves the enabled state — so nothing issued before
 *       the change survives it;</li>
 *   <li>the row is read again and returned as a {@link CredentialChangeOutcome}.</li>
 * </ol>
 * A refusal or failure at any step throws, which rolls the whole change back: the password, the stamp,
 * the flags and the revocation commit together or not at all.
 *
 * <p>A bean of its own so callers reach it through the Spring proxy; a transactional method on a caller
 * would be a self-invocation and run with no transaction. The clock is read INSIDE the transaction,
 * after the lock.
 */
@Component
public class CredentialChangeWriter {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialChangeWriter.class);

    /** The sentence for a reset link that cannot be redeemed because of the account it names. */
    public static final String RESET_LINK_INVALID = "This reset link is invalid";

    /** The sentence for a reset link that was redeemed, possibly a moment ago by a concurrent request. */
    public static final String RESET_LINK_USED = "This reset link has already been used";

    /** The sentence for a reset link that expired, possibly while the new password was being hashed. */
    public static final String RESET_LINK_EXPIRED = "This reset link has expired";

    /** The sentence for a verification link whose account is no longer awaiting activation. */
    public static final String VERIFICATION_LINK_USED = "This verification link has already been used";

    /** The sentence for an activation attempted on an account that is not pending. */
    static final String NOT_AWAITING_ACTIVATION = "Account is not awaiting activation";

    /** The state an account must be in for a change to apply. */
    public enum Required {
        /** No requirement. */
        ANY(0),
        /** Enabled and holding a hash. */
        ACTIVE(1),
        /** A self-registration: disabled with the empty password marker. */
        PENDING(2);

        private final int code;

        Required(int code) {
            this.code = code;
        }

        private boolean admits(LockedAccount account) {
            return switch (this) {
                case ANY -> true;
                case ACTIVE -> account.active();
                case PENDING -> account.pending();
            };
        }
    }

    /** What a change does to the first-login password gate. */
    public enum PasswordGate {
        /** Leave it as it is. */
        LEAVE(0),
        /** Clear it: the reader chose this password. */
        CLEAR(1),
        /** Set it: the password is a temporary one the reader must replace. */
        REQUIRE(2);

        private final int code;

        PasswordGate(int code) {
            this.code = code;
        }
    }

    /** What a change does to the enabled flag. */
    public enum EnabledState {
        /** Leave it as it is. */
        LEAVE(0),
        /** Enable the account (activation). */
        ENABLE(1),
        /** Disable the account. */
        DISABLE(2);

        private final int code;

        EnabledState(int code) {
            this.code = code;
        }
    }

    /**
     * A site's WHOLE account-state change, so the password, the stamp, the flags and the revocation
     * commit together.
     *
     * @param required     the state the account must be in
     * @param passwordGate what happens to {@code passwordChangeRequired}
     * @param enabledState what happens to {@code enabled}
     */
    public record Change(Required required, PasswordGate passwordGate, EnabledState enabledState) {

        /**
         * A signed-in reader changes their own password.
         *
         * @return the change
         */
        public static Change changePassword() {
            return new Change(Required.ACTIVE, PasswordGate.CLEAR, EnabledState.LEAVE);
        }

        /**
         * A reset link is redeemed.
         *
         * @return the change
         */
        public static Change resetRedemption() {
            return new Change(Required.ACTIVE, PasswordGate.CLEAR, EnabledState.LEAVE);
        }

        /**
         * An admin sets a temporary password the reader must replace.
         *
         * @return the change
         */
        public static Change adminTemporaryPassword() {
            return new Change(Required.ANY, PasswordGate.REQUIRE, EnabledState.LEAVE);
        }

        /**
         * A pending registration is activated with the password its owner chose.
         *
         * @return the change
         */
        public static Change activation() {
            return new Change(Required.PENDING, PasswordGate.CLEAR, EnabledState.ENABLE);
        }

        /**
         * An admin disables the account. Applied with a null password: the hash is left untouched.
         *
         * @return the change
         */
        public static Change disable() {
            return new Change(Required.ANY, PasswordGate.LEAVE, EnabledState.DISABLE);
        }
    }

    /**
     * What the caller knows about the account's credential version. Three modes, never a bare null with
     * two meanings.
     *
     * @param mode    how the row's version is judged
     * @param version the version {@link Mode#EXACT} insists on
     */
    public record VersionExpectation(Mode mode, long version) {

        /** How the row's {@code credentials_version} is judged. */
        public enum Mode {
            /** The row must hold exactly {@code version}. */
            EXACT,
            /** The row must still hold 0: the caller's token predates the counter. */
            LEGACY_UNCHANGED,
            /** No check: the authority is the consumed token or the admin role. */
            TRUSTED
        }

        /**
         * Change-password from a token carrying {@code cv}: refused when the row has moved.
         *
         * @param version the token's {@code cv} claim
         * @return the expectation
         */
        public static VersionExpectation exact(long version) {
            return new VersionExpectation(Mode.EXACT, version);
        }

        /**
         * Change-password from a token with NO {@code cv}: accepted only while the row has never changed.
         *
         * @return the expectation
         */
        public static VersionExpectation legacyUnchanged() {
            return new VersionExpectation(Mode.LEGACY_UNCHANGED, 0L);
        }

        /**
         * The reset redeemer, activation, the admin temporary password and the admin disable: no version
         * check at all, whatever the row's version.
         *
         * @return the expectation
         */
        public static VersionExpectation trusted() {
            return new VersionExpectation(Mode.TRUSTED, 0L);
        }

        private boolean checked() {
            return mode != Mode.TRUSTED;
        }

        private long expected() {
            return mode == Mode.EXACT ? version : 0L;
        }

        private boolean admits(long rowVersion) {
            return !checked() || rowVersion == expected();
        }
    }

    private final AppUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final EmailVerificationTokenRepository verificationTokenRepository;
    private final Clock clock;

    /**
     * Constructs the writer.
     *
     * @param userRepository              accounts
     * @param refreshTokenRepository      sessions
     * @param resetTokenRepository        password-reset links
     * @param verificationTokenRepository verification links
     * @param clock                       the application clock, read inside the transaction
     */
    public CredentialChangeWriter(AppUserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository, PasswordResetTokenRepository resetTokenRepository,
            EmailVerificationTokenRepository verificationTokenRepository, Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.clock = clock;
    }

    /**
     * Applies a credential change.
     *
     * @param userId          the account's primary key
     * @param encodedPassword the new BCrypt hash, computed before this call; {@code null} leaves the
     *                        password column out of the statement entirely, so a change that waited on the
     *                        row lock behind a concurrent password change can never restore a stale hash
     * @param change          the site's whole account-state change
     * @param expectation     what the caller knows about the credential version
     * @return the account as it stands after the change
     * @throws IllegalArgumentException  if no such account exists, or the change needs a pending account
     *                                   and the account is not
     * @throws StaleCredentialsException if the row's credential version is not the one expected, or the
     *                                   change needs an active account and the account is not
     */
    @Transactional
    public CredentialChangeOutcome apply(Long userId, String encodedPassword, Change change,
            VersionExpectation expectation) {
        LockedAccount account = userRepository.lockAccount(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        return applyUnderLock(userId, account, encodedPassword, change, expectation, clock.instant());
    }

    /**
     * Redeems a verification link and activates the account it names, in one transaction.
     *
     * <p>Under the row lock it re-checks that the account is STILL pending (a reader who verified two
     * links and activated with one must not overwrite the live password with the other), deletes the
     * presented token by id — a zero count means a concurrent redemption won — deletes every other
     * verification token of the user, and applies the change.
     *
     * @param tokenId         the presented verification token's primary key
     * @param userId          the account the token names
     * @param encodedPassword the chosen password's BCrypt hash, computed before this call
     * @param change          the activation change ({@link Change#activation()})
     * @return the activated account
     * @throws IllegalArgumentException with {@link #VERIFICATION_LINK_USED} when the account is no longer
     *                                  pending or the token was already consumed
     */
    @Transactional
    public CredentialChangeOutcome consumeVerificationAndApply(Long tokenId, Long userId,
            String encodedPassword, Change change) {
        LockedAccount account = userRepository.lockAccount(userId).orElse(null);
        if (account == null || !account.pending()) {
            LOG.info("Verification redemption refused: account for user id {} is no longer pending", userId);
            throw new IllegalArgumentException(VERIFICATION_LINK_USED);
        }
        if (verificationTokenRepository.deleteByIdIfPresent(tokenId) != 1) {
            LOG.info("Verification redemption refused: token {} was already consumed", tokenId);
            throw new IllegalArgumentException(VERIFICATION_LINK_USED);
        }
        verificationTokenRepository.deleteAllByUserIdInBulk(userId);
        return applyUnderLock(userId, account, encodedPassword, change, VersionExpectation.trusted(),
                clock.instant());
    }

    /**
     * Redeems a password-reset link in one transaction: consumes the link, sets the password, bumps the
     * credential version, revokes every active refresh token and voids the account's other links.
     *
     * <p>The link is consumed by compare-and-set on unused AND unexpired, judged at an instant taken
     * HERE, after the BCrypt hash and under the row lock — a link that expired while the password was
     * being hashed must not redeem on the strength of a pre-hash check. An admin who disabled the account
     * while the password was being hashed wins: the link is refused 'invalid' and left unconsumed.
     *
     * @param tokenId         the reset link's primary key
     * @param userId          the account the link names
     * @param encodedPassword the new password's BCrypt hash, computed before this call
     * @return the account as it stands after the redemption
     * @throws IllegalArgumentException with the sentence to show when the link cannot be redeemed
     */
    @Transactional
    public CredentialChangeOutcome consumeResetAndApply(Long tokenId, Long userId, String encodedPassword) {
        LockedAccount account = userRepository.lockAccount(userId).orElse(null);
        if (account == null || !account.active()) {
            LOG.info("Password reset refused: account for user id {} is disabled, pending or gone", userId);
            throw new IllegalArgumentException(RESET_LINK_INVALID);
        }
        Instant now = clock.instant();
        if (resetTokenRepository.markUsed(tokenId, now) != 1) {
            // Zero rows: used (possibly a moment ago, by a concurrent request) or expired. One read of
            // the row, under the account lock held since above, says which; markUsed cleared the
            // persistence context, so this is a fresh read, not an entity from before the hash.
            boolean used = resetTokenRepository.findById(tokenId)
                    .map(token -> token.getUsedAt() != null).orElse(true);
            throw new IllegalArgumentException(used ? RESET_LINK_USED : RESET_LINK_EXPIRED);
        }
        return applyUnderLock(userId, account, encodedPassword, Change.resetRedemption(),
                VersionExpectation.trusted(), now);
    }

    private CredentialChangeOutcome applyUnderLock(Long userId, LockedAccount account, String encodedPassword,
            Change change, VersionExpectation expectation, Instant now) {
        if (!expectation.admits(account.credentialsVersion())) {
            LOG.info("Credential change refused for user id {}: the session's credential version is stale",
                    userId);
            throw new StaleCredentialsException();
        }
        if (!change.required().admits(account)) {
            LOG.info("Credential change refused for user id {}: the account is not {}", userId,
                    change.required());
            if (change.required() == Required.PENDING) {
                throw new IllegalArgumentException(NOT_AWAITING_ACTIVATION);
            }
            throw new StaleCredentialsException();
        }

        int updated;
        if (encodedPassword == null) {
            updated = userRepository.applyCredentialChangeKeepingPassword(userId,
                    change.passwordGate().code, change.enabledState().code, change.required().code,
                    expectation.checked(), expectation.expected(), now);
        } else {
            updated = userRepository.applyCredentialChange(userId, encodedPassword,
                    change.passwordGate().code, change.enabledState().code, change.required().code,
                    expectation.checked(), expectation.expected(), now);
        }
        if (updated != 1) {
            // Unreachable while the row lock is held and the checks above passed, but a count that is not
            // one must never be read as success: throwing rolls everything back.
            throw new IllegalStateException("Credential change matched no row for user id " + userId);
        }

        int revoked = refreshTokenRepository.revokeAllActiveByUserId(userId);
        resetTokenRepository.deleteByUserIdAndUsedAtIsNull(userId);
        if (change.enabledState() != EnabledState.LEAVE) {
            verificationTokenRepository.deleteAllByUserIdInBulk(userId);
        }

        LockedAccount after = userRepository.lockAccount(userId)
                .orElseThrow(() -> new IllegalStateException("Account vanished under its own lock: " + userId));
        LOG.info("Credentials changed for user id {}: version now {}, revoked {} active refresh token(s)",
                userId, after.credentialsVersion(), revoked);
        return new CredentialChangeOutcome(userId, after.username(), after.email(), after.credentialsVersion());
    }
}
