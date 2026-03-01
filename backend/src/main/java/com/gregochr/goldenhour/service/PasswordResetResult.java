package com.gregochr.goldenhour.service;

/**
 * Result of a password reset operation, carrying the raw temporary password
 * alongside the user's identity so the caller can send a notification email.
 *
 * @param temporaryPassword the plain-text temporary password (shown once, never stored)
 * @param username          the user's login name
 * @param email             the user's email address (may be {@code null})
 */
public record PasswordResetResult(String temporaryPassword, String username, String email) {
}
