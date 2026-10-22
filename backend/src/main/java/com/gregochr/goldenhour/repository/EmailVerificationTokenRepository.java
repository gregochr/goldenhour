package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.EmailVerificationTokenEntity;
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
}
