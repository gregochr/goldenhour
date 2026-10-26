package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.util.TimestampColumns;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link AppUserEntity}.
 *
 * <p><strong>Every settings write is a column-scoped update here, never a {@code save()}.</strong>
 * The entity marks its nine settings columns {@code updatable = false} (its class Javadoc records
 * why), so a whole-entity save cannot write them at all; these updates are the only way in. Each
 * writes exactly the columns its own request changes, so two settings saves in two tabs, or a
 * settings save and the nightly drive-time job, can no longer discard one another's change.
 *
 * <p>Every update below carries {@code clearAutomatically = true} and
 * {@code flushAutomatically = true}, for the reasons
 * {@link #updateComingUpLastSeenAtByUsername} records: pending changes are flushed first, and a
 * caller that reads the row back in the same transaction sees the write rather than a stale
 * managed instance.
 */
public interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {

    /**
     * Finds a user by their unique login name.
     *
     * @param username the username to search for
     * @return an {@link Optional} containing the user, or empty if not found
     */
    Optional<AppUserEntity> findByUsername(String username);

    /**
     * Returns whether a user with the given username already exists.
     *
     * @param username the username to check
     * @return {@code true} if a matching user exists
     */
    boolean existsByUsername(String username);

    /**
     * Returns whether a user with the given email already exists.
     *
     * @param email the email to check
     * @return {@code true} if a matching user exists
     */
    boolean existsByEmail(String email);

    /**
     * Finds a user by their email address.
     *
     * @param email the email to search for
     * @return an {@link Optional} containing the user, or empty if not found
     */
    Optional<AppUserEntity> findByEmail(String email);

    /**
     * Counts the number of users whose role is not the specified value.
     *
     * <p>Used to count non-admin users for the early-access registration cap.
     *
     * @param role the role to exclude from the count
     * @return the number of users with a different role
     */
    long countByRoleNot(UserRole role);

    /**
     * Returns every enabled user with the given role — backs {@code AdminAlertService}'s recipient
     * list for the pipeline-degraded email (an enabled ADMIN account may still carry a blank or
     * null email, which the caller filters out; this query answers only "which accounts hold the
     * role and are enabled", not "which have a usable email").
     *
     * @param role the role to match (e.g. {@code ADMIN})
     * @return enabled users with that role, in no particular order
     */
    List<AppUserEntity> findByRoleAndEnabledTrue(UserRole role);

    /**
     * Updates only the Coming-up last-seen instant (plan D3/P5) — never a whole-entity save.
     *
     * <p>{@code AppUserEntity} carries neither {@code @Version} nor {@code @DynamicUpdate}, so
     * Hibernate's default UPDATE writes every mapped column. This write races two other callers
     * that load and save the same row independently — {@code saveHome} and
     * {@code saveMapColourPreferences} — and a full-entity save from any of them can silently
     * restore whichever OTHER column it read stale, discarding a concurrent change (a Codex
     * review finding on PR #695). A column-scoped bulk update touches only this one column, so
     * opening the Coming up tab can never overwrite an unrelated profile/settings write.
     *
     * <p>{@code clearAutomatically = true} evicts the persistence context after the update, so a
     * caller that reads the row back in the same transaction (to build a response) sees this
     * write rather than a stale managed instance cached from an earlier load; {@code
     * flushAutomatically = true} flushes any pending changes first, so this bulk update is never
     * itself the one that becomes stale against an uncommitted change earlier in the transaction.
     *
     * @param username the caller's username
     * @param seenAt   the instant to record
     * @return the number of rows updated — 1, or 0 if the username no longer matches a row
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.comingUpLastSeenAt = :seenAt WHERE u.username = :username")
    int updateComingUpLastSeenAtByUsername(@Param("username") String username,
            @Param("seenAt") Instant seenAt);

    /**
     * Loads a user by login name and LOCKS the row until the calling transaction ends
     * ({@code SELECT … FOR UPDATE}).
     *
     * <p>For a write whose decision depends on the row as it stands. {@code saveHome} decides from
     * the stored home whether the save moves it, and a move must discard the drive times measured
     * from the old one. Read without a lock, that decision could be made against a home another
     * save changes before this one commits — so a save re-sending the old home would write it back
     * and keep drive times measured from the new one. Under the lock, nothing else can write the
     * row between the read and the commit: another save waits, and so does the drive-time
     * refresh's compare-and-set ({@link #stampDriveTimesIfHomeIs}), which then re-checks its
     * condition against the committed result.
     *
     * <p>Must be called inside a transaction; the lock is released when it commits.
     *
     * @param username the login name
     * @return the locked user, or empty if no user has that name
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUserEntity u WHERE u.username = :username")
    Optional<AppUserEntity> findByUsernameForUpdate(@Param("username") String username);

    /**
     * Loads a user by id and LOCKS the row until the calling transaction ends
     * ({@code SELECT … FOR UPDATE}) — the parent-before-children order every credential and deletion
     * path takes ({@code UserDriveTimeWriter}'s rule).
     *
     * <p>⚠️ Use the lock, never the entity. Open Session In View is on, so the instance this returns is
     * the one the request's persistence context already holds, with the fields it had before any slow
     * step (a BCrypt hash) ran. A decision that depends on the row as it stands is made by a row count
     * ({@link #deletePendingById}, {@link #applyCredentialChange}) or by {@link #lockAccount}, never by
     * reading a field off this entity (login-registration plan §2 #14 rule 1).
     *
     * @param id the user's primary key
     * @return the locked user, or empty if no user has that id
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUserEntity u WHERE u.id = :id")
    Optional<AppUserEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * Raw {@code SELECT … FOR UPDATE} behind {@link #lockAccount}: one row of
     * {@code enabled, password, email, username, role, credentials_version, credentials_changed_at,
     * password_change_required, marketing_email_opt_in, terms_version}, or none. A native SCALAR query
     * on purpose — it
     * returns column values straight off the result set, so it can never hand back a stale managed
     * entity, and it takes the row lock in the SQL text rather than relying on how a scalar HQL query
     * applies a lock mode.
     *
     * @param id the user's primary key
     * @return zero or one row of column values
     */
    @Query(value = "SELECT u.enabled, u.password, u.email, u.username, u.role, u.credentials_version, "
            + "u.credentials_changed_at, u.password_change_required, u.marketing_email_opt_in, u.terms_version "
            + "FROM app_user u WHERE u.id = :id FOR UPDATE", nativeQuery = true)
    List<Object[]> lockAccountRow(@Param("id") Long id);

    /**
     * Locks the user's row and reads the state a credential decision needs, as it is NOW: a scalar
     * projection under the lock (login-registration plan §2 #14 rule 1(b)).
     *
     * @param id the user's primary key
     * @return the locked row's state, or empty if no user has that id
     */
    default Optional<LockedAccount> lockAccount(Long id) {
        List<Object[]> rows = lockAccountRow(id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        return Optional.of(new LockedAccount((Boolean) row[0], (String) row[1], (String) row[2],
                (String) row[3], UserRole.valueOf((String) row[4]), ((Number) row[5]).longValue(),
                TimestampColumns.toInstant(row[6]), (Boolean) row[7], (Boolean) row[8], (String) row[9]));
    }

    /**
     * Finds the ACTIVE accounts (enabled, holding a password rather than the empty pending marker)
     * whose email lower-cases to the given key. The caller lower-cases the parameter in Java with
     * {@code Locale.ROOT}; an explicit {@code LOWER()} is used instead of a derived {@code IgnoreCase}
     * method, which emits {@code UPPER()} and lets Postgres fold {@code ı} to {@code I}
     * (login-registration plan §1 #5). More than one row is "no match" to every caller.
     *
     * @param emailLower the trimmed, lower-cased address
     * @return the matching active accounts
     */
    @Query("SELECT u FROM AppUserEntity u WHERE LOWER(u.email) = :emailLower "
            + "AND u.enabled = true AND u.password <> ''")
    List<AppUserEntity> findActiveByEmailLower(@Param("emailLower") String emailLower);

    /**
     * Finds the PENDING registrations (disabled, empty password — the sole shape
     * {@code UserService.createPendingUser} produces) whose email lower-cases to the given key. Used by
     * resend and by the replacement of an abandoned registration; replaces a scan of every user.
     *
     * @param emailLower the trimmed, lower-cased address
     * @return the matching pending accounts
     */
    @Query("SELECT u FROM AppUserEntity u WHERE LOWER(u.email) = :emailLower "
            + "AND u.enabled = false AND u.password = ''")
    List<AppUserEntity> findPendingByEmailLower(@Param("emailLower") String emailLower);

    /**
     * Deletes a pending registration — but only while it is still pending. A compare-and-set whose row
     * count is the answer: a retry of a sign-up that races the verification link's activation deletes
     * nothing (0), so an account that has just been activated can never be erased.
     *
     * @param id the pending user's primary key
     * @return 1 if the pending row was deleted; 0 if it was activated or already gone
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM AppUserEntity u WHERE u.id = :id AND u.enabled = false AND u.password = ''")
    int deletePendingById(@Param("id") Long id);

    /**
     * Lists the pending registrations (disabled, empty password, not ADMIN) created at or before a cutoff,
     * with the sweep's bookkeeping: the pending-registration sweep's candidate query.
     *
     * @param cutoff the latest {@code created_at} considered (now minus the hold minus the first reminder's
     *               hours left), in the JVM default zone
     * @param admin  {@link UserRole#ADMIN}, which is never swept
     * @return the candidates, as scalar projections (no entity is held)
     */
    @Query("SELECT u.id AS id, u.createdAt AS createdAt, u.verificationRemindersSent AS remindersSent, "
            + "u.finalReminderSentAt AS finalReminderSentAt FROM AppUserEntity u "
            + "WHERE u.enabled = false AND u.password = '' AND u.role <> :admin AND u.createdAt <= :cutoff")
    List<PendingStage> findPendingSweepCandidates(@Param("cutoff") LocalDateTime cutoff,
            @Param("admin") UserRole admin);

    /**
     * Reads one account's sweep bookkeeping as a scalar projection (never the managed entity).
     *
     * @param id the user's primary key
     * @return the account's stage, or empty if it is gone
     */
    @Query("SELECT u.id AS id, u.createdAt AS createdAt, u.verificationRemindersSent AS remindersSent, "
            + "u.finalReminderSentAt AS finalReminderSentAt FROM AppUserEntity u WHERE u.id = :id")
    Optional<PendingStage> findPendingStage(@Param("id") Long id);

    /**
     * Claims a reminder stage: a compare-and-set whose row count is the answer. The final-reminder stamp is
     * written only when {@code next} is 2.
     *
     * @param id       the pending user's primary key
     * @param expected the stage the row must be at
     * @param next     the stage to move to (1 or 2)
     * @param now      the stamp for {@code final_reminder_sent_at} when {@code next} is 2
     * @return 1 if claimed; 0 if the stage moved, the account activated or it is gone
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.verificationRemindersSent = :next, "
            + "u.finalReminderSentAt = CASE WHEN :next = 2 THEN :now ELSE u.finalReminderSentAt END "
            + "WHERE u.id = :id AND u.verificationRemindersSent = :expected "
            + "AND u.enabled = false AND u.password = ''")
    int advanceReminderStage(@Param("id") Long id, @Param("expected") int expected, @Param("next") int next,
            @Param("now") LocalDateTime now);

    /**
     * Undoes {@link #advanceReminderStage} after a failed send.
     *
     * @param id              the user's primary key
     * @param expected        the stage to restore
     * @param next            the stage the row must currently be at
     * @param previousFinalAt the {@code final_reminder_sent_at} to restore, or null
     * @return 1 if reverted; 0 if the stage had moved on
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.verificationRemindersSent = :expected, "
            + "u.finalReminderSentAt = :previousFinalAt WHERE u.id = :id AND u.verificationRemindersSent = :next")
    int revertReminderStage(@Param("id") Long id, @Param("expected") int expected, @Param("next") int next,
            @Param("previousFinalAt") LocalDateTime previousFinalAt);

    /**
     * A pending account's sweep bookkeeping.
     */
    interface PendingStage {

        /**
         * Returns the primary key.
         *
         * @return the user id
         */
        Long getId();

        /**
         * Returns when the sign-up was created.
         *
         * @return {@code created_at}, JVM default zone
         */
        LocalDateTime getCreatedAt();

        /**
         * Returns how many reminders have been sent.
         *
         * @return 0, 1 or 2
         */
        int getRemindersSent();

        /**
         * Returns when the final reminder was sent.
         *
         * @return the stamp, or null
         */
        LocalDateTime getFinalReminderSentAt();
    }

    /**
     * Applies a credential change — a new password, the account-state flags, the version bump and its
     * stamp — in ONE statement, as a compare-and-set whose row count is the answer
     * (login-registration plan §2 #14 rule 1(a)). Called only by
     * {@link com.gregochr.goldenhour.service.CredentialChangeWriter}, under the user-row lock.
     *
     * <p>The flags are small integers rather than nullable booleans because a null bound into an
     * expression is the trap §2 #14 rule 2 names: {@code passwordFlag} 1 clears
     * {@code passwordChangeRequired}, 2 sets it, anything else leaves it; {@code enabledFlag} 1 enables,
     * 2 disables, anything else leaves it. {@code required} 1 insists the account is active (enabled and
     * holding a hash), 2 that it is pending (disabled, empty password), anything else asks nothing.
     * {@code versionChecked} false skips the version comparison.
     *
     * @param id              the user's primary key
     * @param encodedPassword the BCrypt hash, computed before any transaction opened
     * @param passwordFlag    what to do to {@code passwordChangeRequired}
     * @param enabledFlag     what to do to {@code enabled}
     * @param required        the state the account must be in
     * @param versionChecked  whether {@code expectedVersion} is compared
     * @param expectedVersion the {@code credentials_version} the row must hold when checked
     * @param now             the change instant, stamped as {@code credentials_changed_at}
     * @return 1 if applied; 0 if the row is gone or any predicate failed
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.password = :encodedPassword, "
            + "u.passwordChangeRequired = CASE WHEN :passwordFlag = 1 THEN false "
            + "WHEN :passwordFlag = 2 THEN true ELSE u.passwordChangeRequired END, "
            + "u.enabled = CASE WHEN :enabledFlag = 1 THEN true WHEN :enabledFlag = 2 THEN false "
            + "ELSE u.enabled END, "
            + "u.credentialsVersion = u.credentialsVersion + 1, u.credentialsChangedAt = :now "
            + "WHERE u.id = :id AND (:versionChecked = false OR u.credentialsVersion = :expectedVersion) "
            + "AND (:required = 0 OR (:required = 1 AND u.enabled = true AND u.password <> '') "
            + "OR (:required = 2 AND u.enabled = false AND u.password = ''))")
    int applyCredentialChange(@Param("id") Long id, @Param("encodedPassword") String encodedPassword,
            @Param("passwordFlag") int passwordFlag, @Param("enabledFlag") int enabledFlag,
            @Param("required") int required, @Param("versionChecked") boolean versionChecked,
            @Param("expectedVersion") long expectedVersion, @Param("now") Instant now);

    /**
     * Bumps the credential version and nothing else — logout's way of killing every outstanding access
     * token. Deliberately not {@link #applyCredentialChange}: {@code credentials_changed_at} is not
     * stamped, so a replayed refresh token revoked by a logout still reads as {@code REVOKED_SEEN}.
     *
     * @param id the user's primary key
     * @return 1 if bumped; 0 if the row is gone
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.credentialsVersion = u.credentialsVersion + 1 WHERE u.id = :id")
    int bumpCredentialsVersion(@Param("id") Long id);

    /**
     * {@link #applyCredentialChange} without a new password: the {@code password} column is not in the
     * statement, so a change that waited on the row lock behind a concurrent password change (an admin
     * disable) can never write back the stale hash it loaded before the lock.
     *
     * @param id              the user's primary key
     * @param passwordFlag    what to do to {@code passwordChangeRequired}
     * @param enabledFlag     what to do to {@code enabled}
     * @param required        the state the account must be in
     * @param versionChecked  whether {@code expectedVersion} is compared
     * @param expectedVersion the {@code credentials_version} the row must hold when checked
     * @param now             the change instant, stamped as {@code credentials_changed_at}
     * @return 1 if applied; 0 if the row is gone or any predicate failed
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET "
            + "u.passwordChangeRequired = CASE WHEN :passwordFlag = 1 THEN false "
            + "WHEN :passwordFlag = 2 THEN true ELSE u.passwordChangeRequired END, "
            + "u.enabled = CASE WHEN :enabledFlag = 1 THEN true WHEN :enabledFlag = 2 THEN false "
            + "ELSE u.enabled END, "
            + "u.credentialsVersion = u.credentialsVersion + 1, u.credentialsChangedAt = :now "
            + "WHERE u.id = :id AND (:versionChecked = false OR u.credentialsVersion = :expectedVersion) "
            + "AND (:required = 0 OR (:required = 1 AND u.enabled = true AND u.password <> '') "
            + "OR (:required = 2 AND u.enabled = false AND u.password = ''))")
    int applyCredentialChangeKeepingPassword(@Param("id") Long id, @Param("passwordFlag") int passwordFlag,
            @Param("enabledFlag") int enabledFlag, @Param("required") int required,
            @Param("versionChecked") boolean versionChecked, @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now);

    /**
     * Reads when an account's credentials last changed, as a scalar (never the entity, which under
     * open-session-in-view may be stale). Empty when the account is gone or has never changed.
     *
     * @param id the user's primary key
     * @return the stamp, or empty
     */
    @Query("SELECT u.credentialsChangedAt FROM AppUserEntity u WHERE u.id = :id AND u.credentialsChangedAt IS NOT NULL")
    Optional<Instant> findCredentialsChangedAt(@Param("id") Long id);

    /**
     * Stamps {@code lastActiveAt}, and nothing else. A column-scoped update: the JWT filter's hourly
     * stamp and login's both used to be whole-entity {@code save()}s of an entity loaded before a gap,
     * which could write a stale password hash or role back over a change committed in the gap
     * (login-registration plan §2 #13).
     *
     * @param id  the user's primary key
     * @param now the instant to record
     * @return the number of rows updated
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.lastActiveAt = :now WHERE u.id = :id")
    int touchLastActiveAt(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * Re-enables an account — and ONLY that: disabling goes through
     * {@link com.gregochr.goldenhour.service.CredentialChangeWriter} so it bumps the credential version,
     * revokes the sessions and voids the links. Re-enabling revives none of them.
     *
     * @param id      the user's primary key
     * @param enabled the new flag
     * @return the number of rows updated
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.enabled = :enabled WHERE u.id = :id")
    int updateEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);

    /**
     * Changes an account's role. A scoped update because the role column is {@code updatable = false}:
     * a request that loaded an ADMIN before a demotion committed must not merge the stale role back,
     * since the JWT filter takes the authority from the row.
     *
     * @param id   the user's primary key
     * @param role the new role
     * @return the number of rows updated
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.role = :role WHERE u.id = :id")
    int updateRole(@Param("id") Long id, @Param("role") UserRole role);

    /**
     * Records the marketing-email consent, and nothing else — the last whole-entity {@code save()} of an
     * existing {@code app_user} row outside creation.
     *
     * @param id    the user's primary key
     * @param optIn the new consent
     * @return the number of rows updated
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.marketingEmailOptIn = :optIn WHERE u.id = :id")
    int updateMarketingEmailOptIn(@Param("id") Long id, @Param("optIn") boolean optIn);

    /**
     * Writes an account's email address. Called only inside {@code UserService.setEmail}'s transaction,
     * after it has locked the row and claimed the address.
     *
     * @param id    the user's primary key
     * @param email the new stored address
     * @return the number of rows updated
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.email = :email WHERE u.id = :id")
    int updateEmail(@Param("id") Long id, @Param("email") String email);

    /**
     * An account's credential-relevant state, read under the row lock by {@link #lockAccount}.
     *
     * @param enabled                whether the account may sign in
     * @param password               the stored hash; empty for a pending registration
     * @param email                  the STORED address — the only address ever mailed
     * @param username               the login name
     * @param role                   the account's role
     * @param credentialsVersion     the credential counter the access token's {@code cv} claim must equal
     * @param credentialsChangedAt   when the credentials last changed, or null if they never have
     * @param passwordChangeRequired whether the account must choose a new password
     * @param marketingEmailOptIn    the marketing consent
     * @param termsVersion           the accepted terms version, or null
     */
    record LockedAccount(boolean enabled, String password, String email, String username, UserRole role,
            long credentialsVersion, Instant credentialsChangedAt, boolean passwordChangeRequired,
            boolean marketingEmailOptIn, String termsVersion) {

        /**
         * Reports whether the account is a self-registration still waiting for its password.
         *
         * @return true when disabled and holding the empty marker rather than a hash
         */
        public boolean pending() {
            return !enabled && password != null && password.isEmpty();
        }

        /**
         * Reports whether the account can use a reset link: enabled and not pending.
         *
         * @return true for an active account
         */
        public boolean active() {
            return enabled && password != null && !password.isEmpty();
        }
    }

    /**
     * Writes the home fields together — postcode, coordinates and radius — and no other column.
     *
     * <p>A {@code null} radius keeps the stored one ({@code coalesce}): the client omits it when
     * only the postcode changed, and treating that as a reset would silently undo a radius the user
     * had widened on purpose.
     *
     * <p>Writes no drive-time stamp. A save that moves the home clears that separately
     * ({@link #clearDriveTimesCalculatedAt}), and one that does not move it must leave it alone.
     *
     * @param id               the user's primary key
     * @param postcode         the home postcode
     * @param latitude         the home latitude
     * @param longitude        the home longitude
     * @param localRadiusMiles the radius to store, already clamped, or {@code null} to keep the
     *                         stored one
     * @return the number of rows updated — 1, or 0 if no user has that id
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.homePostcode = :postcode, u.homeLatitude = :latitude, "
            + "u.homeLongitude = :longitude, "
            + "u.localRadiusMiles = coalesce(:localRadiusMiles, u.localRadiusMiles) "
            + "WHERE u.id = :id")
    int updateHome(@Param("id") Long id, @Param("postcode") String postcode,
            @Param("latitude") double latitude, @Param("longitude") double longitude,
            @Param("localRadiusMiles") Integer localRadiusMiles);

    /**
     * Clears the drive-time stamp — the column half of discarding drive times when the home moves.
     *
     * <p>Not bookkeeping: the stamp is what the settings dialog reads to say when drive times were
     * last calculated, and it is the refresh cooldown's input, so leaving it set would lock someone
     * who has just moved out of recalculating while they are served no drive times at all.
     *
     * @param id the user's primary key
     * @return the number of rows updated — 1, or 0 if no user has that id
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.driveTimesCalculatedAt = null WHERE u.id = :id")
    int clearDriveTimesCalculatedAt(@Param("id") Long id);

    /**
     * Writes the map colour scale alone.
     *
     * @param username       the caller's username
     * @param mapColourScale the validated scale, {@code "temp"} or {@code "verdict"}
     * @return the number of rows updated — 1, or 0 if the username no longer matches a row
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.mapColourScale = :mapColourScale "
            + "WHERE u.username = :username")
    int updateMapColourScaleByUsername(@Param("username") String username,
            @Param("mapColourScale") String mapColourScale);

    /**
     * Writes the Map tab tide mode alone (map-mobile-sheet-plan.md, M4) — the same shape as
     * {@link #updateMapColourScaleByUsername}.
     *
     * @param username    the caller's username
     * @param mapTideMode the validated mode, {@code "auto"}, {@code "always"} or {@code "off"}
     * @return the number of rows updated — 1, or 0 if the username no longer matches a row
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.mapTideMode = :mapTideMode "
            + "WHERE u.username = :username")
    int updateMapTideModeByUsername(@Param("username") String username,
            @Param("mapTideMode") String mapTideMode);

    /**
     * Stamps the drive times as calculated — but only while the home is still the one they were
     * measured from. A compare-and-set.
     *
     * <p>A drive-time refresh reads the home, spends seconds routing from it, and only then
     * stores. A save can move the home in between, and when it does, what was measured describes a
     * journey from a house the user no longer lives in. This update matches no row in that case, so
     * the caller learns that its result is stale from the count. The comparison and the write are
     * one statement: a check read first and written after would reopen the gap it closes. On
     * Postgres, an update that waits for a concurrent writer's row lock re-checks its condition
     * against the row that writer committed, so a move that commits while this waits is seen.
     *
     * <p>Compares the coordinates alone, because they are the whole input to the measurement: two
     * postcodes that geocode to one point yield the same drive times. {@code home_latitude} and
     * {@code home_longitude} are {@code DOUBLE PRECISION}, which round-trips a Java {@code double}
     * exactly, so the equality is exact. A home cleared to null never matches.
     *
     * @param id           the user's primary key
     * @param latitude     the latitude the drive times were measured from
     * @param longitude    the longitude the drive times were measured from
     * @param calculatedAt the instant to record
     * @return 1 if stamped; 0 if the home has moved since (or no user has that id)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.driveTimesCalculatedAt = :calculatedAt "
            + "WHERE u.id = :id AND u.homeLatitude = :latitude AND u.homeLongitude = :longitude")
    int stampDriveTimesIfHomeIs(@Param("id") Long id, @Param("latitude") double latitude,
            @Param("longitude") double longitude, @Param("calculatedAt") Instant calculatedAt);

    /**
     * Clears the drive-time stamp to {@code null} — but only while the home is still the one the
     * (empty) measurement was taken from. The same compare-and-set {@link #stampDriveTimesIfHomeIs}
     * performs, for the one case that has nothing to store:
     * {@link com.gregochr.goldenhour.service.UserDriveTimeWriter#clearIfHomeUnchanged}'s "confirmed
     * unreachable" answer (ORS answered; no valid duration to anywhere), where the rows are cleared
     * alongside the stamp rather than replaced.
     *
     * <p>A literal {@code SET ... = NULL}, not {@link #stampDriveTimesIfHomeIs} called with a
     * {@code null} {@code calculatedAt}. Both would very likely work — Hibernate binds a null
     * parameter as an ordinary typed {@code NULL}, which every JDBC driver this project targets
     * (H2, Postgres) accepts for a nullable timestamp column — but a bound null is one more thing
     * for a driver to get right than a literal that names no type at all, on a statement executed
     * exactly once, for exactly this reason. This method removes the question rather than relying
     * on an answer nothing exercised before this method was added.
     *
     * @param id        the user's primary key
     * @param latitude  the latitude the (empty) measurement was taken from
     * @param longitude the longitude the (empty) measurement was taken from
     * @return 1 if cleared; 0 if the home has moved since (or no user has that id)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.driveTimesCalculatedAt = NULL "
            + "WHERE u.id = :id AND u.homeLatitude = :latitude AND u.homeLongitude = :longitude")
    int clearDriveTimesCalculatedAtIfHomeIs(@Param("id") Long id, @Param("latitude") double latitude,
            @Param("longitude") double longitude);

    /**
     * Records that the caller dismissed the one-time sign-in postcode prompt (V181,
     * login-registration plan §2 #3) — the {@link #updateComingUpLastSeenAtByUsername} shape, for the
     * same reasons: a column-scoped bulk update that writes this one column, so dismissing the prompt
     * can never overwrite a concurrent home save, and a whole-entity {@code save()} can never write it
     * (the column is {@code updatable = false}).
     *
     * <p>Idempotent: a second dismissal overwrites the instant. {@code clearAutomatically} evicts the
     * persistence context so the caller's read-back sees this write.
     *
     * @param username    the caller's username
     * @param dismissedAt the instant to record
     * @return the number of rows updated — 1, or 0 if the username no longer matches a row
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.postcodePromptDismissedAt = :dismissedAt "
            + "WHERE u.username = :username")
    int updatePostcodePromptDismissedAtByUsername(@Param("username") String username,
            @Param("dismissedAt") Instant dismissedAt);

    /**
     * Deletes the account, but only while it is still the one the caller authenticated and verified: a
     * compare-and-set whose ROW COUNT is the answer (login-registration plan §2 #14 rule 1(a), §2 #7).
     * It holds the stored hash to the one the caller's BCrypt check matched, the credential version to the
     * caller's session ({@code credentials_version = :cv}), and the role to anything but ADMIN — a role
     * change does not move the version, and the sole administrator must never be deletable this way. A
     * count of 0 makes the caller throw, which rolls its purge of the dependent rows back.
     *
     * @param id    the user's primary key
     * @param hash  the stored password hash the caller verified
     * @param cv    the credential version the caller's session carries
     * @param admin the ADMIN role, bound as a parameter so no enum literal is spelled in the query
     * @return 1 if the account was deleted; 0 if the password, the version or the role no longer matches
     *         (or the account is gone)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM AppUserEntity u WHERE u.id = :id AND u.password = :hash "
            + "AND u.credentialsVersion = :cv AND u.role <> :admin")
    int deleteOwnAccountIfUnchanged(@Param("id") Long id, @Param("hash") String hash, @Param("cv") long cv,
            @Param("admin") UserRole admin);

    /**
     * Deletes an account by id with one statement and reports whether a row went. The admin path's delete
     * (it holds the row lock and has already purged the dependent rows); the row count, not a re-read
     * entity, says whether the account was there.
     *
     * @param id the user's primary key
     * @return 1 if the account was deleted; 0 if no such account exists
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM AppUserEntity u WHERE u.id = :id")
    int deleteAccountById(@Param("id") Long id);
}
