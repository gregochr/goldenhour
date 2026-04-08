package com.gregochr.goldenhour.model;

import java.time.LocalDateTime;

/**
 * Response DTO for a single waitlist entry.
 *
 * @param id          the database identifier
 * @param email       the waitlisted email address
 * @param submittedAt when the email was submitted
 */
public record WaitlistEntryDto(
        Long id,
        String email,
        LocalDateTime submittedAt
) {
}
