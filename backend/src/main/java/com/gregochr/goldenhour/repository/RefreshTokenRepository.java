package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Spring Data repository for {@link RefreshTokenEntity}.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

    /**
     * Finds a stored token by its SHA-256 hash.
     *
     * @param tokenHash the hex-encoded SHA-256 digest of the raw token
     * @return an {@link Optional} containing the token record, or empty if not found
     */
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    /**
     * Atomically revokes the token with the given hash, but only if it is still active.
     *
     * <p>This is a database-level compare-and-set: the {@code revoked = false} predicate
     * guarantees that of two concurrent refresh calls presenting the same token, exactly
     * one sees an update count of 1. Callers must only rotate (issue new tokens) when the
     * returned count is 1 — a count of 0 means the token was already consumed.
     *
     * @param tokenHash the hex-encoded SHA-256 digest of the raw token
     * @return the number of rows updated: 1 if this call revoked the token, 0 otherwise
     */
    @Modifying
    @Query("UPDATE RefreshTokenEntity t SET t.revoked = true "
            + "WHERE t.tokenHash = :tokenHash AND t.revoked = false")
    int revokeIfActive(@Param("tokenHash") String tokenHash);

    /**
     * Revokes every active refresh token belonging to the given user.
     *
     * <p>Used for reuse-detection hardening: presenting an already-revoked token indicates
     * the token was stolen (the legitimate rotation already consumed it), so the whole
     * token family is invalidated and every session must re-authenticate.
     *
     * @param userId the user whose tokens should be revoked
     * @return the number of tokens revoked
     */
    @Modifying
    @Query("UPDATE RefreshTokenEntity t SET t.revoked = true "
            + "WHERE t.userId = :userId AND t.revoked = false")
    int revokeAllActiveByUserId(@Param("userId") Long userId);

    /**
     * Removes all refresh tokens belonging to the specified user.
     * Called on logout to invalidate all sessions.
     *
     * @param userId the user whose tokens should be deleted
     */
    void deleteByUserId(Long userId);

    /**
     * Removes expired tokens from the database.
     * Intended for future scheduled cleanup jobs.
     *
     * @param now the cutoff timestamp; all tokens expiring before this will be removed
     */
    void deleteAllByExpiresAtBefore(LocalDateTime now);
}
