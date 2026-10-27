package com.gregochr.goldenhour.model;

import java.time.LocalDateTime;

/**
 * When an account's newest unverified verification link was minted, for the admin user list.
 *
 * @param userId    the account
 * @param createdAt the link's creation time (zone-less, in the JVM default zone it was written in)
 */
public record VerificationSentRow(Long userId, LocalDateTime createdAt) {
}
