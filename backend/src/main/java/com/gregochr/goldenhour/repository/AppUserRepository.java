package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data repository for {@link AppUserEntity}.
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
}
