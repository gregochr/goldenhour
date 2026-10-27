package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.EmailVerificationTokenEntity;
import com.gregochr.goldenhour.model.VerificationSentRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link EmailVerificationTokenEntity}.
 */
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationTokenEntity, Long> {

    /**
     * Finds a verification token by its SHA-256 hash.
     *
     * @param tokenHash the hashed token value
     * @return an {@link Optional} containing the token, or empty if not found
     */
    Optional<EmailVerificationTokenEntity> findByTokenHash(String tokenHash);

    /**
     * Finds all unverified tokens for a given user.
     *
     * @param userId the user's primary key
     * @return list of unverified tokens
     */
    List<EmailVerificationTokenEntity> findByUserIdAndVerifiedFalse(Long userId);

    /**
     * When each account's newest unverified verification link was minted, for the admin user list (one
     * query for every user, not one per user).
     *
     * @return one row per account holding an unverified link: the account and its latest link's creation time
     */
    @Query("SELECT new com.gregochr.goldenhour.model.VerificationSentRow(t.userId, MAX(t.createdAt)) "
            + "FROM EmailVerificationTokenEntity t WHERE t.verified = false GROUP BY t.userId")
    List<VerificationSentRow> findLatestUnverifiedCreatedAt();

    /**
     * Counts tokens created for a user after a given timestamp (for rate limiting).
     *
     * @param userId the user's primary key
     * @param after  the cutoff timestamp
     * @return the number of tokens created after the given time
     */
    long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime after);

    /**
     * Deletes one token by id and reports whether THIS call removed it: the compare-and-set that lets
     * exactly one of two concurrent redemptions of a verification link activate the account
     * (login-registration plan §2 #13).
     *
     * @param id the token's primary key
     * @return 1 if the row was deleted; 0 if it was already gone
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM EmailVerificationTokenEntity t WHERE t.id = :id")
    int deleteByIdIfPresent(@Param("id") Long id);

    /**
     * Deletes every verification token of an account, verified or not — a link is keyed by user alone
     * and never re-checks the address, so any outstanding one is voided when the account's address,
     * activation or enabled state changes.
     *
     * @param userId the account's primary key
     * @return the number of tokens deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM EmailVerificationTokenEntity t WHERE t.userId = :userId")
    int deleteAllByUserIdInBulk(@Param("userId") Long userId);

    /**
     * Whether the account holds a verification link, verified or not, that has not yet expired.
     *
     * @param userId the account's primary key
     * @param now    the instant to compare against (JVM default zone)
     * @return true if any such token exists
     */
    boolean existsByUserIdAndExpiresAtAfter(Long userId, LocalDateTime now);

    /**
     * Whether the account holds an UNVERIFIED link that has not yet expired.
     *
     * @param userId the account's primary key
     * @param now    the instant to compare against (JVM default zone)
     * @return true if any such token exists
     */
    boolean existsByUserIdAndVerifiedFalseAndExpiresAtAfter(Long userId, LocalDateTime now);

    /**
     * Deletes one token by its hash.
     *
     * @param tokenHash the SHA-256 hex digest
     * @return the number of tokens deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM EmailVerificationTokenEntity t WHERE t.tokenHash = :tokenHash")
    int deleteByTokenHashInBulk(@Param("tokenHash") String tokenHash);

    /**
     * Rotates an unverified link into the verified setup token, by compare-and-set: the row changes only
     * while it still holds the hash the caller read and is still unverified, so of two concurrent
     * verifications exactly one gets a row count of 1 (token-hardening plan, T (a)).
     *
     * @param id        the token's primary key
     * @param oldHash   the hash of the emailed link, which the row must still hold
     * @param newHash   the hash of the fresh setup token
     * @param expiresAt the new expiry (never later than the old one)
     * @return 1 if this call rotated the row; 0 if it was already used, rotated or gone
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE EmailVerificationTokenEntity t SET t.tokenHash = :newHash, t.verified = true, "
            + "t.expiresAt = :expiresAt WHERE t.id = :id AND t.tokenHash = :oldHash AND t.verified = false")
    int rotateOnVerify(@Param("id") Long id, @Param("oldHash") String oldHash,
            @Param("newHash") String newHash, @Param("expiresAt") LocalDateTime expiresAt);
}
