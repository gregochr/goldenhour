package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
