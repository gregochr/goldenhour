package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.EmailVerificationTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
