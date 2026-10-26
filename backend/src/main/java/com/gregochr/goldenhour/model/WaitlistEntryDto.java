package com.gregochr.goldenhour.model;

import java.time.LocalDateTime;

/**
 * Response DTO for a single waitlist entry.
 *
 * @param id          the database identifier
 * @param email       the waitlisted email address
 * @param submittedAt when the email was submitted
 * @param invitedAt   when the current invitation was sent, or null if never invited
 * @param inviteExpiresAt when the current invitation stops working, or null
 */
public record WaitlistEntryDto(
        Long id,
        String email,
        LocalDateTime submittedAt,
        LocalDateTime invitedAt,
        LocalDateTime inviteExpiresAt
) {
}
