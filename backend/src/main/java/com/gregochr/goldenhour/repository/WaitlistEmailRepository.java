package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.WaitlistEmailEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data repository for {@link WaitlistEmailEntity}.
 */
public interface WaitlistEmailRepository extends JpaRepository<WaitlistEmailEntity, Long> {

    /**
     * Returns whether a waitlist entry with the given email already exists.
     *
     * @param email the email to check
     * @return {@code true} if a matching entry exists
     */
    boolean existsByEmail(String email);

    /**
     * Returns all waitlist entries ordered by submission time ascending (oldest first).
     *
     * @return ordered list of waitlist entries
     */
    List<WaitlistEmailEntity> findAllByOrderBySubmittedAtAsc();

    /**
     * Stamps a fresh invitation on a row, replacing any earlier token and clearing its used mark.
     *
     * @param id        the waitlist row
     * @param hash      SHA-256 hex of the raw token that was mailed
     * @param invitedAt when the invitation was sent
     * @param expiresAt when it stops working
     * @return rows changed (0 when the row no longer exists)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE WaitlistEmailEntity w SET w.inviteTokenHash = :hash, w.invitedAt = :invitedAt, "
            + "w.inviteExpiresAt = :expiresAt, w.inviteUsedAt = NULL WHERE w.id = :id")
    int markInvited(@Param("id") Long id, @Param("hash") String hash,
            @Param("invitedAt") LocalDateTime invitedAt, @Param("expiresAt") LocalDateTime expiresAt);

    /**
     * Redeems an invitation exactly once: matches only a live, unused token issued to this very address.
     *
     * @param hash  SHA-256 hex of the presented token
     * @param now   the current time
     * @param email the address on the registration request
     * @return 1 when the invitation was redeemed by this call, otherwise 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE WaitlistEmailEntity w SET w.inviteUsedAt = :now WHERE w.inviteTokenHash = :hash "
            + "AND w.inviteExpiresAt > :now AND w.inviteUsedAt IS NULL AND lower(w.email) = lower(:email)")
    int redeemInvite(@Param("hash") String hash, @Param("now") LocalDateTime now, @Param("email") String email);

    /**
     * Removes the row for an address, whatever its case.
     *
     * @param email the address
     * @return rows deleted
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM WaitlistEmailEntity w WHERE lower(w.email) = lower(:email)")
    int deleteByEmailIgnoreCase(@Param("email") String email);
}
