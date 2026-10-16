package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.PasswordResetTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data repository for {@link PasswordResetTokenEntity}.
 *
 * <p>Both writes are bulk updates with {@code clearAutomatically = true}, so a caller that reads the row
 * back in the same transaction sees the write rather than a stale managed instance (the request's
 * persistence context outlives the transaction under open-session-in-view).
 */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, Long> {

    /**
     * Finds a reset token by the SHA-256 hash of its raw value.
     *
     * @param tokenHash the hex digest
     * @return the token, or empty if none matches
     */
    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);

    /**
     * Deletes the user's reset links that have not been redeemed — the ones a new request supersedes,
     * and the ones a redemption voids alongside the one it consumed.
     *
     * @param userId the user's primary key
     * @return the number of links deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM PasswordResetTokenEntity t WHERE t.userId = :userId AND t.usedAt IS NULL")
    int deleteByUserIdAndUsedAtIsNull(@Param("userId") Long userId);

    /**
     * Consumes a token — but only while it is still unused AND has not expired at {@code now}. A
     * compare-and-set: of two concurrent redemptions of one link exactly one sees a count of 1, and a link
     * that expires while the new password is being hashed is refused, because the caller passes the instant
     * it takes INSIDE the transaction, not the one it checked before hashing.
     *
     * @param id  the token's primary key
     * @param now the instant to record as the redemption and to judge expiry against
     * @return 1 if this call consumed the token; 0 if it was already used or has expired (or no token has
     *         that id) — the caller reads the row to tell which
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE PasswordResetTokenEntity t SET t.usedAt = :now WHERE t.id = :id AND t.usedAt IS NULL "
            + "AND t.expiresAt > :now")
    int markUsed(@Param("id") Long id, @Param("now") Instant now);
}
