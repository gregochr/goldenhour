package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.WaitlistEmailEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
